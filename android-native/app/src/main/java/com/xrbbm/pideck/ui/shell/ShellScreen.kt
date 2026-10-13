package com.xrbbm.pideck.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.xrbbm.pideck.core.Pairing
import com.xrbbm.pideck.core.net.ApiClient
import com.xrbbm.pideck.core.net.WebSession
import com.xrbbm.pideck.core.net.WebState
import com.xrbbm.pideck.ui.chat.ChatScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Drawer shell (step 4 minimal): polls /api/state every 5s, cold-starts
 * straight into the last chat. Full drawer UI lands in step 6.
 */
@Composable
fun ShellScreen(
    api: ApiClient,
    pairing: Pairing,
    isDark: Boolean,
    onToggleTheme: () -> Unit,
    onUnpair: () -> Unit,
) {
    var state by remember { mutableStateOf<WebState?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var entered by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun refresh() {
        try {
            val s = api.fetchState()
            state = s
            error = null
            loading = false
            if (!entered) {
                entered = true
                val saved = null as String?
                val target = s.sessions.firstOrNull { it.id == saved }
                    ?: s.sessions.firstOrNull()
                selectedId = target?.id
            } else if (selectedId != null && s.sessions.none { it.id == selectedId }) {
                selectedId = null
            }
        } catch (e: Exception) {
            error = (e as? com.xrbbm.pideck.core.net.ApiException)?.message ?: "$e"
            loading = false
        }
    }

    LaunchedEffect(pairing.baseUrl) {
        refresh()
        while (isActive) {
            delay(5000)
            refresh()
        }
    }

    val session: WebSession? = state?.sessions?.firstOrNull { it.id == selectedId }
    when {
        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        session != null -> ChatScreen(
            api = api,
            session = session,
            onOpenDrawer = {},
        )
        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(error ?: "暂无会话，去抽屉选一个（抽屉在第 6 步）")
        }
    }
}
