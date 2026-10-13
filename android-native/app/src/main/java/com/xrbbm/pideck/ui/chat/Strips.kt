package com.xrbbm.pideck.ui.chat

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
    // M3: tonal container + SuggestionChip trail with check on active.
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Surface(
                shape = androidx.compose.foundation.shape.CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(28.dp),
            ) {
                androidx.compose.foundation.layout.Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("⑂", style = MaterialTheme.typography.labelMedium)
                }
            }
            family.forEachIndexed { i, member ->
                val s = byId[member.id] ?: member
                val active = s.id == activeId
                androidx.compose.material3.SuggestionChip(
                    onClick = { if (!active) onSelect(s) },
                    label = {
                        Text(
                            s.title.ifEmpty { "(无标题)" },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    icon = if (active) {
                        { Text("✓", style = MaterialTheme.typography.labelSmall) }
                    } else {
                        null
                    },
                )
                if (i < family.size - 1) {
                    Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
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

/**
 * Pinned summary rows above the input (user screenshot 1): one fixed
 * row per strip (icon + title + count + chevron). Tapping expands the
 * existing StripShell content inline; empty strips render nothing.
 */
@Composable
fun PinnedStrips(api: ApiClient, sessionId: String, modifier: Modifier = Modifier) {
    var changes by remember(sessionId) { mutableStateOf(0) }
    var subagents by remember(sessionId) { mutableStateOf(0) }
    var todos by remember(sessionId) { mutableStateOf(0) }
    var open by remember(sessionId) { mutableStateOf<String?>(null) }

    suspend fun load() {
        try {
            changes = api.fetchSessionFileChanges(sessionId).size
        } catch (_: Exception) {
        }
        try {
            subagents = api.fetchSessionSubagents(sessionId).size
        } catch (_: Exception) {
        }
        try {
            todos = api.fetchSessionTodo(sessionId)?.todos?.size ?: 0
        } catch (_: Exception) {
        }
    }

    LaunchedEffect(sessionId) {
        load()
        while (isActive) {
            delay(5000)
            load()
        }
    }

    val rows = buildList {
        if (changes > 0) add(Triple("file", "📄", "文件修改 $changes"))
        if (subagents > 0) add(Triple("sub", "✨", "子代理 $subagents"))
        if (todos > 0) add(Triple("todo", "📋", "任务清单 $todos"))
    }
    if (rows.isEmpty()) return
    Column(modifier.fillMaxWidth()) {
        rows.forEach { (key, icon, label) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { open = if (open == key) null else key },
                        onLongClick = {},
                    )
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(icon, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.padding(start = 8.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(if (open == key) "▾" else "▸")
            }
            if (open == key) {
                // Reuse the full strip content for the expanded row.
                Box(Modifier.fillMaxWidth()) {
                    ActivityStrips(api = api, sessionId = sessionId)
                }
            }
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
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
    // M3: OutlinedCard shell + Badge count + animated body.
    androidx.compose.material3.OutlinedCard(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        androidx.compose.material3.ListItem(
            headlineContent = { Text(title, style = MaterialTheme.typography.titleSmall) },
            leadingContent = { Text(icon) },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Badge { Text("$count") }
                    Spacer(Modifier.padding(start = 8.dp))
                    Text(if (open) "▾" else "▸")
                }
            },
            modifier = Modifier.combinedClickable(
                onClick = { open = !open },
                onLongClick = {},
            ),
        )
        androidx.compose.animation.AnimatedVisibility(
            visible = open,
            enter = androidx.compose.animation.fadeIn() +
                androidx.compose.animation.expandVertically(),
            exit = androidx.compose.animation.fadeOut() +
                androidx.compose.animation.shrinkVertically(),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
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
