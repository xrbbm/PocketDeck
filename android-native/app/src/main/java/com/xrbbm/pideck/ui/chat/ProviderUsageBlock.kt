package com.xrbbm.pideck.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xrbbm.pideck.core.net.ApiClient
import com.xrbbm.pideck.core.net.ProviderUsage
import com.xrbbm.pideck.ui.theme.AppIcons

/**
 * Provider quota block for the context sheet (web parity `ProviderUsageBlock`).
 * Renders nothing unless the provider has a working usage probe, which is the
 * common case: `/api/usage/:provider` answers `success=false` when off.
 */
@Composable
fun ProviderUsageBlock(
    api: ApiClient,
    provider: String?,
    backend: String,
    modifier: Modifier = Modifier,
) {
    if (provider.isNullOrEmpty()) return
    var usage by remember(provider, backend) { mutableStateOf<ProviderUsage?>(null) }
    var loading by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(provider, backend, refreshTick) {
        loading = true
        usage = runCatching { api.fetchProviderUsage(provider, backend) }.getOrNull()
        loading = false
    }

    val u = usage ?: return
    if (!u.success) return

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "用量",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                provider,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { refreshTick++ }, modifier = Modifier.size(24.dp)) {
                Icon(
                    AppIcons.Refresh,
                    contentDescription = "刷新用量",
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        if (loading && usage == null) {
            Text("加载中…", style = MaterialTheme.typography.bodySmall)
        }
        when (u.kind) {
            "periods" -> u.periods.forEach { (key, percent) ->
                UsageBarRow(periodLabel(key), percent, null)
            }
            "credits" -> u.windows.forEach { UsageBarRow(it.key, it.percent, it.remaining) }
            "balance" -> u.balance?.let { value ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("剩余额度", style = MaterialTheme.typography.bodySmall)
                    Text(
                        formatAmount(value),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (value <= 0) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun UsageBarRow(label: String, percent: Double?, remaining: Double?) {
    val value = (percent ?: 0.0).coerceIn(0.0, 100.0)
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            Text(
                "${value.toInt()}%" + (remaining?.takeIf { value < 90 }?.let { " · 剩${formatAmount(it)}" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(3.dp))
        LinearProgressIndicator(
            progress = { (value / 100).toFloat() },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp),
        )
    }
}

private fun periodLabel(key: String): String = when (key) {
    "rolling" -> "滚动窗口"
    "weekly" -> "本周"
    "monthly" -> "本月"
    else -> key
}

private fun formatAmount(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString()
    else String.format("%.2f", value)
