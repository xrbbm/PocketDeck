package com.xrbbm.pideck.ui.shell

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.xrbbm.pideck.core.Pairing
import com.xrbbm.pideck.core.PairingStore
import com.xrbbm.pideck.core.net.ApiClient
import com.xrbbm.pideck.core.net.ApiException
import com.xrbbm.pideck.core.net.WebSession
import com.xrbbm.pideck.core.net.WebState
import com.xrbbm.pideck.ui.chat.ChatScreen
import com.xrbbm.pideck.ui.chat.DraftBox
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Drawer shell: left drawer = grouped sessions, body = current chat.
 * Cold start jumps straight into the last chat (last_session_id).
 * Write ops mute the 5s poller so no stale frame lands mid-write.
 */
@Composable
fun ShellScreen(
    api: ApiClient,
    pairing: Pairing,
    isDark: Boolean,
    onToggleTheme: () -> Unit,
    onUnpair: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember { PairingStore(context) }
    val scope = rememberCoroutineScope()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val snack = remember { SnackbarHostState() }

    var state by remember { mutableStateOf<WebState?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var entered by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var newDialog by remember { mutableStateOf(false) }
    var newProjectDialog by remember { mutableStateOf(false) }
    var presetProjectId by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<WebSession?>(null) }
    var deleteTarget by remember { mutableStateOf<WebSession?>(null) }
    var confirmUnpair by remember { mutableStateOf(false) }

    suspend fun refresh() {
        if (refreshing || muted) return
        refreshing = true
        try {
            val s = api.fetchState()
            state = s
            error = null
            loading = false
            if (!entered) {
                entered = true
                val saved = store.loadLastSessionId()
                selectedId = if (saved != null && s.sessions.any { it.id == saved }) {
                    saved
                } else {
                    scope.launch { drawer.open() }
                    null
                }
            } else if (selectedId != null && s.sessions.none { it.id == selectedId }) {
                selectedId = null
            }
        } catch (e: Exception) {
            error = (e as? ApiException)?.message ?: "$e"
            loading = false
        } finally {
            refreshing = false
        }
    }

    LaunchedEffect(pairing.baseUrl) {
        refresh()
        while (isActive) {
            delay(5000)
            refresh()
        }
    }

    suspend fun select(s: WebSession) {
        store.saveLastSessionId(s.id)
        selectedId = s.id
        scope.launch { drawer.close() }
    }

    val session: WebSession? = state?.sessions?.firstOrNull { it.id == selectedId }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            // Session list takes 80% screen width (keep chat visible behind).
            ModalDrawerSheet(modifier = Modifier.fillMaxWidth(0.8f)) {
                SessionsDrawer(
                    state = state,
                    loading = loading,
                    error = error,
                    selectedId = selectedId,
                    onSelect = { scope.launch { select(it) } },
                    onNewProject = { newProjectDialog = true },
                    onNewSession = { projectId ->
                        presetProjectId = projectId
                        newDialog = true
                    },
                    onRename = { renameTarget = it },
                    onCopy = { s ->
                        scope.launch {
                            muted = true
                            try {
                                val newId = api.copySession(s.id)
                                refresh()
                                if (newId != null) {
                                    store.saveLastSessionId(newId)
                                    selectedId = newId
                                    drawer.close()
                                }
                            } catch (e: Exception) {
                                snack.showSnackbar(
                                    "复制失败：${(e as? ApiException)?.message ?: e}",
                                )
                            } finally {
                                muted = false
                            }
                        }
                    },
                    onExport = { s ->
                        scope.launch {
                            try {
                                val bytes = api.exportSessionHtml(s.id)
                                shareHtml(context, s, bytes)
                            } catch (e: Exception) {
                                snack.showSnackbar(
                                    "导出失败：${(e as? ApiException)?.message ?: e}",
                                )
                            }
                        }
                    },
                    onDelete = { deleteTarget = it },
                    onDisconnect = { confirmUnpair = true },
                    onRefresh = { scope.launch { refresh() } },
                    isDark = isDark,
                    onToggleTheme = onToggleTheme,
                )
            }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            androidx.compose.material3.Scaffold(
                snackbarHost = { SnackbarHost(snack) },
            ) { padding ->
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    when {
                        session != null -> ChatScreen(
                            api = api,
                            session = session,
                            onOpenDrawer = { scope.launch { drawer.open() } },
                            sessions = state?.sessions,
                            projects = state?.projects,
                            onSelectSession = { scope.launch { select(it) } },
                            isDark = isDark,
                            onToggleTheme = onToggleTheme,
                        )
                        loading -> Box(
                            Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) { CircularProgressIndicator() }
                        error != null -> Column(
                            Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Spacer(Modifier.height(80.dp))
                            Text(error!!)
                            Spacer(Modifier.height(12.dp))
                            FilledTonalButton(
                                onClick = { scope.launch { drawer.open() } },
                            ) { Text("选择会话") }
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { scope.launch { refresh() } }) {
                                Text("重试")
                            }
                        }
                        else -> Column(
                            Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Spacer(Modifier.height(80.dp))
                            FilledTonalButton(
                                onClick = { scope.launch { drawer.open() } },
                            ) { Text("选择会话") }
                        }
                    }
                }
            }
        }
    }

    if (newDialog) {
        NewSessionDialog(
            api = api,
            projects = state?.projects ?: emptyList(),
            initialProjectId = presetProjectId,
            lockProject = presetProjectId != null,
            onDismiss = {
                newDialog = false
                presetProjectId = null
            },
            onCreated = { id ->
                scope.launch {
                    newDialog = false
                    presetProjectId = null
                    refresh()
                    store.saveLastSessionId(id)
                    selectedId = id
                    drawer.close()
                }
            },
        )
    }

    if (newProjectDialog) {
        NewProjectDialog(
            api = api,
            onDismiss = { newProjectDialog = false },
            onCreated = {
                scope.launch {
                    newProjectDialog = false
                    refresh()
                }
            },
        )
    }

    renameTarget?.let { s ->
        var title by remember(s.id) { mutableStateOf(s.title) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名会话") },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    placeholder = { Text("新标题") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val t = title.trim()
                    if (t.isEmpty()) return@TextButton
                    scope.launch {
                        muted = true
                        try {
                            api.renameSession(s.id, t)
                            refresh()
                        } catch (e: Exception) {
                            snack.showSnackbar(
                                "重命名失败：${(e as? ApiException)?.message ?: e}",
                            )
                        } finally {
                            muted = false
                            renameTarget = null
                        }
                    }
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("取消") }
            },
        )
    }

    deleteTarget?.let { s ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除会话？") },
            text = {
                Text("“${s.title.ifEmpty { s.id }}” 将被删除（含会话文件），无法恢复。")
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        muted = true
                        try {
                            api.deleteSession(s.id)
                            DraftBox.clear(s.id)
                            if (store.loadLastSessionId() == s.id) {
                                store.clearLastSessionId()
                            }
                            refresh()
                        } catch (e: Exception) {
                            snack.showSnackbar(
                                "删除失败：${(e as? ApiException)?.message ?: e}",
                            )
                        } finally {
                            muted = false
                            deleteTarget = null
                        }
                    }
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }

    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            title = { Text("断开配对？") },
            text = { Text("将清除配对信息并回到扫码页，需要重新扫码。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnpair = false
                    scope.launch { onUnpair() }
                }) { Text("断开") }
            },
            dismissButton = {
                TextButton(onClick = { confirmUnpair = false }) { Text("取消") }
            },
        )
    }
}

private fun shareHtml(context: Context, s: WebSession, bytes: ByteArray) {
    val title = s.title.ifEmpty { s.id }.replace(Regex("[^\\w\\-]+"), "_")
    val file = File(context.cacheDir, "pideck-$title.html")
    file.writeBytes(bytes)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/html"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TEXT, "PiDeck 会话导出：${s.title.ifEmpty { s.id }}")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "分享会话导出"))
}
