package com.financetracker.app.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A shopping receipt: shared from a store app, photographed, or fetched from a store account.
 * [transactionId] is the bank transaction it belongs to (once matched). With [split], that
 * transaction counts in the receipt items' categories instead of its own (see
 * [com.financetracker.app.util.ReceiptSplits]). */
@Entity(
    tableName = "receipts",
    foreignKeys = [
        ForeignKey(
            entity = Transaction::class,
            parentColumns = ["id"],
            childColumns = ["transactionId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("transactionId"), Index("purchasedAt"), Index(value = ["source", "externalId"], unique = true)]
)
data class Receipt(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    /** Where it came from: "share", "photo", or a store connection such as "coop". */
    val source: String,
    /** The store's own receipt number when known, else a fingerprint, so a receipt is never
     * imported twice. */
    val externalId: String,
    val storeName: String,
    /** Epoch millis of the purchase. */
    val purchasedAt: Long,
    val total: Double,
    /** Discounts on the whole receipt (member discounts etc.), as a positive number. */
    val discount: Double = 0.0,
    val transactionId: Long? = null,
    val split: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

/** One line of a receipt. [amount] is what was paid for the line after its own discount. */
@Entity(
    tableName = "receipt_items",
    foreignKeys = [
        ForeignKey(
            entity = Receipt::class,
            parentColumns = ["id"],
            childColumns = ["receiptId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Category::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("receiptId"), Index("categoryId")]
)
data class ReceiptItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val receiptId: Long,
    val position: Int,
    val name: String,
    val quantity: Double = 1.0,
    val amount: Double,
    val discount: Double = 0.0,
    val categoryId: Long? = null
)

/** A receipt line with its category, for split transactions and the Shopping page. */
data class ReceiptItemDetails(
    val itemId: Long,
    val receiptId: Long,
    val transactionId: Long?,
    val split: Boolean,
    val storeName: String,
    val purchasedAt: Long,
    val name: String,
    val quantity: Double,
    val amount: Double,
    val discount: Double,
    val categoryId: Long?,
    val categoryName: String?,
    val mainCategoryName: String?,
    val categoryColorHex: String?
)
