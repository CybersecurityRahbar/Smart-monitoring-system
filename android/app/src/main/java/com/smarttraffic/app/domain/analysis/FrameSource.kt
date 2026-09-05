package com.smarttraffic.app.domain.analysis

/** Timestamped image frame entering the shared analysis pipeline. */
data class AnalysisFrame(
    val index: Long,
    val timestampMs: Long,
    val payload: Any,
    val width: Int,
    val height: Int,
)

/** Transport-independent frame source for local media, ESP32 MJPEG, or future camera sources. */
interface FrameSource {
    val source: MediaSource

    /** Number of frames discarded by the source before reaching the analysis consumer. */
    val droppedFrameCount: Long
        get() = 0L

    suspend fun nextFrame(): AnalysisFrame?
    suspend fun close()
}
