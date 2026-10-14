package com.xrbbm.pideck.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
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
import com.xrbbm.pideck.core.net.ApiClient
import com.xrbbm.pideck.core.net.ExtensionSummary
import com.xrbbm.pideck.core.net.SkillSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Skills & extensions sheet: list + enable toggle only (mirrors web
 * dialog — no install/uninstall here).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsExtensionsSheet(
    api: ApiClient,
    scope: CoroutineScope,
    onError: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var tab by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var skills by remember { mutableStateOf<List<SkillSummary>>(emptyList()) }
    var extensions by remember { mutableStateOf<List<ExtensionSummary>>(emptyList()) }

    LaunchedEffect(Unit) {
        try {
            skills = api.fetchSkills()
            extensions = api.fetchExtensions()
        } catch (e: Exception) {
            onError("加载失败：${e.message}")
        } finally {
            loading = false
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { tab = 0 }, modifier = Modifier.weight(1f)) {
                    Text(
                        "技能 ${skills.size}",
                        color = if (tab == 0) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                TextButton(onClick = { tab = 1 }, modifier = Modifier.weight(1f)) {
                    Text(
                        "扩展 ${extensions.size}",
                        color = if (tab == 1) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            if (loading) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Spacer(Modifier.weight(1f))
                    CircularProgressIndicator()
                    Spacer(Modifier.weight(1f))
                }
            } else if (tab == 0) {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(skills, key = { it.id }) { s ->
                        var on by remember(s.id) { mutableStateOf(s.enabled) }
                        var busy by remember(s.id) { mutableStateOf(false) }
                        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(s.name, style = MaterialTheme.typography.titleSmall)
                                    if (s.description.isNotEmpty()) {
                                        Text(
                                            s.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                        )
                                    }
                                    Text(
                                        s.sourceLabel.ifEmpty { s.sourceId },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(
                                    checked = on,
                                    enabled = !busy,
                                    onCheckedChange = { v ->
                                        scope.launch {
                                            busy = true
                                            try {
                                                api.toggleSkill(s.name, s.sourceId, v)
                                                on = v
                                                skills = skills.map {
                                                    if (it.id == s.id) it.copy(enabled = v) else it
                                                }
                                            } catch (e: Exception) {
                                                onError("切换失败：${e.message}")
                                            } finally {
                                                busy = false
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(extensions, key = { it.id }) { e ->
                        var on by remember(e.id) { mutableStateOf(e.enabled) }
                        var busy by remember(e.id) { mutableStateOf(false) }
                        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(e.source, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "${e.currentVersion}" +
                                            (if (e.hasUpdate) " → ${e.latestVersion}" else "") +
                                            " · ${e.scope}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(
                                    checked = on,
                                    enabled = !busy,
                                    onCheckedChange = { v ->
                                        scope.launch {
                                            busy = true
                                            try {
                                                api.toggleExtension(e.source, e.scope, v)
                                                on = v
                                                extensions = extensions.map {
                                                    if (it.id == e.id) it.copy(enabled = v) else it
                                                }
                                            } catch (e2: Exception) {
                                                onError("切换失败：${e2.message}")
                                            } finally {
                                                busy = false
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
