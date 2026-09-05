package com.smarttraffic.app.features.alerts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smarttraffic.app.SmartTrafficApplication
import com.smarttraffic.app.core.AppLanguage
import com.smarttraffic.app.core.AppSettings
import com.smarttraffic.app.core.tr
import com.smarttraffic.app.domain.analysis.AnalysisSessionState
import kotlinx.coroutines.flow.MutableStateFlow

@Composable
fun AlertsScreen(paddingValues: PaddingValues) {
    val context = LocalContext.current
    val application = context.applicationContext as? SmartTrafficApplication
    val session = application?.analysisHost?.session
    val fallbackState = remember { MutableStateFlow(AnalysisSessionState()) }
    val stateFlow = remember(session) { session?.state ?: fallbackState }
    val sessionState by stateFlow.collectAsStateWithLifecycle()
    val events = sessionState.result?.trafficEvents.orEmpty()
    val ar = AppSettings.language == AppLanguage.ARABIC

    Column(
        Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(18.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Filled.Notifications, null, tint = MaterialTheme.colorScheme.primary)
            Text(tr("alerts"), style = MaterialTheme.typography.headlineMedium)
        }
        Card(shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (events.isEmpty()) tr("noActiveEvents") else if (ar) "${events.size} مخالفة في آخر نتيجة تحليل" else "${events.size} event(s) in the latest analysis result",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    if (events.isEmpty()) {
                        if (sessionState.result == null) {
                            if (ar) "لا توجد نتيجة تحليل مكتملة بعد." else "There is no completed analysis result yet."
                        } else {
                            if (ar) "لم تُسجل مخالفات ضمن النتيجة الأخيرة." else "No traffic-rule violations were produced by the latest result."
                        }
                    } else {
                        if (ar) "هذه الأحداث صادرة من محرك القواعد وليست تنبيهات تجريبية." else "These events come from the real analysis rule engine; they are not placeholder alerts."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (events.isNotEmpty()) {
            events.forEach { event ->
                Card(shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Filled.WarningAmber, null, tint = MaterialTheme.colorScheme.error)
                            Text(event.type, style = MaterialTheme.typography.titleLarge)
                        }
                        Metric(if (ar) "المسار" else "Track", event.trackId.toString())
                        Metric(if (ar) "السرعة" else "Speed", "%.1f km/h".format(event.measuredSpeedKmh))
                        Metric(if (ar) "الحد" else "Limit", "%.1f km/h".format(event.thresholdKmh))
                        Metric(if (ar) "الثقة" else "Confidence", "%.0f%%".format(event.confidence * 100f))
                        Metric(if (ar) "الزمن" else "Timestamp", "${event.timestampMs} ms")
                        Metric(if (ar) "المعايرة" else "Calibration", event.calibrationId ?: "—")
                    }
                }
            }
        }

        Card(shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tr("alertCategories"), style = MaterialTheme.typography.titleMedium)
                Text(
                    tr("alertCategoriesDetail"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Metric(title: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.labelLarge)
    }
}
