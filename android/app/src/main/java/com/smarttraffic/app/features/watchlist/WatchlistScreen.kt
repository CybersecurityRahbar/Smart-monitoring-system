package com.smarttraffic.app.features.watchlist

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.smarttraffic.app.core.tr

@Composable
fun WatchlistScreen(paddingValues: PaddingValues) {
    var plate by remember { mutableStateOf("") }
    var capture by remember { mutableStateOf(true) }
    var alert by remember { mutableStateOf(true) }
    var preserve by remember { mutableStateOf(true) }

    Column(Modifier.fillMaxSize().padding(paddingValues).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Filled.VerifiedUser, null, tint = MaterialTheme.colorScheme.primary)
            Text(tr("watchlist"), style = MaterialTheme.typography.headlineMedium)
        }
        Text(tr("watchlistDescription"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = plate, onValueChange = { plate = it }, modifier = Modifier.fillMaxWidth(), label = { Text(tr("plateNumber")) }, singleLine = true)
                Button(onClick = {}, enabled = plate.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Add, null)
                    Spacer(Modifier.size(6.dp))
                    Text(tr("addWatchlist"))
                }
            }
        }
        Card(shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("matchActions"), style = MaterialTheme.typography.titleMedium)
                ActionRow(tr("captureOnMatch"), capture) { capture = it }
                ActionRow(tr("alertOnMatch"), alert) { alert = it }
                ActionRow(tr("preserveOnMatch"), preserve) { preserve = it }
            }
        }
        Card(shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(tr("exampleRecord"), style = MaterialTheme.typography.titleMedium)
                Text("1/52863", style = MaterialTheme.typography.headlineSmall)
                Text(tr("watchlistExampleActions"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ActionRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
