package com.xrbbm.pideck.ui.chat

import com.xrbbm.pideck.core.net.UiMessage
import com.xrbbm.pideck.core.net.UiPart
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTimelineTest {
    private fun tool(name: String = "bash") = UiPart.Tool(
        toolName = name,
        callId = "c1",
        isError = false,
        input = JsonPrimitive("ls"),
        output = null,
        errorText = null,
        streaming = false,
    )

    @Test
    fun `mixed thinking plus text splits into execution and single`() {
        val mixed = UiMessage(
            "m1",
            "assistant",
            listOf(UiPart.Reasoning("想一下"), UiPart.Text("结论")),
        )
        val blocks = splitExecutionBlocks(listOf(mixed))
        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is TimelineBlock.Execution)
        assertTrue(blocks[1] is TimelineBlock.Single)
        val exec = blocks[0] as TimelineBlock.Execution
        assertEquals(listOf("m1#0"), exec.messages.map { it.id })
        val single = blocks[1] as TimelineBlock.Single
        assertEquals("m1#1", single.message.id)
    }

    @Test
    fun `mixed text tool text keeps tool inside execution`() {
        val mixed = UiMessage(
            "m2",
            "assistant",
            listOf(UiPart.Text("前"), tool(), UiPart.Text("后")),
        )
        val blocks = splitExecutionBlocks(listOf(mixed))
        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is TimelineBlock.Single)
        assertTrue(blocks[1] is TimelineBlock.Execution)
        assertTrue(blocks[2] is TimelineBlock.Single)
        val exec = blocks[1] as TimelineBlock.Execution
        assertEquals(listOf("m2#1"), exec.messages.map { it.id })
    }

    @Test
    fun `pure execution run still folds`() {
        val a = UiMessage("a", "assistant", listOf(UiPart.Reasoning("r")))
        val b = UiMessage("b", "assistant", listOf(tool()))
        val blocks = splitExecutionBlocks(listOf(a, b))
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is TimelineBlock.Execution)
    }

    @Test
    fun `tail fingerprint catches in-place streaming growth`() {
        assertEquals(0, tailFingerprint(null))
        val before = UiMessage("s", "assistant", listOf(UiPart.Text("hi")))
        val after = UiMessage("s", "assistant", listOf(UiPart.Text("hi there, longer")))
        // Same id, same count: old size-only key would NOT fire.
        assertEquals(1, listOf(before).size)
        assertEquals(1, listOf(after).size)
        assertTrue(tailFingerprint(after) != tailFingerprint(before))
    }

    @Test
    fun `tail fingerprint catches new part arrival`() {
        val one = UiMessage("s", "assistant", listOf(UiPart.Text("hi")))
        val two = UiMessage(
            "s",
            "assistant",
            listOf(UiPart.Text("hi"), UiPart.Reasoning("thinking...")),
        )
        assertTrue(tailFingerprint(two) != tailFingerprint(one))
    }

    @Test
    fun `jump finds the block holding a normalized part`() {
        val mixed = UiMessage(
            "m1",
            "assistant",
            listOf(UiPart.Reasoning("想一下"), UiPart.Text("结论")),
        )
        val blocks = splitExecutionBlocks(listOf(mixed))
        // Normalization suffixes ids (#index); a raw search-hit id must
        // still resolve to the block that holds it.
        assertEquals(0, findJumpBlock(blocks, "m1"))
        assertEquals(-1, findJumpBlock(blocks, "missing"))
    }

    @Test
    fun `jump matches the server history key, not the whole id`() {
        // Server page ids are `{sessionId}-history-{8hex}`; /search hits only
        // carry `{8hex}`.
        val sid = "562e830a-c902-4c4a-86db-23b4d820d031"
        val msg = UiMessage("$sid-history-59670a0c", "user", listOf(UiPart.Text("hi")))
        val blocks = splitExecutionBlocks(listOf(msg))
        assertEquals(0, findJumpBlock(blocks, "59670a0c"))
        assertEquals("59670a0c", historyKey("$sid-history-59670a0c#0"))
        assertEquals("m1", historyKey("m1"))
    }

    @Test
    fun `execution card key does not shift when history prepends`() {
        // Paging up prepends older messages, which shifts every block index.
        // The LazyColumn key must stay identity-only or each card is
        // recreated and its collapse state is lost.
        val card = listOf(
            UiMessage("s-history-bbbbbbbb", "assistant", listOf(tool("read"))),
            UiMessage("s-history-cccccccc", "assistant", listOf(tool("bash"))),
        )
        val before = splitExecutionBlocks(card).first() as TimelineBlock.Execution
        val prepended = listOf(
            UiMessage("s-history-aaaaaaaa", "assistant", listOf(tool("grep"))),
            UiMessage("s-history-99999999", "assistant", listOf(UiPart.Text("更早的回复"))),
        ) + card
        val after = splitExecutionBlocks(prepended).last() as TimelineBlock.Execution
        assertEquals(executionBlockKey(before.messages), executionBlockKey(after.messages))
        assertEquals("exec-s-history-bbbbbbbb", executionBlockKey(after.messages))
    }
}
