package io.nekohasekai.sagernet.ui.compose.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.nekohasekai.sagernet.group.BuiltinPool

@Composable
fun BuiltinPoolStatus() {
    val state by BuiltinPool.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Общий список", style = MaterialTheme.typography.titleMedium)
        Text(
            if (state.isRefreshing) "Загрузка источников: ${state.completedSources}/${state.totalSources}"
            else "Источники: ${state.completedSources}/${state.totalSources}",
            style = MaterialTheme.typography.bodySmall,
        )
        state.lastError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        TextButton(
            enabled = !state.isRefreshing,
            onClick = { BuiltinPool.requestRefresh(reason = "manual", force = true) },
        ) { Text("Обновить общий список") }
    }
}
