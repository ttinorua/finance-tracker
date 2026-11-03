package com.financetracker.app.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Database upgrades that keep everyone's data. Each one adds what a new version needs; the SQL is
 * exactly what Room generates for the entities (see AppDatabase_Impl), since Room checks the
 * result against them after upgrading.
 */
object Migrations {

    /** Version 3: shopping receipts and their lines. */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `receipts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `source` TEXT NOT NULL, " +
                    "`externalId` TEXT NOT NULL, `storeName` TEXT NOT NULL, `purchasedAt` INTEGER NOT NULL, `total` REAL NOT NULL, " +
                    "`discount` REAL NOT NULL, `transactionId` INTEGER, `split` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
                    "FOREIGN KEY(`transactionId`) REFERENCES `transactions`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_receipts_transactionId` ON `receipts` (`transactionId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_receipts_purchasedAt` ON `receipts` (`purchasedAt`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_receipts_source_externalId` ON `receipts` (`source`, `externalId`)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `receipt_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `receiptId` INTEGER NOT NULL, " +
                    "`position` INTEGER NOT NULL, `name` TEXT NOT NULL, `quantity` REAL NOT NULL, `amount` REAL NOT NULL, " +
                    "`discount` REAL NOT NULL, `categoryId` INTEGER, " +
                    "FOREIGN KEY(`receiptId`) REFERENCES `receipts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                    "FOREIGN KEY(`categoryId`) REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_receipt_items_receiptId` ON `receipt_items` (`receiptId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_receipt_items_categoryId` ON `receipt_items` (`categoryId`)")
        }
    }

    val ALL = arrayOf(MIGRATION_2_3)
}
