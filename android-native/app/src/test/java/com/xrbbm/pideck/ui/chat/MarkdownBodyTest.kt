package com.xrbbm.pideck.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownBodyTest {
    @Test
    fun `table splits header and rows`() {
        val md = "| 技能 | 用途 |\n| --- | --- |\n| `bat-ascii` | 写 .bat |\n| `image-gen` | 生成图片 |"
        val blocks = renderMarkdownBlocks(md)
        assertEquals(1, blocks.size)
        val t = blocks[0] as MdBlock.Table
        assertEquals(listOf("技能", "用途"), t.header)
        assertEquals(2, t.rows.size)
        assertEquals(listOf("`bat-ascii`", "写 .bat"), t.rows[0])
    }

    @Test
    fun `table without separator falls back to body`() {
        val md = "| a | b |\n| c | d |"
        val blocks = renderMarkdownBlocks(md)
        assertTrue(blocks.all { it is MdBlock.Body })
    }

    @Test
    fun `bold renders without markers`() {
        val a = inlineStyled("**通用开发能力**", Color.White)
        assertEquals("通用开发能力", a.text)
        assertEquals(
            FontWeight.Bold,
            a.spanStyles.first().item.fontWeight,
        )
    }

    @Test
    fun `code renders without backticks`() {
        val a = inlineStyled("用 `bat-ascii` 写", Color.White)
        assertEquals("用 bat-ascii 写", a.text)
        val code = a.spanStyles.first { it.item.fontFamily == FontFamily.Monospace }
        assertEquals("bat-ascii", a.text.substring(code.start, code.end))
    }

    @Test
    fun `mixed bold and code keep literal guards`() {
        val raw = "**通用** + `按需` skills**。"
        val a = inlineStyled(raw, Color.White)
        assertEquals("通用 + 按需 skills**。", a.text)
    }

    @Test
    fun `del and italic render`() {
        val del = inlineStyled("~~删掉~~", Color.White)
        assertEquals("删掉", del.text)
        assertEquals(
            TextDecoration.LineThrough,
            del.spanStyles.first().item.textDecoration,
        )
        val it = inlineStyled("*斜体*", Color.White)
        assertEquals("斜体", it.text)
        assertEquals(FontStyle.Italic, it.spanStyles.first().item.fontStyle)
    }

    @Test
    fun `heading bullet quote still parse`() {
        val blocks = renderMarkdownBlocks("# 标题\n- 列表\n> 引用\n正文")
        assertEquals(4, blocks.size)
        assertTrue(blocks[0] is MdBlock.Heading)
        assertTrue(blocks[1] is MdBlock.Bullet)
        assertTrue(blocks[2] is MdBlock.Quote)
        assertTrue(blocks[3] is MdBlock.Body)
    }

    @Test
    fun `plain text keeps asterisks`() {
        assertEquals("5 * 3 = 15", inlineStyled("5 * 3 = 15", Color.White).text)
        assertEquals(
            "C:\\path\\*file",
            inlineStyled("C:\\path\\*file", Color.White).text,
        )
    }
}
