package com.xrbbm.pideck.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xrbbm.pideck.core.net.UiMessage
import com.xrbbm.pideck.core.net.UiPart

/**
 * Message search sheet: filters already-loaded timeline messages locally
 * (mirrors web search dialog — no server round trip).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageSearchSheet(
    messages: List<UiMessage>,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val hits = remember(query, messages) {
        val q = query.trim()
        if (q.isEmpty()) return@remember emptyList<UiMessage>()
        messages.filter { it.textForSearch().contains(q, ignoreCase = true) }.take(50)
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(16.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("搜索会话消息") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            if (query.trim().isNotEmpty()) {
                Text(
                    "共 ${hits.size} 条（只搜已加载消息）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(hits, key = { it.id }) { m ->
                        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    if (m.isUser) "我" else "助手",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    m.textForSearch().take(300),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 6,
                                )
                            }
                        }
                    }
                }
            } else {
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("关闭")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun UiMessage.textForSearch(): String = buildString {
    for (p in parts) {
        when (p) {
            is UiPart.Text -> append(p.text).append('\n')
            is UiPart.Reasoning -> append(p.text).append('\n')
            is UiPart.Tool -> {
                append(p.toolName).append('\n')
            }
            else -> Unit
        }
    }
}
