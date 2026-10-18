package com.financetracker.app

import com.financetracker.app.data.ai.ReceiptReader
import com.financetracker.app.data.db.entity.Receipt
import com.financetracker.app.data.db.entity.ReceiptItemDetails
import com.financetracker.app.data.db.entity.TransactionType
import com.financetracker.app.data.db.entity.TransactionWithDetails
import com.financetracker.app.util.ReceiptMatcher
import com.financetracker.app.util.ReceiptSplits
import com.financetracker.app.util.ShoppingStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class ReceiptsTest {

    private val day = 86_400_000L
    private fun at(y: Int, m: Int, d: Int, h: Int = 12) = Calendar.getInstance().apply { set(y, m - 1, d, h, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

    private fun tx(id: Long, amount: Double, date: Long, note: String = "NETTO", categoryId: Long? = 1, type: TransactionType = TransactionType.EXPENSE) =
        TransactionWithDetails(id, amount, type, 1, "Lunar", categoryId, if (categoryId == 1L) "Groceries" else "Other", "Food", "#4CAF50", date, note)

    private fun item(name: String, amount: Double, categoryId: Long?, txId: Long? = 10, split: Boolean = true, at: Long = 0, quantity: Double = 1.0, store: String = "Netto", discount: Double = 0.0) =
        ReceiptItemDetails(0, 1, txId, split, store, at, name, quantity, amount, discount, categoryId, categoryId?.let { "Cat$it" }, "Main", "#000000")

    @Test
    fun `a split payment becomes one part per category that adds up to the payment`() {
        val payment = tx(10, 412.50, at(2026, 10, 16))
        val items = listOf(item("Mælk", 21.90, 1), item("Brød", 268.55, 1), item("Bleer", 69.00, 2), item("Opvask", 13.00, 2), item("Slik", 40.05, 3))
        val parts = ReceiptSplits.split(payment, items)
        assertEquals(3, parts.size)
        assertEquals(412.50, parts.sumOf { it.amount }, 0.001)
        assertEquals(listOf(1L, 2L, 3L), parts.map { it.categoryId })
        assertTrue(parts.all { ReceiptSplits.isPart(it.id) && ReceiptSplits.originalId(it.id) == 10L })
        // Others are untouched, and a payment whose receipt isn't split stays whole.
        val others = listOf(payment, tx(11, 50.0, at(2026, 10, 16)))
        assertEquals(4, ReceiptSplits.expand(others, items).size)
        assertEquals(2, ReceiptSplits.expand(others, items.map { it.copy(split = false) }).size)
    }

    @Test
    fun `money the receipt doesn't explain stays in the payment's own category`() {
        val payment = tx(10, 120.0, at(2026, 10, 16))
        val parts = ReceiptSplits.split(payment, listOf(item("Slik", 40.0, 3), item("Mælk", 20.0, 1)))
        assertEquals(80.0, parts.first { it.categoryId == 1L }.amount, 0.001)
        assertEquals(40.0, parts.first { it.categoryId == 3L }.amount, 0.001)
        // And a receipt that adds up to more than was paid is scaled down to the payment.
        val scaled = ReceiptSplits.split(tx(10, 50.0, 0), listOf(item("A", 60.0, 2), item("B", 40.0, 3)))
        assertEquals(50.0, scaled.sumOf { it.amount }, 0.001)
        assertEquals(30.0, scaled.first { it.categoryId == 2L }.amount, 0.001)
        // Everything in the payment's own category: nothing to split.
        assertEquals(listOf(10L), ReceiptSplits.split(tx(10, 30.0, 0), listOf(item("A", 30.0, 1))).map { it.id })
    }

    @Test
    fun `a receipt matches the payment of the same amount booked around that day`() {
        val bought = at(2026, 10, 16, 17)
        val list = listOf(
            tx(1, 186.00, at(2026, 10, 20)), // too late
            tx(2, 186.00, at(2026, 10, 17), note = "REMA 1000"),
            tx(3, 185.80, at(2026, 10, 18)),
            tx(4, 99.00, at(2026, 10, 16)),
            tx(5, 186.00, at(2026, 10, 17), type = TransactionType.INCOME)
        )
        assertEquals(listOf(2L, 3L), ReceiptMatcher.candidates(186.0, bought, "REMA 1000", list, emptySet()).map { it.id })
        assertEquals(2L, ReceiptMatcher.best(186.0, bought, "REMA 1000", list, emptySet())?.id)
        // Already used by another receipt.
        assertEquals(3L, ReceiptMatcher.best(186.0, bought, "REMA 1000", list, setOf(2L))?.id)
        // Two identical payments the same day: ask rather than guess.
        val twins = listOf(tx(7, 50.0, at(2026, 10, 16)), tx(8, 50.0, at(2026, 10, 16)))
        assertNull(ReceiptMatcher.best(50.0, bought, "Netto", twins, emptySet()))
    }

    @Test
    fun `the AI's reply becomes a receipt with only known categories`() {
        val reply = """
            Here it is: {"store": "REMA 1000", "date": "2026-10-16", "time": "17:42", "receipt_number": "4711",
             "total": "186,00", "discount": 6.5,
             "items": [
               {"name": "Minimælk 1 l", "quantity": 2, "amount": 21.90, "discount": 0, "category_id": 5},
               {"name": "Kaffe 400 g", "quantity": 1, "amount": 42.0, "discount": 6.5, "category_id": 99},
               {"name": "", "amount": 3},
               {"name": "Pant", "amount": 3.0, "category_id": null}
             ]}
        """.trimIndent()
        val now = at(2026, 10, 17)
        val receipt = ReceiptReader.parse(reply, setOf(5L), now)
        assertEquals("REMA 1000", receipt.storeName)
        assertEquals(186.0, receipt.total, 0.001)
        assertEquals(6.5, receipt.discount, 0.001)
        assertEquals(3, receipt.items.size)
        assertEquals(5L, receipt.items[0].categoryId)
        assertEquals(2.0, receipt.items[0].quantity, 0.001)
        assertNull(receipt.items[1].categoryId)
        assertEquals("no:4711", receipt.externalId)
        val cal = Calendar.getInstance().apply { timeInMillis = receipt.purchasedAt }
        assertEquals(16, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(17, cal.get(Calendar.HOUR_OF_DAY))
        // A misread future date falls back to today; no receipt number gives a stable fingerprint.
        val odd = ReceiptReader.parse("""{"store":"Netto","date":"2031-01-01","total":10,"items":[]}""", emptySet(), now)
        assertTrue(odd.purchasedAt in now - day..now)
        assertEquals(odd.externalId, ReceiptReader.parse("""{"store":"netto","date":"2031-01-01","total":10,"items":[]}""", emptySet(), now).externalId)
    }

    @Test
    fun `shopping shows the month by store, by category, and what got dearer`() {
        val range = at(2026, 10, 1, 0) to at(2026, 11, 1, 0)
        val receipts = listOf(
            Receipt(1, "share", "a", "Netto", at(2026, 10, 3), 100.0),
            Receipt(2, "share", "b", "netto ", at(2026, 10, 9), 50.0),
            Receipt(3, "share", "c", "Lidl", at(2026, 10, 9), 70.0),
            Receipt(4, "share", "d", "Lidl", at(2026, 9, 9), 999.0)
        )
        val stores = ShoppingStats.byStore(receipts, range)
        assertEquals(listOf("Netto" to 150.0, "Lidl" to 70.0), stores.map { it.label to it.total })
        assertEquals(2, stores[0].count)

        val items = listOf(
            item("Kaffe 400 g", 36.95, 1, at = at(2026, 8, 20), store = "REMA"),
            item("KAFFE 400G", 42.00, 1, at = at(2026, 10, 5), store = "REMA"),
            item("Smør", 20.0, 1, at = at(2026, 9, 1)),
            item("Smør", 15.0, 1, at = at(2026, 10, 2), discount = 5.0), // an offer, not a rise
            item("Slik", 30.0, 3, at = at(2026, 10, 2))
        )
        assertEquals(listOf(57.0, 30.0), ShoppingStats.byCategory(items, range).map { it.total })
        val rises = ShoppingStats.priceRises(items, range)
        assertEquals(1, rises.size)
        assertEquals(42.0, rises[0].now, 0.001)
        assertEquals(36.95, rises[0].before, 0.001)
        assertEquals(1, ShoppingStats.byItem(items, range).count { it.label.startsWith("KAFFE") })
    }

    @Test
    fun `only receipts paid from your own bank account are kept`() {
        val bought = at(2026, 10, 16)
        val netto = listOf("netto")
        val mine = listOf(tx(1, 182.45, at(2026, 10, 17), note = "MCD 02031 NETTO 7712 Solrød"))
        // Your card payment at Netto the next day, same amount: yours.
        assertTrue(ReceiptMatcher.paidFromOwnAccount(182.45, bought, "Netto Solrød", netto, mine, emptyList()))
        // A household member's Netto receipt: no payment of yours matches the amount.
        assertTrue(!ReceiptMatcher.paidFromOwnAccount(96.00, bought, "Netto Solrød", netto, mine, emptyList()))
        // Same amount, but your payment was at another shop: not this receipt.
        val elsewhere = listOf(tx(2, 96.00, at(2026, 10, 16), note = "MCD 02040 Q8 Solrød"))
        assertTrue(!ReceiptMatcher.paidFromOwnAccount(96.00, bought, "Netto Solrød", netto, elsewhere, emptyList()))
        // Too long after the purchase.
        val late = listOf(tx(3, 182.45, at(2026, 10, 22), note = "NETTO"))
        assertTrue(!ReceiptMatcher.paidFromOwnAccount(182.45, bought, "Netto", netto, late, emptyList()))
        // Only reserved at the bank so far (not booked yet): yours too.
        val reserved = listOf(at(2026, 10, 16) to (182.45 to "Dankort-nota NETTO 7712"))
        assertTrue(ReceiptMatcher.paidFromOwnAccount(182.45, bought, "Netto", netto, emptyList(), reserved))
        // Føtex's bank text is often written FOETEX.
        val foetex = listOf(tx(4, 59.95, at(2026, 10, 16), note = "MCD 02050 FOETEX Køge"))
        assertTrue(ReceiptMatcher.paidFromOwnAccount(59.95, bought, "Føtex Køge", listOf("føtex", "foetex", "fotex"), foetex, emptyList()))
    }

    @Test
    fun `recent receipts are checked once their payment would have shown`() {
        val now = at(2026, 10, 18, 18)
        val today = at(2026, 10, 18, 10)
        val sync = at(2026, 10, 18, 16)
        // A bank that reports reservations synced six hours after the purchase: check it now.
        assertTrue(com.financetracker.app.data.stores.StoreSync.paymentWouldShow(today, now, true, sync))
        // Synced only 30 minutes after the purchase: too soon.
        assertTrue(!com.financetracker.app.data.stores.StoreSync.paymentWouldShow(today, now, true, today + 30 * 60_000L))
        // A bank that never reported a reservation: wait until the payment would be booked (5 days).
        assertTrue(!com.financetracker.app.data.stores.StoreSync.paymentWouldShow(today, now, false, sync))
        assertTrue(com.financetracker.app.data.stores.StoreSync.paymentWouldShow(at(2026, 10, 12), now, false, 0L))
    }
}
