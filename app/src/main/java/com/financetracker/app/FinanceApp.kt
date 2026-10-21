package com.financetracker.app

import com.financetracker.app.data.prefs.WeeklySummarySettings
import com.financetracker.app.data.prefs.UserAlerts
import com.financetracker.app.data.ai.chat.AssistantPrefs
import com.financetracker.app.data.ai.chat.SavedChats
import com.financetracker.app.data.prefs.ViewFilter
import com.financetracker.app.data.enablebanking.BankAutoSync
import android.app.Application
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import com.financetracker.app.data.bank.BankCategories
import com.financetracker.app.data.bank.CategoryCleanup
import com.financetracker.app.data.bank.SupportedBanks
import com.financetracker.app.data.db.AppDatabase
import com.financetracker.app.data.advisor.AttentionMonitor
import com.financetracker.app.data.ai.AiSettings
import com.financetracker.app.data.ai.chatgpt.ChatGptAuth
import com.financetracker.app.data.ai.LearnedCategoryRules
import com.financetracker.app.data.ai.MerchantLogoLookup
import com.financetracker.app.data.prefs.MerchantOverrides
import com.financetracker.app.data.backup.AutoBackupSettings
import com.financetracker.app.data.enablebanking.EnableBankingCredentials
import com.financetracker.app.data.enablebanking.EnableBankingSyncWorker
import com.financetracker.app.data.prefs.AiInsightsCache
import com.financetracker.app.data.prefs.BankReservations
import com.financetracker.app.data.prefs.BankScheduledPayments
import com.financetracker.app.data.prefs.BudgetLimits
import com.financetracker.app.data.prefs.BudgetSettings
import com.financetracker.app.data.prefs.CurrencySettings
import com.financetracker.app.data.prefs.DismissedRecurringExpenses
import com.financetracker.app.data.prefs.EnableBankingPrefs
import com.financetracker.app.data.household.HouseholdSync
import com.financetracker.app.data.prefs.CategoryIconChoices
import com.financetracker.app.data.prefs.FixedExpenseCategories
import com.financetracker.app.data.prefs.LoansAndGoals
import com.financetracker.app.data.prefs.MainAccountSettings
import com.financetracker.app.data.prefs.ThemeSettings
import com.financetracker.app.data.repository.FinanceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class FinanceApp : Application() {

    lateinit var repository: FinanceRepository
        private set

    override fun onCreate() {
        super.onCreate()
        CurrencySettings.init(this)
        LearnedCategoryRules.init(this)
        ChatGptAuth.init(this)
        AiSettings.init(this)
        ThemeSettings.init(this)
        MainAccountSettings.init(this)
        ViewFilter.init(this)
        SavedChats.init(this)
        AssistantPrefs.init(this)
        UserAlerts.init(this)
        WeeklySummarySettings.init(this) { }
        BankScheduledPayments.init(this)
        BankReservations.init(this)
        BudgetSettings.init(this)
        BudgetLimits.init(this)
        AiInsightsCache.init(this)
        DismissedRecurringExpenses.init(this)
        FixedExpenseCategories.init(this)
        CategoryIconChoices.init(this)
        EnableBankingPrefs.init(this)
        EnableBankingCredentials.init(this)
        AutoBackupSettings.init(this)
        LoansAndGoals.init(this)
        MerchantOverrides.init(this)
        MerchantLogoLookup.init(this)
        repository = FinanceRepository(AppDatabase.getInstance(this))
        AttentionMonitor.init(this)
        HouseholdSync.init(this, repository)
        BankAutoSync.init(this)

        // Retroactively adds any starter category the currently selected/connected bank has
        // that an existing install is still missing (the starter set has grown since some
        // installs were first created) — cheap, idempotent, never touches an existing category.
        // Best done before sync/categorization run so they have the fuller category list to
        // match against from the start. Then migrates away any of the app's own original
        // hand-picked category names (e.g. "Salary") that this real taxonomy has since replaced
        // with an unambiguous equivalent (e.g. "Pay, benefits and pension") — see
        // CategoryCleanup, and needs the real taxonomy already seeded to have something to
        // migrate onto.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            BankCategories.ensure(repository, SupportedBanks.byId(EnableBankingPrefs.selectedBankId.value))
            CategoryCleanup.migrateLegacyDuplicates(repository)
            FixedExpenseCategories.seedLegacyDefaultsIfNeeded(repository.getCategories())
        }

        // Best-effort sync once per app launch; a no-op inside the worker if not connected.
        // Runs as WorkManager-managed work (see EnableBankingSyncWorker) rather than a plain
        // coroutine, so a sync that can take a long time (the very first sync, or any sync after
        // a reinstall wiped local data) survives the app being backgrounded or its process being
        // killed instead of silently dying mid-way. KEEP: if a previous launch's sync is still
        // running, this one shouldn't interrupt it — the user can always see current status and
        // force a fresh run via Settings > Bank > Sync now.
        WorkManager.getInstance(this).enqueueUniqueWork(
            EnableBankingSyncWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            EnableBankingSyncWorker.buildRequest()
        )
    }
}
