package com.smarttraffic.app.features.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DevicesOther
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.smarttraffic.app.core.AppLanguage
import com.smarttraffic.app.core.AppSettings
import com.smarttraffic.app.core.DeviceSettings
import com.smarttraffic.app.core.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

@Composable
fun DevicesScreen(paddingValues: androidx.compose.foundation.layout.PaddingValues) {
    val context = LocalContext.current
    var host by remember { mutableStateOf(DeviceSettings.host) }
    var port by remember { mutableStateOf(DeviceSettings.httpPort.toString()) }
    var streamPath by remember { mutableStateOf(DeviceSettings.streamPath) }
    var capturePath by remember { mutableStateOf(DeviceSettings.capturePath) }
    var statusPath by remember { mutableStateOf(DeviceSettings.statusPath) }
    var connectionState by remember { mutableStateOf(ConnectionState.IDLE) }
    var resultMessage by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun saveProfile() {
        DeviceSettings.save(
            context = context,
            newHost = host,
            newHttpPort = port.toIntOrNull() ?: DeviceSettings.httpPort,
            newStreamPath = streamPath,
            newCapturePath = capturePath,
            newStatusPath = statusPath,
        )
    }

    Column(
        Modifier.fillMaxSize().padding(paddingValues).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Filled.DevicesOther, null, tint = MaterialTheme.colorScheme.primary)
            Text(tr("devices"), style = MaterialTheme.typography.headlineMedium)
        }

        Text(
            text = deviceText("Configure the real ESP32-CAM endpoint. Nothing is hard-coded beyond the initial prototype defaults.", "اضبط نقطة اتصال ESP32-CAM الفعلية. لا يوجد عنوان ثابت داخل التطبيق باستثناء القيم الافتراضية الأولية."),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr("deviceProfileHint"), style = MaterialTheme.typography.titleMedium)

                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text(deviceText("Host / IP", "العنوان / IP")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit).take(5) },
                    label = { Text(deviceText("HTTP port", "منفذ HTTP")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = streamPath,
                    onValueChange = { streamPath = it },
                    label = { Text(deviceText("Stream endpoint", "مسار البث")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = capturePath,
                    onValueChange = { capturePath = it },
                    label = { Text(deviceText("Capture endpoint", "مسار الالتقاط")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = statusPath,
                    onValueChange = { statusPath = it },
                    label = { Text(deviceText("Status endpoint", "مسار الحالة")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(4.dp))
                Text(
                    text = buildString {
                        append(deviceText("Base URL: ", "العنوان الأساسي: "))
                        append(baseUrlPreview(host, port.toIntOrNull() ?: 80))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            saveProfile()
                            connectionState = ConnectionState.TESTING
                            resultMessage = deviceText("Testing /status…", "جارٍ اختبار /status…")
                            scope.launch {
                                val result = testEndpoint(DeviceSettings.statusUrl())
                                connectionState = if (result.first) ConnectionState.SUCCESS else ConnectionState.ERROR
                                resultMessage = result.second
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = connectionState != ConnectionState.TESTING,
                    ) { Text(deviceText("Save + test", "حفظ واختبار")) }
                    OutlinedButton(
                        onClick = {
                            host = "192.168.4.1"
                            port = "80"
                            streamPath = "/stream"
                            capturePath = "/capture"
                            statusPath = "/status"
                            saveProfile()
                            connectionState = ConnectionState.IDLE
                            resultMessage = deviceText("Defaults restored", "تمت استعادة القيم الافتراضية")
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(tr("reset")) }
                }
            }
        }

        if (resultMessage.isNotBlank()) {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(
                        imageVector = if (connectionState == ConnectionState.SUCCESS) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
                        contentDescription = null,
                        tint = if (connectionState == ConnectionState.SUCCESS) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                    Text(resultMessage, color = if (connectionState == ConnectionState.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                }
            }
        }

        Text(
            text = deviceText(
                "The profile is stored locally and can later drive stream, capture, status and control calls. The first hardware milestone remains local Wi-Fi only.",
                "يُحفظ ملف الجهاز محليًا وسيقود لاحقًا طلبات البث والالتقاط والحالة والتحكم. المرحلة الأولى من العتاد تبقى ضمن شبكة Wi-Fi المحلية فقط.",
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private enum class ConnectionState { IDLE, TESTING, SUCCESS, ERROR }

private fun deviceText(en: String, ar: String): String = if (AppSettings.language == AppLanguage.ARABIC) ar else en

private suspend fun testEndpoint(urlString: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
    runCatching {
        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            connectTimeout = 2500
            readTimeout = 2500
            requestMethod = "GET"
            instanceFollowRedirects = true
            useCaches = false
        }
        val code = connection.responseCode
        connection.disconnect()
        if (code in 200..299) {
            true to deviceText("Connection successful • HTTP $code", "تم الاتصال بنجاح • HTTP $code")
        } else {
            false to deviceText("Device responded with HTTP $code", "الجهاز ردّ بالرمز HTTP $code")
        }
    }.getOrElse { error ->
        false to deviceText(
            "Connection failed: ${error.message ?: "network error"}",
            "تعذر الاتصال: ${error.message ?: "خطأ في الشبكة"}",
        )
    }
}

private fun baseUrlPreview(host: String, port: Int): String {
    val cleanHost = host.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
    return "http://$cleanHost:$port"
}
