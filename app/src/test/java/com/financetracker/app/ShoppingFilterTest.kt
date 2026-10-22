package com.financetracker.app

import com.financetracker.app.data.db.entity.Receipt
import com.financetracker.app.util.ShoppingStats
import org.junit.Assert.assertEquals
import org.junit.Test

class ShoppingFilterTest {
    private fun receipt(id: Long, store: String, total: Double, payment: Long?) =
        Receipt(id = id, source = "test", externalId = "r$id", storeName = store, purchasedAt = 1_000L, total = total, transactionId = payment)

    private val receipts = listOf(receipt(1, "Netto", 300.0, payment = 11), receipt(2, "REMA", 100.0, payment = 22), receipt(3, "Lidl", 50.0, payment = null))
    private val accounts = mapOf(11L to 1L, 22L to 2L)

    @Test
    fun `account keeps receipts paid from it`() {
        assertEquals(listOf(1L), ShoppingStats.onAccount(receipts, accounts, accountId = 1).map { it.id })
    }

    @Test
    fun `all accounts keeps everything, unmatched receipts included`() {
        assertEquals(3, ShoppingStats.onAccount(receipts, accounts, accountId = null).size)
    }
}
