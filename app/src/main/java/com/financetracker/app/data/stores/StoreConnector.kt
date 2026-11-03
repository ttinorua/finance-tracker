package com.financetracker.app.data.stores

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** How a store is signed in to. Most open the store's own login page in a window in the app
 * ([WEBVIEW]); a few (Nemlig.com) have no usable login page in a window, so the e-mail and
 * password are typed into the app and used once to make a session ([CREDENTIALS]). */
enum class LoginMode { WEBVIEW, CREDENTIALS }

/**
 * How the app signs in to a store and fetches its receipts. For a [LoginMode.WEBVIEW] store the
 * person signs in on the store's own page in a window and the app only keeps what the store hands
 * back (a sign-in code or cookie). For a [LoginMode.CREDENTIALS] store the person types their
 * e-mail and password into the app; the password is used once to make a session and never stored.
 */
interface StoreConnector {
    val store: Store

    /** How this store is signed in to. */
    val loginMode: LoginMode get() = LoginMode.WEBVIEW

    /** True when the login page needs the e-mail/phone up front (Salling), so the app asks for it
     * before opening the window and passes it into [loginUrl]. */
    val needsEmailFirst: Boolean get() = false

    /** JavaScript that puts [password] into the store's own login page and submits it, for a store
     * whose page doesn't take typing well (Salling: the cursor jumps). The password is then typed
     * in the app, passed only into that page and never stored. Returns "done" once filled, "wait"
     * while the page isn't ready. Null when the password is typed on the page itself. */
    fun fillPasswordScript(password: String): String? = null

    /** JavaScript run on the login page after [fillPasswordScript] has signed in, to notice that
     * the store turned the sign-in down. Returns "" while there's no error, "invalid" for a wrong
     * e-mail or password, else the page's own error text. */
    fun loginErrorScript(): String? = null

    /** JavaScript run once when the login page, after signing in, shows nothing to fill in or
     * press: the store's chance to bring back a step the window doesn't display. Returns a short
     * note of what it found, added to the report if the page stays stuck. Null when there's none. */
    fun stuckPageScript(): String? = null

    /** The store's login page to open ([LoginMode.WEBVIEW] only). [emailHint] prefills the
     * e-mail/phone for stores that require it in the address (see [needsEmailFirst]). */
    fun loginUrl(emailHint: String? = null): String = ""

    /** Signs in with the typed e-mail and password ([LoginMode.CREDENTIALS] only), returning the
     * account's stored sign-in. The password is not kept. Throws [StoreLoginExpired] on a wrong
     * e-mail or password. */
    suspend fun loginWithCredentials(email: String, password: String): String =
        throw UnsupportedOperationException()

    /** Called for every address the login window is about to open. Returns what the store handed
     * back when this is the end of the login (e.g. the sign-in code), else null. */
    fun captureFromUrl(url: String): String? = null

    /** The site whose cookies [captureFromCookies] looks at, or null. */
    val cookieUrl: String? get() = null

    /** Called after each page has loaded, with the cookies the login window has for the store.
     * Returns them when they show the person is signed in, else null. */
    fun captureFromCookies(cookies: String?, userAgent: String): String? = null

    /** Turns what was captured into the account's stored sign-in ([LoginMode.WEBVIEW] only). */
    suspend fun finishLogin(captured: String): String = throw UnsupportedOperationException()

    /** Receipts from [since] on. Returns them and the sign-in to keep (it may have been renewed).
     * Throws [StoreLoginExpired] when the person needs to sign in again. */
    suspend fun fetch(secret: String, since: Long): Pair<List<StoreReceipt>, String>

    companion object {
        fun forStore(store: Store): StoreConnector? = when (store) {
            Store.LIDL -> LidlPlus()
            Store.NEMLIG -> Nemlig()
            Store.NETTO -> Salling.netto()
            Store.BILKA -> Salling.bilka()
            Store.FOETEX -> Salling.foetex()
            Store.REMA -> Rema1000()
            Store.COOP -> null
        }
    }
}

/** Helpers for reading the stores' JSON, which writes amounts as numbers or as "12,95". */
internal object StoreJson {
    fun number(o: JSONObject?, key: String): Double? {
        if (o == null || !o.has(key) || o.isNull(key)) return null
        val v = o.opt(key)
        if (v is Number) return v.toDouble()
        val s = v.toString().trim().replace(Regex("[^0-9,.-]"), "")
        if (s.isEmpty()) return null
        val normalised = when {
            s.contains(',') && s.contains('.') -> if (s.lastIndexOf(',') > s.lastIndexOf('.')) s.replace(".", "").replace(',', '.') else s.replace(",", "")
            s.contains(',') -> s.replace(',', '.')
            else -> s
        }
        return normalised.toDoubleOrNull()
    }

    fun text(o: JSONObject?, key: String): String? =
        o?.optString(key)?.trim()?.takeUnless { o.isNull(key) || it.isEmpty() || it == "null" }

    /** ISO 8601 date-time ("2026-10-05T14:23:00", with or without seconds, fraction or zone). */
    fun time(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        val v = value.trim().replace(Regex("\\.\\d+"), "")
        val patterns = listOf("yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd")
        for (p in patterns) {
            val format = SimpleDateFormat(p, Locale.US).apply {
                isLenient = false
                if (p.endsWith("'Z'")) timeZone = TimeZone.getTimeZone("UTC") else timeZone = TimeZone.getTimeZone("Europe/Copenhagen")
            }
            runCatching { return format.parse(v)!!.time }
        }
        return null
    }
}
