@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package io.nekohasekai.sagernet.ui.compose.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.group.BuiltinPool
import java.text.DateFormat
import java.util.Date

@Composable
fun BuiltinPoolStatus() {
    val state by BuiltinPool.state.collectAsStateWithLifecycle()
    val sourceFlow = remember { SagerDatabase.poolDao.observeSources() }
    val countFlow = remember { SagerDatabase.poolDao.observeProfileCount() }
    val sources by sourceFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val count by countFlow.collectAsStateWithLifecycle(initialValue = 0)
    var showSources by remember { mutableStateOf(false) }
    val updated = sources.count { it.lastSuccess > 0 && it.lastError == null }
    val failed = sources.count { it.lastError != null }
    val cached = sources.count { it.hasContent && (it.lastSuccess == 0L || it.lastError != null) }
    val lastUpdate = sources.maxOfOrNull { it.lastSuccess } ?: 0L

    Card(
        onClick = { showSources = true },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ShapedIconStatic(
                    icon = Icons.Default.CloudDownload,
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                    size = 44.dp,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Встроенные подписки", style = MaterialTheme.typography.titleMedium)
                    Text("Источников: ${sources.size} · Серверов: $count", style = MaterialTheme.typography.bodyMedium)
                }
                FilledTonalIconButton(
                    enabled = !state.isRefreshing,
                    onClick = { BuiltinPool.requestRefresh(reason = "manual", force = true) },
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Обновить подписки", modifier = Modifier.size(24.dp))
                }
            }
            if (state.isRefreshing) {
                LinearWavyProgressIndicator(
                    progress = { state.completedSources.toFloat() / state.totalSources.coerceAtLeast(1) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Проверено источников: ${state.completedSources} из ${state.totalSources}", style = MaterialTheme.typography.labelMedium)
            } else {
                Text(
                    "Обновлено: $updated · Из кэша: $cached · С ошибкой: $failed",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (failed > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    if (lastUpdate > 0) "Последнее обновление: ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(lastUpdate))}"
                    else "Локальный снимок · нажми, чтобы посмотреть источники",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    if (showSources) {
        ExpressiveDialog(onDismissRequest = { showSources = false }) {
            Text("Источники подписок", style = MaterialTheme.typography.headlineSmall)
            LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(sources, key = { it.id }) { source ->
                    androidx.compose.material3.Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(source.id, style = MaterialTheme.typography.titleSmall)
                            Text(source.url, style = MaterialTheme.typography.bodySmall)
                            Text(
                                source.lastError ?: if (source.lastSuccess > 0) "Обновлён" else if (source.hasContent) "Локальный снимок" else "Нет сохранённых данных",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (source.lastError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            TextButton(onClick = { showSources = false }, modifier = Modifier.align(Alignment.End)) { Text("Закрыть") }
        }
    }
}
