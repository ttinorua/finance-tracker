package com.financetracker.app.data.ai.agent

import com.financetracker.app.data.ai.AiNotConfiguredException
import com.financetracker.app.data.ai.AiProvider
import com.financetracker.app.data.ai.AiRequestException
import com.financetracker.app.data.ai.AiSettings
import com.financetracker.app.data.ai.ChatAttachment
import com.financetracker.app.data.ai.ChatTurn
import com.financetracker.app.data.ai.WebSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** A function the AI may call: [parameters] is a JSON Schema object. */
data class ToolSpec(val name: String, val description: String, val parameters: JSONObject)

/** One function call the AI made. [id] links the result back to it (empty if the AI gave none). */
data class ToolCall(val id: String, val name: String, val args: JSONObject)

/** What one round trip to the AI produced: its text so far and the functions it wants run, plus
 * whether it searched the web and the pages it used (ChatGPT only). */
internal data class AgentStep(
    val text: String,
    val toolCalls: List<ToolCall>,
    val sources: List<WebSource> = emptyList(),
    val searchedWeb: Boolean = false
)

/** A conversation with one AI that can go back and forth several times within one question:
 * the AI asks for functions, the app runs them and sends the results, until it answers. */
internal interface AgentSession {
    fun step(): AgentStep
    fun addToolResults(results: List<Pair<ToolCall, String>>)
}

data class AgentRun(
    val text: String,
    val provider: AiProvider,
    /** The web pages the answer is based on (empty when it didn't search). */
    val sources: List<WebSource> = emptyList(),
    val searchedWeb: Boolean = false
)

/**
 * Runs a question through an AI that can look things up and prepare changes using the app's own
 * functions ([ToolSpec]s), with the same fallback as [com.financetracker.app.data.ai.AiService]:
 * if the AI in use fails part-way, the whole question starts over with the next AI in the chain.
 * [onAttemptStart] runs before each attempt, so the caller can drop anything a failed attempt
 * prepared. An [attachment] (a photo or PDF) goes with [userMessage]; an AI that can't read
 * files is skipped.
 */
object AiAgent {

    private const val MAX_STEPS = 10

    suspend fun run(
        systemPrompt: String,
        history: List<ChatTurn>,
        userMessage: String,
        tools: List<ToolSpec>,
        attachment: ChatAttachment? = null,
        onAttemptStart: () -> Unit,
        execute: suspend (ToolCall) -> String
    ): Result<AgentRun> {
        val chain = AiSettings.chain
        if (chain.isEmpty()) return Result.failure(AiNotConfiguredException())
        var firstError: Throwable? = null
        for (provider in chain) {
            val key = AiSettings.keyFor(provider) ?: continue
            onAttemptStart()
            try {
                val session = withContext(Dispatchers.IO) {
                    when (provider) {
                        AiProvider.GEMINI -> GeminiAgentSession(key, systemPrompt, history, userMessage, tools, attachment)
                        AiProvider.GROQ -> {
                            if (attachment != null) throw AiRequestException("Groq can't read attached files.")
                            GroqAgentSession(key, systemPrompt, history, userMessage, tools)
                        }
                        AiProvider.CHATGPT -> ChatGptAgentSession(systemPrompt, history, userMessage, tools, attachment)
                        AiProvider.CLAUDE -> ClaudeAgentSession(systemPrompt, history, userMessage, tools, attachment)
                    }
                }
                val result = loop(session, execute)
                return Result.success(AgentRun(result.text, provider, result.sources, result.searchedWeb))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (firstError == null) {
                    firstError = e as? AiRequestException ?: AiRequestException("${provider.label} request failed: ${e.message}", e)
                }
            }
        }
        return Result.failure(firstError ?: AiNotConfiguredException())
    }

    private class LoopResult(val text: String, val sources: List<WebSource>, val searchedWeb: Boolean)

    private suspend fun loop(session: AgentSession, execute: suspend (ToolCall) -> String): LoopResult {
        val texts = mutableListOf<String>()
        val sources = LinkedHashMap<String, WebSource>()
        var searched = false
        fun result(text: String) = LoopResult(text, sources.values.toList(), searched || sources.isNotEmpty())
        repeat(MAX_STEPS) {
            val step = withContext(Dispatchers.IO) { session.step() }
            step.text.takeIf { it.isNotBlank() }?.let(texts::add)
            step.sources.forEach { sources.putIfAbsent(it.url, it) }
            searched = searched || step.searchedWeb
            if (step.toolCalls.isEmpty()) return result(texts.joinToString("\n\n").trim())
            val results = step.toolCalls.map { call ->
                call to try {
                    execute(call)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    "Error: ${e.message ?: "the function failed"}"
                }
            }
            withContext(Dispatchers.IO) { session.addToolResults(results) }
        }
        // Still asking for more after MAX_STEPS: answer with what there is.
        return result(
            texts.joinToString("\n\n").trim().ifBlank {
                "That took more steps than I'm allowed for one question. Try asking for a smaller part of it."
            }
        )
    }
}

/** org.json values → plain Kotlin maps/lists (Android's org.json has no toMap()). */
internal fun jsonToPlain(value: Any?): Any? = when (value) {
    is JSONObject -> value.keys().asSequence().associateWith { jsonToPlain(value.opt(it)) }
    is JSONArray -> (0 until value.length()).map { jsonToPlain(value.opt(it)) }
    JSONObject.NULL -> null
    else -> value
}
