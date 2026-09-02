package com.smarttraffic.app.data.analysis

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.smarttraffic.app.domain.analysis.AnalysisFrame
import com.smarttraffic.app.domain.analysis.FrameSource
import com.smarttraffic.app.domain.analysis.MediaSource

/**
 * FrameSource for local video/image URIs selected by the Analysis Lab.
 * Video frames are sampled by presentation timestamps so the analysis engine
 * receives a real monotonic media clock instead of assuming a fixed FPS.
 */
class LocalVideoFrameSource(
    private val context: Context,
    private val uri: Uri,
    private val sampleIntervalMs: Long = 100L,
) : FrameSource {
    private val retriever = MediaMetadataRetriever()
    private var nextTimestampUs = 0L
    private var frameIndex = 0L
    private var finished = false

    private val durationMs: Long
    private val width: Int
    private val height: Int
    private val frameRate: Double?

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
        source = MediaSource(
            id = uri.toString(),
            uri = uri.toString(),
            frameRate = frameRate,
            width = width,
            height = height,
        )
    }

    override suspend fun nextFrame(): AnalysisFrame? {
        if (finished) return null
        if (durationMs > 0L && nextTimestampUs / 1000L >= durationMs) {
            finished = true
            return null
        }

        val timestampMs = nextTimestampUs / 1000L
        val bitmap = retriever.getFrameAtTime(
            nextTimestampUs,
            MediaMetadataRetriever.OPTION_CLOSEST,
        )
        if (bitmap == null) {
            finished = true
            return null
        }

        nextTimestampUs += sampleIntervalMs.coerceAtLeast(1L) * 1000L
        val currentIndex = frameIndex++
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
