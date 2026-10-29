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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.Surface
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
import com.xrbbm.pideck.ui.theme.AppIcons
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
    expandedGroups: Set<String> = emptySet(),
    onToggleGroup: ((String, Boolean) -> Unit)? = null,
    onSelect: (WebSession) -> Unit,
    onNewProject: () -> Unit,
    onNewSession: (String?) -> Unit,
    onRename: (WebSession) -> Unit,
    onCopy: (WebSession) -> Unit,
    onExport: (WebSession) -> Unit,
    onDelete: (WebSession) -> Unit,
    onDeleteProject: (String) -> Unit = {},
    onDisconnect: () -> Unit,
    onRefresh: () -> Unit,
    isDark: Boolean,
    onToggleTheme: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var menuSession by remember { mutableStateOf<WebSession?>(null) }
    var deleteProjectId by remember { mutableStateOf<String?>(null) }

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
            // Web 1:1 top-right +: new project (register path on server).
            IconButton(
                onClick = onNewProject,
                enabled = state != null,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(AppIcons.Add, contentDescription = "新建项目")
            }
        }
        // M3 SearchBar-style field (skill: rounded 28dp + leading icon).
        androidx.compose.material3.SearchBar(
            inputField = {
                androidx.compose.material3.SearchBarDefaults.InputField(
                    query = query,
                    onQueryChange = { query = it },
                    onSearch = {},
                    expanded = false,
                    onExpandedChange = {},
                    placeholder = { Text("搜索会话") },
                    leadingIcon = {
                        Icon(
                            AppIcons.Search,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                )
            },
            expanded = false,
            onExpandedChange = {},
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
            content = {},
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
                // Web 1:1 (screenshot 2): flat project rows, no group cards.
                // groups[] order = Chat pinned + projects; each row shows
                // ▷ name, right + (new) and trash only on hover-equivalent.
                groups.forEach { g ->
                    item(key = "header-${g.id}") {
                        // Default collapsed; searching forces open so hits
                        // stay visible. Single source: persisted set only
                        // (no local copy, else restart flashes back).
                        val searching = query.trim().isNotEmpty()
                        val open = if (searching) true else (g.id in expandedGroups)
                        fun setOpen(v: Boolean) {
                            onToggleGroup?.invoke(g.id, v)
                        }
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = { setOpen(!open) },
                                        // Web puts 移除项目 in a per-project menu;
                                        // long-press is the mobile equivalent.
                                        onLongClick = {
                                            if (!g.isChat) deleteProjectId = g.id
                                        },
                                    )
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    if (open) "▽" else "▷",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.padding(start = 6.dp))
                                Text(
                                    g.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                // Row +: new session inside this group only.
                                // Chat group has no projectId (null = builtin chat).
                                IconButton(
                                    onClick = {
                                        onNewSession(if (g.isChat) null else g.id)
                                    },
                                    enabled = state != null,
                                    modifier = Modifier.size(32.dp),
                                ) { Text("+") }
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
        // Web 1:1 bottom: online dot + connected label (screenshot 2).
        // Disconnect/theme stay reachable via the top ⋮ menu; keep a
        // compact text row here to avoid dead ends.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.foundation.Canvas(Modifier.size(6.dp)) {
                drawCircle(androidx.compose.ui.graphics.Color(0xFF22C55E))
            }
            Spacer(Modifier.padding(start = 6.dp))
            Text(
                "已连接",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDisconnect) { Text("断开") }
        }
    }

    deleteProjectId?.let { id ->
        AlertDialog(
            onDismissRequest = { deleteProjectId = null },
            title = { Text("移除项目？") },
            text = { Text("只移除项目列表记录，不删除项目文件。确定继续吗？") },
            confirmButton = {
                TextButton(onClick = {
                    deleteProjectId = null
                    onDeleteProject(id)
                }) { Text("移除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteProjectId = null }) { Text("取消") }
            },
        )
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
    // M3 NavigationDrawerItem: pill-selected row; trailing menu kept for
    // rename/copy/export/delete (long-press also opens it when idle).
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = { if (!busy) onMenu() },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.NavigationDrawerItem(
            icon = {
                Icon(
                    if (session.forked) AppIcons.Fork else AppIcons.Chat,
                    contentDescription = null,
                    tint = if (busy) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            },
            label = {
                Text(
                    session.title.ifEmpty { "(无标题)" },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            selected = selected,
            onClick = onTap,
            badge = if (busy) {
                { Text("运行中", style = MaterialTheme.typography.labelSmall) }
            } else {
                null
            },
            modifier = Modifier.weight(1f),
        )
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(end = 16.dp)
                    .size(16.dp),
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
 * New project dialog: register a server-local path (web top-right +).
 */
@Composable
fun NewProjectDialog(
    api: ApiClient,
    onDismiss: () -> Unit,
    onCreated: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var path by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建项目") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = { Text("项目路径（服务端本地路径）") },
                    placeholder = { Text("/home/user/my-project") },
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    scope.launch {
                        busy = true
                        error = null
                        try {
                            if (path.trim().isEmpty()) {
                                throw com.xrbbm.pideck.core.net.ApiException("请先输入项目路径")
                            }
                            api.createProject(path.trim())
                            onCreated()
                        } catch (e: Exception) {
                            error = "$e"
                            busy = false
                        }
                    }
                },
                enabled = !busy,
            ) { Text("创建") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
        },
    )
}

/**
 * New session dialog: pick project (or register path when none) + optional
 * title. Returns the new session id, null on cancel.
 */
@Composable
fun NewSessionDialog(
    api: ApiClient,
    projects: List<WebProject>,
    initialProjectId: String? = null,
    lockProject: Boolean = false,
    onDismiss: () -> Unit,
    onCreated: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var projectId by remember(initialProjectId, projects) {
        mutableStateOf(
            initialProjectId?.takeIf { id ->
                id == com.xrbbm.pideck.core.net.CHAT_PROJECT_ID ||
                    projects.any { it.id == id }
            }
                ?: if (projects.size == 1) projects.first().id else null,
        )
    }
    // Chat row passes null (builtin group): resolve to the real
    // builtin-chat project id so submit() skips the path branch.
    val chatProjectId = remember(projects) {
        projects.firstOrNull { it.id == com.xrbbm.pideck.core.net.CHAT_PROJECT_ID }?.id
            ?: com.xrbbm.pideck.core.net.CHAT_PROJECT_ID
    }
    val effectiveProjectId = projectId ?: if (lockProject) chatProjectId else null
    var path by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun submit() {
        busy = true
        error = null
        try {
            var pid = effectiveProjectId
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
                    // Row + passes lockProject: show the fixed project as
                    // text, no switching (that group only).
                    if (lockProject && effectiveProjectId != null && projectId != null) {
                        val fixed = projects.firstOrNull { it.id == effectiveProjectId }
                        Text(
                            fixed?.name ?: "当前项目",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    projects.forEach { p ->
                        if (lockProject && p.id != effectiveProjectId) return@forEach
                        TextButton(onClick = { projectId = p.id }) {
                            if (effectiveProjectId == p.id) {
                                Icon(
                                    AppIcons.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                            }
                            Text(p.name)
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
