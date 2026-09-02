package com.smarttraffic.app.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.DevicesOther
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
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
import com.smarttraffic.app.features.alerts.AlertsScreen
import com.smarttraffic.app.features.dashboard.DashboardScreen
import com.smarttraffic.app.features.devices.DevicesScreen
import com.smarttraffic.app.features.live.LiveCameraScreen
import com.smarttraffic.app.features.radar.IntelligentRadarScreen
import com.smarttraffic.app.features.settings.SettingsScreen

private enum class TopLevelDestination(val label: String) {
    Dashboard("Dashboard"),
    Radar("Radar"),
    Live("Live"),
    Devices("Devices"),
    Alerts("Alerts"),
    Settings("Settings"),
}

@Composable
fun AppNavHost() {
    var destination by remember { mutableStateOf(TopLevelDestination.Dashboard) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                TopLevelDestination.entries.forEach { item ->
                    NavigationBarItem(
                        selected = destination == item,
                        onClick = { destination = item },
                        icon = {
                            Icon(
                                imageVector = when (item) {
                                    TopLevelDestination.Dashboard -> Icons.Filled.Dashboard
                                    TopLevelDestination.Radar -> Icons.Filled.Speed
                                    TopLevelDestination.Live -> Icons.Filled.CameraAlt
                                    TopLevelDestination.Devices -> Icons.Filled.DevicesOther
                                    TopLevelDestination.Alerts -> Icons.Filled.Notifications
                                    TopLevelDestination.Settings -> Icons.Filled.Settings
                                },
                                contentDescription = item.label,
                            )
                        },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { paddingValues ->
        when (destination) {
            TopLevelDestination.Dashboard -> DashboardScreen(paddingValues)
            TopLevelDestination.Radar -> IntelligentRadarScreen(paddingValues)
            TopLevelDestination.Live -> LiveCameraScreen(paddingValues)
            TopLevelDestination.Devices -> DevicesScreen(paddingValues)
            TopLevelDestination.Alerts -> AlertsScreen(paddingValues)
            TopLevelDestination.Settings -> SettingsScreen(paddingValues)
        }
    }
}
