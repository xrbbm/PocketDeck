package com.xrbbm.pideck.ui.chat

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xrbbm.pideck.core.net.ApiClient
import com.xrbbm.pideck.core.net.SessionFileChange
import com.xrbbm.pideck.core.net.SessionTodoSnapshot
import com.xrbbm.pideck.core.net.SubagentEntry
import com.xrbbm.pideck.core.net.WebSession
import com.xrbbm.pideck.core.net.deriveBranchFamily
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Branch bar: hidden when the family has ≤1 member; tapping a chip
 * (i>0) switches session. Derivation walks root-up / children-down.
 */
@Composable
fun BranchBar(
    sessions: List<WebSession>?,
    activeId: String,
    onSelect: ((WebSession) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    if (sessions == null || onSelect == null) return
    val family = remember(sessions, activeId) { deriveBranchFamily(sessions, activeId) }
    if (family.size <= 1) return
    val byId = remember(sessions) { sessions.associateBy { it.id } }
    Surface(
        tonalElevation = 1.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text("⑂", color = MaterialTheme.colorScheme.onSurfaceVariant)
            family.forEachIndexed { i, member ->
                if (i > 0) {
                    Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val s = byId[member.id] ?: member
                val active = s.id == activeId
                FilterChip(
                    selected = active,
                    onClick = { if (!active) onSelect(s) },
                    label = {
                        Text(
                            s.title.ifEmpty { "(无标题)" },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
        }
    }
}

/**
 * Activity strips (file-changes / subagents / todo): collapsed by default,
 * polled independently every 5s, failures keep the last snapshot.
 */
@Composable
fun ActivityStrips(
    api: ApiClient,
    sessionId: String,
    modifier: Modifier = Modifier,
) {
    var changes by remember(sessionId) { mutableStateOf<List<SessionFileChange>>(emptyList()) }
    var subagents by remember(sessionId) { mutableStateOf<List<SubagentEntry>>(emptyList()) }
    var todo by remember(sessionId) { mutableStateOf<SessionTodoSnapshot?>(null) }

    suspend fun load() {
        var newChanges: List<SessionFileChange>? = null
        var newSubagents: List<SubagentEntry>? = null
        var newTodo: SessionTodoSnapshot? = null
        var todoFailed = false
        try {
            newChanges = api.fetchSessionFileChanges(sessionId)
        } catch (_: Exception) {
        }
        try {
            newSubagents = api.fetchSessionSubagents(sessionId)
        } catch (_: Exception) {
        }
        try {
            newTodo = api.fetchSessionTodo(sessionId)
        } catch (_: Exception) {
            todoFailed = true
        }
        if (newChanges == null && newSubagents == null && todoFailed) return
        if (newChanges != null) changes = newChanges
        if (newSubagents != null) subagents = newSubagents
        if (!todoFailed) todo = newTodo
    }

    LaunchedEffect(sessionId) {
        load()
        var tick = 0
        while (isActive) {
            delay(5000)
            tick++
            val idle = changes.isEmpty() && subagents.isEmpty() && (todo?.todos?.isEmpty() ?: true)
            if (idle && tick % 3 != 1) continue
            load()
        }
    }

    val todos = todo?.todos ?: emptyList()
    if (changes.isEmpty() && subagents.isEmpty() && todos.isEmpty()) return
    Column(modifier.fillMaxWidth()) {
        if (changes.isNotEmpty()) {
            StripShell(icon = "📄", title = "文件修改", count = changes.size) {
                changes.take(30).forEach { c ->
                    AssistChip(
                        onClick = {},
                        label = { Text("${c.basename} ×${c.count}") },
                    )
                }
            }
        }
        if (subagents.isNotEmpty()) {
            StripShell(icon = "✨", title = "子代理", count = subagents.size) {
                subagents.forEach { entry ->
                    SubagentRow(entry = entry)
                }
            }
        }
        if (todos.isNotEmpty()) {
            StripShell(icon = "📋", title = "任务清单", count = todos.size) {
                todos.forEach { item ->
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            if (item.completed) "✅" else "○",
                            color = if (item.completed) {
                                MaterialTheme.colorScheme.secondary
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                        )
                        Spacer(Modifier.padding(start = 6.dp))
                        Text(
                            item.text,
                            style = MaterialTheme.typography.bodySmall.copy(
                                textDecoration = if (item.completed) {
                                    TextDecoration.LineThrough
                                } else {
                                    null
                                },
                            ),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                }
            }
        }
    }
}

@Composable
private fun StripShell(
    icon: String,
    title: String,
    count: Int,
    content: @Composable () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        TextButton(
            onClick = { open = !open },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("$icon $title ($count) ${if (open) "▾" else "▸"}")
        }
        if (open) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun SubagentRow(entry: SubagentEntry) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        TextButton(
            onClick = { open = !open },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "${entry.type} · ${entry.status} · ${entry.description}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        if (open) {
            entry.result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            entry.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
