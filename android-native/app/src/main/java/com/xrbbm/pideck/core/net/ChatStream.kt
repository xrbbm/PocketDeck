package com.xrbbm.pideck.core.net

import kotlinx.serialization.json.JsonObject

/**
 * Streaming draft: folds server frames into one UiMessage.
 *
 * Frame set (WebEventStream.ts hand-rolled, not the full ai SDK set):
 * start / start-step / text-start|delta|end / reasoning-start|delta|end /
 * tool-input-start / tool-input-available / tool-output-available /
 * tool-output-error / error / finish. Unknown types ignored.
 */
class ToolDraft(val name: String) {
    var input: kotlinx.serialization.json.JsonElement? = null
    var settled: Boolean = false
    var output: kotlinx.serialization.json.JsonElement? = null
    var errorText: String? = null
}

private enum class SegKind { TEXT, REASONING, TOOL }

private class Seg(
    val kind: SegKind,
    val callId: String? = null,
    val draft: ToolDraft? = null,
) {
    var buf: String = ""
}

/**
 * Timeline segmentation: text/reasoning/tool interleave in arrival order.
 * Consecutive deltas of the same kind merge only while uninterrupted; once
 * another kind breaks the run, the next same-kind delta opens a new segment.
 * (Prevents the float bug where merged body text pushes a tool card below
 * the reply it actually belongs to.)
 */
class StreamDraft {
    private val segs = mutableListOf<Seg>()
    private val toolIndex = mutableMapOf<String, Seg>()
    private var thinkCarry = ""

    private fun appendKind(kind: SegKind, delta: String) {
        val last = segs.lastOrNull()
        if (last != null && last.kind == kind) {
            last.buf += delta
        } else {
            val seg = Seg(kind)
            seg.buf = delta
            segs.add(seg)
        }
    }

    private fun toolSeg(callId: String, name: String): Seg {
        val old = toolIndex[callId]
        if (old != null) {
            val draft = old.draft
            if (draft != null && draft.name != name && !draft.settled) {
                val seg = Seg(SegKind.TOOL, callId, ToolDraft(name))
                val idx = segs.indexOf(old)
                if (idx >= 0) segs[idx] = seg
                toolIndex[callId] = seg
                return seg
            }
            return old
        }
        val seg = Seg(SegKind.TOOL, callId, ToolDraft(name))
        segs.add(seg)
        toolIndex[callId] = seg
        return seg
    }

    fun applyFrame(frame: JsonObject) {
        when (frame.strOrNull("type")) {
            "text-delta" -> {
                val delta = frame.strOrNull("delta")
                if (!delta.isNullOrEmpty()) appendKind(SegKind.TEXT, delta)
            }
            "reasoning-delta" -> {
                val delta = frame.strOrNull("delta")
                if (!delta.isNullOrEmpty()) appendKind(SegKind.REASONING, delta)
            }
            "tool-input-start", "tool-input-available" -> {
                val callId = frame.strOrNull("toolCallId")
                if (callId.isNullOrEmpty()) return
                val name = frame.strOrNull("toolName") ?: "tool"
                val seg = toolSeg(callId, name)
                frame["input"]?.let { seg.draft?.input = it }
            }
            "tool-output-available" -> {
                val callId = frame.strOrNull("toolCallId") ?: return
                val seg = toolIndex[callId] ?: return
                val draft = seg.draft ?: return
                draft.settled = true
                draft.output = frame["output"]
            }
            "tool-output-error" -> {
                val callId = frame.strOrNull("toolCallId") ?: return
                val seg = toolIndex[callId] ?: return
                val draft = seg.draft ?: return
                draft.settled = true
                draft.errorText = frame.strOrNull("errorText") ?: "Tool failed"
            }
            else -> Unit
        }
    }

    val isEmpty: Boolean get() = segs.isEmpty()

    fun toUiMessage(id: String): UiMessage {
        val parts = mutableListOf<UiPart>()
        for (seg in segs) {
            when (seg.kind) {
                SegKind.TEXT -> {
                    val raw = thinkCarry + seg.buf
                    thinkCarry = ""
                    val body = stripThinkingTags(raw)
                    val tail = Regex("<[a-zA-Z/]*$").find(body)
                    if (tail != null && body.length - tail.range.first < 12) {
                        thinkCarry = body.substring(tail.range.first)
                        val head = body.substring(0, tail.range.first).trimEnd()
                        if (head.isNotEmpty()) parts.add(UiPart.Text(head))
                    } else if (body.isNotEmpty()) {
                        parts.add(UiPart.Text(body))
                    }
                }
                SegKind.REASONING -> {
                    if (seg.buf.isNotEmpty()) parts.add(UiPart.Reasoning(seg.buf))
                }
                SegKind.TOOL -> {
                    val draft = seg.draft ?: continue
                    parts.add(
                        UiPart.Tool(
                            toolName = draft.name,
                            callId = seg.callId ?: "",
                            isError = draft.errorText != null,
                            input = draft.input,
                            output = draft.output,
                            errorText = draft.errorText,
                            streaming = !draft.settled,
                        ),
                    )
                }
            }
        }
        return UiMessage(id, "assistant", parts)
    }
}

data class SseSplit(val frames: List<JsonObject>, val done: Boolean)

/**
 * SSE incremental framer: a TCP chunk may cut a frame mid-way; the tail is
 * kept for the next chunk. Returns complete frames; done on [DONE]/finish.
 */
class SseSplitter {
    private var buffer = ""

    private fun norm(s: String): String =
        s.replace("\r\n", "\n").replace('\r', '\n')

    fun push(chunk: String): SseSplit {
        buffer += norm(chunk)
        val frames = mutableListOf<JsonObject>()
        var done = false
        while (true) {
            val sep = buffer.indexOf("\n\n")
            if (sep < 0) break
            val raw = buffer.substring(0, sep)
            buffer = buffer.substring(sep + 2)
            for (line in raw.split('\n')) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                if (trimmed == "[DONE]" || trimmed == "data:[DONE]" || trimmed == "data: [DONE]") {
                    done = true
                    break
                }
                if (!trimmed.startsWith("data:")) continue
                val payload = trimmed.substring(5).trim()
                if (payload.isEmpty()) continue
                try {
                    val decoded = LenientJson.parseToJsonElement(payload) as? JsonObject
                        ?: continue
                    if (decoded.strOrNull("type") == "finish") done = true
                    frames.add(decoded)
                } catch (_: Exception) {
                    // Heartbeat / torn frame: drop.
                }
            }
            if (done) break
        }
        return SseSplit(frames, done)
    }
}
