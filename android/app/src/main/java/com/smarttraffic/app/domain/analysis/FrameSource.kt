/** Timestamped image frame entering the shared analysis pipeline. */
data class AnalysisFrame(
    val index: Long,
    val timestampMs: Long,
    val payload: Any,
    val width: Int,
    val height: Int,
    /** Absolute source timeline origin used to align recorded-media playback with analysis PTS. */
    val timelineStartTimestampMs: Long? = null,
)

/** Transport-independent frame source for local media, ESP32 MJPEG, or future camera sources. */
interface FrameSource {
    val source: MediaSource
    val droppedFrameCount: Long
        get() = 0L
    suspend fun nextFrame(): AnalysisFrame?
    suspend fun close()
}
