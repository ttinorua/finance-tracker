package com.financetracker.app.data.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.room.withTransaction
import androidx.work.WorkManager
import com.financetracker.app.data.ai.AiCategorizationWorker
import com.financetracker.app.data.ai.AiSettings
import com.financetracker.app.data.db.AppDatabase
import com.financetracker.app.data.enablebanking.EnableBankingCredentials
import com.financetracker.app.data.enablebanking.EnableBankingSyncWorker
import com.financetracker.app.data.stores.StoreAccounts
import com.financetracker.app.data.stores.StoreSync
import kotlin.system.exitProcess

/**
 * Creates and restores a full backup of the app (see [BackupContents]). Restoring replaces
 * everything in the app with the backup — it's meant for a fresh install, so nothing has to be
 * re-synced or re-categorized — and then restarts the app so every screen and setting reloads
 * from the restored data.
 */
object FullBackup {

    private const val PREFS_NAME = "finance_prefs"

    /** In-progress bookkeeping and this phone's own automatic-backup setup, which would be wrong
     * to carry over to another install. */
    private val TRANSIENT_KEYS = setOf(
        "enablebanking_pending_auth_state",
        "enablebanking_pending_auth_bank_id",
        "enable_banking_sync_total",
        "enable_banking_sync_imported",
        "ai_categorization_total",
        "ai_categorization_categorized"
    ) + AutoBackupSettings.KEYS + AiSettings.SECRET_KEYS + EnableBankingCredentials.SECRET_KEYS

    /** The live bank sessions. Together with the app itself they give read access to the bank
     * accounts, so they're only ever written into a password-protected backup. */
    private val BANK_CONNECTION_KEYS = setOf(
        "enablebanking_connections",
        "enablebanking_session_id",
        "enablebanking_linked_accounts",
        "enablebanking_selected_account_uids",
        "enablebanking_consent_valid_until"
    )

    suspend fun create(context: Context, includeBankConnections: Boolean): BackupContents {
        val db = AppDatabase.getInstance(context)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).all
            .filterKeys { it !in TRANSIENT_KEYS && (includeBankConnections || it !in BANK_CONNECTION_KEYS) }
            .filterValues { it != null }
            .mapValues { it.value!! }
        return BackupContents(
            createdAt = System.currentTimeMillis(),
            accounts = db.accountDao().getAll(),
            categories = db.categoryDao().getAll(),
            transactions = db.transactionDao().getAll(),
            receipts = db.receiptDao().getAllReceipts(),
            receiptItems = db.receiptDao().getAllItems(),
            prefs = prefs,
            includesBankConnections = includeBankConnections && prefs.keys.any { it in BANK_CONNECTION_KEYS }
        )
    }

    /** Replaces all of the app's data with [contents]. A backup without bank connections keeps
     * the connections this install already has, if any, and this phone's automatic-backup setup
     * is always kept. Call [restartApp] afterwards. */
    suspend fun restore(context: Context, contents: BackupContents) {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(EnableBankingSyncWorker.UNIQUE_WORK_NAME)
        workManager.cancelUniqueWork(AiCategorizationWorker.UNIQUE_WORK_NAME)

        val db = AppDatabase.getInstance(context)
        db.withTransaction {
            db.receiptDao().deleteAll()
            db.transactionDao().deleteAll()
            db.categoryDao().deleteAll()
            db.accountDao().deleteAll()
            contents.accounts.forEach { db.accountDao().insert(it) }
            contents.categories.forEach { db.categoryDao().insert(it) }
            contents.transactions.chunked(500).forEach { db.transactionDao().insertAll(it) }
            contents.receipts.forEach { db.receiptDao().insertReceipt(it) }
            contents.receiptItems.chunked(500).forEach { db.receiptDao().insertItems(it) }
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val keptConnections = if (contents.includesBankConnections) {
            emptyMap()
        } else {
            prefs.all.filterKeys { it in BANK_CONNECTION_KEYS }
        }
        val keptAutoBackup = prefs.all.filterKeys { it in AutoBackupSettings.KEYS || it in AiSettings.SECRET_KEYS || it in EnableBankingCredentials.SECRET_KEYS }
        val editor = prefs.edit().clear()
        (contents.prefs.filterKeys { it !in TRANSIENT_KEYS } + keptConnections + keptAutoBackup).forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Set<*> -> editor.putStringSet(key, value.map { it.toString() }.toSet())
            }
        }
        editor.commit()

        // Store connections aren't part of a backup, so they still remember their last fetch and
        // would only look back from there. Fetch the last 12 months again instead; receipts
        // already in the app are skipped.
        StoreAccounts.resetFetchDates(context)
        StoreSync.enqueue(context)
    }

    /** Writes a fresh full backup to [uri], encrypted with [password] (which also includes the
     * bank connections) when given. */
    suspend fun writeTo(context: Context, uri: Uri, password: CharArray?): BackupContents {
        val contents = create(context, includeBankConnections = password != null && password.isNotEmpty())
        val bytes = BackupCodec.encode(contents, password)
        writeBytes(context, uri, bytes)
        return contents
    }

    /** A fresh full backup file's bytes, encrypted with [password] when given. */
    suspend fun encode(context: Context, password: CharArray?): ByteArray =
        BackupCodec.encode(create(context, includeBankConnections = password != null && password.isNotEmpty()), password)

    private fun writeBytes(context: Context, uri: Uri, bytes: ByteArray) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
            ?: error("Couldn't open the backup file for writing.")
    }

    /** Relaunches the app from scratch, so everything reloads from the restored data. */
    fun restartApp(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        context.startActivity(Intent.makeRestartActivityTask(launch.component))
        exitProcess(0)
    }
}
