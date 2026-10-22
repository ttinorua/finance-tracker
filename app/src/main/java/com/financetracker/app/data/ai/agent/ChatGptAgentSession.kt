package com.financetracker.app.data.ai.agent

import com.financetracker.app.data.ai.ChatAttachment
import com.financetracker.app.data.ai.ChatTurn
import com.financetracker.app.data.ai.chatgpt.ChatGptService
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ask AI on the person's ChatGPT plan. Nothing is stored at OpenAI on this route, so every step
 * sends the whole exchange again: the earlier output items (with their encrypted thinking) followed
 * by the function results. The plan route wants function tools grouped in a namespace.
 *
 * Only here can the advisor search the web (OpenAI's own web_search tool). If the plan or model
 * doesn't allow it, [ChatGptService.send] drops the tool and tells the AI to say so.
 */
internal class ChatGptAgentSession(
    system: String,
    history: List<ChatTurn>,
    userMessage: String,
    tools: List<ToolSpec>,
    attachment: ChatAttachment? = null
) : AgentSession {

    private val body: JSONObject = ChatGptService.request(
        system + "\n\n" + WEB_SEARCH_INSTRUCTIONS, history + ChatTurn(isUser = true, text = userMessage), quick = false
    ).put("tools", toolsJson(tools).put(JSONObject().put("type", "web_search"))).also { body ->
        if (attachment != null) withAttachment(body.getJSONArray("input"), attachment)
    }

    override fun step(): AgentStep {
        val response = ChatGptService.send(body)
        val input = body.getJSONArray("input")
        val keepThinking = body.has("include")
        val calls = mutableListOf<ToolCall>()
        var searched = false
        val output = response.optJSONArray("output") ?: JSONArray()
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            when (item.optString("type")) {
                "reasoning" -> if (keepThinking) input.put(item)
                "function_call" -> {
                    val args = runCatching { JSONObject(item.optString("arguments").ifBlank { "{}" }) }.getOrElse { JSONObject() }
                    calls += ToolCall(item.optString("call_id"), item.optString("name"), args)
                    input.put(echo(item))
                }
                "web_search_call" -> {
                    searched = true
                    input.put(JSONObject(item.toString()).apply { remove("id") })
                }
                "message" -> input.put(echo(item))
            }
        }
        return AgentStep(ChatGptService.textOf(response), calls, ChatGptService.sourcesOf(response), searched)
    }

    override fun addToolResults(results: List<Pair<ToolCall, String>>) {
        val input = body.getJSONArray("input")
        results.forEach { (call, result) ->
            input.put(JSONObject().put("type", "function_call_output").put("call_id", call.id).put("output", result))
        }
    }

    companion object {
        private const val NAMESPACE = "finance_app"

        internal val WEB_SEARCH_INSTRUCTIONS = """
            Web search: you can search the web (web_search) for current information on any subject — e.g. phone, internet, TV and streaming packages, electricity, insurance, loan and savings rates, product prices, rules and fees, news. Search when the answer depends on current facts from outside the app; don't search for what your functions can answer from the user's own data.
            - When comparing offers with what the user pays: first get their real costs with your functions, then compare like for like (same number of phone lines, similar internet speed, the same streaming services). Include multi-line or family discounts, intro prices that go up later, setup fees and binding periods. Give the saving per month and per year after fees, best option first, and what to check before switching.
            - If you don't know what a bill covers (e.g. one monthly payment to a phone company), ask the user or suggest attaching the bill (photo or PDF) — don't guess. Use what they asked you to remember.
            - Say prices are from the companies' websites today and can change. Cite your sources; the app lists them under your answer.
        """.trimIndent()

        internal fun toolsJson(tools: List<ToolSpec>): JSONArray {
            val functions = JSONArray()
            tools.forEach {
                functions.put(
                    JSONObject().put("type", "function").put("name", it.name)
                        .put("description", it.description).put("parameters", it.parameters)
                )
            }
            return JSONArray().put(
                JSONObject().put("type", "namespace").put("name", NAMESPACE)
                    .put("description", "The Finance Tracker app's own functions for the person's money data.")
                    .put("tools", functions)
            )
        }

        /** The question's text plus the attached photo or PDF, as one message. */
        internal fun withAttachment(input: JSONArray, attachment: ChatAttachment) {
            val question = input.getJSONObject(input.length() - 1)
            val file = if (attachment.isPdf) {
                JSONObject().put("type", "input_file").put("filename", attachment.name)
                    .put("file_data", "data:application/pdf;base64,${attachment.base64}")
            } else {
                JSONObject().put("type", "input_image").put("image_url", "data:${attachment.mimeType};base64,${attachment.base64}")
            }
            question.put(
                "content",
                JSONArray().put(file).put(JSONObject().put("type", "input_text").put("text", question.optString("content")))
            )
        }

        /** An output item sent back as input: without its server id, which isn't kept on this route. */
        private fun echo(item: JSONObject): JSONObject = JSONObject(item.toString()).apply {
            remove("id")
            remove("status")
        }
    }
}
