package com.xrbbm.pideck.ui.chat

import com.xrbbm.pideck.core.net.UiMessage
import com.xrbbm.pideck.core.net.UiPart
import com.xrbbm.pideck.core.net.prettyToolValue

/**
 * Web parity for `sessionUiMessagesToMarkdown`: reasoning and tool calls become
 * collapsible `<details>` blocks, files become (deliberately truncated) image
 * links, each message gets a speaker heading, blocks are joined by a rule.
 */
fun sessionToMarkdown(messages: List<UiMessage>): String {
    val blocks = mutableListOf<String>()
    for (message in messages) {
        val lines = message.parts.mapNotNull { part ->
            when (part) {
                is UiPart.Text -> part.text.trim().ifEmpty { null }
                is UiPart.Reasoning -> part.text.trim().ifEmpty { null }?.let {
                    "<details>\n<summary>Thinking</summary>\n\n$it\n\n</details>"
                }
                is UiPart.Tool -> {
                    val body = listOfNotNull(
                        part.input?.let { "Input:\n```json\n${prettyToolValue(it)}\n```" },
                        part.errorText?.let { "Error: $it" }
                            ?: part.output?.let { "Output:\n```json\n${prettyToolValue(it)}\n```" },
                    ).joinToString("\n\n")
                    "<details>\n<summary>Tool: ${part.toolName}" +
                        "${if (part.isError) " (error)" else ""}</summary>\n\n" +
                        "${body.ifEmpty { "—" }}\n\n</details>"
                }
                is UiPart.File ->
                    if (part.dataUrl.startsWith("data:")) {
                        "![${part.mimeType}](${part.dataUrl.take(64)})"
                    } else {
                        null
                    }
            }
        }
        if (lines.isEmpty()) continue
        val speaker = if (message.isUser) "🧑 **User**" else "🤖 **Assistant**"
        blocks.add("### $speaker\n\n${lines.joinToString("\n\n")}")
    }
    return blocks.joinToString("\n\n---\n\n")
}
