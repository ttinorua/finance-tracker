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
            points = listOf("Fuel up."), tip = null, accountId = 3, accountName = "Sydbank Privatkonto"
        )
        assertEquals(summary, WeeklySummary.decode(WeeklySummary.encode(summary)))
    }

    @Test
    fun `summaries saved before the account was stored don't claim all accounts`() {
        val old = """{"title":"T","subtitle":"AI summary · 1–7 Oct · Sydbank","created":1,"auto":false,"stats":[],"points":[],"tip":null}"""
        assertEquals("", WeeklySummary.decode(old).accountName)
    }

    @Test
    fun `date ranges are short`() {
        fun utc(y: Int, m: Int, d: Int) = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { clear(); set(y, m - 1, d) }.timeInMillis
        val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        assertEquals("1–22 Oct", WeeklySummary.shortRange(utc(year, 10, 1), utc(year, 10, 22)))
        assertEquals("28 Sep – 4 Oct", WeeklySummary.shortRange(utc(year, 9, 28), utc(year, 10, 4)))
        assertEquals("28 Dec 2024 – 3 Jan 2025", WeeklySummary.shortRange(utc(2024, 12, 28), utc(2025, 1, 3)))
    }
}
