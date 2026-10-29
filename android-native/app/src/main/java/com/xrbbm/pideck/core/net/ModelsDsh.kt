package com.xrbbm.pideck.core.net

import kotlinx.serialization.json.JsonObject

/** GET /api/sessions/:id/dsh/goal — null when the session has no goal. */
data class DshGoal(
    val phase: String,
    val objective: String?,
    val roundsStarted: Int?,
    val maxGoalRounds: Int?,
) {
    companion object {
        fun fromJson(json: JsonObject) = DshGoal(
            phase = json.str("phase"),
            objective = json.strOrNull("objective"),
            roundsStarted = json.intOrNull("roundsStarted"),
            maxGoalRounds = json.intOrNull("maxGoalRounds"),
        )
    }
}

/** GET /api/sessions/:id/dsh/skills entry. */
data class DshSkill(
    val name: String,
    val description: String?,
    val whenToUse: String?,
    val modelInvocable: Boolean,
) {
    companion object {
        fun fromJson(json: JsonObject) = DshSkill(
            name = json.str("name"),
            description = json.strOrNull("description"),
            whenToUse = json.strOrNull("whenToUse"),
            modelInvocable = json.bool("modelInvocable", true),
        )
    }
}

/** GET /api/sessions/:id/dsh/subagents entry. */
data class DshSubagent(
    val id: String,
    val label: String?,
    val activity: String?,
    val kind: String?,
) {
    val isRunning: Boolean get() = activity == "running"

    companion object {
        fun fromJson(json: JsonObject) = DshSubagent(
            id = json.str("id"),
            label = json.strOrNull("label"),
            activity = json.strOrNull("activity"),
            kind = json.strOrNull("kind"),
        )
    }
}

/** Dynamic plugin package (one runnable unit inside a plugin). */
data class DshPluginPackage(val packageId: String, val name: String) {
    companion object {
        fun fromJson(json: JsonObject) = DshPluginPackage(
            packageId = json.str("packageId"),
            name = json.str("name"),
        )
    }
}

/** GET /api/dsh/plugins dynamic entry. */
data class DshPlugin(
    val pluginId: String,
    val packages: List<DshPluginPackage>,
    val activeRun: Boolean,
    val error: String?,
) {
    companion object {
        fun fromJson(json: JsonObject) = DshPlugin(
            pluginId = json.str("pluginId"),
            packages = json.arr("packages").mapNotNull {
                (it as? JsonObject)?.let(DshPluginPackage::fromJson)
            },
            activeRun = json.bool("activeRun"),
            error = json.strOrNull("error"),
        )
    }
}

/** GET /api/dsh/plugins static entry (bundled, enable/disable only). */
data class DshStaticPlugin(val entryId: String, val moduleName: String, val enabled: Boolean) {
    companion object {
        fun fromJson(json: JsonObject) = DshStaticPlugin(
            entryId = json.str("entryId"),
            moduleName = json.str("moduleName"),
            enabled = json.bool("enabled"),
        )
    }
}

data class DshPlugins(val dynamic: List<DshPlugin>, val static: List<DshStaticPlugin>) {
    companion object {
        val Empty = DshPlugins(emptyList(), emptyList())
    }
}

/** GET /api/sessions/:id/search hit (server index). */
data class SearchHit(val messageId: String, val role: String, val snippet: String) {
    companion object {
        fun fromJson(json: JsonObject) = SearchHit(
            messageId = json.str("messageId"),
            role = json.str("role"),
            snippet = json.str("snippet"),
        )
    }
}

/**
 * Search envelope: `{result: {sessions: [{sessionId, matches: [...]}]}}`.
 * `result: null` = the server index is unavailable (client falls back to
 * searching already-loaded messages). First session = current one.
 */
fun parseSessionSearch(json: JsonObject): List<SearchHit>? {
    val result = json.objOrNull("result") ?: return null
    val first = result.arr("sessions").firstOrNull() as? JsonObject ?: return emptyList()
    return first.arr("matches").mapNotNull { (it as? JsonObject)?.let(SearchHit::fromJson) }
}
