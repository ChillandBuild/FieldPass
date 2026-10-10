package com.fieldpass.student.data.settings

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Normalises a configured server address before it is used by the API client. */
object BaseUrl {
    /**
     * Returns "scheme://host[:port]" with no trailing slash or path, or null if not usable.
     * A bare "192.168.43.10:8000" is read as http. Only http and https are accepted.
     */
    fun normalize(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        val withScheme = if (trimmed.contains("://")) trimmed else "http://$trimmed"
        val url = withScheme.toHttpUrlOrNull() ?: return null
        val defaultPort = if (url.scheme == "https") 443 else 80
        val port = if (url.port == defaultPort) "" else ":${url.port}"
        val host = if (url.host.contains(':')) "[${url.host}]" else url.host
        return "${url.scheme}://$host$port"
    }
}
