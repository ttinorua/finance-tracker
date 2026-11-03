package com.financetracker.app.data.enablebanking

import com.financetracker.app.data.household.HouseholdSync
import com.financetracker.app.data.receipts.Receipts
import com.financetracker.app.data.stores.StoreSync
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.financetracker.app.FinanceApp
import com.financetracker.app.R
import com.financetracker.app.data.advisor.AttentionMonitor
import java.util.concurrent.TimeUnit

/**
 * Runs [EnableBankingSyncCoordinator] as WorkManager-managed background work instead of a plain
 * application-scoped coroutine, so a sync that can take a long time (the very first sync, or any
 * sync after local data was wiped — see [EnableBankingSyncCoordinator]'s own doc) survives the
 * app being backgrounded or its process being killed, the same durability the AI categorization
 * backfill already has via [com.financetracker.app.data.ai.AiCategorizationWorker] — including
 * the same batching + [Result.retry] design (not a self-enqueue) to stay inside Android's ~10
 * minute execution ceiling for background work, with the run's total kept in SharedPreferences
 * since a retry replays the original request's input unchanged. The imported count is
 * accumulated across retries the same way: [EnableBankingSyncCoordinator.syncSelectedAccounts]
 * only ever counts what it imported in *that* call, so a multi-retry run's final message would
 * otherwise report only the last batch's count instead of the true total for the whole run —
 * the same bug that made [com.financetracker.app.data.ai.AiCategorizationWorker] report
 * "Categorized 0 of N" despite having genuinely categorized plenty in earlier batches. The
 * skipped/duplicate count is deliberately *not* accumulated the same way: because every
 * invocation re-fetches an account's *entire* history from scratch, a row this run imported in
 * an earlier batch shows up as a "duplicate" again on every later batch's fetch — summing it
 * would inflate "already up to date" further with every retry, so only the last invocation's
 * count (a reasonable snapshot of "how much was already there" for the account as a whole) is
 * reported.
 *
 * A large first sync's fetch phase alone (see [EnableBankingSyncCoordinator]'s doc — Enable
 * Banking paginates an account's entire history, one network round trip per page, all before
 * there's anything to checkpoint) can take longer than Android's ~10 minute execution ceiling
 * for ordinary background work — a real run was observed getting silently killed and restarted
 * from scratch by the system mid-fetch, repeatedly, since that kill happens at the OS level
 * (never going through this worker's own graceful [Result.retry] path, so nothing about the
 * fetch survives it). [doWork] promotes itself to a foreground service (with a visible
 * "Syncing…" notification) for its duration, which lifts that ceiling, the standard WorkManager
 * pattern for work that can legitimately run long.
 */
class EnableBankingSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Lets a long first sync run past Android's ~10 minute limit. Android can refuse this for
        // a sync started in the background (the automatic one); a regular sync is quick anyway.
        runCatching { setForeground(buildForegroundInfo()) }
        val repository = (applicationContext as FinanceApp).repository
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val knownTotal = prefs.getInt(KEY_PERSISTED_TOTAL, -1).takeIf { it >= 0 }
        val importedSoFar = prefs.getInt(KEY_PERSISTED_IMPORTED, 0)

        val outcome = EnableBankingSyncCoordinator.syncSelectedAccounts(
            repository,
            maxGroups = BATCH_GROUPS,
            knownTotal = knownTotal
        ) { progress ->
            // total 0 is the fetch-phase "still fetching, not a real total yet" sentinel (see
            // EnableBankingSyncCoordinator) — only persist a real positive total, so a retry
            // right after an interruption mid-fetch never reads back a bogus 0 as knownTotal
            // (which would make alreadyDone go negative once the real total is computed).
            if (progress.total > 0) {
                prefs.edit().putInt(KEY_PERSISTED_TOTAL, progress.total).apply()
            }
            setProgress(workDataOf(KEY_DONE to progress.done, KEY_TOTAL to progress.total))
        } ?: return Result.success()
        val cumulativeImported = importedSoFar + outcome.importedCount

        if (outcome.remaining > 0) {
            prefs.edit().putInt(KEY_PERSISTED_IMPORTED, cumulativeImported).apply()
            return Result.retry()
        }

        prefs.edit().remove(KEY_PERSISTED_TOTAL).remove(KEY_PERSISTED_IMPORTED).apply()
        // No internet when the automatic sync fired: don't buzz the phone about it — just let
        // WorkManager try again (it waits for a connection and backs off).
        if (outcome.networkProblem && outcome.importedCount == 0) {
            AttentionMonitor.recordSyncResult(applicationContext, null)
            return Result.retry()
        }
        // Receipts shared before the bank had booked the payment find their transaction now.
        runCatching { Receipts.matchPending(repository) }
        // Then fetch new receipts from connected store accounts (Lidl Plus, Nemlig.com…).
        runCatching { StoreSync.enqueue(applicationContext) }
        // Fresh transactions: re-run the attention checks (and notify about anything new).
        AttentionMonitor.recordSyncResult(applicationContext, outcome.failureMessage)
        // And straight away share the new spending with the household (if you're in one).
        runCatching { HouseholdSync.publishNow() }
        val output = workDataOf(
            KEY_IMPORTED to cumulativeImported,
            KEY_SKIPPED to outcome.skippedCount,
            KEY_FAILURE to (outcome.failureMessage ?: "")
        )
        return Result.success(output)
    }

    private fun buildForegroundInfo(): ForegroundInfo {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Bank sync",
                NotificationManager.IMPORTANCE_LOW
            )
            applicationContext.getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Syncing bank transactions")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "enable_banking_sync"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_IMPORTED = "imported"
        const val KEY_SKIPPED = "skipped"
        const val KEY_FAILURE = "failure"
        // Each Claude call now covers CategorySuggester.BATCH_SIZE (25) merchants at once (see
        // EnableBankingSyncCoordinator), so this many groups per invocation is still only ~40
        // sequential AI calls, not 1000.
        private const val BATCH_GROUPS = 1000
        private const val PREFS_NAME = "finance_prefs"
        private const val KEY_PERSISTED_TOTAL = "enable_banking_sync_total"
        private const val KEY_PERSISTED_IMPORTED = "enable_banking_sync_imported"
        private const val NOTIFICATION_CHANNEL_ID = "enable_banking_sync"
        private const val NOTIFICATION_ID = 4201

        /** A short, constant (not exponential) backoff between batches — a large first sync can
         * need several retries, and exponential backoff would quickly stretch the gaps between
         * batches to hours. */
        fun buildRequest(): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<EnableBankingSyncWorker>()
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
                .build()

        /** Clears any total/count left over from a previous run so a fresh sync always starts by
         * computing (and reporting) real, current numbers. */
        fun clearPersistedState(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_PERSISTED_TOTAL)
                .remove(KEY_PERSISTED_IMPORTED)
                .apply()
        }
    }
}
