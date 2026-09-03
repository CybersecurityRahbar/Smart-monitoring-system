package com.smarttraffic.app.data.analysis

import android.graphics.Bitmap
import com.smarttraffic.app.core.network.MjpegStreamClient
import com.smarttraffic.app.domain.analysis.AnalysisFrame
import com.smarttraffic.app.domain.analysis.FrameSource
import com.smarttraffic.app.domain.analysis.FrameTimestampPrecision
import com.smarttraffic.app.domain.analysis.MediaSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import android.os.SystemClock

/**
 * Real live-camera FrameSource backed by the ESP32 MJPEG endpoint.
 *
 * The queue is intentionally bounded to one frame: when inference is slower than the camera,
 * stale frames are dropped so tracking follows the live scene instead of building latency.
 * Arrival timestamps are not source presentation timestamps and therefore cannot unlock
 * measurement-grade physical speed.
 */
class MjpegFrameSource(
    private val url: String,
    private val client: MjpegStreamClient = MjpegStreamClient(),
    private val scope: CoroutineScope,
) : FrameSource {
    private val frames: Channel<Bitmap> = Channel(capacity = Channel.CONFLATED)
    private var producer: Job? = null
    private var closed = false
    private var frameIndex = 0L
    private var droppedFrames = 0L

    override val source: MediaSource = MediaSource(
        id = url,
        uri = url,
        frameRate = null,
        width = null,
        height = null,
        timestampPrecision = FrameTimestampPrecision.UNKNOWN,
    )

    init {
        producer = scope.launch(Dispatchers.IO) {
            try {
                client.collect(url) { bitmap ->
                    if (frames.trySend(bitmap).isFailure) {
                        droppedFrames++
                    }
                }
            } catch (_: CancellationException) {
                throw _
            } catch (error: Throwable) {
                frames.close(error)
            } finally {
                frames.close()
            }
        }
    }

    override suspend fun nextFrame(): AnalysisFrame? {
        if (closed) return null
        return try {
            val bitmap = frames.receiveCatching().getOrNull() ?: return null
            val index = frameIndex++
            AnalysisFrame(
                index = index,
                timestampMs = SystemClock.elapsedRealtime(),
                payload = bitmap,
                width = bitmap.width,
                height = bitmap.height,
            )
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            throw IllegalStateException("MJPEG frame source failed for $url", error)
        }
    }

    override suspend fun close() {
        closed = true
        producer?.cancel()
        producer = null
        frames.close()
    }

    fun droppedFrameCount(): Long = droppedFrames
}
