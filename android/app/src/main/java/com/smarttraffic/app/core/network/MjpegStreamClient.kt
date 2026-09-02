package com.smarttraffic.app.core.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL

/** Lightweight dependency-free MJPEG client for local ESP32-CAM streams. */
class MjpegStreamClient(
    private val connectTimeoutMs: Int = 3000,
    private val readTimeoutMs: Int = 7000,
    private val maxJpegBytes: Int = 2_000_000,
) {
    suspend fun collect(
        urlString: String,
        onFrame: suspend (Bitmap) -> Unit,
    ) {
        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            requestMethod = "GET"
            useCaches = false
            doInput = true
        }

        try {
            val contentType = connection.contentType.orEmpty()
            val boundary = parseBoundary(contentType)
                ?: throw MjpegStreamException("MJPEG boundary not found in Content-Type: $contentType")
            if (connection.responseCode !in 200..299) {
                throw MjpegStreamException("HTTP ${connection.responseCode}")
            }

            BufferedInputStream(connection.inputStream, 64 * 1024).use { input ->
                val boundaryBytes = ("--$boundary").toByteArray(Charsets.ISO_8859_1)
                while (true) {
                    if (!readUntil(input, boundaryBytes)) break
                    val headerBytes = readHeaders(input) ?: break
                    val contentLength = headerBytes
                        .lineSequence()
                        .firstNotNullOfOrNull { line ->
                            val parts = line.split(":", limit = 2)
                            if (parts.size == 2 && parts[0].equals("Content-Length", true)) {
                                parts[1].trim().toIntOrNull()
                            } else null
                        }

                    val jpeg = when {
                        contentLength != null && contentLength in 2..maxJpegBytes -> input.readExactly(contentLength)
                        else -> readJpegByMarkers(input, maxJpegBytes)
                    }
                    val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                        ?: throw MjpegStreamException("Received an invalid JPEG frame")
                    onFrame(bitmap)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseBoundary(contentType: String): String? {
        val token = contentType.split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("boundary=", ignoreCase = true) }
            ?: return null
        return token.substringAfter('=').trim().trim('"').removePrefix("--").takeIf { it.isNotBlank() }
    }

    private fun readHeaders(input: BufferedInputStream): String? {
        val bytes = mutableListOf<Byte>()
        var previous = -1
        while (true) {
            val current = input.read()
            if (current == -1) return null
            bytes += current.toByte()
            if (previous == '\r'.code && current == '\n'.code && bytes.takeLast(4).toByteArray().contentEquals("\r\n\r\n".toByteArray())) {
                return bytes.dropLast(4).toByteArray().toString(Charsets.ISO_8859_1)
            }
            previous = current
            if (bytes.size > 16 * 1024) throw MjpegStreamException("MJPEG headers are too large")
        }
    }

    private fun readUntil(input: BufferedInputStream, target: ByteArray): Boolean {
        var matched = 0
        while (true) {
            val value = input.read()
            if (value == -1) return false
            if (value.toByte() == target[matched]) {
                matched++
                if (matched == target.size) return true
            } else {
                matched = if (value.toByte() == target[0]) 1 else 0
            }
        }
    }

    private fun readJpegByMarkers(input: BufferedInputStream, maxBytes: Int): ByteArray {
        var prev = -1
        var started = false
        val output = java.io.ByteArrayOutputStream()
        while (output.size() <= maxBytes) {
            val value = input.read()
            if (value == -1) break
            if (!started) {
                if (prev == 0xFF && value == 0xD8) {
                    output.write(0xFF)
                    output.write(0xD8)
                    started = true
                }
            } else {
                output.write(value)
                if (prev == 0xFF && value == 0xD9) return output.toByteArray()
            }
            prev = value
        }
        throw MjpegStreamException("JPEG frame exceeded $maxBytes bytes or was incomplete")
    }

    private fun BufferedInputStream.readExactly(length: Int): ByteArray {
        val result = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = read(result, offset, length - offset)
            if (count < 0) throw MjpegStreamException("Unexpected end of MJPEG frame")
            offset += count
        }
        return result
    }
}

class MjpegStreamException(message: String) : Exception(message)
