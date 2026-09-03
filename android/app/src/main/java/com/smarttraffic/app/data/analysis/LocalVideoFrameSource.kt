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
import kotlin.math.roundToLong

/**
 * FrameSource for local video/image URIs selected by the Analysis Lab.
 *
 * API 28+ videos are decoded by frame index rather than repeatedly seeking by timestamp.
 * This avoids the expensive random-seek behaviour of getFrameAtTime() and keeps analysis
 * playback much closer to the source cadence. Timestamps are still marked as requested/sample
 * time because MediaMetadataRetriever does not expose the exact decoded PTS here.
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

    private val durationMs: Long
    private val width: Int
    private val height: Int
    private val frameRate: Double?
    private val frameCount: Int?
    private val sequentialFrameRate: Double?

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

        val currentIndex = frameIndex
        val bitmap: Bitmap?
        val timestampMs: Long

        if (indexDecodeEnabled && frameCount != null) {
            if (currentIndex >= frameCount.toLong()) {
                finished = true
                return null
            }

            bitmap = try {
                retriever.getFrameAtIndex(currentIndex.toInt())
            } catch (_: RuntimeException) {
                // A few codecs expose frame-count metadata but cannot seek by index. Once this
                // happens, fall back to timestamp sampling rather than aborting the whole lab run.
                indexDecodeEnabled = false
                null
            }

            if (!indexDecodeEnabled) {
                return nextFrameFromTimestamp(currentIndex)
            }
            timestampMs = sequentialFrameRate?.let {
                (currentIndex.toDouble() * 1000.0 / it).roundToLong().coerceAtLeast(0L)
            } ?: nextTimestampUs / 1000L
        } else {
            return nextFrameFromTimestamp(currentIndex)
        }

        if (bitmap == null) {
            finished = true
            return null
        }

        frameIndex++
        return AnalysisFrame(
            index = currentIndex,
            timestampMs = timestampMs,
            payload = bitmap,
            width = bitmap.width,
            height = bitmap.height,
        )
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
        frameIndex++
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
        retriever.release()
    }
}

/** Minimal still-image FrameSource for one-frame Lab experiments. */
class LocalImageFrameSource(
    private val bitmap: Bitmap,
    private val sourceUri: String,
) : FrameSource {
    private var emitted = false

    override val source: MediaSource = MediaSource(
        id = sourceUri,
        uri = sourceUri,
        frameRate = null,
        width = bitmap.width,
        height = bitmap.height,
        timestampPrecision = FrameTimestampPrecision.EXACT_SOURCE_CLOCK,
    )

    override suspend fun nextFrame(): AnalysisFrame? {
        if (emitted) return null
        emitted = true
        return AnalysisFrame(0L, 0L, bitmap, bitmap.width, bitmap.height)
    }

    override suspend fun close() {
        emitted = true
    }
}
