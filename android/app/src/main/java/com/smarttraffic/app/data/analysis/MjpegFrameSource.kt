package com.smarttraffic.app.data.analysis

import android.graphics.Bitmap
import android.os.SystemClock
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
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Real live-camera FrameSource backed by the ESP32 MJPEG endpoint.
 *
 * The producer keeps exactly one pending frame. A newer frame replaces an unconsumed pending
 * frame, so inference latency can never create an unbounded queue. Analysis timestamps use local
 * monotonic arrival time; those timestamps do not unlock measurement-grade physical speed.
 */
class MjpegFrameSource(
    private val url: String,
    private val client: MjpegStreamClient = MjpegStreamClient(),
    private val scope: CoroutineScope,
) : FrameSource {
    private val latest = AtomicReference<FramePacket?>(null)
    private val wake = Channel<Unit>(capacity = Channel.CONFLATED)
    private val producedSequence = AtomicLong(-1L)
    private var producer: Job? = null
    @Volatile private var closed = false
    private var frameIndex = 0L
    private var lastConsumedSequence = -1L
    private var droppedFrames = 0L

    override val source: MediaSource = MediaSource(
        id = url,
        uri = url,
        timestampPrecision = FrameTimestampPrecision.UNKNOWN,
    )

    init {
        producer = scope.launch(Dispatchers.IO) {
            try {
                client.collect(url) { bitmap ->
                    if (closed) return@collect
                    val packet = FramePacket(producedSequence.incrementAndGet(), bitmap)
                    val replaced = latest.getAndSet(packet)
                    // The replaced Bitmap has no consumer and is therefore safe to release from
                    // the source boundary. This keeps the live producer bounded at one pending frame.
                    replaced?.bitmap?.recycleIfOwned()
                    wake.trySend(Unit)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                wake.close(error)
            } finally {
                wake.close()
            }
        }
    }

    override suspend fun nextFrame(): AnalysisFrame? {
        if (closed) return null
        while (true) {
            val notified = wake.receiveCatching()
            if (notified.isClosed && latest.get() == null) return null
            val packet = latest.getAndSet(null) ?: continue
            if (lastConsumedSequence >= 0L && packet.sequence > lastConsumedSequence + 1L) {
                droppedFrames += packet.sequence - lastConsumedSequence - 1L
            }
            lastConsumedSequence = packet.sequence
            val index = frameIndex++
            return AnalysisFrame(
                index = index,
                timestampMs = SystemClock.elapsedRealtime(),
                payload = packet.bitmap,
                width = packet.bitmap.width,
                height = packet.bitmap.height,
            )
        }
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        producer?.cancel()
        producer = null
        wake.close()
        latest.getAndSet(null)?.bitmap?.recycleIfOwned()
    }

    fun droppedFrameCount(): Long = droppedFrames

    private data class FramePacket(val sequence: Long, val bitmap: Bitmap)
}

/** The frame is source-owned until handed to the analysis pipeline. Avoid double ownership. */
private fun Bitmap.recycleIfOwned() {
    if (!isRecycled) recycle()
}
