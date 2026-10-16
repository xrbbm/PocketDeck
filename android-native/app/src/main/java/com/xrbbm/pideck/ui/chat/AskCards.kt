package com.xrbbm.pideck.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xrbbm.pideck.core.net.WebBatchQuestion
import com.xrbbm.pideck.core.net.WebPendingUiRequest
import com.xrbbm.pideck.core.net.batchAnswerLabel
import com.xrbbm.pideck.core.net.splitAskOption
import com.xrbbm.pideck.ui.theme.AppIcons
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Approval card (mirrors WebAskCard): single select/confirm/input + batch
 * envelope + cancel. Selecting an option only selects; submit sends.
 * Last-touched wins (option tap sends only the option, typing sends only
 * the custom text — never concatenated). Cancel + Submit share one row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AskCard(
    request: WebPendingUiRequest,
    busy: Boolean,
    onRespond: (String) -> Unit,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
) {
    // Rebuild selection state per request (mirrors Flutter per-request reset).
    // Sheet form: options scroll, submit row stays visible, keyboard clears.
    key(request.requestId) {
        if (onDismiss != null) {
            ModalBottomSheet(onDismissRequest = onDismiss) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    AskCardHeader()
                    Spacer(Modifier.height(8.dp))
                    if (request.isBatch) BatchBody(request, busy, onRespond)
                    else SingleBody(request, busy, onRespond)
                    Spacer(Modifier.height(8.dp))
                }
            }
        } else {
        androidx.compose.material3.ElevatedCard(
            shape = RoundedCornerShape(20.dp),
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Column(Modifier.padding(16.dp)) {
                AskCardHeader()
                Spacer(Modifier.height(8.dp))
                if (request.isBatch) BatchBody(request, busy, onRespond)
                else SingleBody(request, busy, onRespond)
            }
        }
        }
    }
}

@Composable
private fun AskCardHeader() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = androidx.compose.foundation.shape.CircleShape,
            color = MaterialTheme.colorScheme.tertiaryContainer,
            modifier = Modifier.size(32.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    AppIcons.Hand,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            "需要你确认",
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun SingleBody(
    req: WebPendingUiRequest,
    busy: Boolean,
    onRespond: (String) -> Unit,
) {
    // ✎-leading options are desktop gesture items; filter like web.
    val options = (req.options ?: emptyList()).filter { !it.startsWith("✎") }
    var selected by remember { mutableStateOf<String?>(null) }
    var custom by remember { mutableStateOf(req.prefill ?: "") }
    var customActive by remember { mutableStateOf(!req.prefill.isNullOrEmpty()) }
    var draft by remember { mutableStateOf(req.prefill ?: "") }

    fun singleSubmit(): String? {
        if (busy) return null
        if (customActive && custom.trim().isNotEmpty()) {
            return responseJson(mapOf("value" to custom.trim()))
        }
        val sel = selected
        if (sel == null || sel.trim().isEmpty()) return null
        return responseJson(mapOf("value" to sel))
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("需要你确认", style = MaterialTheme.typography.titleSmall)
        Text(req.title.ifEmpty { "请作答" }, style = MaterialTheme.typography.bodyMedium)
        if (req.method == "select" && options.isNotEmpty()) {
            options.forEach { option ->
                val split = splitAskOption(option)
                OptionButton(
                    label = split.label,
                    description = split.description,
                    selected = selected == option,
                    busy = busy,
                    onTap = {
                        selected = option
                        customActive = false
                    },
                )
            }
            if (req.allowOther != false) {
                OutlinedTextField(
                    value = custom,
                    onValueChange = {
                        custom = it
                        customActive = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(req.placeholder ?: "自定义回答") },
                    minLines = 1,
                    maxLines = 3,
                    enabled = !busy,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = { onRespond(responseJson(mapOf("cancelled" to true))) },
                    enabled = !busy,
                ) { Text("取消") }
                Spacer(Modifier.padding(start = 8.dp))
                Button(
                    onClick = { singleSubmit()?.let(onRespond) },
                    enabled = singleSubmit() != null,
                ) { Text("提交") }
            }
        } else if (req.method == "confirm") {
            Row {
                Button(
                    onClick = { onRespond(responseJson(mapOf("confirmed" to true))) },
                    enabled = !busy,
                ) { Text("是") }
                Spacer(Modifier.padding(start = 8.dp))
                OutlinedButton(
                    onClick = { onRespond(responseJson(mapOf("confirmed" to false))) },
                    enabled = !busy,
                ) { Text("否") }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = { onRespond(responseJson(mapOf("cancelled" to true))) },
                    enabled = !busy,
                ) { Text("取消") }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(req.placeholder ?: "输入回答") },
                    minLines = 1,
                    maxLines = 4,
                    enabled = !busy,
                )
                Spacer(Modifier.padding(start = 8.dp))
                Button(
                    onClick = { onRespond(responseJson(mapOf("value" to draft.trim()))) },
                    enabled = !busy && draft.trim().isNotEmpty(),
                ) { Text("提交") }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = { onRespond(responseJson(mapOf("cancelled" to true))) },
                    enabled = !busy,
                ) { Text("取消") }
            }
        }
    }
}

@Composable
private fun BatchBody(
    req: WebPendingUiRequest,
    busy: Boolean,
    onRespond: (String) -> Unit,
) {
    val questions = req.batchQuestions
    var tab by remember { mutableStateOf(0) }
    val answers = remember { mutableStateMapOf<String, String>() }
    var custom by remember { mutableStateOf("") }
    var customActive by remember { mutableStateOf(false) }
    val current = questions[tab.coerceIn(0, questions.size - 1)]
    val isLast = tab >= questions.size - 1
    val currentAns = answers[current.id]

    // Prefill once per tab (mirrors Flutter didUpdateWidget prefill).
    val tabPre = current.prefill ?: ""
    if (tabPre.isNotEmpty() && custom.isEmpty()) {
        custom = tabPre
        customActive = true
    }

    fun answerOne(q: WebBatchQuestion, value: String) {
        answers[q.id] = value
        if (tab >= questions.size - 1) {
            onRespond(serializeBatch(questions, answers))
        } else {
            tab++
            custom = ""
            customActive = false
        }
    }

    fun selectSubmit(): (() -> Unit)? {
        if (busy) return null
        val text = custom.trim()
        val value = if (customActive && text.isNotEmpty()) text else currentAns
        if (value == null) return null
        return { answerOne(current, value) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "需要你确认",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Text("${tab + 1}/${questions.size}", style = MaterialTheme.typography.labelSmall)
        }
        Text(current.question, style = MaterialTheme.typography.bodyMedium)
        if (current.type == "select" && current.options.isNotEmpty()) {
            current.options.forEach { opt ->
                OptionButton(
                    label = opt.label,
                    description = opt.description,
                    selected = currentAns == (opt.value ?: opt.label),
                    busy = busy,
                    onTap = {
                        answers[current.id] = opt.value ?: opt.label
                        customActive = false
                    },
                )
            }
            if (current.allowOther != false) {
                OutlinedTextField(
                    value = custom,
                    onValueChange = {
                        custom = it
                        customActive = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(current.placeholder ?: "自定义回答") },
                    minLines = 1,
                    maxLines = 3,
                    enabled = !busy,
                )
            }
        } else if (current.type == "multi_select" && current.options.isNotEmpty()) {
            current.options.forEach { opt ->
                val v = opt.value ?: opt.label
                val selected = currentAns
                    ?.split("、")?.contains(v) == true
                OptionButton(
                    label = opt.label,
                    description = opt.description,
                    selected = selected,
                    busy = busy,
                    onTap = {
                        val arr = (currentAns?.split("、")
                            ?.filter { it.isNotEmpty() }?.toMutableList()
                            ?: mutableListOf())
                        if (arr.contains(v)) arr.remove(v) else arr.add(v)
                        answers[current.id] = arr.joinToString("、")
                    },
                )
            }
        } else if (current.type == "confirm") {
            Row {
                Button(
                    onClick = { answerOne(current, "true") },
                    enabled = !busy,
                ) { Text("是") }
                Spacer(Modifier.padding(start = 8.dp))
                OutlinedButton(
                    onClick = { answerOne(current, "false") },
                    enabled = !busy,
                ) { Text("否") }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(current.placeholder ?: "输入回答") },
                    minLines = 1,
                    maxLines = 4,
                    enabled = !busy,
                )
                Spacer(Modifier.padding(start = 8.dp))
                Button(
                    onClick = { answerOne(current, custom.trim()) },
                    enabled = !busy && custom.trim().isNotEmpty(),
                ) { Text(if (isLast) "提交" else "下一题") }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = { onRespond(responseJson(mapOf("cancelled" to true))) },
                enabled = !busy,
            ) { Text("取消") }
            if (current.type == "select" && current.options.isNotEmpty()) {
                Spacer(Modifier.padding(start = 8.dp))
                val submit = selectSubmit()
                Button(
                    onClick = { submit?.invoke() },
                    enabled = submit != null,
                ) { Text(if (isLast) "提交" else "下一题") }
            } else if (current.type == "multi_select" && current.options.isNotEmpty()) {
                Spacer(Modifier.padding(start = 8.dp))
                Button(
                    onClick = { currentAns?.let { answerOne(current, it) } },
                    enabled = !busy && !currentAns.isNullOrEmpty(),
                ) { Text(if (isLast) "提交" else "下一题") }
            }
        }
    }
}

@Composable
private fun OptionButton(
    label: String,
    description: String?,
    selected: Boolean,
    busy: Boolean,
    onTap: () -> Unit,
) {
    val content: @Composable () -> Unit = {
        Column(
            modifier = Modifier.padding(vertical = 6.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(label)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (selected) {
        FilledTonalButton(
            onClick = onTap,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { content() }
    } else {
        OutlinedButton(
            onClick = onTap,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { content() }
    }
    Spacer(Modifier.height(2.dp))
}

private fun responseJson(fields: Map<String, Any>): String = buildJsonObject {
    fields.forEach { (k, v) ->
        when (v) {
            is String -> put(k, v)
            is Boolean -> put(k, v)
            else -> put(k, "$v")
        }
    }
}.toString()

private fun serializeBatch(
    questions: List<WebBatchQuestion>,
    answers: Map<String, String>,
): String {
    val arr = JsonArray(
        questions.map { q ->
            val v = answers[q.id]
            buildJsonObject {
                put("id", q.id)
                put("type", q.type)
                if (v == null) put("value", JsonNull)
                else put("value", v)
                put("label", v ?: "")
                put("wasCustom", false)
            }
        },
    )
    @Suppress("UNUSED")
    val labelOf: (String?) -> String = { batchAnswerLabel(it?.let(::JsonPrimitive)) };
    return buildJsonObject { put("answers", arr) }.toString()
}
