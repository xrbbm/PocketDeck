package com.xrbbm.pideck.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.xrbbm.pideck.core.net.UiMessage
import com.xrbbm.pideck.core.net.UiPart

import com.xrbbm.pideck.ui.theme.AppIcons
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Chat timeline: history paging (pull-up) + streaming segments in arrival
 * order + jump-to-bottom arrow + inline error with retry/ignore.
 *
 * Mirrors Flutter ChatPage list behavior:
 * - follow-scroll only while pinned near the bottom (>120px away = free)
 * - arrow appears only when >160px from the bottom
 * - history failures degrade inline; the input box stays alive (separate lane)
 */
@Composable
fun ChatTimeline(
    messages: List<UiMessage>,
    loading: Boolean,
    loadingMore: Boolean,
    hasMore: Boolean,
    error: String?,
    unavailable: String?,
    approvalSeq: Int,
    streaming: Boolean,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onIgnoreError: () -> Unit,
    onUserMessageLongPress: (UiMessage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Pinned to bottom while the user stays near it (120px follow window).
    val pinned by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val total = info.totalItemsCount
            if (total == 0) return@derivedStateOf true
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            last.index >= total - 1 &&
                last.offset + last.size <= info.viewportEndOffset + 120
        }
    }
    val showJump by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val total = info.totalItemsCount
            if (total == 0) return@derivedStateOf false
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf false
            !(last.index >= total - 1 &&
                last.offset + last.size <= info.viewportEndOffset + 160)
        }
    }

    // New messages / streaming deltas follow only when pinned.
    LaunchedEffect(messages.size, streaming) {
        if (pinned && messages.isNotEmpty()) {
            listState.scrollToItem(messages.size - 1)
        }
    }
    // New approval arrival always jumps (mirrors Flutter _jumpToBottom).
    LaunchedEffect(approvalSeq) {
        if (approvalSeq > 0 && messages.isNotEmpty()) {
            listState.scrollToItem(messages.size - 1)
        }
    }
    // Pull-up paging: reaching the top loads the next page.
    LaunchedEffect(listState, hasMore) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collectLatest { (index, offset) ->
                if (index == 0 && offset <= 0 && hasMore && !loading && !loadingMore) {
                    onLoadMore()
                }
            }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Box
        }
        // Web 1:1 execution grouping: consecutive assistant tool/reasoning
        // messages fold into one ExecutionCard (no same-tool merging).
        // User text always breaks the run (mirrors web timeline).
        val blocks = remember(messages) { splitExecutionBlocks(messages) }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (loadingMore) {
                item(key = "paging") {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(8.dp)
                                .size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                }
            }
            if (messages.isEmpty() && unavailable == null && error == null) {
                item(key = "empty") {
                    EmptyTimeline()
                }
            }
            unavailable?.let { reason ->
                item(key = "unavailable") {
                    Surface(
                        tonalElevation = 2.dp,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text("历史暂不可用（$reason）", style = MaterialTheme.typography.bodyMedium)
                            Row {
                                TextButton(onClick = onRetry) { Text("重试") }
                                TextButton(onClick = onIgnoreError) { Text("忽略") }
                            }
                        }
                    }
                }
            }
            error?.let { msg ->
                item(key = "error") {
                    Surface(
                        tonalElevation = 2.dp,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(msg, color = MaterialTheme.colorScheme.error)
                            Row {
                                TextButton(onClick = onRetry) { Text("重试") }
                                TextButton(onClick = onIgnoreError) { Text("忽略") }
                            }
                        }
                    }
                }
            }
            blocks.forEachIndexed { bi, block ->
                when (block) {
                    is TimelineBlock.Single -> {
                        val m = block.message
                        item(key = m.id) {
                            MessageRow(
                                message = m,
                                onUserLongPress = { onUserMessageLongPress(m) },
                            )
                        }
                    }
                    is TimelineBlock.Execution -> {
                        item(key = "exec-$bi-${block.messages.first().id}") {
                            ExecutionCard(messages = block.messages)
                        }
                    }
                }
            }
            if (streaming) {
                item(key = "streaming-tail") {
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
        AnimatedVisibility(
            visible = showJump,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            SmallFloatingActionButton(
                onClick = {
                    scope.launch { listState.animateScrollToItem(messages.size - 1) }
                },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                androidx.compose.material3.Icon(
                    Icons.Filled.ArrowDownward,
                    contentDescription = "回到底部",
                )
            }
        }
    }
}

/**
 * Timeline segmentation (web 1:1): consecutive assistant tool/reasoning
 * messages form one execution block; user text or plain assistant text
 * breaks it. Only three card levels: execution -> entry -> content.
 */
sealed interface TimelineBlock {
    data class Single(val message: UiMessage) : TimelineBlock
    data class Execution(val messages: List<UiMessage>) : TimelineBlock
}

private fun isExecutionMessage(m: UiMessage): Boolean {
    if (m.isUser) return false
    if (m.parts.isEmpty()) return false
    return m.parts.all { it is UiPart.Tool || it is UiPart.Reasoning }
}

fun splitExecutionBlocks(messages: List<UiMessage>): List<TimelineBlock> {
    val out = mutableListOf<TimelineBlock>()
    var run = mutableListOf<UiMessage>()
    fun flush() {
        if (run.isNotEmpty()) {
            out.add(TimelineBlock.Execution(run.toList()))
            run = mutableListOf()
        }
    }
    for (m in messages) {
        if (isExecutionMessage(m)) {
            run.add(m)
        } else {
            flush()
            out.add(TimelineBlock.Single(m))
        }
    }
    flush()
    return out
}

/**
 * L1 execution card (web screenshot 1): header with tool/reasoning
 * counts + collapse, entries as L2 rows, each expanding its L3 content.
 */
@Composable
fun ExecutionCard(messages: List<UiMessage>, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(true) }
    var toolCount = 0
    var thinkCount = 0
    for (m in messages) {
        for (p in m.parts) {
            when (p) {
                is UiPart.Tool -> toolCount++
                is UiPart.Reasoning -> thinkCount++
                else -> Unit
            }
        }
    }
    Card(
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(onClick = { expanded = !expanded }, onLongClick = {})
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Icon(
                    if (expanded) AppIcons.ChevronDown else AppIcons.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    AppIcons.Run,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("执行过程", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.width(8.dp))
                if (toolCount > 0) {
                    Icon(
                        AppIcons.Wrench,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        "$toolCount",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                if (thinkCount > 0) {
                    Icon(
                        AppIcons.Brain,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        "$thinkCount",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column(
                    Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    for (m in messages) {
                        for (p in m.parts) {
                            when (p) {
                                is UiPart.Tool -> ExecutionToolRow(part = p)
                                is UiPart.Reasoning -> ExecutionThinkRow(text = p.text)
                                else -> Unit
                            }
                        }
                    }
                    TextButton(onClick = { expanded = false }) { Text("收起") }
                }
            }
        }
    }
}

/** L2 tool entry row: icon + name + chevron, expands L3 input/output. */
@Composable
private fun ExecutionToolRow(part: UiPart.Tool) {
    var open by remember(part.callId) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = { open = !open }, onLongClick = {})
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Icon(
                if (part.isError) AppIcons.ToolError else AppIcons.Wrench,
                contentDescription = null,
                tint = if (part.isError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.secondary
                },
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                part.toolName,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Icon(
                if (open) AppIcons.ChevronDown else AppIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
        if (open) {
            FullToolCard(part = part)
        }
    }
}

/** L2 thinking entry row: icon + summary + chevron, expands L3 text. */
@Composable
private fun ExecutionThinkRow(text: String) {
    var open by remember(text.hashCode()) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = { open = !open }, onLongClick = {})
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Icon(
                AppIcons.Brain,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "思考",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text.take(40),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(2f),
            )
            Icon(
                if (open) AppIcons.ChevronDown else AppIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
        if (open) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
            )
        }
    }
}

// M3 signature bubble shapes: user sharp bottom-end, assistant sharp
// bottom-start (chat-app convention, keeps text breathing room).
private val UserBubbleShape = RoundedCornerShape(
    topStart = 18.dp,
    topEnd = 18.dp,
    bottomStart = 18.dp,
    bottomEnd = 4.dp,
)
private val AssistantBubbleShape = RoundedCornerShape(
    topStart = 18.dp,
    topEnd = 18.dp,
    bottomStart = 4.dp,
    bottomEnd = 18.dp,
)

/** Item entrance: fade + rise (skill motion guidance, 48dp-free list). */
@Composable
private fun ItemEntrance(content: @Composable () -> Unit) {
    androidx.compose.animation.AnimatedVisibility(
        visible = true,
        enter = fadeIn() + slideInVertically(initialOffsetY = { it / 3 }),
    ) {
        content()
    }
}

@Composable
private fun MessageRow(message: UiMessage, onUserLongPress: () -> Unit) {
    if (message.isUser) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            var pressed by remember { mutableStateOf(false) }
            if (pressed) {
                LaunchedEffect(Unit) {
                    onUserLongPress()
                    pressed = false
                }
            }
            ItemEntrance {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    shape = UserBubbleShape,
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    modifier = Modifier
                        .widthIn(max = 320.dp)
                        .combinedClickable(
                            onClick = {},
                            onLongClick = { pressed = true },
                        ),
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        message.parts.forEach { part ->
                            when (part) {
                                is UiPart.Text -> Text(
                                    part.text,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                is UiPart.File -> Text(
                                    "[图片 ${part.mimeType}]",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                else -> Unit
                            }
                        }
                    }
                }
            }
        }
        return
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        message.parts.forEachIndexed { index, part ->
            when (part) {
                is UiPart.Text -> ItemEntrance {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor =
                                MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                        shape = AssistantBubbleShape,
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            part.text,
                            modifier = Modifier.padding(
                                horizontal = 14.dp,
                                vertical = 10.dp,
                            ),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                is UiPart.Reasoning -> ReasoningCard(text = part.text)
                is UiPart.Tool -> FullToolCard(part = part)
                is UiPart.File -> Unit
            }
        }
    }
}

/**
 * Web 1:1 empty state (screenshot 1): big logo + one-liner hint.
 * Copy mirrors the web: type below to auto-create, or pick from left.
 */
@Composable
private fun EmptyTimeline() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.size(88.dp),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    AppIcons.Robot,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(44.dp),
                )
            }
        }
        Text(
            "直接输入消息即可自动新建会话开始对话，或从左侧选择项目、现有会话。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ReasoningCard(text: String) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        tonalElevation = 1.dp,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.combinedClickable(
                    onClick = { expanded = !expanded },
                    onLongClick = {},
                ),
            ) {
                Icon(
                    AppIcons.Brain,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("思考过程", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.weight(1f))
                Text(if (expanded) "收起" else "展开", style = MaterialTheme.typography.labelSmall)
            }
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                Text(text, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}


