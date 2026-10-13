package com.xrbbm.pideck.core

/**
 * Pairing URL parser, ported 1:1 from Flutter PairingStore.parseBaseUrl.
 *
 * Accepts `http(s)://host:port` or bare `host[:port]` (defaults to http +
 * [DEFAULT_PORT]). Returns null for empty text, non-http(s) schemes, or
 * anything carrying path/query/fragment (a QR with token in path is a
 * mis-pair, fail loudly so the user re-scans).
 */
object PairingParser {
    const val DEFAULT_PORT = 8765

    fun parseBaseUrl(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val withScheme = if ("://" in text) text else "http://$text"
        val lower = withScheme.lowercase()
        val isHttp = lower.startsWith("http://")
        val isHttps = lower.startsWith("https://")
        if (!isHttp && !isHttps) return null
        val scheme = if (isHttps) "https" else "http"
        val rest = withScheme.substring(scheme.length + 3)
        if (rest.contains('/') || rest.contains('?') || rest.contains('#')) return null
        // java.net.URI.host keeps IPv6 brackets (Dart Uri.host strips them),
        // so only wrap when the host holds a bare colon without brackets.
        val uri = runCatching { java.net.URI("$scheme://$rest") }.getOrNull()
            ?: return null
        val host = uri.host ?: return null
        if (host.isEmpty()) return null
        val port = if (uri.port != -1) uri.port else DEFAULT_PORT
        val wrappedHost =
            if (':' in host && !(host.startsWith("[") && host.endsWith("]"))) {
                "[$host]"
            } else {
                host
            }
        return "$scheme://$wrappedHost:$port"
    }
}
