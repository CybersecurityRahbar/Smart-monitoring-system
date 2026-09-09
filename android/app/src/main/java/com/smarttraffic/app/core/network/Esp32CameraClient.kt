package com.smarttraffic.app.core.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.smarttraffic.app.core.DeviceSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** HTTP control client for the Smart Traffic ESP32 camera contract. */
class Esp32CameraClient(
    private val connectTimeoutMs: Int = 3000,
    private val readTimeoutMs: Int = 5000,
) {
    suspend fun capture(): Bitmap = withContext(Dispatchers.IO) {
        getBitmap(DeviceSettings.captureUrl())
    }

    suspend fun setFlash(on: Boolean): String = withContext(Dispatchers.IO) {
        getText(buildControlUrl("flash", mapOf("on" to if (on) "1" else "0")))
    }

    suspend fun setJpegQuality(value: Int): String = withContext(Dispatchers.IO) {
        val quality = value.coerceIn(5, 63)
        getText(buildControlUrl("quality", mapOf("value" to quality.toString())))
    }

    suspend fun status(): String = withContext(Dispatchers.IO) {
        getText(DeviceSettings.statusUrl())
    }

    private fun buildControlUrl(action: String, args: Map<String, String>): String {
        val query = buildString {
            append("action=")
            append(URLEncoder.encode(action, StandardCharsets.UTF_8.name()))
            args.forEach { (key, value) ->
                append('&')
                append(URLEncoder.encode(key, StandardCharsets.UTF_8.name()))
                append('=')
                append(URLEncoder.encode(value, StandardCharsets.UTF_8.name()))
            }
        }
        return "${DeviceSettings.controlUrl()}?$query"
    }

    private fun getBitmap(urlString: String): Bitmap {
        val connection = open(urlString)
        return try {
            check(connection.responseCode in 200..299) { responseError(connection) }
            val bitmap = connection.inputStream.use { BitmapFactory.decodeStream(it) }
            requireNotNull(bitmap) { "Capture endpoint returned invalid image data" }
        } finally {
            connection.disconnect()
        }
    }

    private fun getText(urlString: String): String {
        val connection = open(urlString)
        return try {
            check(connection.responseCode in 200..299) { responseError(connection) }
            BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(urlString: String): HttpURLConnection =
        (URL(urlString).openConnection() as HttpURLConnection).apply {
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            requestMethod = "GET"
            instanceFollowRedirects = false
            useCaches = false
            doInput = true
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("Pragma", "no-cache")
        }

    private fun responseError(connection: HttpURLConnection): String {
        val code = connection.responseCode
        val body = runCatching {
            connection.errorStream?.let { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader -> reader.readText().take(512) }
            }
        }.getOrNull().orEmpty()
        return if (body.isBlank()) "HTTP $code" else "HTTP $code: $body"
    }
}
