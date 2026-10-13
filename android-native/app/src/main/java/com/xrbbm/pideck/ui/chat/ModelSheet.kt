package com.xrbbm.pideck.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xrbbm.pideck.core.net.ApiClient
import com.xrbbm.pideck.core.net.AvailableModel
import kotlinx.coroutines.launch

/** Thinking-level fallback (mirrors web WEB_THINKING_LEVELS). */
val THINKING_FALLBACK = listOf("off", "minimal", "low", "medium", "high", "xhigh", "max")

/**
 * Model sheet: tapping a row selects (stays open, expands level chips);
 * tapping a level returns model+level at once; bottom confirm returns
 * model-only (level reset). Disabled when no model changed.
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
    val scope = rememberCoroutineScope()
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
        Column(Modifier.padding(16.dp)) {
            Text("模型", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            val list = models
            if (list == null) {
                Text("加载中…")
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(list, key = { it.provider + "/" + it.id }) { m ->
                        val isSel = selected?.id == m.id &&
                            selected?.provider == m.provider
                        Row {
                            TextButton(
                                onClick = { selected = m },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(
                                    (if (isSel) "✓ " else "") + m.display +
                                        (m.contextLabel.takeIf { it.isNotEmpty() }
                                            ?.let { " · $it" } ?: ""),
                                    maxLines = 1,
                                )
                            }
                        }
                        if (isSel) {
                            val levels = m.levels.takeIf { it.isNotEmpty() }
                                ?: THINKING_FALLBACK
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                levels.forEach { level ->
                                    FilterChip(
                                        selected = currentLevel == level,
                                        onClick = { onPick(m, level) },
                                        label = { Text(level) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
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

/** Standalone thinking-level picker (toolbar chip). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ThinkingSheet(
    levels: List<String>,
    current: String?,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(16.dp)) {
            Text("思考档位", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                levels.forEach { level ->
                    FilterChip(
                        selected = current == level,
                        onClick = { onPick(level) },
                        label = { Text(level) },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
