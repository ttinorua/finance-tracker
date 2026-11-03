package com.financetracker.app.data.enablebanking

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.TimeUnit

/**
 * Syncs with the bank about every 6 hours in the background, so new transactions (and the
 * household's shared spending) arrive without opening the app. Four a day is the most the EU
 * open-banking rules allow banks to be asked without you present. Android picks the exact moment
 * and only runs it with internet; opening the app and "Sync now" still sync straight away.
 */
object BankAutoSync {
    private const val PREFS_NAME = "finance_prefs"
    private const val KEY_ENABLED = "bank_auto_sync"
    private const val PERIODIC_WORK = "bank_auto_sync_periodic"
    private const val HOURS = 6L

    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun init(context: Context) {
        _enabled.value = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)
        apply(context)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
        _enabled.value = enabled
        apply(context)
    }

    private fun apply(context: Context) {
        val work = WorkManager.getInstance(context)
        if (_enabled.value) {
            val request = PeriodicWorkRequestBuilder<AutoSyncWorker>(HOURS, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(HOURS, TimeUnit.HOURS)
                .build()
            // KEEP: opening the app doesn't restart the 6-hour clock.
            work.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        } else {
            work.cancelUniqueWork(PERIODIC_WORK)
        }
    }
}

/** Starts the regular bank sync ([EnableBankingSyncWorker]) unless one is already running. */
class AutoSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            EnableBankingSyncWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            EnableBankingSyncWorker.buildRequest()
        )
        return Result.success()
    }
}
