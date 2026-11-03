package com.financetracker.app

import com.financetracker.app.data.stores.Rema1000
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The REMA 1000 (Scan Selv) response layout isn't confirmed, so these check the flexible reading
 * on layouts shaped like the fields the Scan Selv app stores receipts under. */
class RemaReceiptTest {

    @Test
    fun `a list of receipts with items and price parts is read`() {
        val body = """
            {"receipts": [
              {"id": "991", "displayId": "R-991", "paymentDate": "2026-10-09T16:42:10Z",
               "totalPrice": 47.50, "totalDiscount": 5.00, "totalVat": 9.50,
               "store": {"storeNumber": 123, "name": "Taastrup"},
               "items": [
                 {"id": 1, "shelfText1": "SKRABEÆG", "shelfText2": "10 STK", "prices": [{"price": 30.00, "quantity": 1, "discount": 5.00, "deposit": 0}]},
                 {"id": 2, "shelfText1": "COLA 1,5L", "prices": [{"price": 20.00, "quantity": 2, "discount": 0, "deposit": 2.50}]}
               ]}
            ]}
        """.trimIndent()
        val receipts = Rema1000.parseReceipts(body)
        assertEquals(1, receipts.size)
        val r = receipts[0].receipt
        assertEquals("rema:991", r.receiptNumber)
        assertEquals("REMA 1000 Taastrup", r.storeName)
        assertEquals(47.50, r.total, 0.001)
        assertEquals(5.00, r.discount, 0.001)
        assertEquals(2, r.items.size)
        assertEquals("SKRABEÆG", r.items[0].name) // shelfText2 left out, as on REMA's receipt
        // 30 - 5 discount + 20 + 2.50 deposit = 47.50: the reading with deposit adds up.
        assertEquals(25.00, r.items[0].amount, 0.001)
        assertEquals(22.50, r.items[1].amount, 0.001)
        assertEquals(2.0, r.items[1].quantity, 0.001)
    }

    @Test
    fun `a bare array with epoch dates and flat prices is read`() {
        val body = """[{"id": 5, "paymentDate": 1760028130000, "totalPrice": 12.0,
            "items": [{"shelfText1": "BANANER", "price": 12.0, "quantity": 1, "discount": 0}]}]"""
        val r = Rema1000.parseReceipts(body).single().receipt
        assertEquals(1760028130000L, r.purchasedAt)
        assertEquals(12.0, r.items.single().amount, 0.001)
    }

    @Test
    fun `no receipts is an empty list, not an error`() {
        assertTrue(Rema1000.parseReceipts("""{"data": {"receipts": []}}""").isEmpty())
        assertTrue(Rema1000.parseReceipts("[]").isEmpty())
    }

    @Test
    fun `an unreadable layout names its fields, never their values`() {
        try {
            Rema1000.parseReceipts("""{"receipts": [{"uuid": "secret-123", "when": "x"}]}""")
            fail("expected an error")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("uuid"))
            assertTrue(!e.message!!.contains("secret-123"))
        }
    }

    @Test
    fun `the receipt request forms tried carry the since date`() {
        val since = 1760028130000L // 2025-10-09T16:42:10Z
        val forms = Rema1000.queries(since)
        assertEquals("", forms[0])
        assertTrue(forms.contains("?sinceDate=2025-10-09T16%3A42%3A10Z"))
        assertTrue(forms.contains("?sinceDate=2025-10-09"))
        assertTrue(forms.contains("?since=1760028130000"))
    }

    @Test
    fun `amounts in øre, as REMA sends them, become kroner`() {
        // The real receipt: total 181,68 kr. sent as 18168; Salty Mix 20,00 as 2000.
        val body = """[{"id": 7, "paymentDate": "2026-09-05T16:15:24Z", "totalPrice": 18168, "totalPriceString": "181,68",
            "totalDiscount": 0, "items": [
              {"shelfText1": "SALTY MIX", "shelfText2": "PREMIUM NØDDER", "prices": [{"price": 2000, "quantity": 1, "discount": 0, "deposit": 0}]},
              {"shelfText1": "ØKO. KIWI", "shelfText2": "LAND/KL.: SE VAREN", "prices": [{"price": 16168, "quantity": 1, "discount": 0, "deposit": 0}]}
            ]}]"""
        val r = Rema1000.parseReceipts(body).single().receipt
        assertEquals(181.68, r.total, 0.001)
        assertEquals(20.00, r.items[0].amount, 0.001)
        assertEquals("SALTY MIX", r.items[0].name)
        assertEquals("ØKO. KIWI", r.items[1].name)
    }

    @Test
    fun `the scale follows totalPriceString, else whole numbers mean øre`() {
        assertEquals(100.0, Rema1000.scale(18168, "181,68"), 0.0)
        assertEquals(1.0, Rema1000.scale(181.68, "181,68"), 0.0)
        assertEquals(100.0, Rema1000.scale(18168, null), 0.0)
        assertEquals(1.0, Rema1000.scale(47.5, null), 0.0)
    }

    @Test
    fun `items follow their scan time, as on REMA's receipt`() {
        val body = """[{"id": 8, "paymentDate": "2026-09-05T16:15:24Z", "totalPrice": 5000, "items": [
            {"shelfText1": "KIWI", "sortingDate": "2026-09-05T16:10:30Z", "prices": [{"price": 2000}]},
            {"shelfText1": "SALTY MIX", "sortingDate": "2026-09-05T16:10:05Z", "prices": [{"price": 2000}]},
            {"shelfText1": "KAKAOKIKS", "sortingDate": "2026-09-05T16:10:12Z", "prices": [{"price": 1000}]}
          ]}]"""
        val names = Rema1000.parseReceipts(body).single().receipt.items.map { it.name }
        assertEquals(listOf("SALTY MIX", "KAKAOKIKS", "KIWI"), names)
    }
}
