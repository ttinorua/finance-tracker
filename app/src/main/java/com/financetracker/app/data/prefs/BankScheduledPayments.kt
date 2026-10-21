package com.financetracker.app.data.prefs

import android.content.Context
import com.financetracker.app.data.db.entity.Category
import com.financetracker.app.data.db.entity.TransactionType
import com.financetracker.app.data.db.entity.TransactionWithDetails
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/** An outgoing payment the bank has scheduled but not yet booked (Enable Banking "PDNG", dated
 * today or later) — e.g. a Betalingsservice bill due on the 1st. */
data class ScheduledPayment(
    val accountId: Long,
    val date: Long,
    val amount: Double,
    val note: String,
    val categoryId: Long?
)

/** The bank's own scheduled payments from the latest sync, per local account. Deliberately kept
 * out of the transactions table — they haven't happened yet, so they must never touch balances
 * or totals; they only feed "Upcoming expenses". Each account's list is replaced wholesale on
 * every successful sync, so a payment that got booked or cancelled drops out on its own. */
object BankScheduledPayments {
    private const val PREFS_NAME = "finance_prefs"
    private const val KEY_PAYMENTS = "bank_scheduled_payments"

    private lateinit var prefs: android.content.SharedPreferences
    private val _payments = MutableStateFlow<List<ScheduledPayment>>(emptyList())
    val payments: StateFlow<List<ScheduledPayment>> get() = _payments

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _payments.value = prefs.getString(KEY_PAYMENTS, null)
            ?.let { runCatching { decode(it) }.getOrNull() }
            .orEmpty()
    }

    fun replaceForAccount(accountId: Long, payments: List<ScheduledPayment>) {
        _payments.update { current -> current.filterNot { it.accountId == accountId } + payments.distinct() }
        persist()
    }

    fun onAccountRemoved(accountId: Long) = replaceForAccount(accountId, emptyList())

    /** A merged category: its upcoming payments move to the one kept. */
    fun remapCategory(fromId: Long, toId: Long) {
        if (_payments.value.none { it.categoryId == fromId }) return
        _payments.update { current -> current.map { if (it.categoryId == fromId) it.copy(categoryId = toId) else it } }
        persist()
    }

    private fun persist() {
        if (::prefs.isInitialized) prefs.edit().putString(KEY_PAYMENTS, encode(_payments.value)).apply()
    }

    private fun encode(payments: List<ScheduledPayment>): String = JSONArray().apply {
        payments.forEach { p ->
            put(
                JSONObject()
                    .put("accountId", p.accountId)
                    .put("date", p.date)
                    .put("amount", p.amount)
                    .put("note", p.note)
                    .put("categoryId", p.categoryId ?: JSONObject.NULL)
            )
        }
    }.toString()

    private fun decode(json: String): List<ScheduledPayment> {
        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            ScheduledPayment(
                accountId = o.getLong("accountId"),
                date = o.getLong("date"),
                amount = o.getDouble("amount"),
                note = o.getString("note"),
                categoryId = if (o.isNull("categoryId")) null else o.getLong("categoryId")
            )
        }
    }
}

/** The same shape as a real transaction, so the recurring-bill logic can match a scheduled
 * payment against a bill's posting history with the exact same merchant rules. */
fun ScheduledPayment.toTransactionDetails(categoriesById: Map<Long, Category>): TransactionWithDetails {
    val category = categoryId?.let { categoriesById[it] }
    return TransactionWithDetails(
        id = 0L,
        amount = amount,
        type = TransactionType.EXPENSE,
        accountId = accountId,
        accountName = "",
        categoryId = category?.id,
        categoryName = category?.name,
        mainCategoryName = category?.mainCategory,
        categoryColorHex = category?.colorHex,
        date = date,
        note = note
    )
}
