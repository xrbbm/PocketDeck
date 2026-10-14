package com.xrbbm.pideck.core.net

import com.xrbbm.pideck.core.Pairing
import java.net.URLEncoder
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit

class ApiException(message: String, val statusCode: Int? = null) : Exception(message) {
    override fun toString(): String = "ApiException($statusCode): $message"
}

/**
 * PiDeck Web API client (mirrors docs/api-contract.md / ApiClient).
 *
 * No auth header on token-less LAN servers; token only reserved for future use.
 * Timeouts mirror Flutter (GET 10s / POST 15s) so backgrounded requests fail
 * fast instead of hanging the UI.
 */
class ApiClient(
    val pairing: Pairing,
    private val http: OkHttpClient = defaultHttp(),
) {
    companion object {
        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()

        private val JSON = "application/json; charset=utf-8".toMediaType()
    }

    private fun url(path: String, query: Map<String, String>? = null): String {
        val base = pairing.baseUrl.trimEnd('/')
        val q = buildMap<String, String> {
            query?.let { putAll(it) }
            if (pairing.token.isNotEmpty()) put("token", pairing.token)
        }
        if (q.isEmpty()) return "$base$path"
        val qs = q.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        return "$base$path?$qs"
    }

    private fun headers(): Map<String, String> = buildMap {
        if (pairing.token.isNotEmpty()) put("Authorization", "Bearer ${pairing.token}")
    }

    private fun decode(body: String, path: String): JsonObject {
        val el = try {
            LenientJson.parseToJsonElement(body)
        } catch (_: Exception) {
            throw ApiException("服务端返回格式异常：$path")
        }
        return el as? JsonObject ?: throw ApiException("服务端返回格式异常：$path")
    }

    private suspend fun getJson(path: String, query: Map<String, String>? = null): JsonObject =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url(path, query)).apply {
                headers().forEach { (k, v) -> addHeader(k, v) }
            }.get().build()
            try {
                withTimeout(10_000) {
                    http.newCall(req).execute().use { res ->
                        val body = res.body.string()
                        if (res.code !in 200..299) throw ApiException("请求失败：$path", res.code)
                        decode(body, path)
                    }
                }
            } catch (e: ApiException) {
                throw e
            } catch (e: Exception) {
                throw ApiException("无法连接服务：$e")
            }
        }

    private suspend fun postJson(path: String, body: JsonObject): JsonObject =
        withContext(Dispatchers.IO) {
            val payload = LenientJson.encodeToString(JsonObject.serializer(), body)
            val req = Request.Builder().url(url(path)).apply {
                headers().forEach { (k, v) -> addHeader(k, v) }
                addHeader("content-type", "application/json")
            }.post(payload.toRequestBody(JSON)).build()
            try {
                withTimeout(15_000) {
                    http.newCall(req).execute().use { res ->
                        val text = res.body.string()
                        if (res.code !in 200..299) throw ApiException("请求失败：$path", res.code)
                        decode(text, path)
                    }
                }
            } catch (e: ApiException) {
                throw e
            } catch (e: Exception) {
                throw ApiException("无法连接服务：$e")
            }
        }

    private suspend fun rawPostCode(path: String, body: JsonObject): Int =
        withContext(Dispatchers.IO) {
            val payload = LenientJson.encodeToString(JsonObject.serializer(), body)
            val req = Request.Builder().url(url(path)).apply {
                headers().forEach { (k, v) -> addHeader(k, v) }
                addHeader("content-type", "application/json")
            }.post(payload.toRequestBody(JSON)).build()
            try {
                withTimeout(15_000) {
                    http.newCall(req).execute().use { it.code }
                }
            } catch (e: Exception) {
                throw ApiException("无法连接服务：$e")
            }
        }

    suspend fun health(): Boolean = try {
        getJson("/api/health")
        true
    } catch (_: ApiException) {
        false
    }

    suspend fun fetchState(): WebState = WebState.fromJson(getJson("/api/state"))

    suspend fun fetchMessagePage(
        sessionId: String,
        before: Int? = null,
        pageSize: Int = 30,
    ): MessagePage {
        val query = mutableMapOf("pageSize" to "$pageSize")
        if (before != null) query["before"] = "$before"
        val result = getJson(
            "/api/sessions/${URLEncoder.encode(sessionId, "UTF-8")}/messages/page",
            query,
        )
        val messages = (result["messages"] as? JsonArray).orEmpty().mapNotNull {
            (it as? JsonObject)?.let(::mapChatMessage)
        }
        return MessagePage(
            messages = messages,
            nextBefore = try {
                result["nextBefore"]?.jsonPrimitive?.intOrNull
            } catch (_: Exception) {
                null
            },
            unavailable = (result["unavailable"] as? JsonPrimitive)
                ?.takeIf { it.isString }?.content,
        )
    }

    suspend fun respondToUi(
        sessionId: String,
        requestId: String,
        agentId: String,
        runtimeGeneration: Int,
        response: Map<String, String>,
    ) {
        postJson(
            "/api/ui-response",
            buildJsonObject {
                put("sessionId", sessionId)
                put("requestId", requestId)
                put("agentId", agentId)
                put("runtimeGeneration", runtimeGeneration)
                putJsonObject("response") {
                    response.forEach { (k, v) -> put(k, v) }
                }
            },
        )
    }

    suspend fun respondToUiJson(
        sessionId: String,
        requestId: String,
        agentId: String,
        runtimeGeneration: Int,
        responseJson: String,
    ) {
        val el = LenientJson.parseToJsonElement(responseJson)
        postJson(
            "/api/ui-response",
            buildJsonObject {
                put("sessionId", sessionId)
                put("requestId", requestId)
                put("agentId", agentId)
                put("runtimeGeneration", runtimeGeneration)
                put("response", el)
            },
        )
    }

    suspend fun fetchModels(force: Boolean = true): List<AvailableModel> {
        val json = getJson("/api/models", if (force) mapOf("force" to "1") else null)
        val list = json["models"] as? JsonArray ?: return emptyList()
        return list.mapNotNull { (it as? JsonObject)?.let(AvailableModel::fromJson) }
    }

    private fun target(sessionId: String, agentId: String, runtimeGeneration: Int) =
        buildJsonObject {
            put("sessionId", sessionId)
            put("agentId", agentId)
            put("runtimeGeneration", runtimeGeneration)
        }

    /** Runtime command: POST .../runtime/{action} body {target, ...}. */
    suspend fun runtimeCommand(
        sessionId: String,
        agentId: String,
        runtimeGeneration: Int,
        action: String,
        extra: JsonObject? = null,
    ): kotlinx.serialization.json.JsonElement? {
        val json = postJson(
            "/api/sessions/${URLEncoder.encode(sessionId, "UTF-8")}/runtime/$action",
            buildJsonObject {
                put("target", target(sessionId, agentId, runtimeGeneration))
                extra?.forEach { (k, v) -> put(k, v) }
            },
        )
        val result = json["result"] as? JsonObject
            ?: throw ApiException("runtime $action failed")
        if ((result["ok"] as? JsonPrimitive)?.content != "true" &&
            result["ok"].toString() != "true"
        ) {
            val error = result["error"] as? JsonObject
            val code = error?.strOrNull("code") ?: "runtime $action failed"
            throw ApiException(code)
        }
        val value = result["value"] ?: return null
        return (value as? JsonObject)?.get("value") ?: value
    }

    suspend fun abortRuntime(sessionId: String, agentId: String, runtimeGeneration: Int) {
        runtimeCommand(sessionId, agentId, runtimeGeneration, "abort")
    }

    suspend fun setRuntimeModel(
        sessionId: String,
        agentId: String,
        runtimeGeneration: Int,
        provider: String,
        modelId: String,
        modelName: String? = null,
    ) {
        runtimeCommand(
            sessionId, agentId, runtimeGeneration, "model",
            buildJsonObject {
                put("provider", provider)
                put("modelId", modelId)
                if (modelName != null) put("modelName", modelName)
            },
        )
    }

    suspend fun setRuntimeThinking(
        sessionId: String,
        agentId: String,
        runtimeGeneration: Int,
        level: String,
    ) {
        runtimeCommand(
            sessionId, agentId, runtimeGeneration, "thinking",
            buildJsonObject { put("level", level) },
        )
    }

    suspend fun updateSessionRecord(
        sessionId: String,
        modelProvider: String? = null,
        modelId: String? = null,
        modelName: String? = null,
        thinkingLevel: String? = null,
    ) {
        val patch = buildJsonObject {
            if (modelProvider != null && modelId != null) {
                putJsonObject("model") {
                    put("provider", modelProvider)
                    put("modelId", modelId)
                    if (modelName != null) put("modelName", modelName)
                }
            }
            if (thinkingLevel != null) put("thinkingLevel", thinkingLevel)
        }
        if (patch.isEmpty()) return
        postJson("/api/sessions/${URLEncoder.encode(sessionId, "UTF-8")}/update", patch)
    }

    suspend fun fetchRuntimeContextUsage(
        sessionId: String,
        agentId: String,
        runtimeGeneration: Int,
    ): WebContextUsage {
        val value = runtimeCommand(sessionId, agentId, runtimeGeneration, "state")
        return WebContextUsage.fromJson(value as? JsonObject)
    }

    /**
     * Send a message, emitting SSE frames. messageId must be unique per turn
     * (server idempotency key). Throws ApiException on `error` frame / HTTP fail.
     */
    fun streamChat(
        sessionId: String,
        text: String,
        messageId: String? = null,
        images: List<String> = emptyList(),
        agentMessage: String? = null,
    ): Flow<JsonObject> = flow {
        val turnId = messageId ?: "${System.nanoTime()}-${UUID.randomUUID()}"
        val mediaRe = Regex("^data:([^;]+);")
        val body = buildJsonObject {
            put("id", sessionId)
            putJsonArray("messages") {
                add(
                    buildJsonObject {
                        put("role", "user")
                        putJsonArray("parts") {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", text)
                            })
                            images.take(4).forEach { src ->
                                add(buildJsonObject {
                                    put("type", "file")
                                    put("mediaType", mediaRe.find(src)?.groupValues?.get(1) ?: "image/jpeg")
                                    put("url", src)
                                })
                            }
                        }
                    },
                )
            }
            put("trigger", "submit-message")
            put("messageId", turnId)
            if (!agentMessage.isNullOrEmpty()) put("agentMessage", agentMessage)
        }
        val payload = LenientJson.encodeToString(JsonObject.serializer(), body)
        val req = Request.Builder().url(url("/api/chat")).apply {
            headers().forEach { (k, v) -> addHeader(k, v) }
            addHeader("content-type", "application/json")
            addHeader("Accept", "text/event-stream")
        }.post(payload.toRequestBody(JSON)).build()

        val factory = EventSources.createFactory(http)
        val splitter = SseSplitter()
        // Bridge callback SSE → flow via rendezvous channel (no locks).
        val frames = kotlinx.coroutines.channels.Channel<JsonObject>(capacity = 64)
        val errors = kotlinx.coroutines.channels.Channel<ApiException>(capacity = 1)
        val listener = object : okhttp3.sse.EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                val split = splitter.push("data: $data\n\n")
                for (frame in split.frames) {
                    if (frame.strOrNull("type") == "error") {
                        errors.trySend(
                            ApiException(frame.strOrNull("errorText") ?: "Agent 运行失败"),
                        )
                        eventSource.cancel()
                        return
                    }
                    frames.trySend(frame)
                }
                if (split.done) {
                    eventSource.cancel()
                    frames.close()
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: okhttp3.Response?) {
                errors.trySend(ApiException("发送失败（${response?.code}）：${t?.message}"))
                frames.close()
            }

            override fun onClosed(eventSource: EventSource) {
                frames.close()
            }
        }
        factory.newEventSource(req, listener)
        for (frame in frames) {
            errors.tryReceive().getOrNull()?.let { throw it }
            emit(frame)
        }
        errors.tryReceive().getOrNull()?.let { throw it }
    }.flowOn(Dispatchers.IO)

    suspend fun editRuntimeMessage(
        sessionId: String,
        agentId: String,
        runtimeGeneration: Int,
        messageId: String,
        newText: String,
    ) {
        runtimeCommand(
            sessionId, agentId, runtimeGeneration, "edit-message",
            buildJsonObject {
                put("messageId", messageId)
                put("newText", newText)
            },
        )
    }

    suspend fun deleteRuntimeMessage(
        sessionId: String,
        agentId: String,
        runtimeGeneration: Int,
        messageId: String,
    ) {
        runtimeCommand(
            sessionId, agentId, runtimeGeneration, "delete-message",
            buildJsonObject { put("messageId", messageId) },
        )
    }

    data class Resend(val text: String, val images: List<String>)

    suspend fun prepareResend(
        sessionId: String,
        agentId: String,
        runtimeGeneration: Int,
        messageId: String,
    ): Resend {
        val value = runtimeCommand(
            sessionId, agentId, runtimeGeneration, "prepare-resend",
            buildJsonObject { put("messageId", messageId) },
        ) as? JsonObject ?: return Resend("", emptyList())
        val text = value.str("text")
        val images = (value["images"] as? JsonArray).orEmpty().mapNotNull {
            val o = it as? JsonObject ?: return@mapNotNull null
            val mime = o.strOrNull("mimeType") ?: return@mapNotNull null
            val data = o.strOrNull("data") ?: return@mapNotNull null
            if (data.startsWith("data:")) data else "data:$mime;base64,$data"
        }
        return Resend(text, images)
    }

    suspend fun fetchPrompts(search: String? = null, category: String? = null): PromptList {
        val query = mutableMapOf("page" to "1", "pageSize" to "20")
        if (!search.isNullOrEmpty()) query["search"] = search
        if (!category.isNullOrEmpty()) query["category"] = category
        return PromptList.fromJson(getJson("/api/prompts", query))
    }

    suspend fun fetchPromptContent(slug: String, category: String): String {
        val json = getJson(
            "/api/prompts/${URLEncoder.encode(slug, "UTF-8")}",
            mapOf("category" to category),
        )
        return json.objOrNull("detail")?.strOrNull("promptContent") ?: ""
    }

    suspend fun fetchSkills(): List<SkillSummary> {
        val json = getJson("/api/skills")
        return (json["skills"] as? JsonArray).orEmpty().mapNotNull {
            (it as? JsonObject)?.let(SkillSummary::fromJson)
        }
    }

    suspend fun toggleSkill(name: String, sourceId: String, enabled: Boolean) {
        postJson(
            "/api/skills/toggle",
            buildJsonObject {
                put("name", name)
                put("sourceId", sourceId)
                put("enabled", enabled)
            },
        )
    }

    suspend fun fetchExtensions(): List<ExtensionSummary> {
        val json = getJson("/api/extensions")
        return (json["extensions"] as? JsonArray).orEmpty().mapNotNull {
            (it as? JsonObject)?.let(ExtensionSummary::fromJson)
        }
    }

    suspend fun toggleExtension(source: String, scope: String, enabled: Boolean) {
        postJson(
            "/api/extensions/toggle",
            buildJsonObject {
                put("source", source)
                put("scope", scope)
                put("enabled", enabled)
            },
        )
    }

    suspend fun fetchSessionFileChanges(sessionId: String): List<SessionFileChange> {
        val json = getJson("/api/sessions/${URLEncoder.encode(sessionId, "UTF-8")}/file-changes")
        return (json["changes"] as? JsonArray).orEmpty().mapNotNull {
            (it as? JsonObject)?.let(SessionFileChange::fromJson)
        }
    }

    suspend fun fetchSessionSubagents(sessionId: String): List<SubagentEntry> {
        val json = getJson("/api/sessions/${URLEncoder.encode(sessionId, "UTF-8")}/subagents")
        return (json["subagents"] as? JsonArray).orEmpty().mapNotNull {
            (it as? JsonObject)?.let(SubagentEntry::fromJson)
        }
    }

    suspend fun fetchSessionTodo(sessionId: String): SessionTodoSnapshot? {
        val json = getJson("/api/sessions/${URLEncoder.encode(sessionId, "UTF-8")}/todo")
        val todo = json["todo"] ?: return null
        return SessionTodoSnapshot.fromJson(todo as? JsonObject)
    }

    suspend fun createProject(path: String): String {
        val result = postJson("/api/projects", buildJsonObject { put("path", path) })
        val project = result["project"] as? JsonObject
        val id = project?.strOrNull("id") ?: result.strOrNull("id")
        if (id.isNullOrEmpty()) throw ApiException("新建项目无返回 id")
        return id
    }

    suspend fun createSession(
        projectId: String,
        preferences: Map<String, String>? = null,
    ): String {
        val result = postJson(
            "/api/sessions",
            buildJsonObject {
                put("projectId", projectId)
                preferences?.forEach { (k, v) -> put(k, v) }
            },
        )
        val id = (result["session"] as? JsonObject)?.strOrNull("id")
        if (id.isNullOrEmpty()) throw ApiException("新建会话无返回 id")
        return id
    }

    suspend fun renameSession(sessionId: String, title: String) {
        val code = rawPostCode(
            "/api/sessions/${URLEncoder.encode(sessionId, "UTF-8")}/update",
            buildJsonObject { put("title", title) },
        )
        if (code !in 200..299) throw ApiException("重命名失败（$code）", code)
    }

    suspend fun deleteSession(sessionId: String) {
        val code = rawPostCode(
            "/api/sessions/${URLEncoder.encode(sessionId, "UTF-8")}/delete",
            buildJsonObject {},
        )
        if (code !in 200..299) throw ApiException("删除失败（$code）", code)
    }

    /** Copy session (shallow); returns the new id or null. */
    suspend fun copySession(sessionId: String): String? {
        val json = postJson(
            "/api/sessions/${URLEncoder.encode(sessionId, "UTF-8")}/copy",
            buildJsonObject {},
        )
        val session = (json["result"] as? JsonObject)?.get("session")
            ?: json["session"]
        val id = (session as? JsonObject)?.strOrNull("id")
        return id?.takeIf { it.isNotEmpty() }
    }

    suspend fun exportSessionHtml(sessionId: String): ByteArray =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(url("/api/sessions/${URLEncoder.encode(sessionId, "UTF-8")}/export-html"))
                .apply { headers().forEach { (k, v) -> addHeader(k, v) } }
                .get().build()
            try {
                withTimeout(15_000) {
                    http.newCall(req).execute().use { res ->
                        if (res.code !in 200..299) throw ApiException("导出失败（${res.code}）", res.code)
                        res.body.bytes()
                    }
                }
            } catch (e: ApiException) {
                throw e
            } catch (e: Exception) {
                throw ApiException("无法连接服务：$e")
            }
        }

    suspend fun restartRuntime(sessionId: String, agentId: String, runtimeGeneration: Int) {
        runtimeCommand(sessionId, agentId, runtimeGeneration, "restart")
    }

    suspend fun compactRuntime(
        sessionId: String,
        agentId: String,
        runtimeGeneration: Int,
        prompt: String? = null,
    ) {
        runtimeCommand(
            sessionId, agentId, runtimeGeneration, "compact",
            if (prompt.isNullOrEmpty()) null else buildJsonObject { put("prompt", prompt) },
        )
    }

    /** Clone session (fork); returns the new id or null. */
    suspend fun cloneRuntime(
        sessionId: String,
        agentId: String,
        runtimeGeneration: Int,
    ): String? {
        val value = runtimeCommand(sessionId, agentId, runtimeGeneration, "clone")
        val session = (value as? JsonObject)?.get("session")
        val id = (session as? JsonObject)?.strOrNull("id")
        return id?.takeIf { it.isNotEmpty() }
    }

    data class GitStatus(
        val repo: Boolean,
        val branch: GitBranchInfo?,
        val groups: GitResourceGroups?,
    )

    suspend fun fetchGitStatus(projectId: String): GitStatus {
        val json = getJson("/api/git/status", mapOf("projectId" to projectId))
        val isRepo = (json["repo"] as? JsonPrimitive)?.let {
            it.booleanOrNull ?: (it.content == "true")
        } ?: false
        if (!isRepo) return GitStatus(false, null, null)
        return GitStatus(
            repo = true,
            branch = GitBranchInfo.fromJson(json.objOrNull("branch") ?: JsonObject(emptyMap())),
            groups = GitResourceGroups.fromJson(json.objOrNull("groups")),
        )
    }

    data class GitDiff(val original: String, val modified: String)

    suspend fun fetchGitDiff(projectId: String, group: String, path: String): GitDiff? {
        val json = getJson(
            "/api/git/diff",
            mapOf("projectId" to projectId, "group" to group, "path" to path),
        )
        val diff = json.objOrNull("diff") ?: return null
        return GitDiff(
            original = diff.str("originalContent"),
            modified = diff.str("modifiedContent"),
        )
    }

    suspend fun fetchGitLog(projectId: String, limit: Int = 12): List<CommitEntry> {
        val json = getJson(
            "/api/git/log",
            mapOf("projectId" to projectId, "limit" to "$limit"),
        )
        return (json["commits"] as? JsonArray).orEmpty().mapNotNull {
            (it as? JsonObject)?.let(CommitEntry::fromJson)
        }
    }

    suspend fun fetchFileList(projectId: String, dir: String? = null): List<WebFileNodeLite> {
        val query = mutableMapOf("projectId" to projectId)
        if (!dir.isNullOrEmpty()) query["dir"] = dir
        val json = getJson("/api/files", query)
        return (json["nodes"] as? JsonArray).orEmpty().mapNotNull {
            (it as? JsonObject)?.let(WebFileNodeLite::fromJson)
        }
    }

    suspend fun fetchFileContent(projectId: String, path: String): FileContentResult {
        val json = getJson(
            "/api/file-content",
            mapOf("projectId" to projectId, "path" to path),
        )
        return FileContentResult.fromJson(json)
    }
}
