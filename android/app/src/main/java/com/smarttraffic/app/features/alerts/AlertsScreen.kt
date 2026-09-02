package com.smarttraffic.app.features.alerts

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.smarttraffic.app.core.tr

@Composable
fun AlertsScreen(paddingValues: PaddingValues) {
    Column(Modifier.fillMaxSize().padding(paddingValues).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Filled.Notifications, null, tint = MaterialTheme.colorScheme.primary)
            Text(tr("alerts"), style = MaterialTheme.typography.headlineMedium)
        }
        AssistChip(onClick = {}, label = { Text(tr("noActiveEvents")) })
        Text(tr("alertsDescription"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tr("alertCategories"), style = MaterialTheme.typography.titleMedium)
                Text(tr("alertCategoriesDetail"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
