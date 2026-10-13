package com.xrbbm.pideck.core.net

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Render-side message part: Text / Reasoning / Tool / File. */
sealed interface UiPart {
    data class Text(val text: String) : UiPart
    data class Reasoning(val text: String) : UiPart
    data class Tool(
        val toolName: String,
        val callId: String,
        val isError: Boolean,
        val input: kotlinx.serialization.json.JsonElement? = null,
        val output: kotlinx.serialization.json.JsonElement? = null,
        val errorText: String? = null,
        /** Streaming and not yet settled: render a spinner. */
        val streaming: Boolean = false,
    ) : UiPart
    data class File(val mimeType: String, val dataUrl: String) : UiPart
}

/** Render message: role split user/assistant only (tool folds into assistant). */
data class UiMessage(val id: String, val role: String, val parts: List<UiPart>) {
    val isUser: Boolean get() = role == "user"
}

/** Tool arg/result formatting: raw string, else pretty JSON, capped. */
fun prettyToolValue(
    value: kotlinx.serialization.json.JsonElement?,
    maxChars: Int = 4000,
): String {
    if (value == null || value is JsonNull) return ""
    var text = ""
    if (value is JsonPrimitive && value.isString) text = value.content
    if (text.isEmpty()) {
        text = try {
            LenientJson.encodeToString(
                kotlinx.serialization.json.JsonElement.serializer(),
                value,
            )
        } catch (_: Exception) {
            value.toString()
        }
    }
    return if (text.length > maxChars) {
        "${text.substring(0, maxChars)}\n… (${text.length} chars)"
    } else {
        text
    }
}

private val thinkingTagRegex =
    Regex("<thinking>[\\s\\S]*?</thinking>", RegexOption.IGNORE_CASE)

/** Strip `<thinking>…</thinking>` from assistant body text. */
fun stripThinkingTags(text: String): String =
    text.replace(thinkingTagRegex, "").trim()

data class AskOptionSplit(val label: String, val description: String?)

fun splitAskOption(option: String): AskOptionSplit {
    val pipe = option.indexOf('|')
    if (pipe > 0) {
        val label = option.substring(0, pipe).trim()
        val description = option.substring(pipe + 1).trim()
        return AskOptionSplit(label, description.ifEmpty { null })
    }
    val dash = Regex("^(.+?)\\s+—\\s+(.+)$").find(option)
    if (dash != null) {
        return AskOptionSplit(dash.groupValues[1].trim(), dash.groupValues[2].trim())
    }
    return AskOptionSplit(option, null)
}

/** One-line tool summary for the collapsed subtitle (80 chars cap). */
fun toolSummary(part: UiPart.Tool): String {
    var first = ""
    val input = part.input
    if (input is JsonObject) {
        for (k in listOf("path", "file", "command", "cmd", "query", "url", "pattern", "text", "prompt")) {
            val v = (input[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (!v.isNullOrBlank()) {
                first = v.trim().split('\n').first().trim()
                break
            }
        }
    } else if (input is JsonPrimitive && input.isString && input.content.isNotBlank()) {
        first = input.content.trim().split('\n').first().trim()
    }
    if (first.isEmpty()) return ""
    return if (first.length > 80) "${first.substring(0, 80)}…" else first
}

/** History page result (nextBefore null = top reached). */
data class MessagePage(
    val messages: List<UiMessage>,
    val nextBefore: Int? = null,
    val unavailable: String? = null,
) {
    val hasMore: Boolean get() = nextBefore != null
}

/** ChatMessage → UiMessage (mirrors chatMessagesToUiMessages). */
fun mapChatMessage(m: JsonObject): UiMessage {
    val ts = try {
        m["timestamp"]?.jsonPrimitive?.contentOrNull
    } catch (_: Exception) {
        null
    } ?: ""
    val id = m.strOrNull("id") ?: "hist-$ts"
    val role = m.strOrNull("role")
    val text = m.str("text")
    val thinking = m.strOrNull("thinking")
    val meta = m.objOrNull("meta")

    if (role == "tool") {
        val toolName = meta?.strOrNull("toolName") ?: ""
        if (toolName.isNotEmpty()) {
            val isError = meta?.bool("isError") == true || text.startsWith("✗")
            var input = meta?.get("args")
            val rawArgs = (input as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (!rawArgs.isNullOrEmpty()) {
                input = try {
                    LenientJson.parseToJsonElement(rawArgs)
                } catch (_: Exception) {
                    meta?.get("args")
                }
            }
            val detailText = meta?.strOrNull("detailText")
            return UiMessage(
                id = id,
                role = "assistant",
                parts = listOf(
                    UiPart.Tool(
                        toolName = toolName,
                        callId = "hist-$id",
                        isError = isError,
                        input = input,
                        output = meta?.get("result"),
                        errorText = if (isError) detailText ?: text else null,
                    ),
                ),
            )
        }
        if (text.isNotEmpty()) {
            return UiMessage(id, "assistant", listOf(UiPart.Text(text)))
        }
        if (!thinking.isNullOrEmpty()) {
            return UiMessage(id, "assistant", listOf(UiPart.Reasoning(thinking)))
        }
        return UiMessage(id, "assistant", emptyList())
    }

    val parts = mutableListOf<UiPart>()
    if (!thinking.isNullOrEmpty()) parts.add(UiPart.Reasoning(thinking))
    val body = if (role == "user") text else stripThinkingTags(text)
    if (body.isNotEmpty()) parts.add(UiPart.Text(body))
    if (role == "user") {
        val images = m["images"] as? JsonArray
        images?.forEach { img ->
            val o = img as? JsonObject ?: return@forEach
            if (o.strOrNull("type") != "image") return@forEach
            val mime = o.strOrNull("mimeType") ?: return@forEach
            val data = o.strOrNull("data") ?: return@forEach
            if (data.length > 4 * 1024 * 1024) return@forEach
            parts.add(UiPart.File(mime, "data:$mime;base64,$data"))
        }
    }
    return UiMessage(id, if (role == "user") "user" else "assistant", parts)
}

fun batchAnswerLabel(value: kotlinx.serialization.json.JsonElement?): String {
    if (value == null || value is JsonNull) return ""
    if (value is JsonPrimitive) {
        value.booleanOrNull?.let { return if (it) "true" else "false" }
    }
    if (value is JsonArray) return value.joinToString("、") { "$it".trim('"') }
    return (value as? JsonPrimitive)?.contentOrNull ?: value.toString()
}
