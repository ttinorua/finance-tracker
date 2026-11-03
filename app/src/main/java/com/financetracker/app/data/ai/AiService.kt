package com.financetracker.app.data.ai

import com.anthropic.models.messages.OutputConfig
import com.financetracker.app.data.ai.chatgpt.ChatGptService

/**
 * The one entry point the rest of the app uses for AI. Each request goes to the first AI in
 * [AiSettings.chain] (the one chosen in Settings, then the others with a key); if that one fails —
 * its free limit is used up, it's down, it declined — the next one gets the same request.
 */
object AiService {

    val isConfigured: Boolean get() = AiSettings.chain.isNotEmpty()

    /** One-shot question. [quick] is for simple lookups (e.g. picking a category), answered with
     * less thinking so they're faster. */
    suspend fun ask(systemPrompt: String, userMessage: String, maxTokens: Long = 1024L, quick: Boolean = false): Result<String> =
        firstSuccess { provider, key ->
            when (provider) {
                AiProvider.CLAUDE -> ClaudeService.ask(
                    systemPrompt,
                    userMessage,
                    maxTokens,
                    if (quick) OutputConfig.Effort.LOW else OutputConfig.Effort.MEDIUM
                ).getOrThrow()
                AiProvider.GEMINI -> GeminiService.ask(key, systemPrompt, userMessage, maxTokens.toInt(), quick)
                AiProvider.GROQ -> GroqService.ask(key, systemPrompt, userMessage, maxTokens.toInt(), quick)
                AiProvider.CHATGPT -> ChatGptService.ask(systemPrompt, userMessage, quick)
            }
        }

    /** An empty list from one AI counts as a failure too, so the next AI gets a chance. */
    suspend fun generateInsights(systemPrompt: String, userMessage: String, maxTokens: Long = 1024L): Result<List<InsightCard>> =
        firstSuccess { provider, key ->
            val cards = when (provider) {
                AiProvider.CLAUDE -> ClaudeService.generateInsights(systemPrompt, userMessage, maxTokens).getOrThrow()
                AiProvider.GEMINI -> GeminiService.generateInsights(key, systemPrompt, userMessage, maxTokens.toInt())
                AiProvider.GROQ -> GroqService.generateInsights(key, systemPrompt, userMessage, maxTokens.toInt())
                AiProvider.CHATGPT -> ChatGptService.generateInsights(systemPrompt, userMessage)
            }
            cards.ifEmpty { throw AiRequestException("${provider.label} didn't return any insights.") }
        }

    /** Tries [block] with each AI in the chain until one succeeds; if all fail, returns the first
     * AI's error (usually the most relevant one). */
    private suspend fun <T> firstSuccess(block: suspend (AiProvider, String) -> T): Result<T> {
        val chain = AiSettings.chain
        if (chain.isEmpty()) return Result.failure(AiNotConfiguredException())
        var firstError: Throwable? = null
        for (provider in chain) {
            val key = AiSettings.keyFor(provider) ?: continue
            try {
                return Result.success(block(provider, key))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (firstError == null) {
                    firstError = e as? AiRequestException ?: AiRequestException("${provider.label} request failed: ${e.message}", e)
                }
            }
        }
        return Result.failure(firstError ?: AiNotConfiguredException())
    }
}
