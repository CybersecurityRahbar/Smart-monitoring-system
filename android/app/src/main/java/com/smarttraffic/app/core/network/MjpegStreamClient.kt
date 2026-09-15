package com.smarttraffic.app.core.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Job
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import kotlin.coroutines.coroutineContext

/** Dependency-free raw TCP MJPEG client for the local ESP32 camera stream. */
class MjpegStreamClient(
    private val connectTimeoutMs: Int = 3000,
    private val readTimeoutMs: Int = 7000,
    private val maxJpegBytes: Int = 8_000_000,
) {
    suspend fun collect(
        urlString: String,
        onFrame: suspend (Bitmap) -> Unit,
    ) {
        val url = URL(urlString)
        require(url.protocol.equals("http", ignoreCase = true)) {
            "MJPEG stream requires http://, got ${url.protocol}://"
        }
        val host = url.host.trim()
        require(host.isNotBlank()) { "MJPEG stream host is empty" }
        val port = if (url.port > 0) url.port else 80
        val requestTarget = url.file.ifBlank { "/" }

        val socket = Socket()
        val cancellationHandle = coroutineContext[Job]?.invokeOnCompletion {
            runCatching { socket.close() }
        }
        try {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.soTimeout = readTimeoutMs
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)

            val output = BufferedOutputStream(socket.getOutputStream(), 8 * 1024)
            val request = buildString {
                append("GET ").append(requestTarget).append(" HTTP/1.1\r\n")
                append("Host: ").append(host)
                if (port != 80) append(':').append(port)
                append("\r\n")
                append("Accept: multipart/x-mixed-replace, image/jpeg, */*\r\n")
                append("Cache-Control: no-cache\r\n")
                append("Pragma: no-cache\r\n")
                append("Connection: keep-alive\r\n")
                append("\r\n")
            }
            output.write(request.toByteArray(Charsets.ISO_8859_1))
            output.flush()

            BufferedInputStream(socket.getInputStream(), 64 * 1024).use { input ->
                val response = readHttpResponse(input)
                if (response.statusCode !in 200..299) {
                    throw MjpegStreamException("HTTP ${response.statusCode}: ${response.statusText}".trim())
                }
                val contentType = response.headers["content-type"].orEmpty()
                val boundary = parseBoundary(contentType)
                    ?: throw MjpegStreamException("MJPEG boundary not found in Content-Type: $contentType")
                val boundaryBytes = ("--$boundary").toByteArray(Charsets.ISO_8859_1)

                while (true) {
                    if (!readUntil(input, boundaryBytes)) break
                    val headerBlock = readPartHeaders(input) ?: break
                    val contentLength = headerBlock
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
        } catch (e: MjpegStreamException) {
            throw e
        } catch (e: IOException) {
            throw MjpegStreamException("TCP ${e::class.java.simpleName}: ${e.message ?: "I/O error"}")
        } finally {
            cancellationHandle?.dispose()
            runCatching { socket.close() }
        }
    }

    private data class HttpResponse(
        val statusCode: Int,
        val statusText: String,
        val headers: Map<String, String>,
    )

    private fun readHttpResponse(input: BufferedInputStream): HttpResponse {
        val block = readHeaderBlock(input) ?: throw MjpegStreamException("Empty HTTP response from MJPEG server")
        val lines = block.lineSequence().toList()
        val statusParts = lines.firstOrNull()?.trim()?.split(' ', limit = 3).orEmpty()
        val statusCode = statusParts.getOrNull(1)?.toIntOrNull()
            ?: throw MjpegStreamException("Invalid HTTP status from MJPEG server")
        val statusText = statusParts.getOrNull(2).orEmpty()
        val headers = buildMap {
            lines.drop(1).forEach { line ->
                val parts = line.split(":", limit = 2)
                if (parts.size == 2) put(parts[0].trim().lowercase(), parts[1].trim())
            }
        }
        return HttpResponse(statusCode, statusText, headers)
    }

    private fun parseBoundary(contentType: String): String? {
        val token = contentType.split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("boundary=", ignoreCase = true) }
            ?: return null
        return token.substringAfter('=').trim().trim('"').removePrefix("--").takeIf { it.isNotBlank() }
    }

    private fun readHeaderBlock(input: BufferedInputStream): String? {
        val bytes = java.io.ByteArrayOutputStream()
        var previous = -1
        while (bytes.size() <= 32 * 1024) {
            val current = input.read()
            if (current == -1) return null
            bytes.write(current)
            if (previous == '\r'.code && current == '\n'.code && endsWithCrlfCrlf(bytes)) {
                val all = bytes.toByteArray()
                return all.dropLast(4).toByteArray().toString(Charsets.ISO_8859_1)
            }
            previous = current
        }
        throw MjpegStreamException("HTTP/MJPEG headers are too large")
    }

    private fun readPartHeaders(input: BufferedInputStream): String? = readHeaderBlock(input)

    private fun endsWithCrlfCrlf(bytes: java.io.ByteArrayOutputStream): Boolean {
        if (bytes.size() < 4) return false
        val data = bytes.toByteArray()
        val n = data.size
        return data[n - 4] == '\r'.code.toByte() &&
            data[n - 3] == '\n'.code.toByte() &&
            data[n - 2] == '\r'.code.toByte() &&
            data[n - 1] == '\n'.code.toByte()
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
