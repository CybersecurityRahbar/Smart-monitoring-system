package com.smarttraffic.app.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.smarttraffic.app.core.tr
import com.smarttraffic.app.features.alerts.AlertsScreen
import com.smarttraffic.app.features.analysis.LocalAnalysisScreen
import com.smarttraffic.app.features.calibration.CalibrationLabScreen
import com.smarttraffic.app.features.dashboard.DashboardScreen
import com.smarttraffic.app.features.devices.DevicesScreen
import com.smarttraffic.app.features.evidence.EvidenceScreen
import com.smarttraffic.app.features.live.LiveCameraScreen
import com.smarttraffic.app.features.more.OperationsHubScreen
import com.smarttraffic.app.features.radar.IntelligentRadarScreen
import com.smarttraffic.app.features.reports.IncidentReportsScreen
import com.smarttraffic.app.features.rules.TrafficRulesScreen
import com.smarttraffic.app.features.settings.SettingsScreen
import com.smarttraffic.app.features.watchlist.WatchlistScreen

enum class AppDestination {
    Dashboard, Radar, Live, Alerts, More, Reports, Analysis, Calibration, Devices, Rules, Watchlist, Evidence, Settings
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavHost() {
    var destination by remember { mutableStateOf(AppDestination.Dashboard) }

    val isNested = destination in setOf(
        AppDestination.Reports,
        AppDestination.Analysis,
        AppDestination.Calibration,
        AppDestination.Devices,
        AppDestination.Rules,
        AppDestination.Watchlist,
        AppDestination.Evidence,
        AppDestination.Settings,
    )

    fun navigateBackInApp() {
        destination = if (isNested) AppDestination.More else AppDestination.Dashboard
    }

    BackHandler(enabled = true) {
        if (isNested) {
            navigateBackInApp()
        } else if (destination != AppDestination.Dashboard) {
            destination = AppDestination.Dashboard
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            if (isNested) {
                CenterAlignedTopAppBar(
                    title = { Text(nestedTitle(destination)) },
                    navigationIcon = {
                        IconButton(onClick = ::navigateBackInApp) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("back"))
                        }
                    },
                )
            }
        },
        bottomBar = {
            NavigationBar {
                val primary = listOf(AppDestination.Dashboard, AppDestination.Radar, AppDestination.Live, AppDestination.Alerts, AppDestination.More)
                primary.forEach { item ->
                    NavigationBarItem(
                        selected = destination == item,
                        onClick = { destination = item },
                        icon = {
                            Icon(
                                when (item) {
                                    AppDestination.Dashboard -> Icons.Filled.Dashboard
                                    AppDestination.Radar -> Icons.Filled.Speed
                                    AppDestination.Live -> Icons.Filled.Videocam
                                    AppDestination.Alerts -> Icons.Filled.Notifications
                                    AppDestination.More -> Icons.Filled.MoreHoriz
                                    else -> Icons.Filled.Dashboard
                                },
                                contentDescription = destinationLabel(item),
                            )
                        },
                        label = { Text(destinationLabel(item)) },
                    )
                }
            }
        },
    ) { paddingValues ->
        when (destination) {
            AppDestination.Dashboard -> DashboardScreen(paddingValues, onOpenRadar = { destination = AppDestination.Radar }, onOpenLive = { destination = AppDestination.Live }, onOpenAnalysis = { destination = AppDestination.Analysis })
            AppDestination.Radar -> IntelligentRadarScreen(paddingValues)
            AppDestination.Live -> LiveCameraScreen(paddingValues)
            AppDestination.Alerts -> AlertsScreen(paddingValues)
            AppDestination.More -> OperationsHubScreen(
                paddingValues,
                onReports = { destination = AppDestination.Reports },
                onAnalysis = { destination = AppDestination.Analysis },
                onCalibration = { destination = AppDestination.Calibration },
                onDevices = { destination = AppDestination.Devices },
                onRules = { destination = AppDestination.Rules },
                onWatchlist = { destination = AppDestination.Watchlist },
                onEvidence = { destination = AppDestination.Evidence },
                onSettings = { destination = AppDestination.Settings },
            )
            AppDestination.Reports -> IncidentReportsScreen(paddingValues)
            AppDestination.Analysis -> LocalAnalysisScreen(paddingValues)
            AppDestination.Calibration -> CalibrationLabScreen(paddingValues)
            AppDestination.Devices -> DevicesScreen(paddingValues)
            AppDestination.Rules -> TrafficRulesScreen(paddingValues)
            AppDestination.Watchlist -> WatchlistScreen(paddingValues)
            AppDestination.Evidence -> EvidenceScreen(paddingValues)
            AppDestination.Settings -> SettingsScreen(paddingValues)
        }
    }
}

private fun nestedTitle(destination: AppDestination): String = when (destination) {
    AppDestination.Reports -> "Incident Reports"
    AppDestination.Analysis -> "Local Analysis Lab"
    AppDestination.Calibration -> "Camera Calibration"
    AppDestination.Devices -> "Devices"
    AppDestination.Rules -> "Traffic Rules"
    AppDestination.Watchlist -> "Watchlist"
    AppDestination.Evidence -> "Evidence"
    AppDestination.Settings -> "Settings"
    else -> ""
}

private fun destinationLabel(destination: AppDestination): String = when (destination) {
    AppDestination.Dashboard -> "Dashboard"
    AppDestination.Radar -> "Radar"
    AppDestination.Live -> "Live"
    AppDestination.Alerts -> "Alerts"
    AppDestination.More -> "More"
    else -> nestedTitle(destination)
}
