package com.financetracker.app.data.stores

import com.financetracker.app.data.ai.ParsedReceipt
import com.financetracker.app.data.ai.ParsedReceiptItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

/**
 * Nemlig.com. Its website login is a pop-up behind a waiting room that doesn't work in an in-app
 * window, so the e-mail and password are typed into the app and used once (as the Nemlig app does)
 * to make a session; the password is never stored, only the session cookie (valid for a year).
 * Orders come from the order history the website shows. Unofficial: based on the open-source
 * nemlig_cli notes, and it can stop working if Nemlig changes its website.
 */
class Nemlig : StoreConnector {
    override val store = Store.NEMLIG

    override val loginMode = LoginMode.CREDENTIALS

    override suspend fun loginWithCredentials(email: String, password: String): String = withContext(Dispatchers.IO) {
        val http = StoreHttp()
        val headers = mapOf(
            "Accept" to "application/json, text/plain, */*",
            "Device-Size" to "desktop",
            "Platform" to "web",
            "Version" to WEB_VERSION
        )
        fun corr() = mapOf("X-Correlation-Id" to UUID.randomUUID().toString())
        // 1. An anti-forgery token, 2. a short-lived bearer token, 3. the login itself — the same
        // three steps the Nemlig website does. The .ASPXAUTH cookie it sets is what we keep.
        val af = http.request("GET", "$BASE/webapi/AntiForgery", headers + corr())
        if (!af.ok) throw IllegalStateException("Nemlig.com didn't respond (${af.status}). Try again later.")
        val xsrf = runCatching { JSONObject(af.body).optString("Value") }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: http.cookies["XSRF-TOKEN"].orEmpty()
        val tokenResp = http.request("GET", "$BASE/webapi/Token", headers + corr())
        if (!tokenResp.ok) throw IllegalStateException("Nemlig.com didn't respond (${tokenResp.status}). Try again later.")
        val bearer = JSONObject(tokenResp.body).optString("access_token")
        val body = JSONObject()
            .put("Username", email.trim())
            .put("Password", password)
            .put("CheckForExistingProducts", true)
            .put("DoMerge", true)
            .put("AppInstalled", false)
            .put("SaveExistingBasket", false)
            .toString().toByteArray(Charsets.UTF_8)
        val login = http.request(
            "POST", "$BASE/webapi/login",
            headers + corr() + mapOf(
                "X-XSRF-TOKEN" to xsrf,
                "Authorization" to "Bearer $bearer",
                "Referer" to "$BASE/login?returnUrl=%2F"
            ),
            body, "application/json"
        )
        if (login.status == 400 || login.status == 401) throw StoreLoginExpired("Wrong e-mail or password.")
        if (!login.ok) throw IllegalStateException("Nemlig.com login answered ${login.status}.")
        if (http.cookies[AUTH_COOKIE].isNullOrEmpty()) throw StoreLoginExpired("Couldn't sign in — check your e-mail and password.")
        JSONObject()
            .put("cookies", http.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
            .put("ua", StoreHttp.DEFAULT_AGENT)
            .toString()
    }

    override suspend fun fetch(secret: String, since: Long): Pair<List<StoreReceipt>, String> = withContext(Dispatchers.IO) {
        val saved = JSONObject(secret)
        val http = StoreHttp(saved.optString("ua").ifBlank { StoreHttp.DEFAULT_AGENT })
        http.cookies.putAll(StoreHttp.parseCookies(saved.optString("cookies")))
        val base = mapOf(
            "Accept" to "application/json, text/plain, */*",
            "Device-Size" to "mobile",
            "Platform" to "web",
            "Version" to WEB_VERSION,
            "Referer" to "$BASE/"
        )
        fun get(path: String, extra: Map<String, String> = emptyMap()): StoreHttp.Response {
            val r = http.request("GET", BASE + path, base + extra + ("X-Correlation-Id" to UUID.randomUUID().toString()))
            if (r.status == 401 || r.status == 403) throw StoreLoginExpired("Nemlig.com wants you to sign in again.")
            if (!r.ok) throw IllegalStateException("Nemlig.com answered ${r.status}.")
            return r
        }
        val xsrf = runCatching { JSONObject(get("/webapi/AntiForgery").body).optString("Value") }.getOrNull()
            ?: http.cookies["XSRF-TOKEN"].orEmpty()
        val bearer = JSONObject(get("/webapi/Token").body).optString("access_token")
        val auth = mapOf("Authorization" to "Bearer $bearer", "X-XSRF-TOKEN" to xsrf)

        val orders = mutableListOf<JSONObject>()
        val skipped = mutableListOf<Int>()
        var skip = 0
        while (skip < 400) {
            val json = JSONObject(get("/webapi/order/GetBasicOrderHistory?skip=$skip&take=$PAGE", auth).body)
            // A missing list means the answer isn't the order history (e.g. not signed in), not "no orders".
            val page = json.optJSONArray("Orders")
                ?: throw IllegalStateException("Nemlig.com's order history came back in an unknown form")
            var older = false
            for (i in 0 until page.length()) {
                val o = page.getJSONObject(i)
                val at = orderTime(o) ?: continue
                if (at < since) { older = true; continue }
                if (isCharged(o)) orders += o else skipped += o.optInt("Status")
            }
            if (older || page.length() < PAGE) break
            skip += PAGE
        }
        if (http.cookies[AUTH_COOKIE].isNullOrEmpty()) throw StoreLoginExpired("Nemlig.com wants you to sign in again.")
        // Orders were there but none counted as delivered, and not because they're still on the
        // way: say so instead of a silent "0 receipts".
        if (orders.isEmpty() && skipped.any { it !in PENDING }) {
            throw IllegalStateException("${skipped.size} orders found, none delivered yet (status ${skipped.distinct().joinToString()})")
        }
        val results = orders.map { order ->
            runCatching { parseOrder(order, JSONObject(get("/webapi/v2/order/GetOrderHistory/${order.getLong("Id")}", auth).body)) }
        }
        results.firstNotNullOfOrNull { it.exceptionOrNull() as? StoreLoginExpired }?.let { throw it }
        val receipts = results.mapNotNull { it.getOrNull() }
        // Every order's lines failed: report why instead of a silent "0 receipts".
        if (receipts.isEmpty()) results.firstNotNullOfOrNull { it.exceptionOrNull() }?.let { throw it }
        // Keep the cookies as they are now (the site renews them).
        val renewed = JSONObject()
            .put("cookies", http.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
            .put("ua", saved.optString("ua"))
            .toString()
        receipts to renewed
    }

    companion object {
        private const val BASE = "https://www.nemlig.com"
        private const val AUTH_COOKIE = ".ASPXAUTH"
        private const val WEB_VERSION = "11.201.0"
        private const val PAGE = 10
        private const val DELIVERED = 4
        private val PENDING = setOf(1, 2)

        /** Delivered orders are the ones that were charged; one with an invoice was charged too,
         * whatever its status number says. */
        internal fun isCharged(order: JSONObject): Boolean =
            order.optInt("Status") == DELIVERED || order.optBoolean("HasInvoice")

        /** The delivery is when the card is charged, so it's the date that matches the bank. */
        internal fun orderTime(order: JSONObject): Long? =
            StoreJson.time(order.optJSONObject("DeliveryTime")?.let { StoreJson.text(it, "Start") })
                ?: StoreJson.time(StoreJson.text(order, "OrderDate"))

        /** An order and its lines. The delivery fee is the difference between the total and the
         * lines, so it stays in the payment's own category. */
        internal fun parseOrder(order: JSONObject, details: JSONObject): StoreReceipt {
            val lines = details.optJSONArray("Lines")
            val items = mutableListOf<ParsedReceiptItem>()
            val hints = mutableListOf<String?>()
            if (lines != null) for (i in 0 until lines.length()) {
                val line = lines.optJSONObject(i) ?: continue
                val name = StoreJson.text(line, "ProductName") ?: continue
                val paid = (StoreJson.number(line, "Amount") ?: continue) - (StoreJson.number(line, "RefundedAmount") ?: 0.0)
                if (paid <= 0) continue
                val original = StoreJson.number(line, "OriginalAmount") ?: paid
                items += ParsedReceiptItem(
                    name = name.take(80),
                    quantity = StoreJson.number(line, "Quantity")?.takeIf { it > 0 } ?: 1.0,
                    amount = paid,
                    discount = (original - paid).coerceAtLeast(0.0),
                    categoryId = null
                )
                hints += listOfNotNull(StoreJson.text(line, "MainGroupName"), StoreJson.text(line, "GroupName")).joinToString(" / ").ifEmpty { null }
            }
            return StoreReceipt(
                ParsedReceipt(
                    storeName = "Nemlig.com",
                    purchasedAt = orderTime(order) ?: System.currentTimeMillis(),
                    total = StoreJson.number(order, "Total") ?: items.sumOf { it.amount },
                    discount = items.sumOf { it.discount },
                    receiptNumber = "nemlig:${StoreJson.text(order, "OrderNumber") ?: order.optLong("Id")}",
                    items = items
                ),
                hints
            )
        }
    }
}
