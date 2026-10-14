package com.xrbbm.pideck.ui.chat

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Install sheet: native APK is already installed (no server round trip).
 * Shows version + package, offers share/export entry via system share.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstallAppSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val info = remember { appVersion(context) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(20.dp)) {
            Text("安装应用", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "当前版本 ${info.first}（${info.second}）\n包名 ${context.packageName}\n已通过 APK 安装到本机，无需重复安装。",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(
                onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(
                            Intent.EXTRA_TEXT,
                            "PiDeck Android 原生版 ${info.first}（${context.packageName}）",
                        )
                    }
                    context.startActivity(Intent.createChooser(send, "分享应用信息"))
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("分享应用信息")
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("关闭")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun appVersion(context: Context): Pair<String, Long> = try {
    val pm = context.packageManager
    val pi = if (Build.VERSION.SDK_INT >= 33) {
        pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(context.packageName, 0)
    }
    val code = if (Build.VERSION.SDK_INT >= 28) {
        pi.longVersionCode
    } else {
        @Suppress("DEPRECATION")
        pi.versionCode.toLong()
    }
    (pi.versionName ?: "?") to code
} catch (_: Exception) {
    "?" to 0L
}
