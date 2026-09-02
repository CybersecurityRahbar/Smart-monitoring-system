package com.smarttraffic.app.features.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.DevicesOther
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.smarttraffic.app.core.tr

private data class HubItem(val key: String, val labelKey: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val onClick: () -> Unit)

@Composable
fun OperationsHubScreen(
    paddingValues: PaddingValues,
    onReports: () -> Unit,
    onAnalysis: () -> Unit,
    onDevices: () -> Unit,
    onRules: () -> Unit,
    onWatchlist: () -> Unit,
    onEvidence: () -> Unit,
    onSettings: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(paddingValues).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column {
            Text(tr("controlCenter"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(tr("more"), style = MaterialTheme.typography.headlineMedium)
            Text(tr("operationsDescription"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        val items = listOf(
            HubItem("reports", "incidentReports", Icons.Filled.Assignment, onReports),
            HubItem("analysis", "analysisLab", Icons.Filled.Analytics, onAnalysis),
            HubItem("devices", "devices", Icons.Filled.DevicesOther, onDevices),
            HubItem("rules", "rules", Icons.Filled.Rule, onRules),
            HubItem("watchlist", "watchlist", Icons.Filled.VerifiedUser, onWatchlist),
            HubItem("evidence", "evidence", Icons.Filled.PhotoLibrary, onEvidence),
            HubItem("settings", "settings", Icons.Filled.Settings, onSettings),
        )

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items, key = { it.key }) { item ->
                Card(onClick = item.onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                        Icon(item.icon, null, tint = MaterialTheme.colorScheme.primary)
                        Text(tr(item.labelKey), style = MaterialTheme.typography.titleMedium)
                        Text(tr("open"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
