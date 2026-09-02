package com.smarttraffic.app.core

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Persistent operator-configured profile for an ESP32 camera endpoint. */
object DeviceSettings {
    private const val PREFS = "smart_traffic_device"
    private const val HOST = "host"
    private const val HTTP_PORT = "http_port"
    private const val STREAM_PATH = "stream_path"
    private const val CAPTURE_PATH = "capture_path"
    private const val STATUS_PATH = "status_path"

    var host by mutableStateOf("192.168.4.1")
        private set
    var httpPort by mutableStateOf(80)
        private set
    var streamPath by mutableStateOf("/stream")
        private set
    var capturePath by mutableStateOf("/capture")
        private set
    var statusPath by mutableStateOf("/status")
        private set

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        host = prefs.getString(HOST, host) ?: host
        httpPort = prefs.getInt(HTTP_PORT, httpPort).coerceIn(1, 65535)
        streamPath = normalizePath(prefs.getString(STREAM_PATH, streamPath) ?: streamPath)
        capturePath = normalizePath(prefs.getString(CAPTURE_PATH, capturePath) ?: capturePath)
        statusPath = normalizePath(prefs.getString(STATUS_PATH, statusPath) ?: statusPath)
    }

    fun save(
        context: Context,
        newHost: String,
        newHttpPort: Int,
        newStreamPath: String,
        newCapturePath: String,
        newStatusPath: String,
    ) {
        val cleanHost = newHost.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
        val cleanPort = newHttpPort.coerceIn(1, 65535)
        val cleanStream = normalizePath(newStreamPath)
        val cleanCapture = normalizePath(newCapturePath)
        val cleanStatus = normalizePath(newStatusPath)

        host = cleanHost
        httpPort = cleanPort
        streamPath = cleanStream
        capturePath = cleanCapture
        statusPath = cleanStatus

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(HOST, host)
            .putInt(HTTP_PORT, httpPort)
            .putString(STREAM_PATH, streamPath)
            .putString(CAPTURE_PATH, capturePath)
            .putString(STATUS_PATH, statusPath)
            .apply()
    }

    fun baseUrl(): String = "http://$host:$httpPort"
    fun streamUrl(): String = baseUrl() + streamPath
    fun captureUrl(): String = baseUrl() + capturePath
    fun statusUrl(): String = baseUrl() + statusPath

    private fun normalizePath(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return "/"
        return if (trimmed.startsWith('/')) trimmed else "/$trimmed"
    }
}
