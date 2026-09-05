package com.smarttraffic.app.data.analysis

import android.graphics.Bitmap
import com.smarttraffic.app.domain.analysis.AnalysisFrame
import com.smarttraffic.app.domain.analysis.FrameSource
import com.smarttraffic.app.domain.analysis.FrameTimestampPrecision
import com.smarttraffic.app.domain.analysis.MediaSource

/**
 * One-shot source used by the Local Analysis Lab image mode.
 *
 * Bitmap ownership intentionally remains with the caller/UI after the frame is emitted. The
 * application-scoped analysis session may close the source after analysis while the Compose UI
 * can still be rendering the last preview bitmap; recycling it here would make that UI reference
 * invalid. The one-shot image is therefore left for Android bitmap GC once all references leave.
 */
class LocalImageFrameSource(
    private val bitmap: Bitmap,
    sourceId: String,
) : FrameSource {
    @Volatile private var closed = false
    private var emitted = false

    override val source: MediaSource = MediaSource(
        id = sourceId,
        uri = sourceId,
        width = bitmap.width,
        height = bitmap.height,
        timestampPrecision = FrameTimestampPrecision.EXACT_SOURCE_CLOCK,
    )

    override suspend fun nextFrame(): AnalysisFrame? {
        if (closed || emitted) return null
        emitted = true
        return AnalysisFrame(
            index = 0L,
            timestampMs = 0L,
            payload = bitmap,
            width = bitmap.width,
            height = bitmap.height,
        )
    }

    override suspend fun close() {
        closed = true
        // Do not recycle the emitted bitmap: the preview may still reference it after session close.
    }
}
