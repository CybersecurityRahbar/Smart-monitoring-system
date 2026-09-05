package com.smarttraffic.app.data.analysis

import android.graphics.Bitmap
import com.smarttraffic.app.domain.analysis.AnalysisFrame
import com.smarttraffic.app.domain.analysis.FrameSource
import com.smarttraffic.app.domain.analysis.FrameTimestampPrecision
import com.smarttraffic.app.domain.analysis.MediaSource

/** One-shot source used by the Local Analysis Lab image mode. */
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
        if (closed) return
        closed = true
        if (!bitmap.isRecycled) bitmap.recycle()
    }
}
