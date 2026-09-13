package com.smarttraffic.app.core.network

import android.graphics.Bitmap
import java.io.OutputStream

/** Stores live frames as a multipart MJPEG recording. */
class MjpegRecorder(private val output: OutputStream) : AutoCloseable {
    private var started = false
    private var closed = false
    private var frameCount = 0L

    fun writeFrame(bitmap: Bitmap) {
        check(!closed) { "Recorder is closed" }
        val buffer = java.io.ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, buffer)) { "JPEG encoding failed" }
        val jpeg = buffer.toByteArray()
        if (!started) {
            output.write("MJPEG\r\n".toByteArray(Charsets.US_ASCII))
            started = true
        }
        output.write("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpeg.size}\r\n\r\n".toByteArray(Charsets.US_ASCII))
        output.write(jpeg)
        output.write("\r\n".toByteArray(Charsets.US_ASCII))
        frameCount++
    }

    fun stop() {
        if (closed) return
        closed = true
        output.flush()
    }

    override fun close() = stop()

    val framesRecorded: Long get() = frameCount
}
