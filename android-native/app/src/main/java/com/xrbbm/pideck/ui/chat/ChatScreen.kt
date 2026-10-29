package com.xrbbm.pideck.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.xrbbm.pideck.core.net.ApiClient
import com.xrbbm.pideck.core.net.UiMessage
import com.xrbbm.pideck.core.net.WebSession
import com.xrbbm.pideck.ui.theme.AppIcons
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
    projects: List<com.xrbbm.pideck.core.net.WebProject>? = null,
    onSelectSession: ((WebSession) -> Unit)? = null,
    isDark: Boolean = false,
    onToggleTheme: () -> Unit = {},
    onSearchMessages: () -> Unit = {},
    onOpenSkills: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val vm = remember(session.id) { ChatViewModel(api, session) }
    val snack = remember { SnackbarHostState() }
    var menuMsg by remember { mutableStateOf<UiMessage?>(null) }
    var workspace by remember { mutableStateOf(false) }
    var previewPath by remember { mutableStateOf<String?>(null) }
    // Project resolution order: match session in list > session.projectId >
    // single-project fallback (mirrors Flutter _projectId).
    val projectId = remember(sessions, session, projects) {
        sessions?.firstOrNull { it.id == session.id }?.projectId
            ?.takeIf { it.isNotEmpty() }
            ?: session.projectId.takeIf { it.isNotEmpty() }
            ?: projects?.singleOrNull()?.id?.takeIf { it.isNotEmpty() }
    }

    LaunchedEffect(session.id) { vm.start() }
    LaunchedEffect(vm.notice) {
        vm.notice?.let { (_, text) -> snack.showSnackbar(text) }
    }

    var overflow by remember { mutableStateOf(false) }
    var showUsage by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    // Search-result jump target (message id); consumed by ChatTimeline.
    var jumpTo by remember { mutableStateOf<String?>(null) }
    var showSkills by remember { mutableStateOf(false) }
    var showDsh by remember { mutableStateOf(false) }
    var permissionMenu by remember { mutableStateOf(false) }
    var confirmFullAccess by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val switchPermission: (String) -> Unit = { preset ->
        scope.launch {
            try {
                // Live runtime applies immediately; the record write keeps the
                // next run on the same preset (web does both).
                val rt = vm.runtime
                if (rt != null && rt.agentId.isNotEmpty()) {
                    api.setRuntimePermission(
                        session.id,
                        rt.agentId,
                        rt.runtimeGeneration ?: 0,
                        preset,
                    )
                }
                api.updateSessionPermission(session.id, preset)
                snack.showSnackbar("已切换权限预设：${DSH_PERMISSION_PRESETS.first { it.id == preset }.label}")
            } catch (e: Exception) {
                snack.showSnackbar("切换失败：${e.message}")
            }
        }
    }
    val onlineColor = MaterialTheme.colorScheme.secondary
    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            // Slim header: dense 40dp bar pinned to top (no M3 64dp slack).
            androidx.compose.material3.CenterAlignedTopAppBar(
                expandedHeight = 40.dp,
                windowInsets = androidx.compose.foundation.layout.WindowInsets(0.dp),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            session.title.ifEmpty { "选择或新建会话" },
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.width(6.dp))
                        // Status follows runtime + streaming (was hardcoded idle).
                        // running/starting/streaming = 运行中(blue), else 空闲.
                        val busy = vm.streaming || vm.externalStreaming || (vm.runtime?.isBusy == true)
                        val statusColor = if (busy) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            onlineColor
                        }
                        androidx.compose.foundation.Canvas(Modifier.size(6.dp)) {
                            drawCircle(statusColor)
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (busy) "运行中" else "空闲",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    // DSH-only permission preset (web: DshPermissionMenu in the
                    // composer bar; kept in the header here to avoid growing
                    // InputCard's already wide parameter list).
                    if (session.backend == "dsh") {
                        IconButton(onClick = { permissionMenu = true }) {
                            Icon(
                                AppIcons.Security,
                                contentDescription = "权限预设",
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        androidx.compose.material3.DropdownMenu(
                            expanded = permissionMenu,
                            onDismissRequest = { permissionMenu = false },
                        ) {
                            DSH_PERMISSION_PRESETS.forEach { preset ->
                                androidx.compose.material3.DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(preset.label)
                                            Text(
                                                preset.description,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    },
                                    trailingIcon = {
                                        if (preset.id == vm.usage?.permissionPreset) {
                                            Icon(AppIcons.Check, contentDescription = null)
                                        }
                                    },
                                    onClick = {
                                        permissionMenu = false
                                        if (preset.id == "danger-full-access") {
                                            confirmFullAccess = true
                                        } else {
                                            switchPermission(preset.id)
                                        }
                                    },
                                )
                            }
                        }
                    }
                    // Context ring: only with a runtime window (mirrors ContextRing).
                    // Tap opens the usage sheet (mirrors Flutter _showUsage).
                    val u = vm.usage
                    if (u != null && u.hasWindow) {
                        // Direct clickable text: TextButton min-size can miss
                        // taps inside the dense 40dp bar (sim verified).
                        Text(
                            "${u.percent.toInt()}%",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier
                                .clickable { showUsage = true }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                        )
                    }
                    // Multi-project mismatch hides the entry (mirrors Flutter).
                    if (projectId != null) {
                        IconButton(onClick = { workspace = true }) {
                            Icon(
                                AppIcons.Folder,
                                contentDescription = "工作区",
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                    // Web 1:1 right ⋮ menu (screenshot 1): search messages,
                    // toggle theme, install app (placeholder), skills.
                    IconButton(onClick = { overflow = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                    }
                    androidx.compose.material3.DropdownMenu(
                        expanded = overflow,
                        onDismissRequest = { overflow = false },
                    ) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("搜索会话消息") },
                            leadingIcon = {
                                Icon(AppIcons.Search, contentDescription = null)
                            },
                            onClick = {
                                overflow = false
                                showSearch = true
                            },
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(if (isDark) "切换主题 · 浅色" else "切换主题 · 暗色") },
                            leadingIcon = {
                                Icon(
                                    if (isDark) AppIcons.ThemeLight else AppIcons.ThemeDark,
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                overflow = false
                                onToggleTheme()
                            },
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("技能与扩展") },
                            leadingIcon = {
                                Icon(AppIcons.Skills, contentDescription = null)
                            },
                            onClick = {
                                overflow = false
                                showSkills = true
                            },
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("DSH 工具") },
                            leadingIcon = {
                                Icon(AppIcons.Terminal, contentDescription = null)
                            },
                            onClick = {
                                overflow = false
                                showDsh = true
                            },
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("复制会话为 Markdown") },
                            leadingIcon = {
                                Icon(AppIcons.Copy, contentDescription = null)
                            },
                            onClick = {
                                overflow = false
                                clipboard.setText(AnnotatedString(sessionToMarkdown(vm.messages)))
                                scope.launch { snack.showSnackbar("已复制") }
                            },
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("刷新会话消息") },
                            leadingIcon = {
                                Icon(AppIcons.Refresh, contentDescription = null)
                            },
                            onClick = {
                                overflow = false
                                scope.launch { vm.loadFirst() }
                            },
                        )
                    }
                },
            )
        },
        bottomBar = {
            var modelSheet by remember { mutableStateOf(false) }
            var thinkingSheet by remember { mutableStateOf(false) }
            var promptSheet by remember { mutableStateOf(false) }
            var attaching by remember { mutableStateOf(false) }
            val context = androidx.compose.ui.platform.LocalContext.current
            val pickImages =
                androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(
                        maxItems = ImageAttach.MAX_COUNT,
                    ),
                ) { uris ->
                    if (uris.isEmpty()) return@rememberLauncherForActivityResult
                    scope.launch {
                        attaching = true
                        try {
                            val (encoded, dropped) = ImageAttach.encodeAll(context, uris)
                            vm.addAttachments(encoded)
                            if (dropped > 0) vm.reportError("有 $dropped 张图片过大（>2MB），已跳过")
                        } catch (e: Exception) {
                            vm.reportError("添加图片失败：$e")
                        } finally {
                            attaching = false
                        }
                    }
                }
            // Pinned strips above input (user screenshot 1): fixed rows
            // for file-changes / subagents / todo, content stays collapsed.
            // Keyboard: Scaffold content padding ignores IME; the bottom
            // bar must consume it or the keyboard covers the input.
            Column(Modifier.fillMaxWidth().imePadding()) {
                PinnedStrips(api = api, sessionId = session.id)
                InputCard(
                    text = vm.inputText,
                    onText = vm::onInputChange,
                    streaming = vm.streaming || vm.externalStreaming,
                    attaching = attaching,
                    planMode = vm.planMode,
                    modelLabel = vm.model?.display ?: "模型",
                    thinkingLabel = vm.thinkingLevel,
                    attachments = vm.attachments,
                    onRemoveAttachment = vm::removeAttachment,
                    onPickImages = {
                        pickImages.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                    },
                    onPromptTap = { promptSheet = true },
                    onPlanToggle = { vm.planMode = !vm.planMode },
                    onModelTap = { modelSheet = true },
                    onThinkingTap = { thinkingSheet = true },
                    onSend = { scope.launch { vm.send() } },
                    onStop = { scope.launch { vm.stop() } },
                )
            }
            if (promptSheet) {
                PromptSheet(
                    api = api,
                    onDismiss = { promptSheet = false },
                    onInsert = { content ->
                        promptSheet = false
                        vm.appendPrompt(content)
                    },
                )
            }
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
                        // Sheet form: swipe-dismiss only snoozes (keeps the
                        // request; the 3s poll re-shows it while pending).
                        onDismiss = { vm.snoozeUi() },
                    )
                }
                Box(Modifier.weight(1f)) {
                    ChatTimeline(
                        messages = vm.messages,
                        loading = vm.loading,
                        loadingMore = false,
                        hasMore = vm.hasMore,
                        error = null,
                        unavailable = null,
                        approvalSeq = vm.approvalSeq,
                        streaming = vm.streaming || vm.externalStreaming,
                        onLoadMore = { scope.launch { vm.loadMore() } },
                        onRetry = { scope.launch { vm.loadFirst() } },
                        onIgnoreError = vm::clearError,
                        onUserMessageLongPress = { menuMsg = it },
                        jumpTo = jumpTo,
                        onJumpDone = { jumpTo = null },
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

    // Context usage sheet (mirrors Flutter _showUsage + web dialog):
    // percent + tokens/window, overflow warning in error color.
    if (showUsage) {
        val u = vm.usage
        if (u != null) {
            ModalBottomSheet(onDismissRequest = { showUsage = false }) {
                Column(Modifier.padding(20.dp)) {
                    Text("上下文用量", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${"%.1f".format(u.percent)}% · " +
                            "${u.contextTokens ?: "–"} / ${u.contextWindow ?: "–"} tokens",
                    )
                    if (u.inputTokens != null || u.outputTokens != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "输入 ${u.inputTokens ?: "–"} · 输出 ${u.outputTokens ?: "–"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (u.contextOverflow) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "已溢出，回答可能丢失早段记忆",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    ProviderUsageBlock(
                        api = api,
                        provider = session.modelProvider,
                        backend = session.backend ?: "pi",
                    )
                    Spacer(Modifier.height(24.dp))
                }
            }
        } else {
            showUsage = false
        }
    }

    if (confirmFullAccess) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmFullAccess = false },
            title = { Text("切换到完全访问？") },
            text = {
                Text(
                    "完全访问（danger-full-access）下工具调用无需逐次批准，" +
                        "可以执行任意命令并写入任意文件。确认切换到该预设？",
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    confirmFullAccess = false
                    switchPermission("danger-full-access")
                }) { Text("确认切换") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = {
                    confirmFullAccess = false
                }) { Text("取消") }
            },
        )
    }

    if (showDsh) {
        DshToolsSheet(
            api = api,
            sessionId = session.id,
            onError = { scope.launch { snack.showSnackbar(it) } },
            onDismiss = { showDsh = false },
        )
    }
    if (showSearch) {
        MessageSearchSheet(
            messages = vm.messages,
            onSearchServer = { vm.searchHistory(it) },
            onJump = { jumpTo = it },
            onDismiss = { showSearch = false },
        )
    }
    if (showSkills) {
        SkillsExtensionsSheet(
            api = api,
            scope = scope,
            onError = { scope.launch { snack.showSnackbar(it) } },
            onDismiss = { showSkills = false },
        )
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

    if (workspace && projectId != null) {
        com.xrbbm.pideck.ui.workspace.WorkspaceDrawer(
            api = api,
            projectId = projectId,
            onPreviewFile = { previewPath = it },
            onDismiss = { workspace = false },
        )
    }
    previewPath?.let { path ->
        if (projectId != null) {
            com.xrbbm.pideck.ui.workspace.FilePreviewSheet(
                api = api,
                projectId = projectId,
                path = path,
                onDismiss = { previewPath = null },
            )
        }
    }
}

/**
 * Web 1:1 composer (screenshot 1 bottom): placeholder 发送消息到当前会话,
 * single bottom row: Pi pill / model pill / web icon / image icon / send.
 * Thinking-level + plan mode stay reachable via model sheet long-press
 * area (kept as separate chips above when active).
 */
@Composable
private fun InputCard(
    text: String,
    onText: (String) -> Unit,
    streaming: Boolean,
    attaching: Boolean = false,
    planMode: Boolean,
    modelLabel: String,
    thinkingLabel: String?,
    attachments: List<String>,
    onRemoveAttachment: (Int) -> Unit,
    onPickImages: () -> Unit = {},
    onPromptTap: () -> Unit = {},
    onPlanToggle: () -> Unit,
    onModelTap: () -> Unit,
    onThinkingTap: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    androidx.compose.material3.OutlinedCard(
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            if (attachments.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    attachments.forEachIndexed { i, _ ->
                        androidx.compose.material3.InputChip(
                            selected = false,
                            onClick = {},
                            label = { Text("图${i + 1}") },
                            trailingIcon = {
                                IconButton(
                                    onClick = { onRemoveAttachment(i) },
                                    modifier = Modifier.size(24.dp),
                                ) {
                                    Icon(
                                        AppIcons.Close,
                                        contentDescription = "移除图片",
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            },
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
            // Adaptive height: start ~1/4 screen, grow with content,
            // cap at 1/2 screen then scroll inside (user screenshot spec).
            val density = androidx.compose.ui.platform.LocalDensity.current
            val screenH = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
            val minH = screenH * 0.08f
            val maxH = screenH * 0.5f
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = minH, max = maxH)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (text.isEmpty()) {
                    Text(
                        "发送消息到当前会话",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                androidx.compose.foundation.text.BasicTextField(
                    value = text,
                    onValueChange = onText,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = minH),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                )
            }
            // Model name gets its own top row, full width (no truncation).
            TextButton(
                onClick = onModelTap,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 4.dp,
                    vertical = 0.dp,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    modelLabel,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(4.dp))
            // Bottom row: agent + thinking left, actions right.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Pi",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                thinkingLabel?.let {
                    TextButton(onClick = onThinkingTap) { Text(it) }
                }
                if (planMode) {
                    TextButton(onClick = onPlanToggle) { Text("计划·开") }
                }
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = onPromptTap,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        AppIcons.Prompt,
                        contentDescription = "提示词库",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }
                IconButton(
                    onClick = onPickImages,
                    enabled = !attaching && !streaming,
                    modifier = Modifier.size(40.dp),
                ) {
                    if (attaching) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp))
                    } else {
                        Icon(
                            AppIcons.Image,
                            contentDescription = "添加图片",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                var confirmStop by remember { mutableStateOf(false) }
                if (confirmStop) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { confirmStop = false },
                        title = { Text("停止当前运行？") },
                        text = { Text("中断后已生成的内容会保留，运行时停止。") },
                        confirmButton = {
                            androidx.compose.material3.TextButton(onClick = {
                                confirmStop = false
                                onStop()
                            }) { Text("停止") }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(onClick = {
                                confirmStop = false
                            }) { Text("继续运行") }
                        },
                    )
                }
                androidx.compose.material3.FilledTonalButton(
                    onClick = { if (streaming) confirmStop = true else onSend() },
                    enabled = streaming ||
                        text.trim().isNotEmpty() || attachments.isNotEmpty(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 16.dp,
                        vertical = 8.dp,
                    ),
                ) {
                    Text(if (streaming) "停止" else "发送")
                }
            }
        }
    }
}

/**
 * DSH permission presets (web `DSH_PERMISSION_PRESETS`): sandbox + approval
 * policy in one switch. Ids are the server-side enum values.
 */
private data class DshPermissionPreset(
    val id: String,
    val label: String,
    val description: String,
)

private val DSH_PERMISSION_PRESETS = listOf(
    DshPermissionPreset("read-only", "Read Only", "沙箱只读 + 每次批准"),
    DshPermissionPreset("workspace-write", "Workspace Write", "工作区写入 + 每次批准（默认）"),
    DshPermissionPreset("danger-full-access", "Full access", "完全访问 + 无需批准"),
)
