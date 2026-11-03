package com.financetracker.app.data.repository

import androidx.room.withTransaction
import com.financetracker.app.data.db.AppDatabase
import com.financetracker.app.data.db.entity.Account
import com.financetracker.app.data.db.entity.Category
import com.financetracker.app.data.db.entity.Transaction
import com.financetracker.app.data.db.entity.TransactionType
import com.financetracker.app.data.db.entity.TransactionWithDetails
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import com.financetracker.app.data.db.entity.Receipt
import com.financetracker.app.data.db.entity.ReceiptItem
import com.financetracker.app.data.db.entity.ReceiptItemDetails
import com.financetracker.app.util.ReceiptSplits

class FinanceRepository(private val db: AppDatabase) {

    private val accountDao = db.accountDao()
    private val categoryDao = db.categoryDao()
    private val transactionDao = db.transactionDao()
    private val receiptDao = db.receiptDao()

    // Accounts
    fun observeAccounts(): Flow<List<Account>> = accountDao.observeAll()
    suspend fun getAccounts(): List<Account> = accountDao.getAll()
    suspend fun upsertAccount(account: Account): Long = accountDao.insert(account)
    suspend fun updateAccount(account: Account) = accountDao.update(account)
    suspend fun deleteAccount(account: Account) = accountDao.delete(account)

    suspend fun getAccountBalance(account: Account): Double {
        return account.initialBalance + accountDao.getTransactionDelta(account.id)
    }

    suspend fun getOrCreateAccount(name: String): Account {
        return accountDao.getByName(name) ?: run {
            val id = accountDao.insert(Account(name = name))
            Account(id = id, name = name)
        }
    }

    // Categories
    fun observeCategories(): Flow<List<Category>> = categoryDao.observeAll()
    suspend fun getCategories(): List<Category> = categoryDao.getAll()
    suspend fun upsertCategory(category: Category): Long = categoryDao.insert(category)
    suspend fun updateCategory(category: Category) = categoryDao.update(category)
    suspend fun deleteCategory(category: Category) = categoryDao.delete(category)

    /** Moves every transaction and receipt line from [from] onto [toId], then deletes [from]. */
    suspend fun mergeCategoryInto(from: Category, toId: Long) {
        db.withTransaction {
            transactionDao.moveCategory(from.id, toId)
            receiptDao.moveCategory(from.id, toId)
            categoryDao.delete(from)
        }
    }

    suspend fun getOrCreateCategory(
        mainCategory: String,
        name: String,
        type: TransactionType,
        colorHex: String = "#607D8B"
    ): Category {
        val trimmedMain = mainCategory.ifBlank { "Uncategorized" }
        val trimmedName = name.ifBlank { "Uncategorized" }
        return categoryDao.getByMainAndNameAndType(trimmedMain, trimmedName, type) ?: run {
            val category = Category(name = trimmedName, mainCategory = trimmedMain, type = type, colorHex = colorHex)
            val id = categoryDao.insert(category)
            category.copy(id = id)
        }
    }

    // Transactions
    /** Every transaction, with a purchase split by its receipt shown as its parts (see
     * [ReceiptSplits]) — so every total, budget and breakdown counts the receipt's categories. */
    fun observeTransactions(): Flow<List<TransactionWithDetails>> =
        combine(transactionDao.observeAllWithDetails(), receiptDao.observeAllItemDetails()) { transactions, items ->
            ReceiptSplits.expand(transactions, items)
        }

    suspend fun addTransaction(transaction: Transaction): Long = transactionDao.insert(transaction)

    suspend fun addTransactions(transactions: List<Transaction>): List<Long> =
        transactionDao.insertAll(transactions)

    suspend fun getTransactionsForAccount(accountId: Long): List<Transaction> =
        transactionDao.getByAccountId(accountId)

    suspend fun getAllTransactions(): List<Transaction> = transactionDao.getAll()

    suspend fun getTransaction(id: Long): Transaction? = transactionDao.getById(ReceiptSplits.originalId(id))

    suspend fun updateTransaction(transaction: Transaction) = transactionDao.update(transaction)

    suspend fun deleteTransaction(transaction: Transaction) = transactionDao.delete(transaction)

    fun observeNetBalance(): Flow<Double> = transactionDao.observeNetBalance()

    // Receipts
    fun observeReceipts(): Flow<List<Receipt>> = receiptDao.observeAll()
    fun observeReceipt(id: Long): Flow<Receipt?> = receiptDao.observe(id)
    fun observeReceiptItems(receiptId: Long): Flow<List<ReceiptItem>> = receiptDao.observeItems(receiptId)
    fun observeReceiptItemDetails(): Flow<List<ReceiptItemDetails>> = receiptDao.observeAllItemDetails()
    fun observeTransactionIdsWithReceipt(): Flow<List<Long>> = receiptDao.observeMatchedTransactionIds()
    suspend fun getReceipt(id: Long): Receipt? = receiptDao.get(id)
    suspend fun getReceiptForTransaction(transactionId: Long): Receipt? = receiptDao.getForTransaction(ReceiptSplits.originalId(transactionId))
    suspend fun findReceipt(source: String, externalId: String): Receipt? = receiptDao.find(source, externalId)
    suspend fun addReceipt(receipt: Receipt, items: List<ReceiptItem>): Long = receiptDao.insertWithItems(receipt, items)
    suspend fun updateReceipt(receipt: Receipt) = receiptDao.updateReceipt(receipt)
    suspend fun updateReceiptItem(item: ReceiptItem) = receiptDao.updateItem(item)
    suspend fun deleteReceipt(id: Long) = receiptDao.deleteReceipt(id)
    suspend fun deleteReceiptsFromSource(source: String) = receiptDao.deleteFromSource(source)
    suspend fun getAllReceiptItems(): List<ReceiptItem> = receiptDao.getAllItems()
}
