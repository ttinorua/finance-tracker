package com.financetracker.app.data.stores

import com.financetracker.app.data.ai.ParsedReceipt

/** The store chains whose receipts can be fetched from the customer's own account. */
enum class Store(
    val id: String,
    val label: String,
    /** Words a bank transaction from this chain contains (for the 12-month spend on the intro). */
    val bankWords: List<String>,
    /** Shown under the name on the connections page. */
    val loginNote: String,
    /** The company, for sentences like "Lidl's own login page". */
    val company: String,
    val available: Boolean = true
) {
    LIDL("lidl", "Lidl Plus", listOf("lidl"), "Lidl login", "Lidl"),
    NEMLIG("nemlig", "Nemlig.com", listOf("nemlig"), "Online orders", "Nemlig.com"),
    NETTO("netto", "Netto+", listOf("netto"), "Salling Group login", "Netto"),
    BILKA("bilka", "Bilka+", listOf("bilka"), "Salling Group login", "Bilka"),
    FOETEX("foetex", "Føtex+", listOf("føtex", "foetex", "fotex"), "Salling Group login", "Føtex"),
    REMA("rema", "REMA 1000", listOf("rema"), "Scan Selv receipts", "REMA 1000"),
    COOP("coop", "Coop", listOf("coop", "kvickly", "superbrugsen", "brugsen", "dagli", "irma", "fakta"), "Coop login", "Coop", available = false);

    companion object {
        fun byId(id: String): Store? = entries.firstOrNull { it.id == id }

        /** The consent text shown on the "What happens" screen. Bump this whenever that text
         * changes, so each stored consent records which wording the person agreed to. */
        const val CONSENT_VERSION = 1
    }
}

/** A record that the person agreed to fetch a store's receipts: when, which store, which version
 * of the consent text, and whether it was their own account or someone else's (a partner's).
 * Kept as proof of the consent, and not removed when the account is disconnected. */
data class StoreConsent(
    val store: Store,
    val grantedAt: Long,
    val textVersion: Int,
    val forAnother: Boolean
)

/** One connected store account (yours, or e.g. your partner's). [secret] is the store's sign-in
 * (a refresh token or the login cookies) and is only ever kept encrypted on this phone. */
data class StoreAccount(
    val id: String,
    val store: Store,
    val name: String,
    val secret: String,
    val connectedAt: Long,
    val lastSyncAt: Long = 0L,
    val lastReceiptAt: Long = 0L,
    val receiptCount: Int = 0,
    /** How many receipts the last successful fetch added. */
    val lastFetchAdded: Int = 0,
    /** Set when the last fetch failed, e.g. the store wants you to sign in again. */
    val problem: String? = null
)

/** A receipt fetched from a store: [receipt] as if it had been read, plus the item hints the store
 * gives (its own product group), which help sorting items into categories. */
data class StoreReceipt(val receipt: ParsedReceipt, val hints: List<String?> = emptyList())

class StoreLoginExpired(message: String) : Exception(message)

/** A fetch that failed after the store had already renewed the sign-in. [secret] is the renewed
 * sign-in and must be kept: the old one stops working once it has been renewed. */
class StoreFetchFailed(val secret: String, cause: Exception) : Exception(cause.message, cause)
