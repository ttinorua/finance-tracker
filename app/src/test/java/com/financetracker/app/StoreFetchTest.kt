package com.financetracker.app

import com.financetracker.app.data.stores.Store
import com.financetracker.app.data.stores.StoreAccount
import com.financetracker.app.data.stores.StoreAccounts
import com.financetracker.app.data.stores.StoreSync
import org.junit.Assert.assertEquals
import org.junit.Test

class StoreFetchTest {
    private fun account(lastSyncAt: Long, added: Int) = StoreAccount(
        id = "a", store = Store.NETTO, name = "Your Netto+ account", secret = "s",
        connectedAt = 1L, lastSyncAt = lastSyncAt, lastReceiptAt = 5L, receiptCount = 7, lastFetchAdded = added
    )

    @Test
    fun `after a restore every account fetches its last 12 months again`() {
        val reset = StoreAccounts.withFetchDatesReset(listOf(account(lastSyncAt = 1_000L, added = 3)))
        assertEquals(0L, reset.single().lastSyncAt)
        assertEquals(0, reset.single().lastFetchAdded)
        // The sign-in and everything else stays.
        assertEquals("s", reset.single().secret)
        assertEquals(5L, reset.single().lastReceiptAt)
    }

    @Test
    fun `fetch now says what happened`() {
        fun text(added: Int, problem: String? = null) =
            StoreSync.resultText(StoreSync.FetchResult("a", Store.NETTO, added, problem))
        assertEquals("No new receipts from Netto+", text(0))
        assertEquals("1 new receipt from Netto+", text(1))
        assertEquals("4 new receipts from Netto+", text(4))
        assertEquals("Netto+: Sign in again", text(0, "Sign in again"))
    }
}
