package com.xrbbm.pideck.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xrbbm.pideck.core.net.ApiClient
import com.xrbbm.pideck.core.net.WebProject
import com.xrbbm.pideck.core.net.WebSession
import com.xrbbm.pideck.core.net.WebState
import com.xrbbm.pideck.core.net.groupSessions
import kotlinx.coroutines.launch

/**
 * Grouped session drawer: Chat pinned + per-project groups, search/new,
 * busy rows show a spinner (rename/copy/delete forbidden while running),
 * disconnect + theme toggle at the bottom.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsDrawer(
    state: WebState?,
    loading: Boolean,
    error: String?,
    selectedId: String?,
    onSelect: (WebSession) -> Unit,
    onNewSession: () -> Unit,
    onRename: (WebSession) -> Unit,
    onCopy: (WebSession) -> Unit,
    onExport: (WebSession) -> Unit,
    onDelete: (WebSession) -> Unit,
    onDisconnect: () -> Unit,
    onRefresh: () -> Unit,
    isDark: Boolean,
    onToggleTheme: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var menuSession by remember { mutableStateOf<WebSession?>(null) }

    val projectName = remember(state) {
        (state?.projects ?: emptyList()).associate { it.id to it.name }
    }
    val groups = remember(state, query) {
        val q = query.trim().lowercase()
        val sessions = (state?.sessions ?: emptyList()).toMutableList()
        if (q.isNotEmpty()) {
            sessions.retainAll { s ->
                val title = s.title.ifEmpty { "(无标题)" }
                title.lowercase().contains(q) ||
                    s.id.lowercase().contains(q) ||
                    (projectName[s.projectId] ?: "").lowercase().contains(q)
            }
        }
        // Empty Chat group not rendered (a pinned empty row misleads).
        groupSessions(sessions, projectName).filter { it.sessions.isNotEmpty() }
    }

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "会话",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onRefresh) {
                Text("⟳")
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            placeholder = { Text("搜索会话") },
            singleLine = true,
        )
        Spacer(Modifier.height(4.dp))
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            if (loading) {
                item(key = "loading") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) { CircularProgressIndicator() }
                }
            } else if (error != null && state == null) {
                item(key = "error") {
                    Column(Modifier.padding(24.dp)) {
                        Text(error)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = onRefresh) { Text("重试") }
                    }
                }
            } else {
                groups.forEach { g ->
                    item(key = "header-${g.id}") {
                        var open by remember(g.id, query) {
                            mutableStateOf(g.isChat || query.trim().isNotEmpty())
                        }
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = { open = !open },
                                        onLongClick = {},
                                    )
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(if (g.isChat) "💬" else "📁")
                                Spacer(Modifier.padding(start = 8.dp))
                                Text(
                                    g.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    "${g.sessions.size} 个会话",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            AnimatedVisibility(
                                visible = open,
                                enter = fadeIn() + expandVertically(),
                                exit = fadeOut() + shrinkVertically(),
                            ) {
                                Column {
                                    g.sessions.forEach { s ->
                                        val busy = state?.runtimeOf(s.id)?.isBusy
                                            ?: false
                                        SessionRow(
                                            session = s,
                                            selected = s.id == selectedId,
                                            busy = busy,
                                            onTap = { onSelect(s) },
                                            onMenu = { menuSession = s },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        HorizontalDivider()
        FilledTonalButton(
            onClick = onNewSession,
            enabled = state != null,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) { Text("+ 新会话") }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onDisconnect,
                modifier = Modifier.weight(1f),
            ) { Text("断开配对") }
            IconButton(onClick = onToggleTheme) {
                Text(if (isDark) "☀" else "🌙")
            }
        }
    }

    menuSession?.let { s ->
        ModalBottomSheet(onDismissRequest = { menuSession = null }) {
            Column(Modifier.padding(16.dp)) {
                TextButton(
                    onClick = {
                        menuSession = null
                        onRename(s)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("重命名") }
                TextButton(
                    onClick = {
                        menuSession = null
                        onCopy(s)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("复制") }
                TextButton(
                    onClick = {
                        menuSession = null
                        onExport(s)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("导出 HTML") }
                TextButton(
                    onClick = {
                        menuSession = null
                        onDelete(s)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SessionRow(
    session: WebSession,
    selected: Boolean,
    busy: Boolean,
    onTap: () -> Unit,
    onMenu: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onTap,
                onLongClick = { if (!busy) onMenu() },
            )
            .padding(start = 28.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (session.forked) "⑂" else "💬",
            color = if (busy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.padding(start = 8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                session.title.ifEmpty { "(无标题)" },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
            )
            if (busy) {
                Text("运行中", style = MaterialTheme.typography.labelSmall)
            }
        }
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
            )
        } else {
            IconButton(onClick = onMenu) {
                Icon(Icons.Filled.MoreVert, contentDescription = "会话操作")
            }
        }
    }
}

/**
 * New session dialog: pick project (or register path when none) + optional
 * title. Returns the new session id, null on cancel.
 */
@Composable
fun NewSessionDialog(
    api: ApiClient,
    projects: List<WebProject>,
    onDismiss: () -> Unit,
    onCreated: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var projectId by remember {
        mutableStateOf(if (projects.size == 1) projects.first().id else null)
    }
    var path by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun submit() {
        busy = true
        error = null
        try {
            var pid = projectId
            if (pid == null) {
                if (projects.isNotEmpty()) throw com.xrbbm.pideck.core.net.ApiException("请先选择项目")
                if (path.trim().isEmpty()) {
                    throw com.xrbbm.pideck.core.net.ApiException("请先输入项目路径")
                }
                pid = api.createProject(path.trim())
            }
            val id = api.createSession(
                pid,
                if (title.trim().isEmpty()) null else mapOf("title" to title.trim()),
            )
            onCreated(id)
        } catch (e: Exception) {
            error = "$e"
            busy = false
        }
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建会话") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (projects.isNotEmpty()) {
                    projects.forEach { p ->
                        TextButton(onClick = { projectId = p.id }) {
                            Text((if (projectId == p.id) "✓ " else "") + p.name)
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = path,
                        onValueChange = { path = it },
                        label = { Text("项目路径（服务端本地路径）") },
                        placeholder = { Text("/home/user/my-project") },
                    )
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("标题（可选）") },
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { scope.launch { submit() } },
                enabled = !busy,
            ) { Text("创建") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
        },
    )
}
