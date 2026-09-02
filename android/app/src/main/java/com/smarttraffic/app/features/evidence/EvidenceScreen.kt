package com.smarttraffic.app.features.evidence

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.smarttraffic.app.core.AppLanguage
import com.smarttraffic.app.core.AppSettings
import com.smarttraffic.app.core.tr

@Composable
fun EvidenceScreen(paddingValues: PaddingValues) {
    val ar = AppSettings.language == AppLanguage.ARABIC
    Column(Modifier.fillMaxSize().padding(paddingValues).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Filled.PhotoLibrary, null, tint = MaterialTheme.colorScheme.primary)
            Text(tr("evidence"), style = MaterialTheme.typography.headlineMedium)
        }
        Text(if (ar) "سيتم ربط اللقطات وأدلة الأحداث بالكشف واللوحات والسرعة والطوابع الزمنية." else "Captures and event evidence will be linked to detections, plates, speed and timestamps.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (ar) "خزنة الأدلة" else "Evidence vault", style = MaterialTheme.typography.titleLarge)
                Text(if (ar) "لا توجد سجلات أدلة بعد" else "No evidence records yet", style = MaterialTheme.typography.bodyLarge)
                Text(if (ar) "التخزين المرتبط بالأحداث جاهز لمرحلة الالتقاط ومسار الذكاء الاصطناعي." else "Event-linked storage is ready for the capture and AI pipeline.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
