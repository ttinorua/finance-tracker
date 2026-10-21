package com.financetracker.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.financetracker.app.data.db.entity.Receipt
import com.financetracker.app.data.db.entity.ReceiptItem
import com.financetracker.app.data.db.entity.ReceiptItemDetails
import kotlinx.coroutines.flow.Flow

@Dao
interface ReceiptDao {
    @Query("UPDATE receipt_items SET categoryId = :toId WHERE categoryId = :fromId")
    suspend fun moveCategory(fromId: Long, toId: Long)

    @Query("SELECT * FROM receipts ORDER BY purchasedAt DESC")
    fun observeAll(): Flow<List<Receipt>>

    @Query("SELECT * FROM receipts WHERE id = :id")
    fun observe(id: Long): Flow<Receipt?>

    @Query("SELECT * FROM receipts WHERE id = :id")
    suspend fun get(id: Long): Receipt?

    @Query("SELECT * FROM receipts WHERE transactionId = :transactionId LIMIT 1")
    suspend fun getForTransaction(transactionId: Long): Receipt?

    @Query("SELECT * FROM receipts WHERE source = :source AND externalId = :externalId LIMIT 1")
    suspend fun find(source: String, externalId: String): Receipt?

    @Query("SELECT transactionId FROM receipts WHERE transactionId IS NOT NULL")
    fun observeMatchedTransactionIds(): Flow<List<Long>>

    @Query("SELECT * FROM receipt_items WHERE receiptId = :receiptId ORDER BY position")
    fun observeItems(receiptId: Long): Flow<List<ReceiptItem>>

    @Query(
        """
        SELECT i.id AS itemId, i.receiptId, r.transactionId, r.split, r.storeName, r.purchasedAt,
               i.name, i.quantity, i.amount, i.discount, i.categoryId,
               c.name AS categoryName, c.mainCategory AS mainCategoryName, c.colorHex AS categoryColorHex
        FROM receipt_items i
        JOIN receipts r ON r.id = i.receiptId
        LEFT JOIN categories c ON c.id = i.categoryId
        ORDER BY r.purchasedAt DESC, i.position
        """
    )
    fun observeAllItemDetails(): Flow<List<ReceiptItemDetails>>

    @Query("SELECT * FROM receipts")
    suspend fun getAllReceipts(): List<Receipt>

    @Query("SELECT * FROM receipt_items")
    suspend fun getAllItems(): List<ReceiptItem>

    /** Everything (receipt lines go with their receipts). For restoring a backup. */
    @Query("DELETE FROM receipts")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertReceipt(receipt: Receipt): Long

    @Insert
    suspend fun insertItems(items: List<ReceiptItem>)

    @Update
    suspend fun updateReceipt(receipt: Receipt)

    @Update
    suspend fun updateItem(item: ReceiptItem)

    /** Every receipt from one source, e.g. all "lidl" receipts. For disconnecting a store. */
    @Query("DELETE FROM receipts WHERE source = :source")
    suspend fun deleteFromSource(source: String)

    @Query("DELETE FROM receipts WHERE id = :id")
    suspend fun deleteReceipt(id: Long)

    /** Saves a receipt and its lines together. */
    @Transaction
    suspend fun insertWithItems(receipt: Receipt, items: List<ReceiptItem>): Long {
        val id = insertReceipt(receipt)
        insertItems(items.mapIndexed { i, item -> item.copy(id = 0, receiptId = id, position = i) })
        return id
    }
}
