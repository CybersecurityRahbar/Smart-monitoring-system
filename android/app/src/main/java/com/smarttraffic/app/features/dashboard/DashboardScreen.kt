package com.smarttraffic.app.features.dashboard

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAlert
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smarttraffic.app.SmartTrafficApplication
import com.smarttraffic.app.core.tr
import com.smarttraffic.app.domain.analysis.AnalysisSessionPhase
import com.smarttraffic.app.domain.analysis.AnalysisSessionState
import kotlinx.coroutines.flow.MutableStateFlow

@Composable
fun DashboardScreen(
    paddingValues: PaddingValues,
    onReports: (() -> Unit)? = null,
    onOpenRadar: (() -> Unit)? = null,
    onOpenLive: (() -> Unit)? = null,
    onOpenAnalysis: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val application = context.applicationContext as? SmartTrafficApplication
    val session = application?.analysisHost?.session
    val fallbackState = remember { MutableStateFlow(AnalysisSessionState()) }
    val observedStateFlow = remember(session) { session?.state ?: fallbackState }
    val sessionState by observedStateFlow.collectAsStateWithLifecycle()
    val result = sessionState.result
    val vehicleCount = result?.tracks?.size ?: 0
    val averageSpeed = result?.speedEstimates?.values
        ?.map { it.kilometersPerHour }
        ?.filter { it.isFinite() }
        ?.takeIf { it.isNotEmpty() }
        ?.average()
    val alertCount = result?.trafficEvents?.size ?: 0
    val status = dashboardStatus(sessionState.phase)

    Column(
        modifier = Modifier
            .padding(paddingValues)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "SMART TRAFFIC",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 1.8.sp,
                )
                Text(tr("monitoringCenter"), style = MaterialTheme.typography.headlineMedium)
                Text(
                    tr("situationalAwareness"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AssistChip(
                onClick = {},
                label = { Text(status.label, maxLines = 1) },
                leadingIcon = { Icon(if (status.isHealthy) Icons.Filled.CheckCircle else Icons.Filled.WarningAmber, null, Modifier.size(18.dp)) },
            )
        }

        application?.previousAnalysisWarning?.let { warning ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                shape = RoundedCornerShape(20.dp),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Previous analysis diagnostic", style = MaterialTheme.typography.titleMedium)
                    Text(warning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }

        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(Modifier.padding(19.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(13.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                        Icon(Icons.Filled.CameraAlt, null, Modifier.padding(9.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Spacer(Modifier.size(11.dp))
                    Column(Modifier.weight(1f)) {
                        Text(tr("primaryCamera"), style = MaterialTheme.typography.titleMedium)
                        Text(tr("localNetwork"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(status.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    AssistChip(onClick = { onOpenLive?.invoke() }, label = { Text(tr("openLive"), maxLines = 1) })
                    AssistChip(onClick = { onOpenRadar?.invoke() }, label = { Text(tr("radar"), maxLines = 1) })
                    AssistChip(onClick = { onOpenAnalysis?.invoke() }, label = { Text(tr("analysisLab"), maxLines = 1) })
                }
            }
        }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(
                listOf(
                    Metric(tr("vehicles"), vehicleCount.toString(), tr("tracks")),
                    Metric(tr("averageSpeed"), averageSpeed?.let { "%.1f".format(it) } ?: "—", "km/h"),
                    Metric(tr("activeAlerts"), alertCount.toString(), tr("events")),
                ),
                key = { it.title },
            ) { metric ->
                MetricCard(metric, Modifier.size(width = 150.dp, height = 118.dp))
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(onClick = { onReports?.invoke() }, enabled = onReports != null) {
                Icon(Icons.Filled.AddAlert, null, Modifier.size(18.dp))
                Spacer(Modifier.size(5.dp))
                Text(tr("newReport"), maxLines = 1)
            }
            Button(onClick = { onReports?.invoke() }, enabled = onReports != null) {
                Icon(Icons.Filled.Report, null, Modifier.size(18.dp))
                Spacer(Modifier.size(5.dp))
                Text(tr("incidentReports"), maxLines = 1)
            }
        }

        Text(tr("operationalStatus"), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            StatusCard(tr("visionRadar"), status.detail, Icons.Filled.Speed, Modifier.weight(1f))
            StatusCard(
                tr("alerts"),
                if (alertCount == 0) tr("noActiveEvents") else "$alertCount event(s) in latest result",
                Icons.Filled.WarningAmber,
                Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}

private data class DashboardStatus(val label: String, val detail: String, val isHealthy: Boolean)

private fun dashboardStatus(phase: AnalysisSessionPhase): DashboardStatus = when (phase) {
    AnalysisSessionPhase.IDLE -> DashboardStatus("READY", "Analysis engine is idle and ready for a new session.", true)
    AnalysisSessionPhase.STARTING -> DashboardStatus("STARTING", "Preparing detector, source and analysis pipeline…", true)
    AnalysisSessionPhase.RUNNING -> DashboardStatus("RUNNING", "Live detector, tracker and radar pipeline are active.", true)
    AnalysisSessionPhase.COMPLETED -> DashboardStatus("COMPLETED", "The latest analysis result is available.", true)
    AnalysisSessionPhase.FAILED -> DashboardStatus("ERROR", "The latest analysis session failed. Inspect the analysis diagnostics.", false)
    AnalysisSessionPhase.STOPPED -> DashboardStatus("STOPPED", "The latest analysis session was stopped.", true)
}

private data class Metric(val title: String, val value: String, val unit: String)

@Composable
private fun MetricCard(metric: Metric, modifier: Modifier) {
    Card(modifier = modifier, shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(metric.title, style = MaterialTheme.typography.labelMedium, maxLines = 2)
            Text(metric.value, style = MaterialTheme.typography.headlineSmall)
            Text(metric.unit, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatusCard(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
        }
    }
}
