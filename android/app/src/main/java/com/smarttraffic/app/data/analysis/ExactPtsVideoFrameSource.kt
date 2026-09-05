package com.smarttraffic.app.data.analysis

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.smarttraffic.app.domain.analysis.AnalysisFrame
import com.smarttraffic.app.domain.analysis.FrameSource
import com.smarttraffic.app.domain.analysis.FrameTimestampPrecision
import com.smarttraffic.app.domain.analysis.MediaSource
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Sequential MediaCodec-backed video source that preserves decoded presentation timestamps.
 *
 * Each returned frame uses MediaCodec.BufferInfo.presentationTimeUs. No timestamp is synthesized
 * from frame index or a nominal FPS. The ImageReader listener applies backpressure rather than
 * silently discarding decoded images when the bounded queue is full.
 */
class ExactPtsVideoFrameSource(
    private val context: Context,
    private val uri: Uri,
) : FrameSource {
    private val extractor = MediaExtractor()
    private val decoder: MediaCodec
    private val reader: ImageReader
    private val readerThread: HandlerThread = HandlerThread("smarttraffic-video-reader").apply { start() }
    private val readerHandler = Handler(readerThread.looper)
    private val renderedImages = ArrayBlockingQueue<Image>(3)
    private val mime: String
    private val width: Int
    private val height: Int
    private val frameRate: Double?
    private val rotationDegrees: Int
    private var inputEosQueued = false
    private var outputEosReached = false
    private var frameIndex = 0L
    private var lastPresentationTimeUs = -1L
    private var closed = false

    override val source: MediaSource

    init {
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            "MediaCodec video decoding requires API 21+"
        }

        extractor.setDataSource(context, uri, null)
        val trackIndex = selectVideoTrack(extractor)
        require(trackIndex >= 0) { "No video track found in $uri" }
        extractor.selectTrack(trackIndex)

        val format = extractor.getTrackFormat(trackIndex)
        mime = requireNotNull(format.getString(MediaFormat.KEY_MIME)) { "Video MIME type is missing" }
        width = format.getIntegerOrDefault(MediaFormat.KEY_WIDTH, 0).coerceAtLeast(1)
        height = format.getIntegerOrDefault(MediaFormat.KEY_HEIGHT, 0).coerceAtLeast(1)
        frameRate = format.getIntegerOrDefault(MediaFormat.KEY_FRAME_RATE, 0).toDouble().takeIf { it > 0.0 }
        rotationDegrees = normalizeRotation(format.getIntegerOrDefault(MediaFormat.KEY_ROTATION, 0))

        require(width > 0 && height > 0) { "Invalid decoded video dimensions: ${width}x$height" }
        require(mime.startsWith("video/")) { "Selected track is not a video track: $mime" }

        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3).also { imageReader ->
            imageReader.setOnImageAvailableListener({ readerInstance ->
                while (!closed) {
                    val image = readerInstance.acquireNextImage() ?: break
                    try {
                        renderedImages.put(image)
                    } catch (interrupted: InterruptedException) {
                        image.close()
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }, readerHandler)
        }

        decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(format, reader.surface, null, 0)
        decoder.start()

        source = MediaSource(
            id = uri.toString(),
            uri = uri.toString(),
            frameRate = frameRate,
            width = if (rotationDegrees % 180 == 0) width else height,
            height = if (rotationDegrees % 180 == 0) height else width,
            timestampPrecision = FrameTimestampPrecision.EXACT_SOURCE_CLOCK,
        )
    }

    override suspend fun nextFrame(): AnalysisFrame? {
        if (closed || outputEosReached) return null

        val bufferInfo = MediaCodec.BufferInfo()
        while (!outputEosReached) {
            if (!inputEosQueued) feedInput()

            val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 20_000L)
            when {
                outputIndex >= 0 -> {
                    val presentationTimeUs = bufferInfo.presentationTimeUs
                    val isEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    decoder.releaseOutputBuffer(outputIndex, true)

                    val image = acquireRenderedImage(presentationTimeUs, isEos)
                    if (image != null) {
                        try {
                            validatePresentationTimestamp(presentationTimeUs)
                            validateImageTimestamp(image, presentationTimeUs)
                            val bitmap = imageToBitmap(image)
                            val result = AnalysisFrame(
                                index = frameIndex++,
                                timestampMs = presentationTimeUs / 1000L,
                                payload = bitmap,
                                width = bitmap.width,
                                height = bitmap.height,
                            )
                            if (isEos) outputEosReached = true
                            return result
                        } finally {
                            image.close()
                        }
                    }

                    if (isEos) outputEosReached = true
                }

                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> if (inputEosQueued) {
                    currentCoroutineContext().ensureActive()
                    SystemClock.sleep(1L)
                }
            }
        }
        return null
    }

    private fun feedInput() {
        val inputIndex = decoder.dequeueInputBuffer(20_000L)
        if (inputIndex < 0) return
        val inputBuffer = decoder.getInputBuffer(inputIndex) ?: error("Decoder input buffer $inputIndex is unavailable")
        inputBuffer.clear()
        val sampleTimeUs = extractor.sampleTime
        val sampleSize = extractor.readSampleData(inputBuffer, 0)
        if (sampleSize < 0) {
            decoder.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            inputEosQueued = true
            return
        }
        require(sampleTimeUs >= 0L) { "Extractor returned invalid sample timestamp=$sampleTimeUs us" }
        decoder.queueInputBuffer(inputIndex, 0, sampleSize, sampleTimeUs, 0)
        extractor.advance()
    }

    private suspend fun acquireRenderedImage(presentationTimeUs: Long, isEos: Boolean): Image? {
        val deadlineNs = SystemClock.elapsedRealtimeNanos() + TimeUnit.SECONDS.toNanos(1)
        while (true) {
            currentCoroutineContext().ensureActive()
            renderedImages.poll(25L, TimeUnit.MILLISECONDS)?.let { return it }
            if (SystemClock.elapsedRealtimeNanos() >= deadlineNs) {
                if (isEos) return null
                throw IllegalStateException("Timed out waiting for ImageReader output for presentation timestamp=$presentationTimeUs us")
            }
        }
    }

    private fun validatePresentationTimestamp(presentationTimeUs: Long) {
        require(presentationTimeUs >= 0L) { "Decoder returned invalid presentation timestamp=$presentationTimeUs us" }
        require(lastPresentationTimeUs < 0L || presentationTimeUs >= lastPresentationTimeUs) {
            "Decoder returned non-monotonic presentation timestamp=$presentationTimeUs us after $lastPresentationTimeUs us"
        }
        lastPresentationTimeUs = presentationTimeUs
    }

    private fun validateImageTimestamp(image: Image, presentationTimeUs: Long) {
        val imageTimestampNs = image.timestamp
        if (imageTimestampNs <= 0L || presentationTimeUs <= 0L) return
        val presentationTimestampNs = presentationTimeUs * 1000L
        val absoluteDeltaNs = kotlin.math.abs(imageTimestampNs - presentationTimestampNs)
        val toleranceNs = 5_000_000L
        require(absoluteDeltaNs <= toleranceNs) {
            "ImageReader timestamp mismatch: image=${imageTimestampNs}ns decoder=${presentationTimestampNs}ns"
        }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        require(image.format == PixelFormat.RGBA_8888) { "Expected RGBA_8888 decoder output, got format=${image.format}" }
        require(image.planes.isNotEmpty()) { "Decoder output contains no image planes" }
        val plane = image.planes[0]
        val buffer = plane.buffer.duplicate()
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        require(pixelStride >= 4) { "Unexpected RGBA pixel stride=$pixelStride" }

        val pixels = IntArray(image.width * image.height)
        for (y in 0 until image.height) {
            val rowOffset = y * rowStride
            for (x in 0 until image.width) {
                val offset = rowOffset + x * pixelStride
                if (offset + 3 >= buffer.limit()) throw IllegalStateException("RGBA plane is smaller than advertised stride")
                val r = buffer.get(offset).toInt() and 0xFF
                val g = buffer.get(offset + 1).toInt() and 0xFF
                val b = buffer.get(offset + 2).toInt() and 0xFF
                val a = buffer.get(offset + 3).toInt() and 0xFF
                pixels[y * image.width + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        val bitmap = Bitmap.createBitmap(pixels, image.width, image.height, Bitmap.Config.ARGB_8888)
        if (rotationDegrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            .also { rotated -> if (rotated !== bitmap) bitmap.recycle() }
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        while (true) {
            val image = renderedImages.poll() ?: break
            runCatching { image.close() }
        }
        runCatching { decoder.stop() }
        runCatching { decoder.release() }
        runCatching { reader.close() }
        runCatching { extractor.release() }
        readerThread.quitSafely()
        readerThread.join(1000L)
    }

    private fun selectVideoTrack(extractor: MediaExtractor): Int {
        for (index in 0 until extractor.trackCount) {
            if (extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/")) return index
        }
        return -1
    }

    private fun normalizeRotation(value: Int): Int = (((value % 360) + 360) % 360).let { if (it % 90 == 0) it else 0 }

    private fun MediaFormat.getIntegerOrDefault(key: String, fallback: Int): Int = if (containsKey(key)) getInteger(key) else fallback
}
