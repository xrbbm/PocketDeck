package com.xrbbm.pideck.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xrbbm.pideck.core.net.UiPart
import com.xrbbm.pideck.core.net.prettyToolValue
import com.xrbbm.pideck.core.net.toolSummary

/**
 * Tool call card (mirrors WebToolCard): icon + name + status, expandable
 * input/output. Disabled from expanding when there is no body.
 */
@Composable
fun FullToolCard(part: UiPart.Tool, modifier: Modifier = Modifier) {
    val errorText = prettyToolValue(
        part.errorText?.let {
            kotlinx.serialization.json.JsonPrimitive(it)
        },
    )
    val inputText = prettyToolValue(part.input)
    val outputText = prettyToolValue(part.output)
    val hasBody = errorText.isNotEmpty() || inputText.isNotEmpty() || outputText.isNotEmpty()
    val summary = toolSummary(part)
    var expanded by remember { mutableStateOf(false) }

    // M3 hierarchy: ElevatedCard + status AssistChip + tonal content blocks.
    Card(
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.elevatedCardColors(),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        enabled = hasBody,
                        onClick = { expanded = !expanded },
                        onLongClick = {},
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                if (part.streaming) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Surface(
                        shape = androidx.compose.foundation.shape.CircleShape,
                        color = if (part.isError) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        },
                        modifier = Modifier.size(32.dp),
                    ) {
                        androidx.compose.foundation.layout.Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (part.isError) "❌" else "🔧")
                        }
                    }
                }
                Spacer(Modifier.padding(start = 10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            part.toolName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        if (part.streaming) {
                            AssistChip(
                                onClick = {},
                                label = { Text("运行中") },
                            )
                        } else if (part.isError) {
                            AssistChip(
                                onClick = {},
                                label = { Text("执行失败") },
                            )
                        }
                    }
                    if (summary.isNotEmpty()) {
                        Text(
                            summary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            AnimatedVisibility(
                visible = expanded && hasBody,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column(Modifier.padding(bottom = 8.dp)) {
                    if (errorText.isNotEmpty()) {
                        ValueBlock(label = "错误", text = errorText, isError = true)
                    }
                    if (inputText.isNotEmpty()) ValueBlock(label = "输入", text = inputText)
                    if (outputText.isNotEmpty()) ValueBlock(label = "输出", text = outputText)
                }
            }
        }
    }
}

/** Grouped same-name tool calls (e.g. read × 4). */
@Composable
fun FullToolGroupCard(
    toolName: String,
    parts: List<UiPart.Tool>,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.elevatedCardColors(),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(onClick = { expanded = !expanded }, onLongClick = {})
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text("🔧")
                Spacer(Modifier.padding(start = 8.dp))
                Text(
                    "$toolName × ${parts.size}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (expanded) "收起" else "展开",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column(
                    Modifier.padding(horizontal = 8.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                ) {
                    parts.forEach { FullToolCard(part = it) }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

/** Monospace value block: FilledTonal surface + label chip. */
@Composable
private fun ValueBlock(label: String, text: String, isError: Boolean = false) {
    val color = if (isError) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
        Spacer(Modifier.height(2.dp))
        Surface(
            tonalElevation = 2.dp,
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text,
                modifier = Modifier.padding(8.dp),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = color,
            )
        }
    }
}
