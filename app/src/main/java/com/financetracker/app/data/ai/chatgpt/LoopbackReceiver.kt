package com.financetracker.app.data.ai.chatgpt

import java.io.Closeable
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URLDecoder

/**
 * A tiny web server inside the app, on 127.0.0.1 (reachable only from this phone), that catches
 * the browser's return from the ChatGPT sign-in page — OpenAI only sends sign-ins back to such a
 * local address. It answers one request to /auth/callback and is closed right after.
 */
internal class LoopbackReceiver private constructor(private val server: ServerSocket) : Closeable {

    val port: Int get() = server.localPort

    /** Waits up to [timeoutMs] for the browser to open /auth/callback, shows it [page] (built from
     * the returned parameters) and returns those parameters. Other paths get a 404. */
    fun awaitCallback(timeoutMs: Int, page: (Map<String, String>) -> String): Map<String, String> {
        server.soTimeout = timeoutMs
        while (true) {
            server.accept().use { socket ->
                socket.soTimeout = 10_000
                val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val requestLine = reader.readLine().orEmpty()
                // Read the headers to the empty line so the browser isn't cut off mid-request.
                while (reader.readLine()?.isNotEmpty() == true) Unit
                val target = requestLine.split(' ').getOrNull(1).orEmpty()
                val out = socket.getOutputStream()
                if (target.substringBefore('?') != CALLBACK_PATH) {
                    out.write(response(404, "Not found").toByteArray(Charsets.UTF_8))
                    out.flush()
                    return@use
                }
                val params = parseQuery(target.substringAfter('?', ""))
                out.write(response(200, page(params)).toByteArray(Charsets.UTF_8))
                out.flush()
                return params
            }
        }
    }

    override fun close() {
        runCatching { server.close() }
    }

    companion object {
        const val CALLBACK_PATH = "/auth/callback"

        /** Listens on [preferredPort], or on any free port if that one is taken. */
        fun open(preferredPort: Int): LoopbackReceiver {
            val loopback = InetAddress.getByName("127.0.0.1")
            fun bind(port: Int) = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(loopback, port), 4)
            }
            val server = try {
                bind(preferredPort)
            } catch (e: BindException) {
                bind(0)
            }
            return LoopbackReceiver(server)
        }

        internal fun parseQuery(query: String): Map<String, String> =
            query.split('&').filter { it.isNotEmpty() }.associate { pair ->
                val name = pair.substringBefore('=')
                val value = pair.substringAfter('=', "")
                URLDecoder.decode(name, "UTF-8") to URLDecoder.decode(value, "UTF-8")
            }

        private fun response(status: Int, html: String): String {
            val body = html.toByteArray(Charsets.UTF_8)
            val reason = if (status == 200) "OK" else "Not Found"
            return "HTTP/1.1 $status $reason\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n\r\n" + html
        }
    }
}
