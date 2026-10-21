package com.financetracker.app

import com.financetracker.app.data.ai.AiSummary
import com.financetracker.app.data.ai.SummaryStat
import com.financetracker.app.data.ai.WeeklySummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeeklySummaryTest {

    @Test
    fun `the AI's reply is read even with text around it`() {
        val raw = "Here you go:\n```json\n{\"title\": \"Your week: on track\", \"points\": [\"Fuel up 150 kr.\", \" \", \"Groceries down.\"], \"tip\": \"Skip takeaway twice.\"}\n```"
        val (title, points, tip) = WeeklySummary.parseWritten(raw)!!
        assertEquals("Your week: on track", title)
        assertEquals(listOf("Fuel up 150 kr.", "Groceries down."), points)
        assertEquals("Skip takeaway twice.", tip)
    }

    @Test
    fun `an unusable reply gives null so the app writes its own lines`() {
        assertNull(WeeklySummary.parseWritten("Sorry, I can't help with that."))
        assertNull(WeeklySummary.parseWritten("{\"title\": \"  \"}"))
    }

    @Test
    fun `a summary survives saving and loading`() {
        val summary = AiSummary(
            title = "Spending above usual", subtitle = "AI summary · 13 Oct – 19 Oct · Main", createdAt = 5, automatic = true,
            stats = listOf(SummaryStat("Spent", "1,200 kr", "↑ 12% vs usual", false), SummaryStat("Income", "0 kr", "your week")),
            points = listOf("Fuel up."), tip = null
        )
        assertEquals(summary, WeeklySummary.decode(WeeklySummary.encode(summary)))
    }
}
