package com.xrbbm.pideck.core.net

import kotlinx.serialization.json.JsonObject

/**
 * `GET /api/usage/:provider` result. The server answers one of three shapes:
 * `periods` (rolling/weekly/monthly percent), `credits` (window list) or
 * `balance` (prepaid amount). `success=false` means the provider has no
 * usage probe configured — the web renders nothing in that case, so do we.
 */
data class ProviderUsage(
    val success: Boolean = false,
    val provider: String? = null,
    val kind: String? = null,
    /** rolling/weekly/monthly → percent, in server order. */
    val periods: List<Pair<String, Double>> = emptyList(),
    val windows: List<UsageWindow> = emptyList(),
    val balance: Double? = null,
) {
    companion object {
        fun fromJson(json: JsonObject?): ProviderUsage {
            if (json == null) return ProviderUsage()
            val credits = json.objOrNull("credits")
            return ProviderUsage(
                success = json.bool("success"),
                provider = json.strOrNull("provider"),
                kind = json.strOrNull("kind"),
                periods = listOf("rolling", "weekly", "monthly").mapNotNull { key ->
                    val percent = json.objOrNull("periods")
                        ?.objOrNull(key)
                        ?.doubleOrNull("percent")
                        ?: return@mapNotNull null
                    key to percent.coerceIn(0.0, 100.0)
                },
                windows = (credits?.arr("windows") ?: json.arr("windows"))
                    .mapNotNull { (it as? JsonObject)?.let(UsageWindow::fromJson) },
                balance = json.objOrNull("balance")?.doubleOrNull("value"),
            )
        }
    }
}

data class UsageWindow(
    val key: String,
    val percent: Double?,
    val remaining: Double?,
) {
    companion object {
        fun fromJson(json: JsonObject): UsageWindow {
            val total = json.doubleOrNull("total")
            val used = json.doubleOrNull("used")
            // Web derives percent from used/total when the probe omits it.
            val percent = json.doubleOrNull("percent")
                ?: if (total != null && total > 0 && used != null) used / total * 100 else null
            val remaining = json.doubleOrNull("remaining")
                ?: if (total != null && used != null) (total - used).coerceAtLeast(0.0) else null
            return UsageWindow(
                key = json.str("key"),
                percent = percent?.coerceIn(0.0, 100.0),
                remaining = remaining,
            )
        }
    }
}
