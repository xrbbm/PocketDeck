package com.xrbbm.pideck.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * Shared minimal Markdown body (chat text + file preview).
 * Blocks: heading / code / quote / bullet / table / body.
 * Tables render as header + rows; malformed tables fall back to body.
 * Inline spans: **bold**, *italic* / _italic_, ~~del~~, `code` (monospace + chip bg).
 */
@Composable
fun MarkdownBody(content: String, modifier: Modifier = Modifier) {
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHighest
    val blocks = remember(content) { renderMarkdownBlocks(content) }
    Column(modifier = modifier.fillMaxWidth()) {
        for (b in blocks) {
            when (b) {
                is MdBlock.Heading -> Text(
                    inlineStyled(b.text, codeBg),
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
                    inlineStyled(b.text, codeBg),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp, top = 2.dp, bottom = 2.dp),
                )
                is MdBlock.Bullet -> Text(
                    inlineStyled("• " + b.text, codeBg),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 8.dp, top = 1.dp, bottom = 1.dp),
                )
                is MdBlock.Table -> MarkdownTable(block = b)
                is MdBlock.Body -> Text(
                    inlineStyled(b.text, codeBg),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun MarkdownTable(block: MdBlock.Table) {
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHighest
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            for ((i, cell) in block.header.withIndex()) {
                Text(
                    inlineStyled(cell, codeBg),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = if (i < block.header.lastIndex) 8.dp else 0.dp),
                )
            }
        }
        for (row in block.rows) {
            Row(Modifier.fillMaxWidth()) {
                for ((i, cell) in row.withIndex()) {
                    Text(
                        inlineStyled(cell, codeBg),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .weight(1f)
                            .padding(
                                top = 2.dp,
                                end = if (i < row.lastIndex) 8.dp else 0.dp,
                            ),
                    )
                }
            }
        }
    }
}

sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Code(val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Bullet(val text: String) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
    data class Body(val text: String) : MdBlock
}

/** Inline spans (`**bold**`, `*italic*`/`_italic_`, `~~del~~`, `` `code` ``) → styled text. */
/** Unclosed markers stay literal; single `*`/`_` only open at a word boundary. */
fun inlineStyled(text: String, codeBg: Color): AnnotatedString = buildAnnotatedString {
    val bold = SpanStyle(fontWeight = FontWeight.Bold)
    val italic = SpanStyle(fontStyle = FontStyle.Italic)
    val del = SpanStyle(textDecoration = TextDecoration.LineThrough)
    val code = SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)
    var i = 0
    while (i < text.length) {
        if (text[i] == '`') {
            val end = text.indexOf('`', i + 1)
            if (end > i + 1) {
                withStyle(code) { append(text.substring(i + 1, end)) }
                i = end + 1
                continue
            }
            append("`")
            i++
            continue
        }
        if (text.startsWith("**", i)) {
            val end = text.indexOf("**", i + 2)
            if (end > i + 2) {
                withStyle(bold) { append(text.substring(i + 2, end)) }
                i = end + 2
                continue
            }
            append("**")
            i += 2
            continue
        }
        if (text.startsWith("~~", i)) {
            val end = text.indexOf("~~", i + 2)
            if (end > i + 2) {
                withStyle(del) { append(text.substring(i + 2, end)) }
                i = end + 2
                continue
            }
            append("~~")
            i += 2
            continue
        }
        val c = text[i]
        if ((c == '*' || c == '_') && isInlineOpener(text, i)) {
            val end = findInlineCloser(text, i)
            if (end > i + 1) {
                withStyle(italic) { append(text.substring(i + 1, end)) }
                i = end + 1
                continue
            }
        }
        append(text[i].toString())
        i++
    }
}

private fun isInlineOpener(text: String, i: Int): Boolean {
    val prev = if (i == 0) null else text[i - 1]
    return prev == null || prev.isWhitespace() || prev in "([{<'\"“‘（：:;*-~`|•"
}

private fun findInlineCloser(text: String, i: Int): Int {
    val c = text[i]
    var j = text.indexOf(c, i + 1)
    while (j > 0) {
        val next = if (j + 1 >= text.length) null else text[j + 1]
        if (next == null || next.isWhitespace() || next in ")]}>'\"”’，。、！？；：,.!?:;*~`|•") return j
        j = text.indexOf(c, j + 1)
    }
    return -1
}

/** Split a `| a | b |` row into cells (trims outer pipes). */
fun splitTableRow(line: String): List<String> {
    var s = line.trim()
    if (s.startsWith("|")) s = s.drop(1)
    if (s.endsWith("|")) s = s.dropLast(1)
    return s.split("|").map { it.trim() }
}

/** A `| --- | --- |` separator row: every cell is dashes/colons only. */
fun isTableSeparator(line: String): Boolean {
    val cells = splitTableRow(line)
    if (cells.isEmpty()) return false
    return cells.all { it.matches(Regex(":?-{2,}:?")) }
}

private fun isTableRow(line: String): Boolean {
    val t = line.trim()
    return t.contains("|") && t.startsWith("|")
}

fun renderMarkdownBlocks(content: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    var inCode = false
    val codeBuf = StringBuilder()
    val lines = content.split('\n')
    var i = 0
    while (i < lines.size) {
        val raw = lines[i]
        val line = raw.trimEnd()
        if (line.trimStart().startsWith("```")) {
            if (inCode) {
                out.add(MdBlock.Code(codeBuf.toString().trimEnd()))
                codeBuf.clear()
                inCode = false
            } else {
                inCode = true
            }
            i++
            continue
        }
        if (inCode) {
            codeBuf.appendLine(raw)
            i++
            continue
        }
        val trimmed = line.trim()
        // GFM table: header row + separator row + body rows.
        if (isTableRow(trimmed) && i + 1 < lines.size &&
            isTableSeparator(lines[i + 1])
        ) {
            val header = splitTableRow(trimmed)
            val rows = mutableListOf<List<String>>()
            var j = i + 2
            while (j < lines.size && isTableRow(lines[j].trim())) {
                rows.add(splitTableRow(lines[j].trim()))
                j++
            }
            out.add(MdBlock.Table(header, rows))
            i = j
            continue
        }
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
        i++
    }
    if (inCode && codeBuf.isNotEmpty()) out.add(MdBlock.Code(codeBuf.toString().trimEnd()))
    return out
}
