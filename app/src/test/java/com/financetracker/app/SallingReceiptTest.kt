package com.financetracker.app

import com.financetracker.app.data.stores.Salling
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class SallingReceiptTest {

    private fun secondsAt(y: Int, m: Int, d: Int): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Europe/Copenhagen"))
        cal.set(y, m - 1, d, 17, 30, 0); cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis / 1000
    }

    @Test
    fun `the receipt list yields each receipt's id, type and date`() {
        val posting = secondsAt(2026, 10, 5)
        val body = """
            {"bannerImageUrl": null,
             "receiptsList": [
               {"groupTitle": "October", "key": 1, "receipts": [
                 {"id": "R-111", "type": "store", "postingDate": $posting, "storeName": "Netto Taastrup", "salesTotal": 186.50},
                 {"id": "R-112", "itemType": "store", "postingDate": ${secondsAt(2026, 9, 20)}, "storeName": "Netto", "salesTotal": 42.00}
               ]}
             ]}
        """.trimIndent()
        val list = Salling.parseList(body)
        assertEquals(2, list.size)
        assertEquals("R-111", list[0].id)
        assertEquals("store", list[0].type)
        assertEquals(posting * 1000, list[0].postingAt) // seconds -> millis
        assertEquals("store", list[1].type) // from itemType
    }

    @Test
    fun `a receipt's details become a receipt with its line items and discounts`() {
        val summary = Salling.Summary("R-111", "store", secondsAt(2026, 10, 5) * 1000, "Netto Taastrup", 186.50)
        val body = """
            [{"id": "R-111", "storeId": 701, "storeName": "Netto Taastrup", "postingDate": ${secondsAt(2026, 10, 5)},
              "transactionNumber": 4455, "transactionCurrency": "DKK", "salesTotal": 186.50, "discountTotal": 12.00,
              "lineItems": [
                {"articleDescription": "Minimælk 1 l", "articleNumber": 123, "qtyInSalesUnit": 2, "salesPrice": 10.95, "salesTotal": 21.90, "discountTotal": 0},
                {"articleDescription": "Kaffe 400 g", "qtyInSalesUnit": 1, "salesPrice": 42.00, "salesTotal": 42.00, "discountTotal": 6.50},
                {"articleDescription": "Udgået", "qtyInSalesUnit": 1, "salesTotal": 0, "discountTotal": 0},
                {"articleDescription": "Pant", "qtyInSalesUnit": 3, "salesPrice": 1.00, "salesTotal": 3.00, "pant": true}
              ]}]
        """.trimIndent()
        val receipt = Salling.parseDetails("Netto", summary, body).receipt
        assertEquals("Netto Taastrup", receipt.storeName)
        assertEquals(186.50, receipt.total, 0.001)
        assertEquals(12.00, receipt.discount, 0.001)
        assertEquals(3, receipt.items.size) // the 0-amount line is dropped
        assertEquals(2.0, receipt.items[0].quantity, 0.001)
        assertEquals(6.50, receipt.items[1].discount, 0.001)
        assertEquals("no:salling:R-111", receipt.externalId)
        assertTrue(receipt.items.any { it.name == "Pant" })
    }

    @Test
    fun `epoch millis are left alone, seconds are scaled, zero is dropped`() {
        assertEquals(1_700_000_000_000L, Salling.epochMillis(1_700_000_000_000L))
        assertEquals(1_700_000_000_000L, Salling.epochMillis(1_700_000_000L))
        assertEquals(null, Salling.epochMillis(0L))
    }
}
