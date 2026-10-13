package com.xrbbm.pideck.core.net

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatStreamTest {
    private fun obj(json: String): JsonObject =
        LenientJson.parseToJsonElement(json) as JsonObject

    @Test
    fun `splitter reassembles torn frames`() {
        val splitter = SseSplitter()
        val a = splitter.push("data: {\"type\":\"text-delta\",\"delta\":\"hel")
        assertTrue(a.frames.isEmpty())
        val b = splitter.push("lo\"}\n\n")
        assertEquals(1, b.frames.size)
        assertEquals("hello", b.frames[0].strOrNull("delta"))
    }

    @Test
    fun `splitter stops at DONE`() {
        val splitter = SseSplitter()
        val split = splitter.push("data: {\"type\":\"finish\"}\n\n[ DONE ]\n\n".replace(" ", ""))
        // "[DONE]" without space
        assertTrue(split.done)
    }

    @Test
    fun `draft interleaves text tool text`() {
        val draft = StreamDraft()
        draft.applyFrame(obj("""{"type":"text-start"}"""))
        draft.applyFrame(obj("""{"type":"text-delta","delta":"first"}"""))
        draft.applyFrame(obj("""{"type":"tool-input-start","toolCallId":"c1","toolName":"bash"}"""))
        draft.applyFrame(obj("""{"type":"tool-input-available","toolCallId":"c1","input":{"command":"ls"}}"""))
        draft.applyFrame(obj("""{"type":"tool-output-available","toolCallId":"c1","output":"ok"}"""))
        draft.applyFrame(obj("""{"type":"text-delta","delta":"second"}"""))
        val msg = draft.toUiMessage("s1")
        assertEquals(3, msg.parts.size)
        assertTrue(msg.parts[0] is UiPart.Text)
        assertTrue(msg.parts[1] is UiPart.Tool)
        assertTrue(msg.parts[2] is UiPart.Text)
        assertEquals("first", (msg.parts[0] as UiPart.Text).text)
        assertEquals("second", (msg.parts[2] as UiPart.Text).text)
    }

    @Test
    fun `draft drops orphan output`() {
        val draft = StreamDraft()
        draft.applyFrame(obj("""{"type":"tool-output-available","toolCallId":"ghost","output":"x"}"""))
        assertTrue(draft.isEmpty)
    }

    @Test
    fun `same callId rename wins`() {
        val draft = StreamDraft()
        draft.applyFrame(obj("""{"type":"tool-input-start","toolCallId":"c1","toolName":"old"}"""))
        draft.applyFrame(obj("""{"type":"tool-input-start","toolCallId":"c1","toolName":"new"}"""))
        val msg = draft.toUiMessage("s1")
        assertEquals("new", (msg.parts[0] as UiPart.Tool).toolName)
    }

    @Test
    fun `thinking tags stripped from streamed text`() {
        val draft = StreamDraft()
        draft.applyFrame(obj("""{"type":"text-delta","delta":"hi <thinking>secret</thinking> there"}"""))
        val msg = draft.toUiMessage("s1")
        assertEquals(1, msg.parts.size)
        assertEquals("hi  there", (msg.parts[0] as UiPart.Text).text)
    }

    @Test
    fun `map history tool message`() {
        val msg = mapChatMessage(
            obj("""{"id":"m1","role":"tool","text":"✗ boom","meta":{"toolName":"bash","isError":true}}"""),
        )
        assertEquals("assistant", msg.role)
        val tool = msg.parts[0] as UiPart.Tool
        assertEquals("bash", tool.toolName)
        assertTrue(tool.isError)
    }

    @Test
    fun `map user images capped at 4MB skipped`() {
        val big = "a".repeat(4 * 1024 * 1024 + 1)
        val msg = mapChatMessage(
            obj(
                """{"id":"u1","role":"user","text":"hi","images":[{"type":"image","mimeType":"image/png","data":"${
                    "x".repeat(10)
                }"},{"type":"image","mimeType":"image/png","data":"$big"}]}""",
            ),
        )
        assertEquals(2, msg.parts.size) // text + 1 file
        assertTrue(msg.parts[1] is UiPart.File)
    }

    @Test
    fun `branch family root upward children downward`() {
        val now = 100
        val sessions = listOf(
            WebSession(id = "root", projectId = "p", title = "r", status = "", createdAt = now),
            WebSession(
                id = "child",
                projectId = "p",
                title = "c",
                status = "",
                createdAt = now + 1,
                parentSessionId = "root",
            ),
            WebSession(id = "other", projectId = "p", title = "o", status = "", createdAt = now + 2),
        )
        val family = deriveBranchFamily(sessions, "child")
        assertEquals(listOf("root", "child"), family.map { it.id })
    }

    @Test
    fun `branch family lone session`() {
        val sessions = listOf(
            WebSession(id = "solo", projectId = "p", title = "s", status = ""),
        )
        assertEquals(1, deriveBranchFamily(sessions, "solo").size)
    }

    @Test
    fun `group sessions chat pinned`() {
        val sessions = listOf(
            WebSession(id = "a", projectId = "p1", title = "a", status = "", updatedAt = 5),
            WebSession(id = "c", projectId = "builtin-chat", title = "c", status = "", updatedAt = 1),
        )
        val groups = groupSessions(sessions, mapOf("p1" to "P1"))
        assertEquals("builtin-chat", groups[0].id)
        assertTrue(groups[0].isChat)
        assertFalse(groups[1].isChat)
    }

    @Test
    fun `groupOf priority merge first`() {
        val groups = GitResourceGroups(
            merge = listOf(GitResource("a.txt", "M")),
            workingTree = listOf(GitResource("a.txt", "M")),
        )
        assertEquals("merge", groups.groupOf("a.txt"))
        assertNull(groups.groupOf("missing.txt"))
    }
}
