package com.financetracker.app.data.stores

import java.net.HttpURLConnection
import java.net.URL

/** Plain HTTP for the store connections, keeping the cookies a store sets along the way. */
internal class StoreHttp(private val userAgent: String = DEFAULT_AGENT) {
    data class Response(val status: Int, val body: String, val cookies: Map<String, String>) {
        val ok: Boolean get() = status in 200..299
    }

    /** Cookies sent with every request, updated from each response. */
    val cookies = linkedMapOf<String, String>()

    fun request(method: String, url: String, headers: Map<String, String> = emptyMap(), body: ByteArray? = null, contentType: String? = null): Response {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 20_000
            connection.readTimeout = 45_000
            connection.setRequestProperty("User-Agent", userAgent)
            if (cookies.isNotEmpty()) connection.setRequestProperty("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (body != null) {
                connection.doOutput = true
                contentType?.let { connection.setRequestProperty("Content-Type", it) }
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val set = mutableMapOf<String, String>()
            connection.headerFields.filterKeys { it.equals("Set-Cookie", ignoreCase = true) }.values.flatten().forEach { header ->
                val pair = header.substringBefore(';')
                val name = pair.substringBefore('=').trim()
                if (name.isNotEmpty()) set[name] = pair.substringAfter('=', "").trim()
            }
            cookies.putAll(set)
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return Response(status, text, set)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val DEFAULT_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Mobile Safari/537.36"

        /** "a=1; b=2" into a map. */
        fun parseCookies(header: String?): Map<String, String> =
            header.orEmpty().split(';').mapNotNull { part ->
                val name = part.substringBefore('=').trim()
                if (name.isEmpty() || !part.contains('=')) null else name to part.substringAfter('=').trim()
            }.toMap()
    }
}
