package com.financetracker.app

import com.financetracker.app.data.db.entity.Receipt
import com.financetracker.app.data.db.entity.ReceiptItemDetails
import com.financetracker.app.util.CategoryFilter
import com.financetracker.app.util.ShoppingStats
import org.junit.Assert.assertEquals
import org.junit.Test

class ShoppingFilterTest {
    private fun receipt(id: Long, store: String, total: Double, payment: Long?) =
        Receipt(id = id, source = "test", externalId = "r$id", storeName = store, purchasedAt = 1_000L, total = total, transactionId = payment)

    private fun item(receiptId: Long, store: String, amount: Double, categoryId: Long?, main: String?) = ReceiptItemDetails(
        itemId = receiptId * 10 + amount.toLong(), receiptId = receiptId, transactionId = null, split = false, storeName = store,
        purchasedAt = 1_000L, name = "x$amount", quantity = 1.0, amount = amount, discount = 0.0,
        categoryId = categoryId, categoryName = "c$categoryId", mainCategoryName = main, categoryColorHex = null
    )

    private val receipts = listOf(receipt(1, "Netto", 300.0, payment = 11), receipt(2, "REMA", 100.0, payment = 22), receipt(3, "Lidl", 50.0, payment = null))
    private val items = listOf(
        item(1, "Netto", 200.0, 5, "Food"), item(1, "Netto", 100.0, 6, "Home"),
        item(2, "REMA", 100.0, 5, "Food"), item(3, "Lidl", 50.0, 6, "Home")
    )
    private val accounts = mapOf(11L to 1L, 22L to 2L)

    @Test
    fun `account keeps receipts paid from it`() {
        val f = ShoppingStats.filter(receipts, items, accounts, accountId = 1, category = CategoryFilter.All)
        assertEquals(listOf(1L), f.receipts.map { it.id })
        assertEquals(300.0, f.items.sumOf { it.amount }, 0.01)
    }

    @Test
    fun `all accounts keeps everything, unmatched receipts included`() {
        val f = ShoppingStats.filter(receipts, items, accounts, accountId = null, category = CategoryFilter.All)
        assertEquals(3, f.receipts.size)
    }

    @Test
    fun `category keeps only its lines and the receipts that have them`() {
        val f = ShoppingStats.filter(receipts, items, accounts, accountId = null, category = CategoryFilter.Main("Food"))
        assertEquals(listOf(1L, 2L), f.receipts.map { it.id })
        val stores = ShoppingStats.byStoreFromItems(f.items, 0L to 2_000L)
        assertEquals(listOf("Netto" to 200.0, "REMA" to 100.0), stores.map { it.label to it.total })
    }
}
