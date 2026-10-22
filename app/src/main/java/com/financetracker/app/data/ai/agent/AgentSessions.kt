package com.financetracker.app.data.ai.agent

import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.Base64PdfSource
import com.anthropic.models.messages.DocumentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import com.financetracker.app.data.ai.AiNotConfiguredException
import com.financetracker.app.data.ai.AiRequestException
import com.financetracker.app.data.ai.ChatAttachment
import com.financetracker.app.data.ai.ChatTurn
import com.financetracker.app.data.ai.ClaudeService
import com.financetracker.app.data.ai.GeminiService
import com.financetracker.app.data.ai.GroqService
import org.json.JSONArray
import org.json.JSONObject

/** Reply budget per step; each service adds its own thinking headroom on top. */
private const val STEP_REPLY_TOKENS = 3_000

/** Gemini: echoes the model's own content back unchanged (it carries the thought signatures
 * Gemini needs to keep its reasoning across function calls), and answers with functionResponse
 * parts. */
internal class GeminiAgentSession(
    private val key: String,
    system: String,
    history: List<ChatTurn>,
    userMessage: String,
    tools: List<ToolSpec>,
    attachment: ChatAttachment? = null
) : AgentSession {

    private val body: JSONObject = GeminiService.request(
        system, history + ChatTurn(isUser = true, text = userMessage), STEP_REPLY_TOKENS, quick = false
    ).put(
        "tools",
        JSONArray().put(
            JSONObject().put(
                "functionDeclarations",
                JSONArray().apply {
                    tools.forEach {
                        put(JSONObject().put("name", it.name).put("description", it.description).put("parameters", it.parameters))
                    }
                }
            )
        )
    ).also { body ->
        // The attached file goes in front of the question's text.
        if (attachment != null) {
            val contents = body.getJSONArray("contents")
            val question = contents.getJSONObject(contents.length() - 1)
            val parts = JSONArray().put(
                JSONObject().put("inlineData", JSONObject().put("mimeType", attachment.mimeType).put("data", attachment.base64))
            )
            val old = question.getJSONArray("parts")
            for (i in 0 until old.length()) parts.put(old.get(i))
            question.put("parts", parts)
        }
    }

    override fun step(): AgentStep {
        val response = GeminiService.send(key, body)
        val candidate = response.optJSONArray("candidates")?.optJSONObject(0)
            ?: throw AiRequestException("Gemini sent an empty reply.")
        val finish = candidate.optString("finishReason")
        if (finish == "MALFORMED_FUNCTION_CALL" || finish == "SAFETY") {
            throw AiRequestException("Gemini couldn't complete this ($finish).")
        }
        candidate.optJSONObject("content")?.let { content ->
            if (!content.has("role")) content.put("role", "model")
            body.getJSONArray("contents").put(content)
        }
        val parts = GeminiService.parts(response)
        val text = parts.filter { !it.optBoolean("thought") && it.has("text") }.joinToString("") { it.optString("text") }.trim()
        val calls = parts.mapNotNull { part ->
            val call = part.optJSONObject("functionCall") ?: return@mapNotNull null
            ToolCall(call.optString("id"), call.optString("name"), call.optJSONObject("args") ?: JSONObject())
        }
        return AgentStep(text, calls)
    }

    override fun addToolResults(results: List<Pair<ToolCall, String>>) {
        val parts = JSONArray()
        results.forEach { (call, result) ->
            val response = JSONObject().put("name", call.name).put("response", JSONObject().put("result", result))
            if (call.id.isNotBlank()) response.put("id", call.id)
            parts.put(JSONObject().put("functionResponse", response))
        }
        body.getJSONArray("contents").put(JSONObject().put("role", "user").put("parts", parts))
    }
}

/** Groq (OpenAI-style chat): echoes the assistant message with its tool_calls, and answers with
 * role "tool" messages. */
internal class GroqAgentSession(
    private val key: String,
    system: String,
    history: List<ChatTurn>,
    userMessage: String,
    tools: List<ToolSpec>
) : AgentSession {

    private val body: JSONObject = GroqService.request(
        system, history + ChatTurn(isUser = true, text = userMessage), STEP_REPLY_TOKENS, quick = false
    ).put(
        "tools",
        JSONArray().apply {
            tools.forEach {
                put(
                    JSONObject().put("type", "function").put(
                        "function",
                        JSONObject().put("name", it.name).put("description", it.description).put("parameters", it.parameters)
                    )
                )
            }
        }
    )

    override fun step(): AgentStep {
        val message = GroqService.messageOf(GroqService.send(key, body))
        val text = message.optString("content").takeIf { it != "null" }.orEmpty().trim()
        val toolCalls = message.optJSONArray("tool_calls")
        val calls = (0 until (toolCalls?.length() ?: 0)).mapNotNull { i ->
            val call = toolCalls?.optJSONObject(i) ?: return@mapNotNull null
            val function = call.optJSONObject("function") ?: return@mapNotNull null
            val args = runCatching { JSONObject(function.optString("arguments").ifBlank { "{}" }) }.getOrElse { JSONObject() }
            ToolCall(call.optString("id"), function.optString("name"), args)
        }
        // Only the fields the API takes back (not the model's reasoning text).
        val echo = JSONObject().put("role", "assistant").put("content", text.ifEmpty { JSONObject.NULL })
        if (toolCalls != null && toolCalls.length() > 0) echo.put("tool_calls", toolCalls)
        body.getJSONArray("messages").put(echo)
        return AgentStep(text, calls)
    }

    override fun addToolResults(results: List<Pair<ToolCall, String>>) {
        val messages = body.getJSONArray("messages")
        results.forEach { (call, result) ->
            messages.put(JSONObject().put("role", "tool").put("tool_call_id", call.id).put("content", result))
        }
    }
}

/** Claude through the Anthropic Java SDK: echoes each reply back whole (its thinking included,
 * which Claude needs across tool calls), and answers with tool_result blocks. */
internal class ClaudeAgentSession(
    system: String,
    history: List<ChatTurn>,
    userMessage: String,
    tools: List<ToolSpec>,
    attachment: ChatAttachment? = null
) : AgentSession {

    private val client = ClaudeService.client() ?: throw AiNotConfiguredException()

    private val builder: MessageCreateParams.Builder = ClaudeService.baseParams(STEP_REPLY_TOKENS.toLong(), OutputConfig.Effort.MEDIUM)
        .system(system)
        .apply {
            tools.forEach { addTool(toClaudeTool(it)) }
            history.forEach { turn -> if (turn.isUser) addUserMessage(turn.text) else addAssistantMessage(turn.text) }
            if (attachment == null) {
                addUserMessage(userMessage)
            } else {
                val file = if (attachment.isPdf) {
                    ContentBlockParam.ofDocument(
                        DocumentBlockParam.builder().source(Base64PdfSource.builder().data(attachment.base64).build()).build()
                    )
                } else {
                    ContentBlockParam.ofImage(
                        ImageBlockParam.builder()
                            .source(Base64ImageSource.builder().data(attachment.base64).mediaType(Base64ImageSource.MediaType.IMAGE_JPEG).build())
                            .build()
                    )
                }
                addUserMessageOfBlockParams(listOf(file, ContentBlockParam.ofText(TextBlockParam.builder().text(userMessage).build())))
            }
        }

    override fun step(): AgentStep {
        val message = try {
            ClaudeService.send(client, builder.build())
        } catch (e: Exception) {
            throw ClaudeService.mapError(e)
        }
        builder.addMessage(message)
        val text = message.content().filter { it.isText() }.joinToString("") { it.asText().text() }.trim()
        val calls = message.content().filter { it.isToolUse() }.map { block ->
            val use = block.asToolUse()
            @Suppress("UNCHECKED_CAST")
            val input = runCatching { use._input().convert(Map::class.java) as Map<String, Any?> }.getOrNull()
            ToolCall(use.id(), use.name(), input?.let { JSONObject(it) } ?: JSONObject())
        }
        if (calls.isEmpty() && message.stopReason().orElse(null) == StopReason.MAX_TOKENS && text.isBlank()) {
            throw AiRequestException("Claude ran out of room for its answer.")
        }
        return AgentStep(text, calls)
    }

    override fun addToolResults(results: List<Pair<ToolCall, String>>) {
        builder.addMessage(
            MessageParam.builder()
                .role(MessageParam.Role.USER)
                .contentOfBlockParams(
                    results.map { (call, result) ->
                        ContentBlockParam.ofToolResult(
                            ToolResultBlockParam.builder().toolUseId(call.id).content(result).build()
                        )
                    }
                )
                .build()
        )
    }

    private fun toClaudeTool(spec: ToolSpec): Tool {
        val properties = Tool.InputSchema.Properties.builder().apply {
            spec.parameters.optJSONObject("properties")?.let { props ->
                props.keys().forEach { name -> putAdditionalProperty(name, JsonValue.from(jsonToPlain(props.get(name)))) }
            }
        }.build()
        val required = spec.parameters.optJSONArray("required")?.let { array -> (0 until array.length()).map { array.getString(it) } }
        return Tool.builder()
            .name(spec.name)
            .description(spec.description)
            .inputSchema(
                Tool.InputSchema.builder()
                    .type(JsonValue.from("object"))
                    .properties(properties)
                    .apply { if (!required.isNullOrEmpty()) required(required) }
                    .build()
            )
            .build()
    }
}
