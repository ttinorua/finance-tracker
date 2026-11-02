package com.financetracker.app.data.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val GEMINI_MODEL = "gemini-3.8-flash"
private const val GEMINI_URL =
    "https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL:generateContent"

/** Gemini counts its thinking toward maxOutputTokens, so each caller's reply budget gets this much
 * on top. */
private const val GEMINI_THINKING_HEADROOM = 4_000

/**
 * Google Gemini over its REST API (platform HttpURLConnection + org.json, no SDK), offering the
 * same operations as [ClaudeService]: a plain question, the dashboard insight cards (as
 * schema-constrained JSON). The AI advisor's function calling lives in
 * [com.financetracker.app.data.ai.agent.GeminiAgentSession].
 */
internal object GeminiService {

    suspend fun ask(key: String, systemPrompt: String, userMessage: String, maxTokens: Int, quick: Boolean): String =
        withContext(Dispatchers.IO) {
            val body = request(systemPrompt, listOf(ChatTurn(isUser = true, text = userMessage)), maxTokens, quick)
            textOf(send(key, body))
        }

    suspend fun generateInsights(key: String, systemPrompt: String, userMessage: String, maxTokens: Int): List<InsightCard> =
        withContext(Dispatchers.IO) {
            val body = request(systemPrompt, listOf(ChatTurn(isUser = true, text = userMessage)), maxTokens, quick = false)
            body.getJSONObject("generationConfig")
                .put("responseMimeType", "application/json")
                .put("responseJsonSchema", AiJson.insightsSchema())
            AiJson.parseInsights(textOf(send(key, body)))
        }

    fun request(system: String, turns: List<ChatTurn>, maxTokens: Int, quick: Boolean): JSONObject {
        val contents = JSONArray()
        turns.forEach { turn ->
            contents.put(
                JSONObject()
                    .put("role", if (turn.isUser) "user" else "model")
                    .put("parts", JSONArray().put(JSONObject().put("text", turn.text)))
            )
        }
        return JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", contents)
            .put(
                "generationConfig",
                JSONObject()
                    .put("maxOutputTokens", maxTokens + GEMINI_THINKING_HEADROOM)
                    .put("thinkingConfig", JSONObject().put("thinkingLevel", if (quick) "low" else "medium"))
            )
    }

    /** Sends [body]; if Gemini rejects an optional setting (400), retries once without the
     * thinking and schema settings rather than failing outright. */
    fun send(key: String, body: JSONObject, readTimeoutMs: Int = 90_000): JSONObject {
        var first = post(key, body, readTimeoutMs)
        // "High demand" is usually over within seconds.
        if (first.first == 503) {
            Thread.sleep(2_000)
            first = post(key, body, readTimeoutMs)
        }
        val response = if (first.first == 400) {
            val config = body.getJSONObject("generationConfig")
            config.remove("thinkingConfig")
            config.remove("responseJsonSchema")
            post(key, body, readTimeoutMs)
        } else {
            first
        }
        val (status, text) = response
        val json = runCatching { JSONObject(text) }.getOrNull()
        if (status !in 200..299) {
            val message = json?.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() } ?: text.take(200)
            throw AiRequestException(
                when (status) {
                    429 -> "Gemini's free limit is used up for now. Try again in a minute, or tomorrow."
                    400, 401, 403 -> "Gemini rejected the request ($status): $message"
                    else -> "Gemini request failed ($status): $message"
                }
            )
        }
        json ?: throw AiRequestException("Gemini sent an unreadable reply.")
        json.optJSONObject("promptFeedback")?.optString("blockReason")?.takeIf { it.isNotBlank() }?.let {
            throw AiRequestException("Gemini declined this request ($it). Try rephrasing it.")
        }
        return json
    }

    private fun post(key: String, body: JSONObject, readTimeoutMs: Int): Pair<Int, String> {
        val connection = URL(GEMINI_URL).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 20_000
            connection.readTimeout = readTimeoutMs
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("x-goog-api-key", key)
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            return status to (stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "")
        } finally {
            connection.disconnect()
        }
    }

    fun parts(response: JSONObject): List<JSONObject> {
        val parts = response.optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts") ?: return emptyList()
        return (0 until parts.length()).mapNotNull { parts.optJSONObject(it) }
    }

    /** The answer's text, leaving out the model's own thought summaries. */
    private fun textOf(response: JSONObject): String =
        parts(response).filter { !it.optBoolean("thought") }.joinToString("") { it.optString("text") }.trim()
}
