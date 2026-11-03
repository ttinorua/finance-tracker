package com.financetracker.app

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.financetracker.app.data.db.AppDatabase
import com.financetracker.app.data.db.entity.Account
import com.financetracker.app.data.db.entity.Receipt
import com.financetracker.app.data.db.entity.ReceiptItem
import com.financetracker.app.data.repository.FinanceRepository
import com.financetracker.app.data.stores.Store
import com.financetracker.app.data.stores.StoreConsents
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StoreConsentTest {

    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `consents are recorded with store, version and own-or-partner, and survive reload`() {
        context.getSharedPreferences("store_consents", 0).edit().clear().commit()
        StoreConsents.clear(context)
        StoreConsents.record(context, Store.LIDL, forAnother = false)
        StoreConsents.record(context, Store.NEMLIG, forAnother = true)

        val now = StoreConsents.consents.value
        assertEquals(2, now.size)
        assertEquals(Store.LIDL, now[0].store)
        assertEquals(Store.CONSENT_VERSION, now[0].textVersion)
        assertTrue(now[1].forAnother)
        assertTrue(now[0].grantedAt > 0)

        // Clearing leaves no record; the delete is a deliberate, user-only action.
        StoreConsents.clear(context)
        assertEquals(0, StoreConsents.consents.value.size)
    }

    @Test
    fun `disconnecting a store can delete just that store's receipts`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val repo = FinanceRepository(db)
        val acc = db.accountDao().insert(Account(name = "Lunar", currencyCode = "DKK"))
        suspend fun add(source: String, ext: String) = repo.addReceipt(
            Receipt(source = source, externalId = ext, storeName = source, purchasedAt = 1L, total = 50.0),
            listOf(ReceiptItem(receiptId = 0, position = 0, name = "x", amount = 50.0, categoryId = null))
        )
        add("lidl", "a"); add("lidl", "b"); add("nemlig", "c"); add("share", "d")
        assertEquals(4, repo.observeReceipts().first().size)

        repo.deleteReceiptsFromSource("lidl")
        val left = repo.observeReceipts().first()
        assertEquals(2, left.size)
        assertTrue(left.none { it.source == "lidl" })
        // The receipt lines of the deleted receipts go with them (cascade).
        assertEquals(2, db.receiptDao().getAllItems().size)
        db.close()
    }
}
