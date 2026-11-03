package com.financetracker.app.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.financetracker.app.data.db.entity.Transaction
import com.financetracker.app.data.db.entity.TransactionWithDetails
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
    @Query("UPDATE transactions SET categoryId = :toId WHERE categoryId = :fromId")
    suspend fun moveCategory(fromId: Long, toId: Long)


    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(transaction: Transaction): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(transactions: List<Transaction>): List<Long>

    @Update
    suspend fun update(transaction: Transaction)

    @Delete
    suspend fun delete(transaction: Transaction)

    @Query("DELETE FROM transactions")
    suspend fun deleteAll()

    @Query(
        """
        SELECT
            t.id AS id,
            t.amount AS amount,
            t.type AS type,
            t.accountId AS accountId,
            a.name AS accountName,
            t.categoryId AS categoryId,
            c.name AS categoryName,
            c.mainCategory AS mainCategoryName,
            c.colorHex AS categoryColorHex,
            t.date AS date,
            t.note AS note
        FROM transactions t
        INNER JOIN accounts a ON a.id = t.accountId
        LEFT JOIN categories c ON c.id = t.categoryId
        ORDER BY t.date DESC, t.id DESC
        """
    )
    fun observeAllWithDetails(): Flow<List<TransactionWithDetails>>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getById(id: Long): Transaction?

    @Query(
        """
        SELECT
            COALESCE((SELECT SUM(initialBalance) FROM accounts), 0) +
            COALESCE(SUM(CASE WHEN type = 'INCOME' THEN amount ELSE -amount END), 0)
        FROM transactions
        """
    )
    fun observeNetBalance(): Flow<Double>

    @Query("SELECT * FROM transactions WHERE accountId = :accountId")
    suspend fun getByAccountId(accountId: Long): List<Transaction>

    @Query("SELECT * FROM transactions")
    suspend fun getAll(): List<Transaction>

}
