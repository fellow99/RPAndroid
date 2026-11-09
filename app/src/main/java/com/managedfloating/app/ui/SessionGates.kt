package com.managedfloating.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** 顶部临时信息条（应用/自动化栏目共用）。 */
@Composable
fun Banner(text: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onDismiss) { Text("✕") }
        }
    }
}

/** S1：未配置 adbd 连接端口 → 只显示"去设置"提示（其它 UI 不显示）。 */
@Composable
fun PortNeededContent(onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("⚙", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(16.dp))
        Text(
            "请先配置 adbd 连接端口",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "在「设置」中填写无线调试的 adbd 连接端口后，才能建立托管会话。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onOpenSettings) { Text("去设置") }
    }
}

/** S2：未建立会话 → 居中"连接并建立会话"按钮 + 可选的连接端口重试（其它 UI 不显示）。 */
@Composable
fun ConnectContent(
    connecting: Boolean,
    message: String?,
    onConnect: () -> Unit,
    savedPort: Int = 0,
    onConnectWithPort: ((Int?) -> Unit)? = null,
) {
    var portText by remember { mutableStateOf(savedPort.takeIf { it > 0 }?.toString() ?: "") }
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("▦", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(16.dp))
        Text(
            "尚未建立托管会话",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "连接 adbd 并拉起助手，创建被托管应用运行所需的虚拟屏。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        if (connecting) {
            CircularProgressIndicator(modifier = Modifier.size(32.dp))
        } else {
            Button(onClick = onConnect) { Text("连接并建立会话") }
        }
        if (message != null) {
            Spacer(Modifier.height(16.dp))
            Text(message, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }
        // 连接失败（常见于无线调试端口已变、自动发现不可用）：手动填端口重连。
        if (onConnectWithPort != null) {
            Spacer(Modifier.height(20.dp))
            Text(
                "连不上？打开 设置 › 开发者选项 › 无线调试，把顶部那一行的端口填到这里重试（避免本应用离开后再回来时端口已变）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = portText,
                onValueChange = { portText = it.filter { ch -> ch.isDigit() }.take(5) },
                label = { Text("无线调试连接端口（可选）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { onConnectWithPort(portText.trim().toIntOrNull()?.takeIf { it in 1..65535 }) }) {
                Text("用此端口连接")
            }
        }
    }
}

/** 通用：会话未就绪时的提示 + 可选按钮。 */
@Composable
fun SessionUnavailable(msg: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(msg, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}
