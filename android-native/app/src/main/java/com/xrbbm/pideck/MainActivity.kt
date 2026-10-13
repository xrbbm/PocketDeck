package com.xrbbm.pideck

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import com.xrbbm.pideck.core.Pairing
import com.xrbbm.pideck.core.PairingStore
import com.xrbbm.pideck.ui.pair.PairScreen
import com.xrbbm.pideck.ui.theme.PiDeckTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Gate() }
    }
}

/**
 * Startup gate: no pairing -> scan page; paired -> placeholder shell
 * (chat shell lands in step 4; step 2 only proves scan + persist + route).
 */
@Composable
private fun Gate() {
    val context = LocalContext.current
    val store = remember { PairingStore(context) }
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var pairing by remember { mutableStateOf<Pairing?>(null) }
    var dark by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        pairing = store.load()
        dark = store.loadDarkTheme()
        loading = false
    }

    PiDeckTheme(dark = dark) {
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            pairing == null || pairing!!.baseUrl.isEmpty() -> PairScreen(
                onPaired = { baseUrl ->
                    scope.launch {
                        val next = Pairing(baseUrl = baseUrl)
                        store.save(next)
                        pairing = next
                    }
                },
            )
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "已配对 ${pairing!!.baseUrl}\n聊天壳在第 4 步接入",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
