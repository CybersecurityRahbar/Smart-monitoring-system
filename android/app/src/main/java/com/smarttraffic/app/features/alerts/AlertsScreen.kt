package com.smarttraffic.app.features.alerts

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun AlertsScreen(paddingValues: PaddingValues) {
    Column(
        modifier = Modifier.fillMaxSize().padding(paddingValues).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Alerts", style = MaterialTheme.typography.headlineMedium)
        AssistChip(onClick = {}, label = { Text("No active alerts") })
        Text("Future events: speeding, watchlist hits, device faults, camera errors, power/storage warnings.")
    }
}
