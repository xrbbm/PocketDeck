package com.xrbbm.pideck.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xrbbm.pideck.core.net.ApiClient
import com.xrbbm.pideck.core.net.MessagePage
import com.xrbbm.pideck.core.net.StreamDraft
import com.xrbbm.pideck.core.net.UiMessage
import com.xrbbm.pideck.core.net.UiPart
import com.xrbbm.pideck.core.net.WebPendingUiRequest
import com.xrbbm.pideck.core.net.WebRuntime
import com.xrbbm.pideck.core.net.WebSession
import com.xrbbm.pideck.core.net.WebContextUsage
import com.xrbbm.pideck.core.net.AvailableModel
import com.xrbbm.pideck.core.net.SearchHit
import com.xrbbm.pideck.core.net.strOrNull
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Plan-mode marker (mirrors desktop composerBehavior.PI_DECK_PLAN_MODE_MARKER). */
const val PLAN_MARKER = "__PI_DECK_PLAN_MODE__"

fun planAgentMessage(planMode: Boolean, text: String): String? {
    if (!planMode) return null
    val trimmed = text.trim()
    if (trimmed.startsWith("/")) return null
    val visible = trimmed.ifEmpty { "请根据已附加的图片或上下文先制定实施计划。" }
    return listOf(
        PLAN_MARKER,
        visible,
        "",
        "请先只做只读分析，不要修改文件。最后必须输出以 `Plan:` 开头的编号计划，格式如下：",
        "Plan:",
        "1. 第一步",
        "2. 第二步",
    ).joinToString("\n")
}

/**
 * Chat timeline state: history paging + streaming draft + approval polling.
 *
 * Chat page state machine:
 * - first page without `before`, follow-ups with last nextBefore (null = top)
 * - `unavailable` is a dedicated error state (never rendered as empty chat)
 * - streaming keeps arrived content on stop; abort goes to the server after
 * - approval + runtime polled every 3s; idle backoff (10s cadence when idle)
 * - new approval arrival scrolls to bottom (exposed via approvalSeq)
 */
class ChatViewModel(
    private val api: ApiClient,
    val session: WebSession,
) : ViewModel() {
    var messages by mutableStateOf<List<UiMessage>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set
    var loadingMore by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var unavailable by mutableStateOf<String?>(null)
        private set
    var nextBefore by mutableStateOf<Int?>(null)
        private set
    val hasMore: Boolean get() = nextBefore != null

    var streaming by mutableStateOf(false)
        private set
    // External live tail: runs started elsewhere (web / other device).
    // Web 1:1: local send owns its POST flow, external subscribe only
    // runs while local is idle (enabled = session && !streaming).
    var externalStreaming by mutableStateOf(false)
        private set
    var pendingUi by mutableStateOf<WebPendingUiRequest?>(null)
        private set
    var runtime by mutableStateOf<WebRuntime?>(null)
        private set
    var usage by mutableStateOf<WebContextUsage?>(null)
        private set
    var model by mutableStateOf<AvailableModel?>(null)
        private set
    var thinkingLevel by mutableStateOf<String?>(null)
        private set
    var uiResponding by mutableStateOf(false)
        private set

    /** Bumped whenever a new approval arrives (UI scrolls to bottom). */
    var approvalSeq by mutableStateOf(0)
        private set

    /** Lightweight event channel for one-shot messages (snackbar text). */
    var notice by mutableStateOf<Pair<Long, String>?>(null)
        private set

    private var streamJob: Job? = null
    private var externalJob: Job? = null
    private var pollJob: Job? = null
    private var runtimeGen: Int? = null
    private var usageAt: Long = 0
    // Model catalog cache: session record only carries the triple, levels
    // come from /api/models (muse-spark has 5, agnes has 7). Loaded once
    // at start so the thinking sheet shows the actual model's levels.
    private var catalog: List<AvailableModel>? = null
    private var externalId: String? = null
    private var lastBusy: Boolean = false
    private var refreshing: Boolean = false

    // Draft box (per-session, process-level) mirrors ChatDraftBox.
    var inputText by mutableStateOf(DraftBox.text(session.id))
    var attachments by mutableStateOf(DraftBox.images(session.id))
        private set
    var planMode by mutableStateOf(false)

    fun start() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            loadFirst()
            try {
                catalog = api.fetchModels()
                catalog?.let { enrichModel(it) }
            } catch (_: Exception) {
                // Catalog failure keeps the record triple; sheets fall back.
            }
            var tick = 0
            var refreshTick = 0
            while (isActive) {
                delay(3000)
                tick++
                val idle = runtime == null && pendingUi == null && !streaming
                if (idle && tick % 3 != 1) continue
                pollState()
                // Busy fallback refresh: the external stream only carries
                // the live tail; settled turns land via history. Refresh
                // every ~15s while remote is busy (silent, keeps the tail).
                val busy = runtime?.isBusy == true
                if (busy && !streaming) {
                    refreshTick++
                    if (refreshTick % 5 == 0) refreshAfterSettle()
                } else {
                    refreshTick = 0
                }
            }
        }
    }

    fun saveDraft() {
        DraftBox.save(session.id, inputText, attachments)
    }

    fun onInputChange(text: String) {
        inputText = text
    }

    fun addAttachments(urls: List<String>) {
        attachments = (attachments + urls).take(4)
    }

    fun removeAttachment(index: Int) {
        attachments = attachments.filterIndexed { i, _ -> i != index }
    }

    fun clearAttachments() {
        attachments = emptyList()
    }

    fun clearError() {
        error = null
    }

    fun reportError(msg: String) {
        error = msg
    }

    /** Prompt insert: empty input takes content, else append after blank line. */
    fun appendPrompt(content: String) {
        if (content.isEmpty()) return
        inputText = if (inputText.isEmpty()) content else "$inputText\n\n$content"
    }

    suspend fun loadFirst() {
        loading = true
        try {
            val page: MessagePage = api.fetchMessagePage(session.id)
            messages = page.messages
            nextBefore = page.nextBefore
            unavailable = page.unavailable
            error = null
        } catch (e: Exception) {
            error = (e as? com.xrbbm.pideck.core.net.ApiException)?.message ?: "$e"
        } finally {
            loading = false
        }
    }

    /** Server-side history index; null = index unavailable (fall back to local). */
    suspend fun searchHistory(query: String): List<SearchHit>? =
        runCatching { api.searchSession(session.id, query) }.getOrNull()

    suspend fun loadMore() {
        val before = nextBefore ?: return
        if (loadingMore) return
        loadingMore = true
        try {
            val page = api.fetchMessagePage(session.id, before = before)
            messages = page.messages + messages
            nextBefore = page.nextBefore
        } catch (e: Exception) {
            error = (e as? com.xrbbm.pideck.core.net.ApiException)?.message ?: "$e"
        } finally {
            loadingMore = false
        }
    }

    suspend fun pollState(force: Boolean = false) {
        if (uiResponding && !force) return
        try {
            val state = api.fetchState()
            val pending = state.pendingUiOf(session.id)
            val had = pendingUi?.requestId
            val rt = state.runtimeOf(session.id)
            val busy = rt?.isBusy == true
            pendingUi = pending
            runtime = rt
            // Web 1:1 external follow: subscribe while remote is busy
            // and local is idle; busy->idle settles with a history refresh.
            if (busy && !streaming) startExternal() else stopExternal()
            if (lastBusy && !busy && !streaming) refreshAfterSettle()
            lastBusy = busy
            adoptSessionRecord(state.sessions)
            if (rt == null) {
                usage = null
                usageAt = 0
            } else if (rt.runtimeGeneration != runtimeGen) {
                runtimeGen = rt.runtimeGeneration
                usage = null
                usageAt = 0
            }
            if (rt != null) {
                try {
                    val u = api.fetchRuntimeContextUsage(
                        rt.sessionId,
                        rt.agentId,
                        rt.runtimeGeneration ?: 0,
                    )
                    usage = u
                    usageAt = System.currentTimeMillis()
                } catch (_: Exception) {
                    // Next poll converges.
                }
            }
            if (pending != null && had != pending.requestId) approvalSeq++
        } catch (_: Exception) {
            // Poll failures stay silent (never clobber the body error state).
        }
    }

    private fun adoptSessionRecord(sessions: List<WebSession>) {
        val record = sessions.firstOrNull { it.id == session.id } ?: return
        val cur = model
        if (record.modelId != null && record.modelProvider != null) {
            if (cur == null || cur.id != record.modelId || cur.provider != record.modelProvider) {
                val cached = catalog?.firstOrNull {
                    it.id == record.modelId && it.provider == record.modelProvider
                }
                model = cached ?: AvailableModel(
                    id = record.modelId,
                    provider = record.modelProvider,
                    name = record.modelName,
                )
            }
        }
        if (record.thinkingLevel != null && record.thinkingLevel != thinkingLevel) {
            thinkingLevel = record.thinkingLevel
        }
    }

    /** Fill model levels from the catalog (record triple has none). */
    private fun enrichModel(models: List<AvailableModel>) {
        val cur = model ?: return
        val full = models.firstOrNull {
            it.id == cur.id && it.provider == cur.provider
        } ?: return
        model = full
    }

    private fun startExternal() {
        if (externalJob != null || streaming) return
        val draft = StreamDraft()
        val id = "external-${System.currentTimeMillis()}"
        externalId = id
        externalStreaming = false
        externalJob = viewModelScope.launch {
            try {
                api.streamSession(session.id).collect { frame ->
                    if (frame.strOrNull("type") == "start" && !draft.isEmpty) {
                        // New remote turn over a finished tail: reset like web.
                        return@collect
                    }
                    draft.applyFrame(frame)
                    if (draft.isEmpty) return@collect
                    externalStreaming = true
                    val ui = draft.toUiMessage(id)
                    messages = messages.filter { it.id != id } + ui
                }
            } catch (_: Exception) {
                // Idle subscribe drops stay silent; next poll converges.
            } finally {
                val had = !draft.isEmpty
                externalJob = null
                externalStreaming = false
                messages = messages.filter { it.id != id }
                if (had) refreshAfterSettle()
            }
        }
    }

    private fun stopExternal() {
        externalJob?.cancel()
        externalJob = null
        externalStreaming = false
        externalId?.let { id ->
            externalId = null
            messages = messages.filter { it.id != id }
        }
    }

    private suspend fun refreshAfterSettle() {
        if (refreshing || streaming) return
        refreshing = true
        try {
            val page: MessagePage = api.fetchMessagePage(session.id)
            // Keep locally paged-up head: only refresh when the first page
            // still covers the timeline (hasMore=false), else next open
            // converges without clobbering the pulled-up history.
            if (!page.hasMore) {
                messages = page.messages
                nextBefore = page.nextBefore
                unavailable = page.unavailable
                error = null
            }
        } catch (_: Exception) {
            // Silent: keeps the live tail instead of erroring the body.
        } finally {
            refreshing = false
        }
    }

    /**
     * Stop: cancel local collection first (keep arrived content), then abort.
     * External follow also stops here (web parity: header stop aborts the
     * remote run, not just the local tail).
     */
    suspend fun stop() {
        val job = streamJob
        val following = externalJob != null
        if (job == null && !following) return
        job?.cancel()
        streamJob = null
        streaming = false
        stopExternal()
        val rt = runtime ?: return
        try {
            api.abortRuntime(rt.sessionId, rt.agentId, rt.runtimeGeneration ?: 0)
        } catch (_: Exception) {
            // Runtime already gone; next poll converges.
        }
    }

    suspend fun send() {
        val text = inputText.trim()
        if (text.isEmpty() && attachments.isEmpty() || streaming) return
        error = null
        unavailable = null
        val userMsg = UiMessage(
            id = "local-${UUID.randomUUID()}",
            role = "user",
            parts = buildList {
                if (text.isNotEmpty()) add(UiPart.Text(text))
                attachments.forEach { url ->
                    val mime = Regex("^data:([^;]+);").find(url)?.groupValues?.get(1)
                        ?: "image/jpeg"
                    add(UiPart.File(mime, url))
                }
            },
        )
        messages = messages + userMsg
        val draft = StreamDraft()
        val draftId = "stream-${UUID.randomUUID()}"
        val agentMessage = planAgentMessage(planMode, text)
        if (planMode) planMode = false
        inputText = ""
        val sentImages = attachments.toList()
        attachments = emptyList()
        DraftBox.clear(session.id)
        streaming = true
        stopExternal()
        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            try {
                api.streamChat(
                    sessionId = session.id,
                    text = text,
                    messageId = UUID.randomUUID().toString(),
                    images = sentImages,
                    agentMessage = agentMessage,
                ).catch { e ->
                    error = (e as? com.xrbbm.pideck.core.net.ApiException)?.message ?: "$e"
                }.collect { frame ->
                    draft.applyFrame(frame)
                    val ui = draft.toUiMessage(draftId)
                    messages = messages.filter { it.id != draftId } + ui
                }
            } finally {
                streaming = false
                streamJob = null
            }
        }
        streamJob?.join()
    }

    /** Swipe-dismiss the approval sheet: hide locally, keep the request.
     * The 3s poll re-shows it while the request is still pending. */
    fun snoozeUi() {
        pendingUi = null
    }

    suspend fun respondUi(responseJson: String) {
        val req = pendingUi ?: return
        if (uiResponding) return
        uiResponding = true
        try {
            api.respondToUiJson(
                req.sessionId,
                req.requestId,
                req.agentId,
                req.runtimeGeneration,
                responseJson,
            )
            pollState(force = true)
        } catch (e: Exception) {
            val msg = (e as? com.xrbbm.pideck.core.net.ApiException)?.message ?: "$e"
            if (msg.contains("STALE_GENERATION") || msg.contains("generation")) {
                pollState(force = true)
                return
            }
            error = msg
        } finally {
            uiResponding = false
        }
    }

    suspend fun pickModel(models: List<AvailableModel>? = null) {
        // Model sheet UI lives in step 5; ViewModel exposes the mutation only.
    }

    suspend fun setModel(m: AvailableModel, level: String? = null) {
        val rt = runtime
        try {
            if (rt != null) {
                api.setRuntimeModel(
                    rt.sessionId, rt.agentId, rt.runtimeGeneration ?: 0,
                    m.provider, m.id, m.name,
                )
            } else {
                api.updateSessionRecord(
                    session.id,
                    modelProvider = m.provider,
                    modelId = m.id,
                    modelName = m.name,
                )
            }
            model = m
            if (level != null) setThinking(level)
        } catch (e: Exception) {
            error = (e as? com.xrbbm.pideck.core.net.ApiException)?.message ?: "$e"
        }
    }

    suspend fun setThinking(level: String) {
        val rt = runtime
        try {
            if (rt != null) {
                api.setRuntimeThinking(
                    rt.sessionId, rt.agentId, rt.runtimeGeneration ?: 0, level,
                )
            } else {
                api.updateSessionRecord(session.id, thinkingLevel = level)
            }
            thinkingLevel = level
        } catch (e: Exception) {
            error = (e as? com.xrbbm.pideck.core.net.ApiException)?.message ?: "$e"
        }
    }

    suspend fun resendFill(messageId: String) {
        val rt = runtime
        if (rt == null) {
            error = "会话进程未运行，无法操作该消息"
            return
        }
        try {
            val prepared = api.prepareResend(
                rt.sessionId, rt.agentId, rt.runtimeGeneration ?: 0, messageId,
            )
            inputText = prepared.text
            attachments = prepared.images.take(4)
            error = null
        } catch (e: Exception) {
            error = (e as? com.xrbbm.pideck.core.net.ApiException)?.message ?: "$e"
        }
    }

    suspend fun sessionCommand(action: String, prompt: String? = null): String? {
        val rt = runtime
        if (rt == null) {
            error = "会话进程未运行，无法操作该消息"
            return null
        }
        return try {
            when (action) {
                "restart" -> {
                    api.restartRuntime(rt.sessionId, rt.agentId, rt.runtimeGeneration ?: 0)
                    notice = System.currentTimeMillis() to "已发送重启"
                    null
                }
                "compact" -> {
                    api.compactRuntime(
                        rt.sessionId, rt.agentId, rt.runtimeGeneration ?: 0,
                        prompt?.ifEmpty { null },
                    )
                    loadFirst()
                    null
                }
                "clone" -> api.cloneRuntime(rt.sessionId, rt.agentId, rt.runtimeGeneration ?: 0)
                else -> null
            }
        } catch (e: Exception) {
            error = (e as? com.xrbbm.pideck.core.net.ApiException)?.message ?: "$e"
            null
        }
    }

    override fun onCleared() {
        saveDraft()
        pollJob?.cancel()
        streamJob?.cancel()
        externalJob?.cancel()
    }
}

/** Process-level draft box: unsent text survives session switches. */
object DraftBox {
    private val box = mutableMapOf<String, Pair<String, List<String>>>()

    fun text(sessionId: String): String = box[sessionId]?.first ?: ""
    fun images(sessionId: String): List<String> = box[sessionId]?.second ?: emptyList()

    fun save(sessionId: String, text: String, images: List<String>) {
        if (text.isEmpty() && images.isEmpty()) box.remove(sessionId)
        else box[sessionId] = text to images.toList()
    }

    fun clear(sessionId: String) {
        box.remove(sessionId)
    }
}
