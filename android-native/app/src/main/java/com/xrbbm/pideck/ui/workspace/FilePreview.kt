package com.xrbbm.pideck.ui.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.xrbbm.pideck.core.net.ApiClient
import com.xrbbm.pideck.core.net.FileContentResult

/** File preview size cap (mirrors server 512KB bound; change here only). */
const val FILE_PREVIEW_LIMIT_KB = 512

fun isMarkdown(path: String): Boolean {
    val lower = path.lowercase()
    return lower.endsWith(".md") || lower.endsWith(".markdown") || lower.endsWith(".mdown")
}

/**
 * File/diff preview sheet (mirrors WebFilePreview).
 * - file: md renders Markdown, other text gets line numbers
 * - diff: group lookup (merge>index>workingTree>untracked) then two panes;
 *   falls back to file preview with a notice when missing
 * - oversized/missing/binary: notice only, no extra requests
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilePreviewSheet(
    api: ApiClient,
    projectId: String,
    path: String,
    line: Int? = null,
    isDiff: Boolean = false,
    onDismiss: () -> Unit,
) {
    var file by remember(path) { mutableStateOf<FileContentResult?>(null) }
    var failed by remember(path) { mutableStateOf(false) }
    var diffOriginal by remember(path) { mutableStateOf<String?>(null) }
    var diffModified by remember(path) { mutableStateOf<String?>(null) }
    var diffMissing by remember(path) { mutableStateOf(false) }
    var showOriginal by remember(path) { mutableStateOf(false) }

    suspend fun loadFile() {
        try {
            file = api.fetchFileContent(projectId, path)
        } catch (_: Exception) {
            failed = true
        }
    }

    suspend fun loadDiff() {
        try {
            val status = api.fetchGitStatus(projectId)
            val group = status.groups?.groupOf(path)
            if (group == null) {
                diffMissing = true
                loadFile()
                return
            }
            val diff = api.fetchGitDiff(projectId, group, path)
            if (diff == null) {
                diffMissing = true
                loadFile()
                return
            }
            diffOriginal = diff.original
            diffModified = diff.modified
        } catch (_: Exception) {
            diffMissing = true
            loadFile()
        }
    }

    LaunchedEffect(path, isDiff) {
        if (isDiff) loadDiff() else loadFile()
    }

    val isNew = diffOriginal != null && diffOriginal!!.isEmpty() &&
        !diffModified.isNullOrEmpty() && !diffMissing

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
        ) {
            Text(
                path.split('/', '\\').lastOrNull()?.takeIf { it.isNotEmpty() } ?: path,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Text(
                path,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(8.dp))
            when {
                failed -> Box(
                    Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("预览加载失败") }
                isDiff && !diffMissing && diffModified == null -> Box(
                    Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
                isDiff && !diffMissing -> {
                    Row(Modifier.padding(horizontal = 8.dp)) {
                        TextButton(
                            onClick = { showOriginal = true },
                            enabled = !isNew,
                        ) { Text("改前") }
                        TextButton(onClick = { showOriginal = false }) { Text("改后") }
                        if (isNew) {
                            Surface(
                                color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f),
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                                modifier = Modifier.padding(start = 4.dp),
                            ) {
                                Text(
                                    "新文件",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                    NumberedText(
                        content = if (showOriginal) diffOriginal ?: "" else diffModified ?: "",
                        modifier = Modifier.height(420.dp),
                    )
                }
                else -> FileBody(
                    file = file,
                    path = path,
                    line = line,
                    fallbackNotice = if (diffMissing) "找不到该改动，已回退为文件预览" else null,
                    modifier = Modifier.height(420.dp),
                )
            }
        }
    }
}

@Composable
private fun FileBody(
    file: FileContentResult?,
    path: String,
    line: Int?,
    fallbackNotice: String?,
    modifier: Modifier = Modifier,
) {
    if (file == null) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    if (file.tooLarge) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                "文件过大，无法预览（>${FILE_PREVIEW_LIMIT_KB}KB），请回桌面端查看",
                modifier = Modifier.padding(24.dp),
            )
        }
        return
    }
    if (file.binary) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text("二进制文件无法预览")
        }
        return
    }
    val content = file.content
    if (content == null) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text("预览加载失败")
        }
        return
    }
    Column(modifier.fillMaxWidth()) {
        if (fallbackNotice != null) {
            Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                Text(
                    fallbackNotice,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        Box(Modifier.weight(1f)) {
            if (isMarkdown(path)) {
                MarkdownText(content = content)
            } else {
                NumberedText(content = content, highlightLine = line)
            }
        }
        if (file.truncated) {
            Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "内容过长，仅显示前 ${FILE_PREVIEW_LIMIT_KB}KB，完整内容请回桌面端",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** Line-numbered text with optional highlight line. */
@Composable
private fun NumberedText(
    content: String,
    highlightLine: Int? = null,
    modifier: Modifier = Modifier,
) {
    val lines = remember(content) { content.split('\n') }
    LazyColumn(modifier = modifier.fillMaxWidth()) {
        items(lines.size) { i ->
            val n = i + 1
            val hit = highlightLine == n
            Surface(
                color = if (hit) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                } else {
                    MaterialTheme.colorScheme.surface
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 1.dp)) {
                    Text(
                        "$n",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(40.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        lines[i].ifEmpty { " " },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** Minimal Markdown renderer (commonmark → annotated plain text blocks). */
@Composable
private fun MarkdownText(content: String, modifier: Modifier = Modifier) {
    val blocks = remember(content) { renderMarkdown(content) }
    LazyColumn(modifier = modifier
        .fillMaxWidth()
        .padding(12.dp)) {
        items(blocks.size) { i ->
            val b = blocks[i]
            when (b) {
                is MdBlock.Heading -> Text(
                    b.text,
                    style = when (b.level) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        else -> MaterialTheme.typography.titleMedium
                    },
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                is MdBlock.Code -> Surface(
                    tonalElevation = 2.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                ) {
                    Text(
                        b.text,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(8.dp),
                    )
                }
                is MdBlock.Quote -> Text(
                    b.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp, top = 2.dp, bottom = 2.dp),
                )
                is MdBlock.Bullet -> Text(
                    "• ${b.text}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 8.dp, top = 1.dp, bottom = 1.dp),
                )
                is MdBlock.Body -> Text(
                    b.text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}

private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Code(val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Bullet(val text: String) : MdBlock
    data class Body(val text: String) : MdBlock
}

private fun renderMarkdown(content: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    var inCode = false
    val codeBuf = StringBuilder()
    for (raw in content.split('\n')) {
        val line = raw.trimEnd()
        if (line.trimStart().startsWith("```")) {
            if (inCode) {
                out.add(MdBlock.Code(codeBuf.toString().trimEnd()))
                codeBuf.clear()
                inCode = false
            } else {
                inCode = true
            }
            continue
        }
        if (inCode) {
            codeBuf.appendLine(raw)
            continue
        }
        val trimmed = line.trim()
        when {
            trimmed.startsWith("#") -> {
                val level = trimmed.takeWhile { it == '#' }.length.coerceIn(1, 6)
                out.add(MdBlock.Heading(level, trimmed.drop(level).trim()))
            }
            trimmed.startsWith(">") -> out.add(MdBlock.Quote(trimmed.drop(1).trim()))
            trimmed.startsWith("- ") || trimmed.startsWith("* ") ->
                out.add(MdBlock.Bullet(trimmed.drop(2).trim()))
            trimmed.matches(Regex("\\d+\\.\\s+.*")) ->
                out.add(MdBlock.Bullet(trimmed.substringAfter(" ").trim()))
            trimmed.isEmpty() -> Unit
            else -> out.add(MdBlock.Body(trimmed))
        }
    }
    if (inCode && codeBuf.isNotEmpty()) out.add(MdBlock.Code(codeBuf.toString().trimEnd()))
    return out
}
