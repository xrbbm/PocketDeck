package com.xrbbm.pideck.core.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Lenient JSON readers mirroring Flutter's `as X? ?? default` parsing. */
internal fun JsonObject.str(key: String, default: String = ""): String =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)
        ?.takeIf { it.isString }?.content ?: default

internal fun JsonObject.strOrNull(key: String): String? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)
        ?.takeIf { it.isString }?.content

internal fun JsonObject.intOrNull(key: String): Int? =
    this[key]?.jsonPrimitive?.intOrNull

internal fun JsonObject.doubleOrNull(key: String): Double? =
    this[key]?.jsonPrimitive?.doubleOrNull

internal fun JsonObject.bool(key: String, default: Boolean = false): Boolean =
    this[key]?.jsonPrimitive?.booleanOrNull ?: default

internal fun JsonObject.objOrNull(key: String): JsonObject? =
    this[key] as? JsonObject

internal fun JsonObject.arr(key: String): JsonArray =
    (this[key] as? JsonArray) ?: JsonArray(emptyList())

internal fun JsonElement?.asObjectOrNull(): JsonObject? = this as? JsonObject

val LenientJson = Json { ignoreUnknownKeys = true; isLenient = true }
