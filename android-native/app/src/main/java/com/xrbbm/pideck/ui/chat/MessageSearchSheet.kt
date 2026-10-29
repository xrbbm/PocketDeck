package com.xrbbm.pideck.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xrbbm.pideck.core.net.SearchHit
import com.xrbbm.pideck.core.net.UiMessage
import com.xrbbm.pideck.core.net.UiPart
import kotlinx.coroutines.delay

/**
 * Message search sheet (web parity): the server-side history index is the
 * primary source (`/api/sessions/:id/search`), local loaded messages are
 * the fallback when the index is unavailable. Server hits jump to the
 * message when it is already in the timeline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageSearchSheet(
    messages: List<UiMessage>,
    onSearchServer: suspend (String) -> List<SearchHit>?,
    onJump: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var serverHits by remember { mutableStateOf<List<SearchHit>?>(null) }
    var searching by remember { mutableStateOf(false) }
    val q = query.trim()

    // 250ms debounce, same cadence as the prompt library search.
    LaunchedEffect(q) {
        if (q.isEmpty()) {
            serverHits = null
            searching = false
            return@LaunchedEffect
        }
        delay(250)
        searching = true
        serverHits = onSearchServer(q)
        searching = false
    }

    // Server hits carry only the trailing history key (`{8hex}`); page ids are
    // `{sessionId}-history-{8hex}` (+ `#index` after part normalization).
    val loaded = remember(messages) { messages.map { historyKey(it.id) }.toSet() }
    val localHits = remember(q, messages) {
        if (q.isEmpty()) emptyList() else {
            messages.filter { it.textForSearch().contains(q, ignoreCase = true) }.take(50)
        }
    }
    val hits = serverHits

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
            when {
                q.isEmpty() -> Unit
                searching -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("搜索中…", style = MaterialTheme.typography.labelSmall)
                }
                hits != null -> Text(
                    if (hits.isEmpty()) "没有匹配（服务器索引）" else "服务器索引 · ${hits.size} 条",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> Text(
                    "服务器索引不可用 · 只搜已加载消息（${localHits.size} 条）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                if (hits != null) {
                    items(hits, key = { it.messageId }) { hit ->
                        val inTimeline = historyKey(hit.messageId) in loaded
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .then(
                                    if (inTimeline) {
                                        Modifier.clickable {
                                            onJump(hit.messageId)
                                            onDismiss()
                                        }
                                    } else {
                                        Modifier
                                    },
                                ),
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    if (hit.role == "user") "我" else "助手",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    hit.snippet,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 6,
                                )
                                if (!inTimeline) {
                                    Text(
                                        "未加载，无法跳转",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                } else {
                    items(localHits, key = { it.id }) { m ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable {
                                    onJump(m.id)
                                    onDismiss()
                                },
                        ) {
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
            }
            if (q.isEmpty()) {
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("关闭")
                }
            }
        }
    }
}

internal fun UiMessage.textForSearch(): String = buildString {
    for (p in parts) {
        when (p) {
            is UiPart.Text -> append(p.text).append('\n')
            is UiPart.Reasoning -> append(p.text).append('\n')
            is UiPart.Tool -> append(p.toolName).append('\n')
            else -> Unit
        }
    }
}
