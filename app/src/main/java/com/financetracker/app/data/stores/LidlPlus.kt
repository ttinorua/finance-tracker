package com.financetracker.app.data.stores

import android.net.Uri
import android.util.Base64
import com.financetracker.app.data.ai.ParsedReceipt
import com.financetracker.app.data.ai.ParsedReceiptItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.math.abs

/**
 * Lidl Plus (Denmark). Login is Lidl's own sign-in page with the same sign-in setup as the Lidl
 * Plus app (OAuth with PKCE); the page hands back a one-time code, which the app swaps for a
 * refresh token. Receipts come from the tickets service the Lidl Plus app uses. Unofficial: based
 * on the open-source lidl-plus projects, and it can stop working if Lidl changes it.
 */
class LidlPlus : StoreConnector {
    override val store = Store.LIDL

    private val verifier: String = ByteArray(48).also { SecureRandom().nextBytes(it) }
        .let { Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP) }

    override fun loginUrl(emailHint: String?): String {
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
        return "$AUTH/connect/authorize?client_id=$CLIENT_ID&response_type=code" +
            "&scope=${enc("openid profile offline_access lpprofile lpapis")}" +
            "&redirect_uri=${enc(REDIRECT)}&code_challenge=$challenge&code_challenge_method=S256" +
            "&Country=$COUNTRY&language=$LANGUAGE-$COUNTRY"
    }

    override fun captureFromUrl(url: String): String? {
        if (!url.startsWith(REDIRECT)) return null
        return Uri.parse(url).getQueryParameter("code")
    }

    override suspend fun finishLogin(captured: String): String = withContext(Dispatchers.IO) {
        token(
            "grant_type" to "authorization_code",
            "code" to captured,
            "redirect_uri" to REDIRECT,
            "code_verifier" to verifier
        ).second
    }

    override suspend fun fetch(secret: String, since: Long): Pair<List<StoreReceipt>, String> = withContext(Dispatchers.IO) {
        val (access, refresh) = token("grant_type" to "refresh_token", "refresh_token" to secret)
        try {
            fetchTickets(access, since) to refresh
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The refresh token was renewed (the old one is used up): hand it back so it's kept.
            throw StoreFetchFailed(refresh, e)
        }
    }

    private fun fetchTickets(access: String, since: Long): List<StoreReceipt> {
        val http = StoreHttp(AGENT)
        // The full set of headers the Lidl Plus app sends with the tickets request; the service
        // rejects the request (401) without them.
        val headers = mapOf(
            "Authorization" to "Bearer $access",
            "App-Version" to APP_VERSION,
            "Operating-System" to "Android",
            "App" to "com.lidl.eci.lidlplus",
            "Accept-Language" to LANGUAGE,
            "Accept" to "application/json",
            "OS-Version" to "14",
            "Model" to "Pixel 7",
            "Brand" to "Google",
            "deviceid" to DEVICE_ID,
            "country" to COUNTRY
        )
        val ids = mutableListOf<String>()
        var page = 1
        while (page <= 40) {
            val response = http.request("GET", "$TICKETS/v2/$COUNTRY/tickets?pageNumber=$page&onlyFavorite=false", headers)
            if (response.status == 401) throw StoreLoginExpired("Lidl Plus wants you to sign in again.")
            if (!response.ok) throw IllegalStateException("Lidl Plus answered ${response.status}.")
            val json = JSONObject(response.body)
            val tickets = json.optJSONArray("tickets") ?: break
            var older = false
            for (i in 0 until tickets.length()) {
                val t = tickets.getJSONObject(i)
                val at = StoreJson.time(StoreJson.text(t, "date")) ?: continue
                if (at < since) { older = true; continue }
                StoreJson.text(t, "id")?.let(ids::add)
            }
            val size = json.optInt("size", tickets.length()).coerceAtLeast(1)
            val total = json.optInt("totalCount", 0)
            if (older || tickets.length() == 0 || page * size >= total) break
            page++
        }
        return ids.mapNotNull { id ->
            val response = http.request("GET", "$TICKETS/v3/$COUNTRY/tickets/$id", headers)
            if (!response.ok) null else runCatching { parseTicket(JSONObject(response.body), id) }.getOrNull()
        }
    }

    /** Access and refresh token from Lidl's token service. */
    private fun token(vararg fields: Pair<String, String>): Pair<String, String> {
        val basic = Base64.encodeToString("$CLIENT_ID:secret".toByteArray(), Base64.NO_WRAP)
        val body = fields.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }.toByteArray()
        val response = StoreHttp(AGENT).request(
            "POST", "$AUTH/connect/token",
            mapOf("Authorization" to "Basic $basic", "Accept" to "application/json"),
            body, "application/x-www-form-urlencoded"
        )
        if (response.status == 400 || response.status == 401) throw StoreLoginExpired("Lidl Plus wants you to sign in again.")
        if (!response.ok) throw IllegalStateException("Lidl Plus sign-in answered ${response.status}.")
        val json = JSONObject(response.body)
        return json.getString("access_token") to json.optString("refresh_token", fields.toMap()["refresh_token"].orEmpty())
    }

    companion object {
        private const val CLIENT_ID = "LidlPlusNativeClient"
        private const val AUTH = "https://accounts.lidl.com"
        private const val TICKETS = "https://tickets.lidlplus.com/api"
        private const val REDIRECT = "com.lidlplus.app://callback"
        private const val COUNTRY = "DK"
        private const val LANGUAGE = "da"
        private const val APP_VERSION = "16.43.4"
        private const val AGENT = "okhttp/5.4.0"
        // A fixed device id, as the app sends one; the tickets service expects the header present.
        private const val DEVICE_ID = "f7a31c44276c0651"

        private fun enc(v: String) = URLEncoder.encode(v, "UTF-8").replace("+", "%20")

        /** A Lidl Plus ticket. Lines carry their price before discounts and the discounts
         * separately; the line is what was paid after them. A ticket without lines (only the
         * printed receipt) keeps its total, so it still counts. */
        internal fun parseTicket(json: JSONObject, id: String): StoreReceipt {
            val at = StoreJson.time(StoreJson.text(json, "date")) ?: System.currentTimeMillis()
            val lines = json.optJSONArray("itemsLine")
            val items = mutableListOf<ParsedReceiptItem>()
            var discounts = 0.0
            if (lines != null) for (i in 0 until lines.length()) {
                val line = lines.optJSONObject(i) ?: continue
                val name = StoreJson.text(line, "name") ?: StoreJson.text(line, "description") ?: continue
                val original = StoreJson.number(line, "originalAmount")
                    ?: (StoreJson.number(line, "currentUnitPrice") ?: continue) * (StoreJson.number(line, "quantity") ?: 1.0)
                val lineDiscounts = line.optJSONArray("discounts")?.let { d ->
                    (0 until d.length()).sumOf { abs(StoreJson.number(d.optJSONObject(it), "amount") ?: 0.0) }
                } ?: 0.0
                discounts += lineDiscounts
                items += ParsedReceiptItem(
                    name = name.trim().take(80),
                    quantity = StoreJson.number(line, "quantity")?.takeIf { it > 0 } ?: 1.0,
                    amount = original - lineDiscounts,
                    discount = lineDiscounts,
                    categoryId = null
                )
            }
            val total = StoreJson.number(json, "totalAmount") ?: StoreJson.number(json, "total") ?: items.sumOf { it.amount }
            val storeName = json.optJSONObject("store")?.let { StoreJson.text(it, "name") }
            return StoreReceipt(
                ParsedReceipt(
                    storeName = if (storeName != null && !storeName.contains("lidl", ignoreCase = true)) "Lidl $storeName" else "Lidl",
                    purchasedAt = at,
                    total = total,
                    discount = StoreJson.number(json, "totalDiscount")?.let { abs(it) } ?: discounts,
                    receiptNumber = "lidl:$id",
                    items = items
                )
            )
        }
    }
}
