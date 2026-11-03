package com.financetracker.app

import com.financetracker.app.data.db.entity.Account
import com.financetracker.app.data.db.entity.Category
import com.financetracker.app.data.db.entity.TransactionType
import com.financetracker.app.data.db.entity.TransactionWithDetails
import com.financetracker.app.data.prefs.AlertKind
import com.financetracker.app.data.prefs.UserAlert
import com.financetracker.app.data.prefs.UserAlerts
import com.financetracker.app.util.advisor.AttentionChecks
import com.financetracker.app.util.advisor.AttentionInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class UserAlertsTest {

    private fun utc(year: Int, month: Int, day: Int): Long = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(year, month - 1, day)
    }.timeInMillis

    private val takeaway = Category(id = 7, name = "Takeaway", mainCategory = "Food", type = TransactionType.EXPENSE)
    private var nextId = 1L

    private fun tx(date: Long, amount: Double, note: String = "Wolt") = TransactionWithDetails(
        id = nextId++, amount = amount, type = TransactionType.EXPENSE, accountId = 1, accountName = "Main",
        categoryId = takeaway.id, categoryName = takeaway.name, mainCategoryName = takeaway.mainCategory,
        categoryColorHex = null, date = date, note = note
    )

    private fun run(now: Long, transactions: List<TransactionWithDetails>, alerts: List<UserAlert>) = AttentionChecks.run(
        AttentionInput(
            now = now, currency = "DKK", transactions = transactions,
            accounts = listOf(Account(id = 1, name = "Main")), balances = mapOf(1L to 50_000.0),
            categories = listOf(takeaway), uncategorizedCategoryIds = emptySet(),
            excludeTransfers = true, shiftSalary = false, mainAccountId = 1,
            overallBudgets = emptyMap(), categoryBudgets = emptyMap(), fixedCategoryIds = emptySet(),
            goals = emptyList(), loans = emptyList(), consents = emptyList(), lastSyncFailure = null,
            backupEnabled = false, backupLastSuccessAt = null, backupLastError = null,
            userAlerts = alerts
        )
    ).filter { it.key.startsWith("alert-") }

    private val limitAlert = UserAlert(id = "a", kind = AlertKind.CATEGORY_LIMIT, limit = 1000.0, categoryId = 7, categoryName = "Takeaway")

    @Test
    fun `early warning at 80 percent`() {
        val items = run(utc(2026, 10, 20), listOf(tx(utc(2026, 10, 5), 500.0), tx(utc(2026, 10, 12), 320.0)), listOf(limitAlert))
        assertEquals(listOf("alert-early:a:2026-10"), items.map { it.key })
        assertTrue(items.single().title.contains("82%"))
    }

    @Test
    fun `over the limit replaces the early warning`() {
        val items = run(utc(2026, 10, 20), listOf(tx(utc(2026, 10, 5), 700.0), tx(utc(2026, 10, 12), 400.0)), listOf(limitAlert))
        assertEquals(listOf("alert-over:a:2026-10"), items.map { it.key })
    }

    @Test
    fun `last month's spending doesn't count`() {
        val items = run(utc(2026, 10, 3), listOf(tx(utc(2026, 9, 25), 2000.0)), listOf(limitAlert))
        assertTrue(items.isEmpty())
    }

    @Test
    fun `switched-off alerts stay quiet`() {
        val items = run(utc(2026, 10, 20), listOf(tx(utc(2026, 10, 5), 2000.0)), listOf(limitAlert.copy(enabled = false)))
        assertTrue(items.isEmpty())
    }

    @Test
    fun `large payments only from the last two weeks`() {
        val alert = UserAlert(id = "b", kind = AlertKind.LARGE_PAYMENT, limit = 3000.0)
        val recent = tx(utc(2026, 10, 15), 3500.0, "Elgiganten")
        val items = run(utc(2026, 10, 20), listOf(recent, tx(utc(2026, 9, 1), 9000.0), tx(utc(2026, 10, 18), 200.0)), listOf(alert))
        assertEquals(listOf("alert-big:b:${recent.id}"), items.map { it.key })
    }

    @Test
    fun `alerts survive saving and loading`() {
        val list = listOf(limitAlert, UserAlert(id = "b", kind = AlertKind.LARGE_PAYMENT, limit = 3000.0, accountId = 2, warnAtPercent = null, enabled = false))
        assertEquals(list, UserAlerts.decode(UserAlerts.encode(list)))
    }
}
