package com.xrbbm.pideck.ui.chat

import com.xrbbm.pideck.core.net.UiMessage
import com.xrbbm.pideck.core.net.UiPart
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionMarkdownTest {

    @Test
    fun `speakers and rule separate messages`() {
        val md = sessionToMarkdown(
            listOf(
                UiMessage("a", "user", listOf(UiPart.Text("你好"))),
                UiMessage("b", "assistant", listOf(UiPart.Text("在的"))),
            ),
        )
        assertEquals(
            "### 🧑 **User**\n\n你好\n\n---\n\n### 🤖 **Assistant**\n\n在的",
            md,
        )
    }

    @Test
    fun `reasoning becomes a collapsed details block`() {
        val md = sessionToMarkdown(
            listOf(
                UiMessage(
                    "a",
                    "assistant",
                    listOf(UiPart.Reasoning("想一下"), UiPart.Text("结论")),
                ),
            ),
        )
        assertTrue(md.contains("<summary>Thinking</summary>"))
        assertTrue(md.contains("想一下"))
        // Order preserved: thinking before the answer.
        assertTrue(md.indexOf("Thinking") < md.indexOf("结论"))
    }

    @Test
    fun `tool call carries name input and output`() {
        val md = sessionToMarkdown(
            listOf(
                UiMessage(
                    "a",
                    "assistant",
                    listOf(
                        UiPart.Tool(
                            toolName = "bash",
                            callId = "c1",
                            isError = false,
                            input = JsonPrimitive("ls -la"),
                            output = JsonPrimitive("ok"),
                        ),
                    ),
                ),
            ),
        )
        assertTrue(md.contains("<summary>Tool: bash</summary>"))
        assertTrue(md.contains("Input:"))
        assertTrue(md.contains("ls -la"))
        assertTrue(md.contains("Output:"))
        assertFalse(md.contains("(error)"))
    }

    @Test
    fun `tool error is flagged and wins over output`() {
        val md = sessionToMarkdown(
            listOf(
                UiMessage(
                    "a",
                    "assistant",
                    listOf(
                        UiPart.Tool(
                            toolName = "bash",
                            callId = "c1",
                            isError = true,
                            output = JsonPrimitive("ignored"),
                            errorText = "boom",
                        ),
                    ),
                ),
            ),
        )
        assertTrue(md.contains("Tool: bash (error)"))
        assertTrue(md.contains("Error: boom"))
        assertFalse(md.contains("ignored"))
    }

    @Test
    fun `empty parts are skipped entirely`() {
        val md = sessionToMarkdown(
            listOf(
                UiMessage("a", "assistant", listOf(UiPart.Text("   "))),
                UiMessage("b", "user", listOf(UiPart.Text("hi"))),
            ),
        )
        assertEquals("### 🧑 **User**\n\nhi", md)
    }

    @Test
    fun `image part is truncated to a short link`() {
        val url = "data:image/png;base64," + "A".repeat(200)
        val md = sessionToMarkdown(
            listOf(
                UiMessage(
                    "a",
                    "user",
                    listOf(UiPart.File("image/png", url), UiPart.Text("看图")),
                ),
            ),
        )
        assertTrue(md.contains("![image/png](data:image/png;base64,"))
        assertFalse(md.contains("A".repeat(80)))
    }
}
