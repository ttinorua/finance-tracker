package com.financetracker.app.data.ai.chatgpt

import com.financetracker.app.data.ai.chat.AssistantPrefs
import com.financetracker.app.data.ai.AiJson
import com.financetracker.app.data.ai.AiRequestException
import com.financetracker.app.data.ai.ChatTurn
import com.financetracker.app.data.ai.InsightCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * ChatGPT on the person's own ChatGPT plan ([ChatGptAuth]), through OpenAI's Responses API, offering
 * the same operations as the other AIs. This route is a preview with its own rules: every request
 * is streamed and not stored (`store: false`, `stream: true`), the system prompt goes in
 * `instructions`, and settings such as temperature or a reply-length cap aren't accepted.
 * Docs: developers.openai.com/siwc/token-sharing-open-source/models-and-inference.
 */
/** A model the person's ChatGPT plan offers apps. */
data class ChatGptModel(val slug: String, val name: String)

internal object ChatGptService {

    private const val RESPONSES_URL = "${ChatGptAuth.RESOURCE}/responses"
    private const val MODELS_URL = "${ChatGptAuth.RESOURCE}/models"

    /** The model this account offers first, looked up once per app start. */
    @Volatile private var model: String? = null

    suspend fun ask(systemPrompt: String, userMessage: String, quick: Boolean): String = withContext(Dispatchers.IO) {
        textOf(send(request(systemPrompt, listOf(ChatTurn(isUser = true, text = userMessage)), quick)))
    }

    suspend fun generateInsights(systemPrompt: String, userMessage: String): List<InsightCard> = withContext(Dispatchers.IO) {
        val system = systemPrompt + "\n\n" + AiJson.INSIGHTS_INSTRUCTIONS
        AiJson.parseInsights(textOf(send(request(system, listOf(ChatTurn(isUser = true, text = userMessage)), quick = false))))
    }

    /** A Responses request body. Thinking is kept between tool steps by asking for it back in
     * encrypted form (nothing is stored at OpenAI on this route). */
    fun request(instructions: String, turns: List<ChatTurn>, quick: Boolean, model: String = model()): JSONObject {
        val input = JSONArray()
        turns.forEach { turn ->
            input.put(JSONObject().put("role", if (turn.isUser) "user" else "assistant").put("content", turn.text))
        }
        return JSONObject()
            .put("model", model)
            .put("instructions", instructions)
            .put("input", input)
            .put("store", false)
            .put("stream", true)
            .put("reasoning", JSONObject().put("effort", if (quick) "low" else "medium"))
            .put("include", JSONArray().put("reasoning.encrypted_content"))
    }

    /** Sends [body] and returns the finished response. Renews the sign-in once on 401, and drops a
     * setting this route says it doesn't support, then tries again. */
    fun send(body: JSONObject): JSONObject {
        var renewed = false
        val dropped = mutableSetOf<String>()
        while (true) {
            val outcome = post(ChatGptAuth.accessToken(forceRefresh = renewed), body)
            if (outcome is Outcome.Done) return outcome.response
            val failure = outcome as Outcome.Failure
            if (failure.status == 401 && !renewed) {
                renewed = true
                continue
            }
            val field = failure.param?.substringBefore('.')?.substringBefore('[')
            if (failure.code == "subscription_sharing_unsupported_capability" && field != null &&
                field in OPTIONAL_FIELDS && body.has(field) && dropped.add(field)
            ) {
                body.remove(field)
                if (field == "include") dropReasoningItems(body)
                continue
            }
            throw AiRequestException(failure.userMessage())
        }
    }

    /** Settings this app can do without if the route turns them down. */
    private val OPTIONAL_FIELDS = setOf("reasoning", "include")

    /** Without encrypted thinking, earlier thinking items can't be sent back. */
    private fun dropReasoningItems(body: JSONObject) {
        val input = body.optJSONArray("input") ?: return
        val kept = JSONArray()
        for (i in 0 until input.length()) {
            val item = input.optJSONObject(i)
            if (item?.optString("type") != "reasoning") kept.put(input.opt(i))
        }
        body.put("input", kept)
    }

    internal sealed interface Outcome {
        data class Done(val response: JSONObject) : Outcome
        data class Failure(val status: Int, val code: String?, val param: String?, val message: String) : Outcome {
            fun userMessage(): String = when (code) {
                "subscription_sharing_usage_limit_exceeded" ->
                    "Your ChatGPT plan's limit for this app is reached for now. See ChatGPT Settings > Usage (Manage usage)."
                "subscription_sharing_user_not_eligible" ->
                    "This ChatGPT account can't share its plan with apps (it may need a Go, Plus or Pro plan)."
                "subscription_sharing_usage_unavailable", "subscription_sharing_user_unavailable" ->
                    "ChatGPT couldn't check your plan's usage right now. Try again later."
                "subscription_sharing_invalid_user" ->
                    "ChatGPT didn't accept the sign-in. Connect again in Settings > AI advisor."
                else -> when (status) {
                    401 -> "ChatGPT didn't accept the sign-in. Connect again in Settings > AI advisor."
                    403 -> "ChatGPT turned the request down: ${message.take(160)}"
                    503 -> "ChatGPT is unavailable right now. Try again later."
                    else -> "ChatGPT request failed${if (status > 0) " ($status)" else ""}: ${message.take(200)}"
                }
            }
        }
    }

    private fun post(token: String, body: JSONObject): Outcome {
        val connection = URL(RESPONSES_URL).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 20_000
            connection.readTimeout = 120_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "text/event-stream")
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) {
                val text = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                return errorOutcome(status, text)
            }
            return connection.inputStream.bufferedReader(Charsets.UTF_8).useLines { parseStream(it) }
        } finally {
            connection.disconnect()
        }
    }

    /** An error before the stream started: either `{"error": {...}}` or `{"detail": "..."}`. */
    internal fun errorOutcome(status: Int, text: String): Outcome.Failure {
        val json = runCatching { JSONObject(text) }.getOrNull()
        val error = json?.optJSONObject("error")
        return Outcome.Failure(
            status = status,
            code = error?.optString("code")?.ifBlank { null },
            param = error?.optString("param")?.ifBlank { null },
            message = error?.optString("message")?.ifBlank { null }
                ?: json?.optString("detail")?.ifBlank { null }
                ?: text.take(200)
        )
    }

    /** Reads the server-sent events until the response finishes. Only `response.completed` counts
     * as success: a usage limit can still stop the answer part-way. */
    internal fun parseStream(lines: Sequence<String>): Outcome {
        for (line in lines) {
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data.isEmpty() || data == "[DONE]") continue
            val event = runCatching { JSONObject(data) }.getOrNull() ?: continue
            when (event.optString("type")) {
                "response.completed" -> return Outcome.Done(event.optJSONObject("response") ?: JSONObject())
                "response.failed" -> {
                    val error = event.optJSONObject("response")?.optJSONObject("error")
                    return Outcome.Failure(
                        0, error?.optString("code")?.ifBlank { null }, null,
                        error?.optString("message")?.ifBlank { null } ?: "the answer failed"
                    )
                }
                "response.incomplete" -> {
                    val reason = event.optJSONObject("response")?.optJSONObject("incomplete_details")?.optString("reason")
                    return Outcome.Failure(0, null, null, "the answer stopped early (${reason ?: "unknown reason"})")
                }
                "error" -> {
                    val error = event.optJSONObject("error") ?: event
                    return Outcome.Failure(
                        0, error.optString("code").ifBlank { null }, error.optString("param").ifBlank { null },
                        error.optString("message").ifBlank { "the answer failed" }
                    )
                }
            }
        }
        return Outcome.Failure(0, null, null, "the answer was cut off")
    }

    /** All text the response's messages contain. */
    internal fun textOf(response: JSONObject): String {
        val output = response.optJSONArray("output") ?: return ""
        val texts = mutableListOf<String>()
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            if (item.optString("type") != "message") continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val part = content.optJSONObject(j) ?: continue
                if (part.optString("type") == "output_text") texts += part.optString("text")
            }
        }
        return texts.joinToString("").trim()
    }

    /** The model to use: the one picked in the AI advisor, else the one this account offers first. */
    private fun model(): String {
        AssistantPrefs.chatGptModel.value?.let { return it }
        model?.let { return it }
        val picked = pickModel(modelsJson()) ?: throw AiRequestException("This ChatGPT account doesn't offer a model for apps.")
        model = picked
        return picked
    }

    /** The models this account offers apps, in the server's preferred order (the first is what
     * Auto uses). */
    suspend fun availableModels(): List<ChatGptModel> = withContext(Dispatchers.IO) { listModels(modelsJson()) }

    private fun modelsJson(): JSONObject {
        val connection = URL(MODELS_URL).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Authorization", "Bearer ${ChatGptAuth.accessToken()}")
            val status = connection.responseCode
            if (status !in 200..299) {
                val text = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                throw AiRequestException(errorOutcome(status, text).userMessage())
            }
            return JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    /** Every model meant to be shown, with its display name. */
    internal fun listModels(json: JSONObject): List<ChatGptModel> {
        val models = json.optJSONArray("models") ?: json.optJSONArray("data") ?: return emptyList()
        val all = (0 until models.length()).mapNotNull { models.optJSONObject(it) }
        val listed = all.filter { it.optString("visibility") == "list" }.ifEmpty { all }
        return listed.mapNotNull { o ->
            val slug = o.optString("slug").ifBlank { o.optString("id") }.ifBlank { return@mapNotNull null }
            ChatGptModel(slug, o.optString("display_name").ifBlank { slug })
        }
    }

    /** The first model meant to be shown (the server lists them in its preferred order). */
    internal fun pickModel(json: JSONObject): String? {
        val models = json.optJSONArray("models") ?: json.optJSONArray("data") ?: return null
        val all = (0 until models.length()).mapNotNull { models.optJSONObject(it) }
        val listed = all.firstOrNull { it.optString("visibility") == "list" } ?: all.firstOrNull()
        return listed?.let { it.optString("slug").ifBlank { it.optString("id") } }?.ifBlank { null }
    }
}
