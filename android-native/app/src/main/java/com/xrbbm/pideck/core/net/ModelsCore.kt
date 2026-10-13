package com.xrbbm.pideck.core.net

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** GET /api/state project record. */
data class WebProject(
    val id: String,
    val name: String,
    val path: String,
    val lastOpenedAt: Int? = null,
) {
    companion object {
        fun fromJson(json: JsonObject) = WebProject(
            id = json.str("id"),
            name = json.str("name"),
            path = json.str("path"),
            lastOpenedAt = json.intOrNull("lastOpenedAt"),
        )
    }
}

/** GET /api/state session record (branch + model preference fields kept). */
data class WebSession(
    val id: String,
    val projectId: String,
    val title: String,
    val status: String,
    val backend: String? = null,
    val updatedAt: Int? = null,
    val createdAt: Int? = null,
    val parentSessionId: String? = null,
    val forked: Boolean = false,
    val modelProvider: String? = null,
    val modelId: String? = null,
    val modelName: String? = null,
    val thinkingLevel: String? = null,
) {
    companion object {
        fun fromJson(json: JsonObject): WebSession {
            val model = json.objOrNull("model")
            return WebSession(
                id = json.str("id"),
                projectId = json.str("projectId"),
                title = json.str("title"),
                status = json.str("status"),
                backend = json.strOrNull("backend"),
                updatedAt = json.intOrNull("updatedAt"),
                createdAt = json.intOrNull("createdAt"),
                parentSessionId = json.strOrNull("parentSessionId"),
                forked = json.bool("forked"),
                modelProvider = model?.strOrNull("provider"),
                modelId = model?.strOrNull("modelId"),
                modelName = model?.strOrNull("modelName"),
                thinkingLevel = json.strOrNull("thinkingLevel"),
            )
        }
    }
}

/** GET /api/state runtime record. */
data class WebRuntime(
    val sessionId: String,
    val agentId: String,
    val status: String,
    val runtimeGeneration: Int? = null,
) {
    val isBusy: Boolean get() = status == "running" || status == "starting"

    companion object {
        fun fromJson(json: JsonObject) = WebRuntime(
            sessionId = json.str("sessionId"),
            agentId = json.str("agentId"),
            status = json.str("status"),
            runtimeGeneration = json.intOrNull("runtimeGeneration"),
        )
    }
}

/** POST runtime/state value, feeds the header context ring. */
data class WebContextUsage(
    val contextTokens: Int? = null,
    val contextWindow: Int? = null,
    val contextPercent: Double? = null,
    val contextOverflow: Boolean = false,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
) {
    val percent: Double get() {
        val p = contextPercent
        if (p != null && p.isFinite()) return p.coerceIn(0.0, 100.0)
        val w = contextWindow ?: 0
        if (w <= 0) return 0.0
        return ((contextTokens ?: 0).toDouble() / w * 100).coerceIn(0.0, 100.0)
    }

    val hasWindow: Boolean get() = (contextWindow ?: 0) > 0

    companion object {
        fun fromJson(json: JsonObject?) = WebContextUsage(
            contextTokens = json?.intOrNull("contextTokens"),
            contextWindow = json?.intOrNull("contextWindow"),
            contextPercent = json?.doubleOrNull("contextPercent"),
            contextOverflow = json?.bool("contextOverflow") ?: false,
            inputTokens = json?.intOrNull("inputTokens"),
            outputTokens = json?.intOrNull("outputTokens"),
        )
    }
}

/** Available model (switching needs the triple + levels only). */
data class AvailableModel(
    val id: String,
    val provider: String,
    val name: String? = null,
    val contextWindow: Int? = null,
    val reasoning: Boolean? = null,
    val images: Boolean? = null,
    val levels: List<String> = emptyList(),
) {
    val display: String get() = if (!name.isNullOrEmpty()) name else id

    val contextLabel: String get() {
        val ctx = contextWindow ?: return ""
        if (ctx <= 0) return ""
        if (ctx < 10000) return "$ctx ctx"
        val w = ctx / 10000.0
        val s = if (w == kotlin.math.floor(w)) w.toInt().toString() else "%.1f".format(w)
        return "${s}万 ctx"
    }

    companion object {
        fun fromJson(json: JsonObject): AvailableModel {
            val levels = mutableListOf<String>()
            val rawLevels = json["thinkingLevels"] as? JsonArray
            if (rawLevels != null) {
                rawLevels.mapNotNullTo(levels) {
                    (it as? kotlinx.serialization.json.JsonPrimitive)
                        ?.takeIf { p -> p.isString }?.content?.takeIf { s -> s.isNotEmpty() }
                }
            } else {
                val efforts = json["reasoningEfforts"] as? JsonArray
                efforts?.forEach {
                    val id = (it as? JsonObject)?.strOrNull("id")
                    if (!id.isNullOrEmpty()) levels.add(id)
                }
            }
            return AvailableModel(
                id = json.str("id"),
                provider = json.str("provider"),
                name = json.strOrNull("name"),
                contextWindow = json.intOrNull("contextWindow"),
                reasoning = (json["reasoning"] as? JsonPrimitive)?.booleanOrNull,
                images = (json["images"] as? JsonPrimitive)?.booleanOrNull,
                levels = levels,
            )
        }
    }
}
