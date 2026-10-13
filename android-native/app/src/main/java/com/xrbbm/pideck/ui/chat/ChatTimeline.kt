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
import com.xrbbm.pideck.core.net.groupToolMessages
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
        val groups = remember(messages) { groupToolMessages(messages) }
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
            groups.forEachIndexed { gi, group ->
                if (group.size > 1) {
                    val tools = group.mapNotNull {
                        it.parts.firstOrNull() as? UiPart.Tool
                    }
                    val name = (tools.firstOrNull()?.toolName) ?: "tool"
                    item(key = "group-$gi-${group.first().id}") {
                        FullToolGroupCard(toolName = name, parts = tools)
                    }
                } else {
                    val m = group.first()
                    item(key = m.id) {
                        MessageRow(
                            message = m,
                            onUserLongPress = { onUserMessageLongPress(m) },
                        )
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
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
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

/** Illustrated empty state (skill: never leave a bare hint line). */
@Composable
private fun EmptyTimeline() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(72.dp),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Text("💬", style = MaterialTheme.typography.headlineMedium)
            }
        }
        Text("开始新的对话", style = MaterialTheme.typography.titleMedium)
        Text(
            "发一句开始聊，思考与工具调用会一段段冒出来",
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
                Text("💭 思考过程", style = MaterialTheme.typography.labelMedium)
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


