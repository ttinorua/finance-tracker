package com.financetracker.app

import com.financetracker.app.data.ai.chat.SavedCard
import com.financetracker.app.data.ai.chat.SavedChat
import com.financetracker.app.data.ai.chat.SavedChats
import com.financetracker.app.data.ai.chat.SavedMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedChatsTest {

    private val day = 24L * 60 * 60 * 1000

    @Test
    fun `chats survive saving and loading`() {
        val chat = SavedChat(
            id = "a", title = "November budget", createdAt = 1, updatedAt = 2, pinned = true, renamed = true,
            messages = listOf(
                SavedMessage(isUser = true, text = "Make a budget"),
                SavedMessage(
                    isUser = false, text = "Here it is", usedChatGptPlan = true,
                    cards = listOf(SavedCard("Set budgets", listOf("Food 3,000"), "APPLIED", "Budgets updated.", listOf(5L, 6L), true))
                )
            )
        )
        assertEquals(listOf(chat), SavedChats.decode(SavedChats.encode(listOf(chat))))
    }

    @Test
    fun `old chats are dropped unless pinned`() {
        val now = 100 * day
        val list = listOf(
            SavedChat("old", "Old", 0, now - 31 * day),
            SavedChat("pinned", "Pinned", 0, now - 90 * day, pinned = true),
            SavedChat("recent", "Recent", 0, now - 2 * day)
        )
        assertEquals(setOf("pinned", "recent"), SavedChats.prune(list, now).map { it.id }.toSet())
    }

    @Test
    fun `pinned first, then newest`() {
        val list = listOf(SavedChat("a", "A", 0, 10), SavedChat("b", "B", 0, 30), SavedChat("c", "C", 0, 5, pinned = true))
        assertEquals(listOf("c", "b", "a"), SavedChats.sorted(list).map { it.id })
    }

    @Test
    fun `titles come from the first question, cut at a word`() {
        assertEquals("Where can I save money?", SavedChats.titleFrom("  Where can I save   money? "))
        val long = SavedChats.titleFrom("Please make me a budget for November so that I can save more for Christmas")
        assertTrue(long.endsWith("…"))
        assertTrue(long.length <= 41)
    }
}
