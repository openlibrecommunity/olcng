@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package io.nekohasekai.sagernet.ui.compose.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.nekohasekai.sagernet.bg.test.BatchProbeCoordinator
import io.nekohasekai.sagernet.bg.test.ProbeOutcome
import io.nekohasekai.sagernet.bg.test.ProbeProgress

@Composable
fun ProbeStatusCard(progress: ProbeProgress?, outcome: ProbeOutcome?, currentGroupId: Long?) {
    val result = progress ?: outcome?.progress ?: return
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ShapedIconStatic(
                    icon = Icons.Default.Speed,
                    containerColor = MaterialTheme.colorScheme.tertiary,
                    contentColor = MaterialTheme.colorScheme.onTertiary,
                    size = 44.dp,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        when {
                            progress != null -> "Проверка серверов"
                            outcome?.error != null -> "Не удалось выполнить проверку"
                            outcome?.cancelled == true -> "Проверка остановлена"
                            else -> "Проверка завершена"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text("Проверено: ${result.completed} из ${result.total}", style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (progress != null) {
                LinearWavyProgressIndicator(
                    progress = { result.completed.toFloat() / result.total.coerceAtLeast(1) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text("Доступны: ${result.succeeded} · Ошибки: ${result.failed}", style = MaterialTheme.typography.labelLarge)
            if (result.groupId != currentGroupId) Text("Проверяется другая группа", style = MaterialTheme.typography.bodySmall)
            outcome?.error?.takeIf { progress == null }?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (progress != null) {
                TextButton(onClick = { BatchProbeCoordinator.cancel() }, modifier = Modifier.align(Alignment.End)) { Text("Остановить") }
            } else if (outcome?.cancelled == true) {
                Text("Готовые результаты сохранены", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
