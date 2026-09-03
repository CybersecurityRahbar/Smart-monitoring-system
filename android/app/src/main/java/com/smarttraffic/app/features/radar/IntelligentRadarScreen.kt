package com.smarttraffic.app.features.radar

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smarttraffic.app.core.TrafficRulePreferences
import com.smarttraffic.app.core.tr
import com.smarttraffic.app.data.vision.DetectorModelRegistry
import com.smarttraffic.app.domain.analysis.AnalysisConfig
import com.smarttraffic.app.domain.analysis.AnalysisPreviewFrame
import com.smarttraffic.app.domain.analysis.Track
import com.smarttraffic.app.features.analysis.AnalysisMediaType
import com.smarttraffic.app.features.analysis.AnalysisRadarPreview
import com.smarttraffic.app.features.analysis.AnalysisRunPhase
import com.smarttraffic.app.features.analysis.LocalAnalysisViewModel

@Composable
fun IntelligentRadarScreen(
    paddingValues: PaddingValues,
    viewModel: LocalAnalysisViewModel = viewModel(),
) {
    var minSpeed by remember { mutableFloatStateOf(0f) }
    var radarRunning by remember { mutableStateOf(false) }
    var vehiclesOnly by remember { mutableStateOf(true) }
    var highConfidence by remember { mutableStateOf(false) }
    var forwardOnly by remember { mutableStateOf(false) }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }

    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val modelSpec = remember { DetectorModelRegistry.requireSpec("yolo26n") }
    val modelInstalled = remember { DetectorModelRegistry.isInstalled(context, modelSpec) }
    val filteredPreview = remember(preview, minSpeed, vehiclesOnly, highConfidence, forwardOnly) {
        filterPreview(preview, minSpeed, vehiclesOnly, highConfidence, forwardOnly)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        selectedUri = uri
        radarRunning = false
        if (uri != null) viewModel.reset()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Analytics, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(8.dp))
                    Text(tr("radar"), style = MaterialTheme.typography.headlineSmall)
                }
                Text(
                    "Live radar is now powered by the same detector + tracker + geometry pipeline as the Analysis Lab.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AssistChip(
                onClick = {
                    if (state.phase == AnalysisRunPhase.RUNNING) {
                        viewModel.reset()
                        radarRunning = false
                    } else {
                        radarRunning = !radarRunning
                    }
                },
                leadingIcon = { Icon(if (radarRunning) Icons.Filled.Stop else Icons.Filled.PlayArrow, null) },
                label = { Text(if (radarRunning) tr("running") else tr("standby"), maxLines = 1) },
            )
        }

        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.VideoLibrary, null, Modifier.size(20.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Radar source", style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    selectedUri?.toString() ?: "Choose a local traffic video. The same video may also be analyzed in the Local Analysis Lab.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { picker.launch(arrayOf("video/*")) },
                        enabled = state.phase != AnalysisRunPhase.RUNNING,
                    ) {
                        Text("Choose video")
                    }
                    Button(
                        onClick = {
                            val uri = selectedUri ?: return@Button
                            val rules = TrafficRulePreferences.load(context)
                            radarRunning = true
                            viewModel.run(
                                uri = uri,
                                mediaType = AnalysisMediaType.VIDEO,
                                config = AnalysisConfig(
                                    detectorModel = modelSpec.id,
                                    tracker = "bytetrack",
                                    minimumDetectionConfidence = 0.20f,
                                    useAppearanceAssociation = true,
                                    useGroundPlane = false,
                                    enableRules = rules.enabled,
                                    trafficRules = rules,
                                    showRadarOverlay = true,
                                ),
                            )
                        },
                        enabled = selectedUri != null && modelInstalled && state.phase != AnalysisRunPhase.RUNNING,
                    ) {
                        Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Start radar")
                    }
                }
            }
        }

        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Tune, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(8.dp))
                    Column {
                        Text(tr("targetPolicy"), style = MaterialTheme.typography.titleMedium)
                        Text("Filters are applied to the real tracked output, not a separate radar simulation.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(tr("minimumSpeed") + ": ${minSpeed.toInt()} km/h", style = MaterialTheme.typography.bodyMedium)
                Slider(value = minSpeed, onValueChange = { minSpeed = it }, valueRange = 0f..160f)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = vehiclesOnly, onClick = { vehiclesOnly = !vehiclesOnly }, label = { Text(tr("vehicles")) })
                    FilterChip(selected = highConfidence, onClick = { highConfidence = !highConfidence }, label = { Text(tr("highConfidence")) })
                    FilterChip(selected = forwardOnly, onClick = { forwardOnly = !forwardOnly }, label = { Text(tr("forward")) })
                }
            }
        }

        if (filteredPreview != null) {
            AnalysisRadarPreview(filteredPreview, Modifier.fillMaxWidth())
        } else {
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Speed, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(8.dp))
                    Column {
                        Text("Tracking standby", style = MaterialTheme.typography.labelLarge)
                        Text(
                            "No processed frame yet. Select a traffic video and start the real radar pipeline.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(
                Modifier.fillMaxWidth().padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Tracking status", style = MaterialTheme.typography.labelLarge)
                    Text(
                        state.message ?: "Real detector/tracker state",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Metric((filteredPreview?.tracks?.size ?: 0).toString(), tr("tracks"))
                    val maxSpeed = filteredPreview?.speedEstimates?.values?.maxOfOrNull { it.kilometersPerHour }
                    Metric(maxSpeed?.let { "%.1f".format(it) } ?: "—", tr("speed"))
                }
            }
        }
    }
}

private fun filterPreview(
    preview: AnalysisPreviewFrame?,
    minSpeed: Float,
    vehiclesOnly: Boolean,
    highConfidence: Boolean,
    forwardOnly: Boolean,
): AnalysisPreviewFrame? {
    if (preview == null) return null
    val tracks = preview.tracks.filter { track: Track ->
        val speed = preview.speedEstimates[track.id]
        val classAllowed = !vehiclesOnly || track.className.lowercase() in setOf("car", "motorcycle", "bus", "truck", "vehicle")
        val speedAllowed = speed == null || speed.kilometersPerHour >= minSpeed.toDouble()
        val confidenceAllowed = !highConfidence || track.trackConfidence >= 0.75f
        val directionAllowed = if (!forwardOnly) true else speed?.directionDegrees?.let { angle -> kotlin.math.cos(Math.toRadians(angle)) > 0.0 } ?: false
        classAllowed && speedAllowed && confidenceAllowed && directionAllowed
    }
    val ids = tracks.map { it.id }.toSet()
    return preview.copy(
        tracks = tracks,
        speedEstimates = preview.speedEstimates.filterKeys { it in ids },
    )
}

@Composable
private fun Metric(value: String, label: String) {
    Column(horizontalAlignment = Alignment.End) {
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
