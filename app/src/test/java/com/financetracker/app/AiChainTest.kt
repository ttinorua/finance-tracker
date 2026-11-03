package com.financetracker.app

import com.financetracker.app.data.ai.AiProvider
import com.financetracker.app.data.ai.AiProvider.CHATGPT
import com.financetracker.app.data.ai.AiProvider.CLAUDE
import com.financetracker.app.data.ai.AiProvider.GEMINI
import com.financetracker.app.data.ai.AiProvider.GROQ
import com.financetracker.app.data.ai.AiSettingsState
import com.financetracker.app.data.ai.aiChain
import org.junit.Assert.assertEquals
import org.junit.Test

class AiChainTest {

    private val all: (AiProvider) -> Boolean = { true }

    @Test
    fun `the chosen AI goes first, the rest keep their default order`() {
        assertEquals(listOf(GEMINI, GROQ, CHATGPT, CLAUDE), aiChain(GEMINI, all))
        assertEquals(listOf(GROQ, GEMINI, CHATGPT, CLAUDE), aiChain(GROQ, all))
        assertEquals(listOf(CHATGPT, GEMINI, GROQ, CLAUDE), aiChain(CHATGPT, all))
        assertEquals(listOf(CLAUDE, GEMINI, GROQ, CHATGPT), aiChain(CLAUDE, all))
    }

    @Test
    fun `AIs without a key are skipped, including the chosen one`() {
        assertEquals(listOf(GEMINI, GROQ), aiChain(CLAUDE) { it != CLAUDE && it != CHATGPT })
        assertEquals(listOf(GROQ), aiChain(GEMINI) { it == GROQ })
        assertEquals(emptyList<AiProvider>(), aiChain(GEMINI) { false })
    }

    @Test
    fun `ChatGPT counts as set up only while signed in, never through a key`() {
        assertEquals(false, AiSettingsState(ownKeys = setOf(CHATGPT)).hasKey(CHATGPT))
        assertEquals(false, AiSettingsState(ownKeys = setOf(CHATGPT)).hasOwnKey(CHATGPT))
        assertEquals(true, AiSettingsState(chatGptEmail = "me@example.com").hasKey(CHATGPT))
        assertEquals(
            listOf(CHATGPT),
            AiSettingsState(primary = CHATGPT, chatGptEmail = "me@example.com").chain.filter { it == CHATGPT }
        )
    }
}
