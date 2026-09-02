package com.smarttraffic.app.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.smarttraffic.app.core.tr
import com.smarttraffic.app.features.alerts.AlertsScreen
import com.smarttraffic.app.features.analysis.LocalAnalysisScreen
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
    Dashboard, Radar, Live, Alerts, More, Reports, Analysis, Devices, Rules, Watchlist, Evidence, Settings
}

@Composable
fun AppNavHost() {
    var destination by remember { mutableStateOf(AppDestination.Dashboard) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                val primary = listOf(
                    AppDestination.Dashboard,
                    AppDestination.Radar,
                    AppDestination.Live,
                    AppDestination.Alerts,
                    AppDestination.More,
                )
                primary.forEach { item ->
                    NavigationBarItem(
                        selected = destination == item,
                        onClick = { destination = item },
                        icon = {
                            Icon(
                                imageVector = when (item) {
                                    AppDestination.Dashboard -> Icons.Filled.Dashboard
                                    AppDestination.Radar -> Icons.Filled.Speed
                                    AppDestination.Live -> Icons.Filled.Videocam
                                    AppDestination.Alerts -> Icons.Filled.Notifications
                                    AppDestination.More -> Icons.Filled.MoreHoriz
                                    else -> Icons.Filled.MoreHoriz
                                },
                                contentDescription = item.name,
                            )
                        },
                        label = {
                            Text(
                                when (item) {
                                    AppDestination.Dashboard -> tr("dashboard")
                                    AppDestination.Radar -> tr("radar")
                                    AppDestination.Live -> tr("live")
                                    AppDestination.Alerts -> tr("alerts")
                                    AppDestination.More -> tr("more")
                                    else -> item.name
                                },
                            )
                        },
                    )
                }
            }
        },
    ) { paddingValues ->
        when (destination) {
            AppDestination.Dashboard -> DashboardScreen(paddingValues)
            AppDestination.Radar -> IntelligentRadarScreen(paddingValues)
            AppDestination.Live -> LiveCameraScreen(paddingValues)
            AppDestination.Alerts -> AlertsScreen(paddingValues)
            AppDestination.More -> OperationsHubScreen(
                paddingValues = paddingValues,
                onReports = { destination = AppDestination.Reports },
                onAnalysis = { destination = AppDestination.Analysis },
                onDevices = { destination = AppDestination.Devices },
                onRules = { destination = AppDestination.Rules },
                onWatchlist = { destination = AppDestination.Watchlist },
                onEvidence = { destination = AppDestination.Evidence },
                onSettings = { destination = AppDestination.Settings },
            )
            AppDestination.Reports -> IncidentReportsScreen(paddingValues)
            AppDestination.Analysis -> LocalAnalysisScreen(paddingValues)
            AppDestination.Devices -> DevicesScreen(paddingValues)
            AppDestination.Rules -> TrafficRulesScreen(paddingValues)
            AppDestination.Watchlist -> WatchlistScreen(paddingValues)
            AppDestination.Evidence -> EvidenceScreen(paddingValues)
            AppDestination.Settings -> SettingsScreen(paddingValues)
        }
    }
}
