package com.xrbbm.pideck.ui.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xrbbm.pideck.core.net.ApiClient
import com.xrbbm.pideck.core.net.CommitEntry
import com.xrbbm.pideck.core.net.GitBranchInfo
import com.xrbbm.pideck.core.net.GitResourceGroups
import com.xrbbm.pideck.core.net.WebFileNodeLite
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/** Git commit date: server authorDate is ms (<1e12 treated as s). */
fun formatGitDate(ts: Int): String {
    if (ts <= 0) return ""
    val ms = if (ts < 1000000000000) ts * 1000L else ts.toLong()
    if (ms < 0 || ms > 4102444800000L) return ""
    return try {
        val d = java.util.Date(ms)
        val cal = java.util.Calendar.getInstance()
        cal.time = d
        "%04d-%02d-%02d".format(
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH),
        )
    } catch (_: Exception) {
        ""
    }
}

/**
 * Workspace drawer (mirrors WebWorkspaceDrawer): read-only Git / Files tabs.
 * Opened on demand, discarded on close (no resident polling).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceDrawer(
    api: ApiClient,
    projectId: String,
    onPreviewFile: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var tab by remember { mutableStateOf(0) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
        ) {
            Text(
                "工作区",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            // Segmented switch instead of TabRow: TabRow taps go missing
            // inside ModalBottomSheet on the MuMu simulator (no Pager to sync).
            Row(Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = { tab = 0 },
                    modifier = Modifier.weight(1f),
                ) { Text(if (tab == 0) "● Git" else "Git") }
                TextButton(
                    onClick = { tab = 1 },
                    modifier = Modifier.weight(1f),
                ) { Text(if (tab == 1) "● 文件" else "文件") }
            }
            if (tab == 0) {
                GitPanel(api = api, projectId = projectId)
            } else {
                FilesPanel(
                    api = api,
                    projectId = projectId,
                    onPreviewFile = onPreviewFile,
                )
            }
        }
    }
}

@Composable
private fun GitPanel(api: ApiClient, projectId: String) {
    val scope = rememberCoroutineScope()
    var loading by remember(projectId) { mutableStateOf(true) }
    var noRepo by remember(projectId) { mutableStateOf(false) }
    var loadError by remember(projectId) { mutableStateOf<String?>(null) }
    var branch by remember(projectId) { mutableStateOf<GitBranchInfo?>(null) }
    var groups by remember(projectId) { mutableStateOf<GitResourceGroups?>(null) }
    var commits by remember(projectId) { mutableStateOf<List<CommitEntry>>(emptyList()) }
    var diffPath by remember(projectId) { mutableStateOf<String?>(null) }
    var diffGroup by remember(projectId) { mutableStateOf("workingTree") }
    var diffLoading by remember(projectId) { mutableStateOf(false) }
    var diffOriginal by remember(projectId) { mutableStateOf<String?>(null) }
    var diffModified by remember(projectId) { mutableStateOf<String?>(null) }

    suspend fun load() {
        loading = true
        noRepo = false
        loadError = null
        try {
            // status/log in parallel: serial doubles RTT on weak networks.
            val statusDef = scope.async { api.fetchGitStatus(projectId) }
            val logDef = scope.async { api.fetchGitLog(projectId) }
            val status = statusDef.await()
            val log = logDef.await()
            if (!status.repo) {
                noRepo = true
                loading = false
                return
            }
            branch = status.branch
            groups = status.groups
            commits = log
            loading = false
        } catch (e: Exception) {
            loadError = (e as? com.xrbbm.pideck.core.net.ApiException)?.message ?: "$e"
            loading = false
        }
    }

    suspend fun openDiff(group: String, path: String) {
        if (diffPath == path && diffGroup == group) {
            diffPath = null
            return
        }
        diffPath = path
        diffGroup = group
        diffLoading = true
        diffOriginal = null
        diffModified = null
        try {
            val diff = api.fetchGitDiff(projectId, group, path)
            diffOriginal = diff?.original ?: ""
            diffModified = diff?.modified ?: ""
            diffLoading = false
        } catch (_: Exception) {
            diffLoading = false
        }
    }

    LaunchedEffect(projectId) { load() }

    when {
        loading -> Row(
            Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalArrangement = Arrangement.Center,
        ) { CircularProgressIndicator() }
        noRepo -> Column(
            Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("当前项目不是 Git 仓库")
            TextButton(onClick = { scope.launch { load() } }) { Text("重试") }
        }
        loadError != null -> Column(
            Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("工作区加载失败：$loadError")
            TextButton(onClick = { scope.launch { load() } }) { Text("重试") }
        }
        else -> LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .height(420.dp)
                .padding(12.dp),
        ) {
            branch?.current?.let { current ->
                item(key = "branch") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("⑂", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.padding(start = 6.dp))
                        Text(
                            current,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "变更 ${groups?.total ?: 0}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            val g = groups
            if (g != null) {
                listOf(
                    "合并冲突" to "merge" to g.merge,
                    "已暂存" to "index" to g.index,
                    "工作区修改" to "workingTree" to g.workingTree,
                    "未跟踪" to "untracked" to g.untracked,
                ).forEach { (labeled, resources) ->
                    val (label, group) = labeled
                    if (resources.isNotEmpty()) {
                        item(key = "group-$group") {
                            ResourceGroup(
                                label = label,
                                group = group,
                                paths = resources.map { it.path to it.letter },
                                diffPath = diffPath,
                                diffGroup = diffGroup,
                                diffLoading = diffLoading,
                                diffOriginal = diffOriginal,
                                diffModified = diffModified,
                                onOpenDiff = { grp, path ->
                                    scope.launch { openDiff(grp, path) }
                                },
                            )
                        }
                    }
                }
            }
            if (commits.isNotEmpty()) {
                item(key = "commits-header") {
                    Spacer(Modifier.height(8.dp))
                    Text("最近提交", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                }
                // Cap at 50: a server ignoring limit could return 1000 rows.
                items(commits.take(50), key = { it.hash.ifEmpty { it.message } }) { c ->
                    Surface(
                        tonalElevation = 1.dp,
                        modifier = Modifier.padding(bottom = 6.dp),
                    ) {
                        Row(
                            Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                c.shortHash,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.padding(start = 8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    c.message,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val meta = listOf(c.authorName, formatGitDate(c.authorDate))
                                    .filter { it.isNotEmpty() }
                                    .joinToString(" · ")
                                if (meta.isNotEmpty()) {
                                    Text(meta, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
                if (commits.size > 50) {
                    item(key = "commits-more") {
                        Text(
                            "仅显示前 50 条，共 ${commits.size} 条",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ResourceGroup(
    label: String,
    group: String,
    paths: List<Pair<String, String>>,
    diffPath: String?,
    diffGroup: String,
    diffLoading: Boolean,
    diffOriginal: String?,
    diffModified: String?,
    onOpenDiff: (String, String) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(10.dp))
        Text("$label (${paths.size})", style = MaterialTheme.typography.titleSmall)
        paths.take(30).forEach { (path, letter) ->
            TextButton(
                onClick = { onOpenDiff(group, path) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                val color = when (letter) {
                    "D" -> MaterialTheme.colorScheme.error
                    "A" -> MaterialTheme.colorScheme.secondary
                    else -> MaterialTheme.colorScheme.primary
                }
                Text(
                    letter,
                    fontWeight = FontWeight.Bold,
                    color = color,
                )
                Spacer(Modifier.padding(start = 8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        path.split('/', '\\').lastOrNull()?.takeIf { it.isNotEmpty() }
                            ?: path,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        path,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            if (diffPath == path && diffGroup == group) {
                if (diffLoading) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) { CircularProgressIndicator() }
                } else if (diffOriginal == null && diffModified == null) {
                    Text("暂无 diff（纯改名或内容超限）", style = MaterialTheme.typography.labelSmall)
                } else {
                    DiffBlock(label = "original", content = diffOriginal ?: "")
                    DiffBlock(label = "modified", content = diffModified ?: "")
                }
            }
        }
        if (paths.size > 30) {
            Text(
                "仅显示前 30 条，共 ${paths.size} 条",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun DiffBlock(label: String, content: String) {
    Surface(
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
    ) {
        Column(Modifier.padding(8.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                content.ifEmpty { "—" },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun FilesPanel(
    api: ApiClient,
    projectId: String,
    onPreviewFile: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var dir by remember(projectId) { mutableStateOf("") }
    var nodes by remember(projectId) { mutableStateOf<List<WebFileNodeLite>>(emptyList()) }
    var loading by remember(projectId) { mutableStateOf(true) }

    suspend fun load() {
        loading = true
        try {
            nodes = api.fetchFileList(projectId, dir)
        } catch (_: Exception) {
            nodes = emptyList()
        } finally {
            loading = false
        }
    }

    LaunchedEffect(projectId, dir) { load() }

    val crumbs = dir.split('/').filter { it.isNotEmpty() }
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { dir = "" }) { Text("文件") }
            crumbs.forEachIndexed { i, c ->
                Text(" / ", style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = { dir = crumbs.subList(0, i + 1).joinToString("/") }) {
                    Text(c)
                }
            }
        }
        if (loading) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(300.dp),
                horizontalArrangement = Arrangement.Center,
            ) { CircularProgressIndicator() }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(380.dp),
            ) {
                items(nodes, key = { it.relativePath }) { n ->
                    TextButton(
                        onClick = {
                            if (n.isDirectory) dir = n.relativePath
                            else onPreviewFile(n.relativePath)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (n.isDirectory) "📁" else "📄")
                        Spacer(Modifier.padding(start = 8.dp))
                        Text(
                            n.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}
