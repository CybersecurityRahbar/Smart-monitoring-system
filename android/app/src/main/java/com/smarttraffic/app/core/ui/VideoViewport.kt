package com.smarttraffic.app.core.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.smarttraffic.app.MainActivity
import com.smarttraffic.app.core.AppSettings
import com.smarttraffic.app.core.VideoDisplayMode
import com.smarttraffic.app.core.tr

@Composable
fun VideoViewport(
    title: String,
    mode: VideoDisplayMode,
    onModeChange: (VideoDisplayMode) -> Unit,
    modifier: Modifier = Modifier,
    showPipAction: Boolean = true,
) {
    val context = LocalContext.current
    val height = when (mode) {
        VideoDisplayMode.FULLSCREEN -> null
        VideoDisplayMode.STANDARD -> 300.dp
        VideoDisplayMode.COMPACT -> 190.dp
    }

    Card(
        modifier = modifier.then(if (height != null) Modifier.height(height) else Modifier),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clip(RoundedCornerShape(24.dp)),
                contentAlignment = Alignment.Center,
            ) {
                ColumnPlaceholder(title)
            }

            Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(10.dp),
                shape = RoundedCornerShape(15.dp),
                color = Color.Black.copy(alpha = 0.68f),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ViewModeChip(tr("fullscreen"), mode == VideoDisplayMode.FULLSCREEN) {
                        onModeChange(VideoDisplayMode.FULLSCREEN)
                    }
                    ViewModeChip(tr("standard"), mode == VideoDisplayMode.STANDARD) {
                        onModeChange(VideoDisplayMode.STANDARD)
                    }
                    ViewModeChip(tr("compact"), mode == VideoDisplayMode.COMPACT) {
                        onModeChange(VideoDisplayMode.COMPACT)
                    }
                    if (showPipAction) {
                        AssistChip(
                            onClick = {
                                (context as? MainActivity)?.enterVideoPictureInPicture()
                            },
                            leadingIcon = { Icon(Icons.Filled.PictureInPictureAlt, null) },
                            label = { Text("PiP") },
                        )
                    }
                }
            }

            Surface(
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                shape = RoundedCornerShape(14.dp),
                color = Color.Black.copy(alpha = 0.72f),
            ) {
                Row(
                    Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Icon(Icons.Filled.Tune, null, tint = MaterialTheme.colorScheme.primary)
                    Text("ESP32-CAM • local feed", color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun ViewModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(11.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(Icons.Filled.Fullscreen, null, modifier = Modifier.height(14.dp), tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else Color.White)
            Text(label, style = MaterialTheme.typography.labelSmall, color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else Color.White)
        }
    }
}

@Composable
private fun ColumnPlaceholder(title: String) {
    androidx.compose.foundation.layout.Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("LIVE", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        Text(title, color = Color.White, style = MaterialTheme.typography.titleLarge)
        Text("Video pipeline ready • stream endpoint not connected yet", color = Color(0xFFB4BFC3), style = MaterialTheme.typography.bodySmall)
    }
}
