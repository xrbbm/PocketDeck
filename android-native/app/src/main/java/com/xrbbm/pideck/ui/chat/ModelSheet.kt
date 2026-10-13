package com.xrbbm.pideck.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xrbbm.pideck.core.net.ApiClient
import com.xrbbm.pideck.core.net.AvailableModel

/** Thinking-level fallback (mirrors web WEB_THINKING_LEVELS). */
val THINKING_FALLBACK = listOf("off", "minimal", "low", "medium", "high", "xhigh", "max")

/**
 * Model sheet (M3): Card list rows with leading monogram + context chip,
 * selected row tonal + check; level FilterChips expand inline; bottom
 * confirm returns model-only. Disabled when no model changed.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ModelSheet(
    api: ApiClient,
    current: AvailableModel?,
    currentLevel: String?,
    onDismiss: () -> Unit,
    onPick: (AvailableModel, String?) -> Unit,
) {
    var models by remember { mutableStateOf<List<AvailableModel>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(current) }

    LaunchedEffect(Unit) {
        try {
            models = api.fetchModels()
        } catch (e: Exception) {
            error = (e as? com.xrbbm.pideck.core.net.ApiException)?.message ?: "$e"
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "模型",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
                models?.let { AssistChip(onClick = {}, label = { Text("${it.size}") }) }
            }
            Spacer(Modifier.height(8.dp))
            error?.let {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            val list = models
            if (list == null) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(list, key = { it.provider + "/" + it.id }) { m ->
                        val isSel = selected?.id == m.id &&
                            selected?.provider == m.provider
                        Card(
                            onClick = { selected = m },
                            shape = RoundedCornerShape(16.dp),
                            colors = if (isSel) {
                                CardDefaults.cardColors(
                                    containerColor =
                                        MaterialTheme.colorScheme.primaryContainer,
                                    contentColor =
                                        MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            } else {
                                CardDefaults.cardColors(
                                    containerColor =
                                        MaterialTheme.colorScheme.surfaceContainerLow,
                                )
                            },
                            elevation = CardDefaults.cardElevation(
                                defaultElevation = if (isSel) 2.dp else 0.dp,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = if (isSel) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    },
                                    modifier = Modifier.size(40.dp),
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(
                                            m.display.take(1).uppercase(),
                                            style = MaterialTheme.typography.titleMedium,
                                            color = if (isSel) {
                                                MaterialTheme.colorScheme.onPrimary
                                            } else {
                                                MaterialTheme.colorScheme
                                                    .onSecondaryContainer
                                            },
                                        )
                                    }
                                }
                                Spacer(Modifier.padding(start = 12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        m.display,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    Row(
                                        horizontalArrangement =
                                            Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            m.provider,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        m.contextLabel.takeIf { it.isNotEmpty() }?.let {
                                            AssistChip(
                                                onClick = {},
                                                label = { Text(it) },
                                            )
                                        }
                                    }
                                }
                                if (isSel) Text("✓", style = MaterialTheme.typography.titleMedium)
                            }
                        }
                        if (isSel) {
                            val levels = m.levels.takeIf { it.isNotEmpty() }
                                ?: THINKING_FALLBACK
                            FlowRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                levels.forEach { level ->
                                    FilterChip(
                                        selected = currentLevel == level,
                                        onClick = { onPick(m, level) },
                                        label = { Text(level) },
                                        leadingIcon = if (currentLevel == level) {
                                            { Text("✓") }
                                        } else {
                                            null
                                        },
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) { Text("取消") }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        val m = selected
                        if (m != null) onPick(m, null)
                    },
                    enabled = selected != null &&
                        (selected?.id != current?.id ||
                            selected?.provider != current?.provider),
                ) { Text("确定") }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Standalone thinking-level picker (M3 FilterChips with check lead). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ThinkingSheet(
    levels: List<String>,
    current: String?,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text("思考档位", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "只切档位，模型保持当前",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                levels.forEach { level ->
                    FilterChip(
                        selected = current == level,
                        onClick = { onPick(level) },
                        label = { Text(level) },
                        leadingIcon = if (current == level) {
                            { Text("✓") }
                        } else {
                            null
                        },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
