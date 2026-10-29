package com.xrbbm.pideck.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
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
import androidx.compose.ui.unit.dp
import com.xrbbm.pideck.core.net.ApiClient
import com.xrbbm.pideck.core.net.DshGoal
import com.xrbbm.pideck.core.net.DshPlugin
import com.xrbbm.pideck.core.net.DshPlugins
import com.xrbbm.pideck.core.net.DshSkill
import com.xrbbm.pideck.core.net.DshSubagent
import com.xrbbm.pideck.core.net.UiMessage
import kotlinx.coroutines.launch

/**
 * DSH tools sheet (web parity): goal / subagents / skills / plugins.
 * Read-mostly; only plugins expose actions (run / stop / uninstall),
 * same as the web config panel (no install form yet).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DshToolsSheet(
    api: ApiClient,
    sessionId: String,
    onError: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var tab by remember { mutableStateOf(0) }
    val titles = listOf("目标", "子代理", "技能", "插件")
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
        ) {
            Text("DSH 工具", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            PrimaryTabRow(selectedTabIndex = tab) {
                titles.forEachIndexed { index, title ->
                    Tab(
                        selected = tab == index,
                        onClick = { tab = index },
                        text = { Text(title) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                when (tab) {
                    0 -> GoalsTab(api, sessionId)
                    1 -> SubagentsTab(api, sessionId)
                    2 -> SkillsTab(api, sessionId)
                    3 -> PluginsTab(api, sessionId, onError)
                }
            }
        }
    }
}

@Composable
private fun LoadingRow() {
    Row(
        Modifier.fillMaxWidth().padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text("加载中…", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        modifier = Modifier.padding(12.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun GoalsTab(api: ApiClient, sessionId: String) {
    var loaded by remember(sessionId) { mutableStateOf(false) }
    var goal by remember(sessionId) { mutableStateOf<DshGoal?>(null) }
    LaunchedEffect(sessionId) {
        goal = runCatching { api.fetchDshGoal(sessionId) }.getOrNull()
        loaded = true
    }
    if (!loaded) {
        LoadingRow()
        return
    }
    val g = goal
    if (g == null) {
        Hint("本会话没有目标")
        return
    }
    val phaseLabel = when (g.phase) {
        "active" -> "进行中"
        "paused" -> "已暂停"
        "blocked" -> "受阻"
        "complete" -> "已完成"
        else -> g.phase
    }
    Surface(
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(4.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    phaseLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "第 ${g.roundsStarted ?: 0}/${g.maxGoalRounds ?: 0} 轮",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            g.objective?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun SubagentsTab(api: ApiClient, sessionId: String) {
    var loaded by remember(sessionId) { mutableStateOf(false) }
    var subs by remember(sessionId) { mutableStateOf<List<DshSubagent>>(emptyList()) }
    var expanded by remember(sessionId) { mutableStateOf<String?>(null) }
    var history by remember(sessionId) { mutableStateOf<List<UiMessage>>(emptyList()) }

    LaunchedEffect(sessionId) {
        subs = runCatching { api.fetchDshSubagents(sessionId) }.getOrDefault(emptyList())
        loaded = true
    }
    LaunchedEffect(expanded) {
        val id = expanded
        history = if (id == null) {
            emptyList()
        } else {
            runCatching { api.fetchDshSubagentHistory(sessionId, id) }.getOrDefault(emptyList())
        }
    }

    if (!loaded) {
        LoadingRow()
        return
    }
    if (subs.isEmpty()) {
        Hint("没有子代理")
        return
    }
    LazyColumn {
        items(subs, key = { it.id }) { sub ->
            val open = expanded == sub.id
            Surface(
                tonalElevation = if (open) 3.dp else 1.dp,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
            ) {
                Column(Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            sub.label ?: sub.id,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            if (sub.isRunning) "运行中" else (sub.activity ?: "空闲"),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (sub.isRunning) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    TextButton(onClick = { expanded = if (open) null else sub.id }) {
                        Text(if (open) "收起记录" else "查看记录")
                    }
                    if (open) {
                        if (history.isEmpty()) {
                            Hint("没有可读记录")
                        } else {
                            history.forEach { m ->
                                Text(
                                    (if (m.isUser) "我：" else "助手：") + m.textForSearch().take(600),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(vertical = 3.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SkillsTab(api: ApiClient, sessionId: String) {
    var loaded by remember(sessionId) { mutableStateOf(false) }
    var skills by remember(sessionId) { mutableStateOf<List<DshSkill>>(emptyList()) }
    LaunchedEffect(sessionId) {
        skills = runCatching { api.fetchDshSkills(sessionId) }.getOrDefault(emptyList())
        loaded = true
    }
    if (!loaded) {
        LoadingRow()
        return
    }
    if (skills.isEmpty()) {
        Hint("没有可用技能")
        return
    }
    LazyColumn {
        items(skills, key = { it.name }) { skill ->
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        skill.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (skill.modelInvocable) "可调用" else "仅手动",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                skill.description?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun PluginsTab(api: ApiClient, sessionId: String, onError: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(false) }
    var data by remember { mutableStateOf(DshPlugins.Empty) }
    var busy by remember { mutableStateOf(false) }
    var confirmUninstall by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        scope.launch {
            data = runCatching { api.fetchDshPlugins() }.getOrDefault(DshPlugins.Empty)
            loaded = true
        }
    }
    LaunchedEffect(Unit) { refresh() }

    fun act(plugin: DshPlugin, action: String, packageId: String? = null) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val body = kotlinx.serialization.json.buildJsonObject {
                    put("sessionId", kotlinx.serialization.json.JsonPrimitive(sessionId))
                    if (packageId != null) {
                        put("packageId", kotlinx.serialization.json.JsonPrimitive(packageId))
                    }
                }
                api.dshPluginAction(plugin.pluginId, action, body)
                data = runCatching { api.fetchDshPlugins() }.getOrDefault(data)
            } catch (e: Exception) {
                onError((e as? com.xrbbm.pideck.core.net.ApiException)?.message ?: "$e")
            } finally {
                busy = false
            }
        }
    }

    if (!loaded) {
        LoadingRow()
        return
    }
    LazyColumn {
        if (data.dynamic.isEmpty()) {
            item(key = "dyn-empty") { Hint("没有动态插件") }
        }
        items(data.dynamic, key = { it.pluginId }) { plugin ->
            Surface(
                tonalElevation = 1.dp,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
            ) {
                Column(Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            plugin.pluginId,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            if (plugin.activeRun) "运行中" else "已停止",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (plugin.activeRun) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    plugin.error?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    plugin.packages.forEach { pkg ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                pkg.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                enabled = !busy && !plugin.activeRun,
                                onClick = { act(plugin, "run", pkg.packageId) },
                            ) { Text("运行") }
                            TextButton(
                                enabled = !busy && plugin.activeRun,
                                onClick = { act(plugin, "stop") },
                            ) { Text("停止") }
                            TextButton(
                                enabled = !busy,
                                onClick = {
                                    if (confirmUninstall != plugin.pluginId) {
                                        confirmUninstall = plugin.pluginId
                                    } else {
                                        confirmUninstall = null
                                        act(plugin, "uninstall")
                                    }
                                },
                            ) {
                                Text(
                                    if (confirmUninstall == plugin.pluginId) "确认删除" else "卸载",
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        }
        if (data.static.isNotEmpty()) {
            item(key = "static-title") {
                Text(
                    "内置插件",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
            }
            items(data.static, key = { it.entryId }) { entry ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        entry.moduleName,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (entry.enabled) "已启用" else "已停用",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
