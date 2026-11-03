package com.financetracker.app

import com.financetracker.app.data.stores.LidlPlus
import com.financetracker.app.data.stores.Nemlig
import com.financetracker.app.data.stores.StoreHttp
import com.financetracker.app.data.stores.StoreJson
import com.financetracker.app.ui.components.StoreLogos
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class StoreReceiptsTest {

    @Test
    fun `amounts are read whether written as numbers or Danish text`() {
        val o = JSONObject("""{"a": 12.5, "b": "2,19", "c": "1.234,50", "d": null, "e": "kr. 10,00"}""")
        assertEquals(12.5, StoreJson.number(o, "a")!!, 0.001)
        assertEquals(2.19, StoreJson.number(o, "b")!!, 0.001)
        assertEquals(1234.5, StoreJson.number(o, "c")!!, 0.001)
        assertNull(StoreJson.number(o, "d"))
        assertNull(StoreJson.number(o, "missing"))
        assertEquals(10.0, StoreJson.number(o, "e")!!, 0.001)
    }

    @Test
    fun `store times are read in their own time zone`() {
        val utc = StoreJson.time("2026-10-05T10:00:00Z")!!
        val withZone = StoreJson.time("2026-10-05T12:00:00+02:00")!!
        assertEquals(utc, withZone)
        val local = StoreJson.time("2026-10-05T12:00:00.123")!!
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Europe/Copenhagen")).apply { timeInMillis = local }
        assertEquals(12, cal.get(Calendar.HOUR_OF_DAY))
        assertNotNull(StoreJson.time("2026-10-05"))
        assertNull(StoreJson.time("not a date"))
    }

    @Test
    fun `a Lidl Plus ticket becomes a receipt with discounts taken off each line`() {
        val ticket = JSONObject(
            """
            {"id": "abc", "date": "2026-10-05T17:42:00+02:00", "totalAmount": "65,29",
             "store": {"name": "Taastrup Hovedgade"},
             "itemsLine": [
               {"name": "Vegane Frikadellen", "currentUnitPrice": "21,90", "quantity": "1", "originalAmount": "21,90",
                "discounts": [{"description": "Lidl Plus kupon", "amount": "4,40"}]},
               {"name": "Bananer", "currentUnitPrice": "14,95", "quantity": "1,254", "isWeight": true, "originalAmount": "18,75", "discounts": []},
               {"name": "Pant", "currentUnitPrice": "1,00", "quantity": "2", "originalAmount": "2,00"},
               {"name": "Kaffe", "currentUnitPrice": "27,04", "quantity": "1"}
             ]}
            """.trimIndent()
        )
        val r = LidlPlus.parseTicket(ticket, "abc").receipt
        assertEquals("Lidl Taastrup Hovedgade", r.storeName)
        assertEquals(65.29, r.total, 0.001)
        assertEquals(4.40, r.discount, 0.001)
        assertEquals(4, r.items.size)
        assertEquals(17.50, r.items[0].amount, 0.001)
        assertEquals(1.254, r.items[1].quantity, 0.0001)
        assertEquals(27.04, r.items[3].amount, 0.001)
        assertEquals("no:lidl:abc", r.externalId)
        // A ticket with only the printed receipt keeps its total.
        val bare = LidlPlus.parseTicket(JSONObject("""{"date":"2026-10-05T10:00:00","totalAmount":99.5}"""), "x").receipt
        assertEquals(99.5, bare.total, 0.001)
        assertEquals("Lidl", bare.storeName)
    }

    @Test
    fun `a Nemlig order counts as charged when delivered or invoiced`() {
        assertEquals(true, Nemlig.isCharged(JSONObject("""{"Status": 4}""")))
        assertEquals(true, Nemlig.isCharged(JSONObject("""{"Status": 6, "HasInvoice": true}""")))
        assertEquals(false, Nemlig.isCharged(JSONObject("""{"Status": 1, "HasInvoice": false}""")))
    }

    @Test
    fun `a Nemlig order is dated by its delivery and leaves the delivery fee out of the lines`() {
        val order = JSONObject(
            """
            {"Status": 4, "OrderNumber": "2026-12345678", "Total": 604.48, "SubTotal": 574.48, "Id": 12345678,
             "OrderDate": "2026-09-25T06:07:18Z", "DeliveryTime": {"Start": "2026-09-27T17:00:00Z", "End": "2026-09-27T20:00:00Z"}}
            """.trimIndent()
        )
        val details = JSONObject(
            """
            {"Lines": [
              {"ProductName": "Black Coffee Blend Supreme", "Quantity": 2.0, "Amount": 180.0, "OriginalAmount": 199.9,
               "GroupName": "Kaffe, hele bønner", "MainGroupName": "Drikke", "RefundedAmount": 0.0},
              {"ProductName": "Cocio kakaomælk", "Quantity": 1.0, "Amount": 119.0, "OriginalAmount": 119.0, "MainGroupName": "Drikke"},
              {"ProductName": "Udsolgt vare", "Quantity": 1.0, "Amount": 20.0, "OriginalAmount": 20.0, "RefundedAmount": 20.0},
              {"ProductName": "Diverse", "Quantity": 1.0, "Amount": 275.48, "OriginalAmount": 275.48}
            ]}
            """.trimIndent()
        )
        val parsed = Nemlig.parseOrder(order, details)
        val r = parsed.receipt
        assertEquals("Nemlig.com", r.storeName)
        assertEquals(StoreJson.time("2026-09-27T17:00:00Z"), r.purchasedAt)
        assertEquals(604.48, r.total, 0.001)
        assertEquals(3, r.items.size) // the refunded line is gone
        assertEquals(19.9, r.items[0].discount, 0.001)
        assertEquals("Drikke / Kaffe, hele bønner", parsed.hints[0])
        assertEquals("no:nemlig:2026-12345678", r.externalId)
    }

    @Test
    fun `store names are matched to their chain's logo`() {
        assertEquals("netto.dk", StoreLogos.domainFor("Netto Taastrup"))
        assertEquals("superbrugsen.dk", StoreLogos.domainFor("SuperBrugsen Hvidovre"))
        assertEquals("brugsen.dk", StoreLogos.domainFor("Dagli'Brugsen"))
        assertEquals("coop365.dk", StoreLogos.domainFor("Coop 365"))
        assertEquals("coop.dk", StoreLogos.domainFor("Coop"))
        assertEquals("foetex.dk", StoreLogos.domainFor("Føtex Food"))
        assertEquals("rema1000.dk", StoreLogos.domainFor("REMA 1000"))
        assertEquals("lidl.dk", StoreLogos.domainFor("Lidl Taastrup Hovedgade"))
        assertNull(StoreLogos.domainFor("Bager Hansen"))
    }

    @Test
    fun `cookies are read from a cookie header`() {
        val map = StoreHttp.parseCookies("a=1; .ASPXAUTH=XYZ==; flag; b = 2")
        assertEquals("XYZ==", map[".ASPXAUTH"])
        assertEquals("2", map["b"])
        assertNull(map["flag"])
        // Nemlig signs in with e-mail and password in the app.
        assertEquals(com.financetracker.app.data.stores.LoginMode.CREDENTIALS, Nemlig().loginMode)
    }
}
