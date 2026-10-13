package com.xrbbm.pideck.core.net

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Batch ask option (string or {label,value,description}). */
data class WebBatchOption(
    val label: String,
    val value: String? = null,
    val description: String? = null,
) {
    companion object {
        fun parse(raw: kotlinx.serialization.json.JsonElement): WebBatchOption {
            if (raw is JsonPrimitive && raw.isString) {
                return WebBatchOption(label = raw.content, value = raw.content)
            }
            if (raw is JsonObject) {
                val label = raw.str("label")
                return WebBatchOption(
                    label = label,
                    value = raw.strOrNull("value") ?: label,
                    description = raw.strOrNull("description"),
                )
            }
            return WebBatchOption(label = raw.toString(), value = raw.toString())
        }
    }
}

data class WebBatchQuestion(
    val id: String,
    val type: String,
    val question: String,
    val options: List<WebBatchOption> = emptyList(),
    val allowOther: Boolean? = null,
    val placeholder: String? = null,
    val prefill: String? = null,
) {
    companion object {
        fun fromJson(json: JsonObject) = WebBatchQuestion(
            id = json.str("id"),
            type = json.str("type", "input"),
            question = json.str("question"),
            options = json.arr("options").map { WebBatchOption.parse(it) },
            allowOther = json["allowOther"]?.let {
                (it as? JsonPrimitive)?.booleanOrNull
            },
            placeholder = json.strOrNull("placeholder"),
            prefill = json.strOrNull("prefill"),
        )
    }
}

/** Pending approval (ask/confirm/input); only the current session's is shown. */
data class WebPendingUiRequest(
    val sessionId: String,
    val agentId: String,
    val runtimeGeneration: Int,
    val requestId: String,
    val method: String,
    val title: String,
    val options: List<String>? = null,
    val placeholder: String? = null,
    val prefill: String? = null,
    val allowOther: Boolean? = null,
    val batchQuestions: List<WebBatchQuestion> = emptyList(),
) {
    val isBatch: Boolean get() = batchQuestions.isNotEmpty()

    companion object {
        fun fromJson(json: JsonObject) = WebPendingUiRequest(
            sessionId = json.str("sessionId"),
            agentId = json.str("agentId"),
            runtimeGeneration = json.intOrNull("runtimeGeneration") ?: 0,
            requestId = json.str("requestId"),
            method = json.str("method"),
            title = json.str("title"),
            options = (json["options"] as? JsonArray)
                ?.map { "$it".trim('"') },
            placeholder = json.strOrNull("placeholder"),
            prefill = json.strOrNull("prefill"),
            allowOther = json["allowOther"]?.let {
                (it as? JsonPrimitive)?.booleanOrNull
            },
            batchQuestions = json.arr("batchQuestions").map {
                WebBatchQuestion.fromJson(it as JsonObject)
            },
        )
    }
}

/** GET /api/state aggregate. */
data class WebState(
    val projects: List<WebProject>,
    val sessions: List<WebSession>,
    val runtimes: List<WebRuntime>,
    val pendingUiRequests: List<WebPendingUiRequest>,
) {
    fun runtimeOf(sessionId: String): WebRuntime? =
        runtimes.firstOrNull { it.sessionId == sessionId }

    fun pendingUiOf(sessionId: String): WebPendingUiRequest? =
        pendingUiRequests.firstOrNull { it.sessionId == sessionId }

    companion object {
        fun fromJson(json: JsonObject) = WebState(
            projects = json.arr("projects").map { WebProject.fromJson(it as JsonObject) },
            sessions = json.arr("sessions").map { WebSession.fromJson(it as JsonObject) },
            runtimes = json.arr("runtimes").map { WebRuntime.fromJson(it as JsonObject) },
            pendingUiRequests = json.arr("pendingUiRequests").map {
                WebPendingUiRequest.fromJson(it as JsonObject)
            },
        )
    }
}

/** Prompt library entry (list display + fetch body by slug). */
data class PromptItem(
    val slug: String,
    val title: String,
    val category: String,
    val subcategory: String = "",
    val tags: List<String> = emptyList(),
    val description: String = "",
) {
    companion object {
        fun fromJson(json: JsonObject) = PromptItem(
            slug = json.str("slug"),
            title = json.str("title"),
            category = json.str("category"),
            subcategory = json.str("subcategory"),
            tags = json.arr("tags").map { "$it".trim('"') },
            description = json.str("description"),
        )
    }
}

data class PromptCategory(
    val slug: String,
    val name: String,
    val count: Int = 0,
) {
    companion object {
        fun fromJson(json: JsonObject) = PromptCategory(
            slug = json.str("slug"),
            name = json.str("name"),
            count = json.intOrNull("count") ?: 0,
        )
    }
}

data class PromptList(
    val categories: List<PromptCategory>,
    val prompts: List<PromptItem>,
) {
    companion object {
        fun fromJson(json: JsonObject) = PromptList(
            categories = json.arr("categories").map {
                PromptCategory.fromJson(it as JsonObject)
            },
            prompts = json.arr("prompts").map {
                PromptItem.fromJson(it as JsonObject)
            },
        )
    }
}
