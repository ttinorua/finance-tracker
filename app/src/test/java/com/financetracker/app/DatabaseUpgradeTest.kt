package com.financetracker.app

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.financetracker.app.data.db.AppDatabase
import com.financetracker.app.data.db.Migrations
import com.financetracker.app.data.db.entity.Account
import com.financetracker.app.data.db.entity.Category
import com.financetracker.app.data.db.entity.Receipt
import com.financetracker.app.data.db.entity.ReceiptItem
import com.financetracker.app.data.db.entity.Transaction
import com.financetracker.app.data.db.entity.TransactionType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Upgrading the app must keep everyone's data: a version 2 database (before receipts) opens as
 * version 3 with every account, category and transaction still there, and receipts work. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DatabaseUpgradeTest {

    private val name = "upgrade-test.db"

    private fun open(): AppDatabase {
        val context = ApplicationProvider.getApplicationContext<Application>()
        return Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*Migrations.ALL)
            .allowMainThreadQueries()
            .build()
    }

    @Test
    fun `a version 2 database keeps its data when upgraded`(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.deleteDatabase(name)

        // Version 2 is version 3 without the receipt tables: build it that way.
        open().apply {
            val account = accountDao().insert(Account(name = "Lunar", currencyCode = "DKK"))
            val category = categoryDao().insert(Category(name = "Groceries", mainCategory = "Food", type = TransactionType.EXPENSE))
            transactionDao().insert(Transaction(amount = 412.5, type = TransactionType.EXPENSE, accountId = account, categoryId = category, date = 1_700_000_000_000, note = "NETTO"))
            close()
        }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).apply {
            execSQL("DROP TABLE receipt_items")
            execSQL("DROP TABLE receipts")
            version = 2
            close()
        }

        val db = open()
        val transactions = db.transactionDao().getAll()
        assertEquals(1, transactions.size)
        assertEquals("NETTO", transactions[0].note)
        assertEquals(1, db.accountDao().getAll().size)
        assertEquals(1, db.categoryDao().getAll().size)

        val category = db.categoryDao().getAll()[0].id
        val receiptId = db.receiptDao().insertWithItems(
            Receipt(source = "share", externalId = "no:1", storeName = "Netto", purchasedAt = 1_700_000_000_000, total = 412.5, transactionId = transactions[0].id, split = true),
            listOf(ReceiptItem(receiptId = 0, position = 0, name = "Mælk", amount = 12.5, categoryId = category))
        )
        assertEquals(1, db.receiptDao().getAllItems().size)
        assertEquals(transactions[0].id, db.receiptDao().getForTransaction(transactions[0].id)?.transactionId)

        // Deleting the receipt takes its lines; deleting the payment just unlinks a receipt.
        db.receiptDao().deleteReceipt(receiptId)
        assertEquals(0, db.receiptDao().getAllItems().size)
        db.close()
        context.deleteDatabase(name)
    }
}
