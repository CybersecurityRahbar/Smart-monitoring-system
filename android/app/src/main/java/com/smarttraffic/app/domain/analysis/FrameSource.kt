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
    suspend fun nextFrame(): AnalysisFrame?
    suspend fun close()
}
