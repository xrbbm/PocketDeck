package com.xrbbm.pideck.ui.chat

import com.xrbbm.pideck.core.net.LenientJson
import com.xrbbm.pideck.core.net.WebBatchQuestion
import com.xrbbm.pideck.core.net.WebPendingUiRequest
import com.xrbbm.pideck.core.net.batchAnswerLabel
import com.xrbbm.pideck.core.net.splitAskOption
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AskCardTest {
    @Test
    fun `split option pipe and dash`() {
        val a = splitAskOption("label|desc")
        assertEquals("label", a.label)
        assertEquals("desc", a.description)
        val b = splitAskOption("label — desc")
        assertEquals("label", b.label)
        assertEquals("desc", b.description)
        val c = splitAskOption("plain")
        assertEquals("plain", c.label)
        assertNull(c.description)
    }

    @Test
    fun `batch answer label joins list`() {
        val el = LenientJson.parseToJsonElement("""["a","b"]""")
        assertEquals("a、b", batchAnswerLabel(el))
        assertEquals("true", batchAnswerLabel(JsonPrimitive(true)))
    }

    @Test
    fun `pending request batch flag`() {
        val req = WebPendingUiRequest(
            sessionId = "s",
            agentId = "a",
            runtimeGeneration = 1,
            requestId = "r",
            method = "ask",
            title = "t",
            batchQuestions = listOf(
                WebBatchQuestion(id = "q1", type = "input", question = "q?"),
            ),
        )
        assertEquals(true, req.isBatch)
        val single = req.copy(batchQuestions = emptyList())
        assertEquals(false, single.isBatch)
    }

    @Test
    fun `batch question parses options`() {
        // Batch options parse as string-or-object (pipe split is single-mode only).
        val q = WebBatchQuestion.fromJson(
            LenientJson.parseToJsonElement(
                """{"id":"q","type":"select","question":"pick?","options":[{"label":"a","value":"a","description":"desc A"},"b"]}""",
            ) as JsonObject,
        )
        assertEquals(2, q.options.size)
        assertEquals("a", q.options[0].label)
        assertEquals("desc A", q.options[0].description)
        assertEquals("b", q.options[1].label)
    }
}
