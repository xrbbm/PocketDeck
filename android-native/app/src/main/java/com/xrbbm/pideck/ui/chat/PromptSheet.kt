package com.xrbbm.pideck.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xrbbm.pideck.core.net.ApiClient
import com.xrbbm.pideck.core.net.PromptList
import com.xrbbm.pideck.ui.theme.AppIcons
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Prompt library sheet (mirrors Flutter _PromptSheet): 250ms debounce
 * search + categories, tapping an entry inserts its body into input.
 * Only the last tap's response is honored (insert generation guard).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromptSheet(
    api: ApiClient,
    onDismiss: () -> Unit,
    onInsert: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<String?>(null) }
    var list by remember { mutableStateOf<PromptList?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var inserting by remember { mutableStateOf<String?>(null) }
    var insertSeq by remember { mutableIntStateOf(0) }
    var searchJob by remember { mutableStateOf<Job?>(null) }

    suspend fun load() {
        loading = true
        error = null
        try {
            list = api.fetchPrompts(
                search = query.trim().takeIf { it.isNotEmpty() },
                category = category,
            )
        } catch (e: Exception) {
            error = (e as? com.xrbbm.pideck.core.net.ApiException)?.message ?: "$e"
        } finally {
            loading = false
        }
    }

    fun onSearchChanged(v: String) {
        query = v
        searchJob?.cancel()
        searchJob = scope.launch {
            delay(250)
            load()
        }
    }

    suspend fun insert(slug: String, cat: String) {
        val stamp = insertSeq++
        inserting = slug
        try {
            val content = api.fetchPromptContent(slug, cat)
            if (stamp != insertSeq - 1) return
            onInsert(content)
        } catch (e: Exception) {
            error = (e as? com.xrbbm.pideck.core.net.ApiException)?.message ?: "$e"
            inserting = null
        }
    }

    LaunchedEffect(Unit) { load() }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text("提示词库", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            SearchBar(
                inputField = {
                    SearchBarDefaults.InputField(
                        query = query,
                        onQueryChange = ::onSearchChanged,
                        onSearch = {},
                        expanded = false,
                        onExpandedChange = {},
                        placeholder = { Text("搜索提示词") },
                        leadingIcon = {
                            Icon(
                                AppIcons.Search,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                    )
                },
                expanded = false,
                onExpandedChange = {},
                modifier = Modifier.fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
                content = {},
            )
            Spacer(Modifier.height(8.dp))
            list?.let { l ->
                if (l.categories.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        item(key = "all") {
                            FilterChip(
                                selected = category == null,
                                onClick = {
                                    category = null
                                    scope.launch { load() }
                                },
                                label = { Text("全部") },
                            )
                        }
                        items(l.categories, key = { it.slug }) { c ->
                            FilterChip(
                                selected = category == c.slug,
                                onClick = {
                                    category = c.slug
                                    scope.launch { load() }
                                },
                                label = { Text(c.name) },
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
            val prompts = list?.prompts ?: emptyList()
            when {
                loading && list == null -> Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator() }
                error != null && list == null -> Text(
                    error!!,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(24.dp),
                )
                else -> LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                ) {
                    items(prompts, key = { it.slug }) { item ->
                        val busy = inserting == item.slug
                        TextButton(
                            onClick = {
                                if (!busy) scope.launch { insert(item.slug, item.category) }
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    item.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                if (item.description.isNotEmpty()) {
                                    Text(
                                        item.description,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                            if (busy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                        }
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
