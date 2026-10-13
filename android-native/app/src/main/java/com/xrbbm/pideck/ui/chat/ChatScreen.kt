package com.xrbbm.pideck.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xrbbm.pideck.core.net.ApiClient
import com.xrbbm.pideck.core.net.UiMessage
import com.xrbbm.pideck.core.net.WebSession
import kotlinx.coroutines.launch

/** Chat screen: timeline + approval card + floating input + ring/menu. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    api: ApiClient,
    session: WebSession,
    onOpenDrawer: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    sessions: List<WebSession>? = null,
    onSelectSession: ((WebSession) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val vm = remember(session.id) { ChatViewModel(api, session) }
    val snack = remember { SnackbarHostState() }
    var menuMsg by remember { mutableStateOf<UiMessage?>(null) }

    LaunchedEffect(session.id) { vm.start() }
    LaunchedEffect(vm.notice) {
        vm.notice?.let { (_, text) -> snack.showSnackbar(text) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            session.title.ifEmpty { session.id },
                            maxLines = 1,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        val m = vm.model
                        Text(
                            (m?.display ?: "模型") +
                                (vm.thinkingLevel?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
                navigationIcon = {
                    if (onOpenDrawer != null) {
                        IconButton(onClick = onOpenDrawer) {
                            Icon(Icons.Filled.Menu, contentDescription = "会话")
                        }
                    } else if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回",
                            )
                        }
                    }
                },
                actions = {
                    // Context ring: only with a runtime window (mirrors ContextRing).
                    val u = vm.usage
                    if (u != null && u.hasWindow) {
                        TextButton(onClick = {}) {
                            Text("${u.percent.toInt()}%")
                        }
                    }
                    if (vm.streaming) {
                        IconButton(onClick = { scope.launch { vm.stop() } }) {
                            Icon(Icons.Filled.Stop, contentDescription = "中断")
                        }
                    }
                },
            )
        },
        bottomBar = {
            var modelSheet by remember { mutableStateOf(false) }
            var thinkingSheet by remember { mutableStateOf(false) }
            InputCard(
                text = vm.inputText,
                onText = vm::onInputChange,
                streaming = vm.streaming,
                planMode = vm.planMode,
                modelLabel = vm.model?.display ?: "模型",
                thinkingLabel = vm.thinkingLevel,
                attachments = vm.attachments,
                onRemoveAttachment = vm::removeAttachment,
                onPlanToggle = { vm.planMode = !vm.planMode },
                onModelTap = { modelSheet = true },
                onThinkingTap = { thinkingSheet = true },
                onSend = { scope.launch { vm.send() } },
                onStop = { scope.launch { vm.stop() } },
            )
            if (modelSheet) {
                ModelSheet(
                    api = api,
                    current = vm.model,
                    currentLevel = vm.thinkingLevel,
                    onDismiss = { modelSheet = false },
                    onPick = { m, level ->
                        modelSheet = false
                        scope.launch { vm.setModel(m, level) }
                    },
                )
            }
            if (thinkingSheet) {
                ThinkingSheet(
                    levels = vm.model?.levels?.takeIf { it.isNotEmpty() }
                        ?: THINKING_FALLBACK,
                    current = vm.thinkingLevel,
                    onDismiss = { thinkingSheet = false },
                    onPick = { level ->
                        thinkingSheet = false
                        scope.launch { vm.setThinking(level) }
                    },
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(Modifier.fillMaxSize()) {
                BranchBar(
                    sessions = sessions,
                    activeId = session.id,
                    onSelect = onSelectSession,
                )
                vm.pendingUi?.let { req ->
                    AskCard(
                        request = req,
                        busy = vm.uiResponding,
                        onRespond = { json -> scope.launch { vm.respondUi(json) } },
                    )
                }
                ActivityStrips(api = api, sessionId = session.id)
                Box(Modifier.weight(1f)) {
                    ChatTimeline(
                        messages = vm.messages,
                        loading = vm.loading,
                        loadingMore = false,
                        hasMore = vm.hasMore,
                        error = null,
                        unavailable = null,
                        approvalSeq = vm.approvalSeq,
                        streaming = vm.streaming,
                        onLoadMore = { scope.launch { vm.loadMore() } },
                        onRetry = { scope.launch { vm.loadFirst() } },
                        onIgnoreError = vm::clearError,
                        onUserMessageLongPress = { menuMsg = it },
                    )
                }
            }
            // Inline lanes float above the timeline (input stays alive).
            vm.error?.let { msg ->
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(8.dp),
                ) {
                    androidx.compose.material3.Surface(
                        tonalElevation = 3.dp,
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                msg,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { scope.launch { vm.loadFirst() } }) {
                                Text("重试")
                            }
                            TextButton(onClick = vm::clearError) { Text("忽略") }
                        }
                    }
                }
            }
        }
    }

    menuMsg?.let { m ->
        ModalBottomSheet(onDismissRequest = { menuMsg = null }) {
            Column(Modifier.padding(16.dp)) {
                TextButton(
                    onClick = {
                        scope.launch { vm.resendFill(m.id) }
                        menuMsg = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("重发（回填输入框）") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Rounded floating input card: attachment strip + text field + two-row
 * toolbar (send/stop right, model + thinking + plan chips left).
 * Mirrors Flutter: model name truncates at 12 chars, plan chip never
 * squeezed out.
 */
@Composable
private fun InputCard(
    text: String,
    onText: (String) -> Unit,
    streaming: Boolean,
    planMode: Boolean,
    modelLabel: String,
    thinkingLabel: String?,
    attachments: List<String>,
    onRemoveAttachment: (Int) -> Unit,
    onPlanToggle: () -> Unit,
    onModelTap: () -> Unit,
    onThinkingTap: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    androidx.compose.material3.Surface(
        tonalElevation = 3.dp,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
    ) {
        Column(Modifier.padding(10.dp)) {
            if (attachments.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    attachments.forEachIndexed { i, _ ->
                        androidx.compose.material3.AssistChip(
                            onClick = {},
                            label = { Text("图${i + 1}") },
                            trailingIcon = {
                                TextButton(onClick = { onRemoveAttachment(i) }) {
                                    Text("✕")
                                }
                            },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            OutlinedTextField(
                value = text,
                onValueChange = onText,
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                placeholder = { Text("输入消息…") },
                shape = RoundedCornerShape(14.dp),
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onThinkingTap) {
                    Text(thinkingLabel ?: "档位")
                }
                TextButton(onClick = onModelTap) {
                    Text(
                        if (modelLabel.length > 12) modelLabel.take(12) + "…" else modelLabel,
                    )
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onPlanToggle) {
                    Text(if (planMode) "计划·开" else "计划")
                }
                if (streaming) {
                    IconButton(onClick = onStop) {
                        Icon(Icons.Filled.Stop, contentDescription = "中断")
                    }
                } else {
                    IconButton(
                        onClick = onSend,
                        enabled = text.trim().isNotEmpty() || attachments.isNotEmpty(),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "发送",
                        )
                    }
                }
            }
        }
    }
}
