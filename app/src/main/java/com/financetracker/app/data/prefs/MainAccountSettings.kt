package com.financetracker.app.data.prefs

import android.content.Context
import com.financetracker.app.data.repository.FinanceRepository
import com.financetracker.app.util.pickMainAccount
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The account every screen's account selector starts on (Settings > Accounts > "Main
 * account"). Only one account can be main at a time, and [ensure] keeps one set whenever there's
 * an account to pick: with none, every single-account view would count transfers in as income. */
object MainAccountSettings {
    private const val PREFS_NAME = "finance_prefs"
    private const val KEY_MAIN_ACCOUNT_ID = "main_account_id"
    private const val KEY_AUTO_PICKED = "main_account_auto_picked"

    private lateinit var prefs: android.content.SharedPreferences
    private val _mainAccountId = MutableStateFlow<Long?>(null)
    val mainAccountId: StateFlow<Long?> get() = _mainAccountId

    private val _autoPicked = MutableStateFlow(false)
    /** True while the main account is the one the app picked, not one the person chose. */
    val autoPicked: StateFlow<Boolean> get() = _autoPicked

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _mainAccountId.value = prefs.getLong(KEY_MAIN_ACCOUNT_ID, -1L).takeIf { it >= 0 }
        _autoPicked.value = prefs.getBoolean(KEY_AUTO_PICKED, false)
    }

    fun setMainAccount(accountId: Long?, autoPicked: Boolean = false) {
        _mainAccountId.value = accountId
        _autoPicked.value = autoPicked && accountId != null
        if (::prefs.isInitialized) {
            prefs.edit().apply {
                if (accountId == null) remove(KEY_MAIN_ACCOUNT_ID) else putLong(KEY_MAIN_ACCOUNT_ID, accountId)
                putBoolean(KEY_AUTO_PICKED, _autoPicked.value)
            }.apply()
        }
    }

    /** Picks a main account ([pickMainAccount]) when none is set or the set one no longer exists. */
    suspend fun ensure(repository: FinanceRepository) {
        val accounts = repository.getAccounts()
        val current = _mainAccountId.value
        if (current != null && accounts.any { it.id == current }) return
        val picked = pickMainAccount(accounts.map { it.id }, repository.observeTransactions().first(), System.currentTimeMillis())
        if (picked != current) setMainAccount(picked, autoPicked = true)
    }

    /** Called when [removedAccountId] is deleted or merged away — the main flag moves to
     * [replacementId] (a merge target) or is cleared, never left pointing at a missing account. */
    fun onAccountRemoved(removedAccountId: Long, replacementId: Long? = null) {
        if (_mainAccountId.value == removedAccountId) setMainAccount(replacementId)
    }
}
