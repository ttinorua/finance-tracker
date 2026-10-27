package com.financetracker.app.data.advisor

import com.financetracker.app.data.prefs.UserAlerts
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.financetracker.app.FinanceApp
import com.financetracker.app.MainActivity
import com.financetracker.app.R
import com.financetracker.app.data.backup.AutoBackupSettings
import com.financetracker.app.data.bank.LegacyCategories
import com.financetracker.app.data.bank.SupportedBanks
import com.financetracker.app.data.prefs.BudgetLimits
import com.financetracker.app.data.prefs.BudgetSettings
import com.financetracker.app.data.prefs.CurrencySettings
import com.financetracker.app.data.prefs.EnableBankingPrefs
import com.financetracker.app.data.prefs.FixedExpenseCategories
import com.financetracker.app.data.prefs.FixedMerchants
import com.financetracker.app.data.prefs.SharedBillsSettings
import com.financetracker.app.util.SharedBills
import com.financetracker.app.data.prefs.LoansAndGoals
import com.financetracker.app.data.prefs.MainAccountSettings
import com.financetracker.app.data.repository.FinanceRepository
import com.financetracker.app.util.advisor.AttentionChecks
import com.financetracker.app.util.advisor.AttentionInput
import com.financetracker.app.util.advisor.AttentionActionKind
import com.financetracker.app.util.advisor.AttentionItem
import com.financetracker.app.util.advisor.AttentionLevel
import com.financetracker.app.util.advisor.BankConsent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Runs [AttentionChecks] for the Dashboard's "Needs your attention" section and the phone
 * notifications: when the Dashboard opens or the data changes, once a day in the background, and
 * after each bank sync. Remembers what the user dismissed and what's already been notified, so
 * nothing shows or buzzes twice.
 */
object AttentionMonitor {
    private const val PREFS_NAME = "finance_prefs"
    private const val KEY_DISMISSED = "attention_dismissed"
    private const val KEY_NOTIFIED = "attention_notified"
    private const val KEY_NOTIFICATIONS = "attention_notifications_enabled"
    private const val KEY_PERMISSION_ASKED = "attention_permission_asked"
    private const val KEY_SYNC_FAILURE = "attention_last_sync_failure"
    private const val CHANNEL_ID = "needs_attention"
    private const val NOTIFICATION_BASE_ID = 7300
    private const val DAILY_WORK = "attention_daily"
    private const val NOW_WORK = "attention_now"
    private const val MAX_REMEMBERED = 400

    private lateinit var prefs: SharedPreferences
    private val mutex = Mutex()

    private val _items = MutableStateFlow<List<AttentionItem>>(emptyList())
    /** What the Dashboard shows (dismissed items left out). */
    val items: StateFlow<List<AttentionItem>> = _items.asStateFlow()

    private val _notificationsEnabled = MutableStateFlow(true)
    val notificationsEnabled: StateFlow<Boolean> = _notificationsEnabled.asStateFlow()

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _notificationsEnabled.value = prefs.getBoolean(KEY_NOTIFICATIONS, true)
        val request = PeriodicWorkRequestBuilder<AttentionWorker>(1, TimeUnit.DAYS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(DAILY_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        _notificationsEnabled.value = enabled
        prefs.edit().putBoolean(KEY_NOTIFICATIONS, enabled).apply()
    }

    /** Asks for the notification permission once (Android 13+), the first time there's
     * something to tell the user. */
    fun shouldAskPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && _notificationsEnabled.value &&
            !prefs.getBoolean(KEY_PERMISSION_ASKED, false) &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    fun markPermissionAsked() {
        prefs.edit().putBoolean(KEY_PERMISSION_ASKED, true).apply()
    }

    fun dismiss(key: String) {
        prefs.edit().putStringSet(KEY_DISMISSED, remember(KEY_DISMISSED, listOf(key))).apply()
        _items.value = _items.value.filterNot { it.key == key }
        _dismissals.value++
    }

    /** Carries out an attention card's one-tap fix, then drops the card and refreshes. */
    suspend fun perform(repository: FinanceRepository, item: AttentionItem) {
        val action = item.action ?: return
        when (action.kind) {
            AttentionActionKind.MARK_TRANSFER -> action.transactionIds.forEach { id ->
                val tx = repository.getTransaction(id) ?: return@forEach
                val category = repository.getOrCreateCategory("Other", "Other (Transfer)", tx.type, "#D32F2F")
                repository.updateTransaction(tx.copy(categoryId = category.id))
            }
            AttentionActionKind.MARK_FIXED_MERCHANT -> action.text?.let { FixedMerchants.setFixed(it, true) }
        }
        dismiss(item.key)
        refresh(repository, seen = true)
    }

    private val _dismissals = MutableStateFlow(0)
    /** Bumps on every dismissal, for items shown alongside [items] (e.g. the household budget's). */
    val dismissals: StateFlow<Int> = _dismissals.asStateFlow()

    fun isDismissed(key: String): Boolean = prefs.getStringSet(KEY_DISMISSED, emptySet()).orEmpty().contains(key)

    /** Called by the bank sync: a failure shows as an item until a later sync succeeds. */
    fun recordSyncResult(context: Context, failure: String?) {
        prefs.edit().putString(KEY_SYNC_FAILURE, failure?.takeIf { it.isNotBlank() }).apply()
        WorkManager.getInstance(context).enqueueUniqueWork(
            NOW_WORK, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<AttentionWorker>().build()
        )
    }

    /** Re-runs the checks and updates [items]. With [seen] (the Dashboard is showing them), the
     * items count as already notified, so they won't buzz the phone later. */
    suspend fun refresh(repository: FinanceRepository, seen: Boolean = false): List<AttentionItem> = mutex.withLock {
        withContext(Dispatchers.Default) {
            val input = input(repository)
            val dismissed = prefs.getStringSet(KEY_DISMISSED, emptySet()).orEmpty()
            val items = AttentionChecks.run(input).filterNot { it.key in dismissed }
            _items.value = items
            if (seen && items.isNotEmpty()) prefs.edit().putStringSet(KEY_NOTIFIED, remember(KEY_NOTIFIED, items.map { it.key })).apply()
            items
        }
    }

    /** Background run: refresh, then notify about anything new that's worth a notification. */
    suspend fun checkAndNotify(context: Context, repository: FinanceRepository) {
        val items = refresh(repository)
        val notified = prefs.getStringSet(KEY_NOTIFIED, emptySet()).orEmpty()
        val fresh = items.filter { it.notify && it.key !in notified }
        if (fresh.isEmpty()) return
        // Remembered even when notifications are off, so turning them on later doesn't replay old items.
        prefs.edit().putStringSet(KEY_NOTIFIED, remember(KEY_NOTIFIED, fresh.map { it.key })).apply()
        if (_notificationsEnabled.value) notify(context, fresh)
    }

    private suspend fun input(repository: FinanceRepository): AttentionInput {
        val accounts = repository.getAccounts()
        val balances = accounts.associate { it.id to repository.getAccountBalance(it) }
        val categories = repository.getCategories()
        // A goal linked to an account starts measuring from that account's balance the first time it's seen.
        LoansAndGoals.goals.value.filter { it.accountId != null && it.startAmount == null }.forEach { goal ->
            balances[goal.accountId]?.let { LoansAndGoals.restoreGoal(goal.copy(startAmount = it, startedAt = goal.startedAt ?: System.currentTimeMillis())) }
        }
        val backup = AutoBackupSettings.state.value
        val transactions = repository.observeTransactions().first()
        val now = System.currentTimeMillis()
        val shared = SharedBillsSettings.setup.value
        val sharedStatus = shared.accountId?.let { id -> accounts.firstOrNull { it.id == id } }?.let { account ->
            SharedBills.status(
                account.id, transactions, balances[account.id] ?: 0.0, SharedBills.averageNetSalary(transactions, now),
                shared.partnerNetIncome, shared.partnerName, shared.answers, now
            )
        }
        return AttentionInput(
            now = now,
            currency = CurrencySettings.currencyCode.value,
            transactions = transactions,
            accounts = accounts,
            balances = balances,
            categories = categories,
            uncategorizedCategoryIds = categories
                .filter { (it.name == "Uncategorized" && it.mainCategory == "Uncategorized") || LegacyCategories.isAmbiguousBucket(it) }
                .map { it.id }.toSet(),
            excludeTransfers = BudgetSettings.excludeTransfersFromSpending.value,
            shiftSalary = BudgetSettings.shiftSalaryToNextMonth.value,
            mainAccountId = MainAccountSettings.mainAccountId.value,
            overallBudgets = BudgetLimits.overallBudgets.value,
            categoryBudgets = BudgetLimits.categoryBudgets.value,
            fixedCategoryIds = FixedExpenseCategories.fixedCategoryIds.value,
            goals = LoansAndGoals.goals.value,
            loans = LoansAndGoals.loans.value,
            consents = EnableBankingPrefs.connections.value.map { BankConsent(SupportedBanks.byId(it.bankId).displayName, it.consentValidUntil) },
            lastSyncFailure = prefs.getString(KEY_SYNC_FAILURE, null),
            backupEnabled = backup.enabled,
            backupLastSuccessAt = backup.lastSuccessAt,
            backupLastError = backup.lastError,
            userAlerts = UserAlerts.alerts.value,
            fixedMerchants = FixedMerchants.merchants.value,
            sharedBills = sharedStatus,
            partnerName = shared.partnerName
        )
    }

    private fun remember(key: String, add: List<String>): Set<String> =
        (prefs.getStringSet(key, emptySet()).orEmpty().toList() + add).distinct().takeLast(MAX_REMEMBERED).toSet()

    private fun notify(context: Context, items: List<AttentionItem>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Needs your attention", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Budgets, unusual payments, goals and other things the app spots"
            }
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val manager = NotificationManagerCompat.from(context)
        try {
            items.take(5).forEach { item ->
                val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(item.title)
                    .setContentText(item.detail)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(item.detail))
                    .setPriority(if (item.level == AttentionLevel.URGENT) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build()
                manager.notify(NOTIFICATION_BASE_ID + (item.key.hashCode() and 0xFFFF), notification)
            }
        } catch (e: SecurityException) {
            // Permission withdrawn in the meantime — nothing to do.
        }
    }
}

/** The daily (and after-sync) background check. */
class AttentionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repository = (applicationContext as FinanceApp).repository
        return try {
            AttentionMonitor.checkAndNotify(applicationContext, repository)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
