package com.financetracker.app.data.ai.chatgpt

import com.financetracker.app.data.ai.chat.AssistantPrefs
import com.financetracker.app.data.ai.AiJson
import com.financetracker.app.data.ai.AiRequestException
import com.financetracker.app.data.ai.ChatTurn
import com.financetracker.app.data.ai.InsightCard
import com.financetracker.app.data.ai.WebSource
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
            if (refusesWebSearch(failure) && dropWebSearch(body)) continue
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

    /** Whether the route turned down the web_search tool (not allowed for this plan or model). */
    internal fun refusesWebSearch(failure: Outcome.Failure): Boolean {
        if (failure.status == 401 || failure.status >= 500) return false
        val text = (failure.message + " " + failure.code.orEmpty()).lowercase()
        return failure.param?.startsWith("tools") == true || "web_search" in text || "web search" in text
    }

    /** Takes the web_search tool (and any searches already in the exchange) out of [body] and
     * tells the AI it couldn't search. False if there was nothing to take out. */
    internal fun dropWebSearch(body: JSONObject): Boolean {
        val tools = body.optJSONArray("tools") ?: return false
        val kept = JSONArray()
        for (i in 0 until tools.length()) {
            val tool = tools.optJSONObject(i)
            if (tool?.optString("type") != "web_search") kept.put(tools.opt(i))
        }
        if (kept.length() == tools.length()) return false
        body.put("tools", kept)
        body.optJSONArray("input")?.let { input ->
            val items = JSONArray()
            for (i in 0 until input.length()) {
                if (input.optJSONObject(i)?.optString("type") != "web_search_call") items.put(input.opt(i))
            }
            body.put("input", items)
        }
        body.put("instructions", body.optString("instructions") + "\n\n" + NO_WEB_SEARCH)
        return true
    }

    private const val NO_WEB_SEARCH =
        "Web search isn't available on the user's ChatGPT plan or model right now (ignore the web search notes above). " +
            "If the question needs current information from the web, start your answer by saying you couldn't search " +
            "online with their ChatGPT plan right now, then help with what the app's data shows."

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
        // The finished items as they stream in. On this route (nothing stored at OpenAI) the final
        // response.completed can carry an empty "output", so the answer is rebuilt from these.
        val items = sortedMapOf<Int, JSONObject>()
        val text = StringBuilder()
        for (line in lines) {
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data.isEmpty() || data == "[DONE]") continue
            val event = runCatching { JSONObject(data) }.getOrNull() ?: continue
            when (event.optString("type")) {
                "response.output_item.done" -> event.optJSONObject("item")?.let { items[event.optInt("output_index", items.size)] = it }
                "response.output_text.delta" -> text.append(event.optString("delta"))
                "response.completed" -> {
                    val response = event.optJSONObject("response") ?: JSONObject()
                    if ((response.optJSONArray("output")?.length() ?: 0) == 0) {
                        val output = JSONArray()
                        items.values.forEach { output.put(it) }
                        if (items.values.none { it.optString("type") == "message" } && text.isNotBlank()) {
                            output.put(
                                JSONObject().put("type", "message").put("role", "assistant").put(
                                    "content", JSONArray().put(JSONObject().put("type", "output_text").put("text", text.toString()))
                                )
                            )
                        }
                        response.put("output", output)
                    }
                    return Outcome.Done(response)
                }
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

    /** The web pages the answer cites (from web search), each once, in the order cited. */
    internal fun sourcesOf(response: JSONObject): List<WebSource> {
        val output = response.optJSONArray("output") ?: return emptyList()
        val found = LinkedHashMap<String, WebSource>()
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            if (item.optString("type") != "message") continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val annotations = content.optJSONObject(j)?.optJSONArray("annotations") ?: continue
                for (k in 0 until annotations.length()) {
                    val a = annotations.optJSONObject(k) ?: continue
                    if (a.optString("type") != "url_citation") continue
                    val url = cleanUrl(a.optString("url"))
                    if (!url.startsWith("http")) continue
                    found.putIfAbsent(url, WebSource(a.optString("title").trim(), url))
                }
            }
        }
        return found.values.toList()
    }

    /** Drops the "?utm_source=openai" OpenAI adds to cited links. */
    internal fun cleanUrl(url: String): String = url.trim()
        .replace(Regex("""([?&])utm_source=openai(&|$)""")) { m -> if (m.groupValues[2] == "&") m.groupValues[1] else "" }

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
