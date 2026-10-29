package com.xrbbm.pideck.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatPlanTest {
    @Test
    fun `plan message format matches the web`() {
        val msg = planAgentMessage(true, "看看这段代码")
        assertEquals(
            "__PI_DECK_PLAN_MODE__\n看看这段代码\n\n请先只做只读分析，不要修改文件。" +
                "最后必须输出以 `Plan:` 开头的编号计划，格式如下：\nPlan:\n1. 第一步\n2. 第二步",
            msg,
        )
    }

    @Test
    fun `plan mode off returns null`() {
        assertNull(planAgentMessage(false, "hi"))
    }

    @Test
    fun `slash command bypasses plan marker`() {
        assertNull(planAgentMessage(true, "/compact"))
    }

    @Test
    fun `empty text uses default plan prompt`() {
        val msg = planAgentMessage(true, "   ")
        assertEquals(true, msg?.contains("请根据已附加的图片或上下文先制定实施计划。"))
    }

    @Test
    fun `draft box isolates sessions`() {
        DraftBox.save("s1", "hello", listOf("img1"))
        DraftBox.save("s2", "world", emptyList())
        assertEquals("hello", DraftBox.text("s1"))
        assertEquals(listOf("img1"), DraftBox.images("s1"))
        assertEquals("world", DraftBox.text("s2"))
        DraftBox.clear("s1")
        assertEquals("", DraftBox.text("s1"))
        DraftBox.clear("s2")
    }
}
