package com.smarttraffic.app.data.analysis

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import com.smarttraffic.app.domain.analysis.AnalysisFrame
import com.smarttraffic.app.domain.analysis.FrameSource
import com.smarttraffic.app.domain.analysis.FrameTimestampPrecision
import com.smarttraffic.app.domain.analysis.MediaSource
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * FrameSource for local video URIs selected by the Analysis Lab.
 *
 * API 28+ videos with frame-count metadata are decoded in small sequential batches. Android
 * recommends getFramesAtIndex() when several consecutive frames are required; this reduces
 * repeated decoder/indexing overhead while keeping frame order deterministic.
 * Timestamps remain REQUESTED_SAMPLE_TIME because indexed frame position is not proof of decoded PTS.
 */
class LocalVideoFrameSource(
    private val context: Context,
    private val uri: Uri,
) : FrameSource {
    private val retriever = MediaMetadataRetriever()
    private var frameIndex = 0L
    private var nextTimestampUs = 0L
    private var finished = false
    private var indexDecodeEnabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
    private val pendingFrames = ArrayDeque<Bitmap>()

    private val durationMs: Long
    private val width: Int
    private val height: Int
    private val frameRate: Double?
    private val frameCount: Int?
    private val sequentialFrameRate: Double?
    private val batchSize = 4

    override val source: MediaSource

    init {
        retriever.setDataSource(context, uri)
        durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            ?.toIntOrNull()?.coerceAtLeast(1) ?: 0
        height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            ?.toIntOrNull()?.coerceAtLeast(1) ?: 0
        frameRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
            ?.toDoubleOrNull()?.takeIf { it > 0.0 }
        frameCount = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                ?.toLongOrNull()?.takeIf { it > 0L && it <= Int.MAX_VALUE }?.toInt()
        } else null
        sequentialFrameRate = frameRate ?: if (frameCount != null && durationMs > 0L) {
            frameCount.toDouble() * 1000.0 / durationMs.toDouble()
        } else null

        source = MediaSource(
            id = uri.toString(),
            uri = uri.toString(),
            frameRate = sequentialFrameRate,
            width = width,
            height = height,
            timestampPrecision = FrameTimestampPrecision.REQUESTED_SAMPLE_TIME,
        )
    }

    override suspend fun nextFrame(): AnalysisFrame? {
        if (finished) return null

        if (indexDecodeEnabled && frameCount != null) {
            fillBatchIfNeeded()
            if (pendingFrames.isNotEmpty()) {
                val currentIndex = frameIndex++
                val bitmap = pendingFrames.removeFirst()
                val timestampMs = sequentialFrameRate?.let {
                    (currentIndex.toDouble() * 1000.0 / it).roundToLong().coerceAtLeast(0L)
                } ?: nextTimestampUs / 1000L
                return AnalysisFrame(
                    index = currentIndex,
                    timestampMs = timestampMs,
                    payload = bitmap,
                    width = bitmap.width,
                    height = bitmap.height,
                )
            }
            if (finished) return null
        }

        return nextFrameFromTimestamp(frameIndex)
    }

    private fun fillBatchIfNeeded() {
        if (pendingFrames.isNotEmpty() || finished || !indexDecodeEnabled || frameCount == null) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            // getFramesAtIndex() is API 28+ while the app supports minSdk 26.
            indexDecodeEnabled = false
            return
        }
        if (frameIndex >= frameCount.toLong()) {
            finished = true
            return
        }

        val startIndex = frameIndex.toInt()
        val count = min(batchSize.toLong(), frameCount.toLong() - frameIndex).toInt()
        try {
            val decoded = retriever.getFramesAtIndex(startIndex, count)
            if (decoded.isEmpty()) throw IllegalStateException("No frames decoded from indexed batch")
            pendingFrames.addAll(decoded)
        } catch (_: RuntimeException) {
            indexDecodeEnabled = false
            nextTimestampUs = sequentialFrameRate?.let {
                (frameIndex.toDouble() * 1_000_000.0 / it).roundToLong().coerceAtLeast(0L)
            } ?: nextTimestampUs
        }
    }

    private fun nextFrameFromTimestamp(currentIndex: Long): AnalysisFrame? {
        if (durationMs > 0L && nextTimestampUs / 1000L >= durationMs) {
            finished = true
            return null
        }
        val timestampMs = nextTimestampUs / 1000L
        val bitmap = retriever.getFrameAtTime(
            nextTimestampUs,
            MediaMetadataRetriever.OPTION_CLOSEST,
        ) ?: run {
            finished = true
            return null
        }
        val intervalMs = sequentialFrameRate?.let {
            (1000.0 / it).coerceAtLeast(1.0)
        } ?: 33.333
        nextTimestampUs += (intervalMs * 1000.0).roundToLong()
        frameIndex = currentIndex + 1L
        return AnalysisFrame(
            index = currentIndex,
            timestampMs = timestampMs,
            payload = bitmap,
            width = bitmap.width,
            height = bitmap.height,
        )
    }

    override suspend fun close() {
        finished = true
        pendingFrames.clear()
        retriever.release()
    }
}
