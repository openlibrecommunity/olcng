package io.nekohasekai.sagernet.ui.compose.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

@Composable
fun OwengIntroDialog(onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("oweng") },
        text = {
            Text("OLCNG теперь oweng. Приложение переведено на базу owenclave: новый интерфейс, встроенные подписки и быстрая проверка серверов")
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Продолжить") } },
    )
}
