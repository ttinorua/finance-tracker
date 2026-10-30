package com.financetracker.app.data.prefs

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** One loan or mortgage, as entered in Settings > My loans & goals (or read from a document).
 * Text fields are in English, with the Danish term in brackets where the document used one. */
data class Loan(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val lender: String = "",
    /** mortgage, bank_loan, car_loan, student_loan or other. */
    val kind: String = "mortgage",
    /** e.g. "Adjustable-rate F5 (rentetilpasningslån F5)". */
    val loanType: String = "",
    val originalAmount: Double? = null,
    val remainingDebt: Double? = null,
    val interestRatePct: Double? = null,
    /** Danish mortgage contribution rate (bidragssats), % a year of the remaining debt. */
    val contributionRatePct: Double? = null,
    val monthlyPayment: Double? = null,
    val yearsLeft: Double? = null,
    val endDate: String = "",
    val interestOnlyUntil: String = "",
    /** fixed or variable. */
    val rateType: String = "",
    val nextRateReset: String = "",
    val earlyRepayment: String = "",
    val otherTerms: String = "",
    /** A short English summary of the source document, if the loan was read from one. */
    val summary: String = "",
    /** The repeating payment (PBS) that pays this loan, by merchant
     * ([com.financetracker.app.util.advisor.FinanceAnalysis.merchantOf]); blank = not linked. */
    val paymentMerchant: String = "",
    /** When [remainingDebt] was last exact (typed in, or read from an årsopgørelse). Payments
     * after this lower the estimated debt ([com.financetracker.app.util.Mortgage]). */
    val debtAsOf: Long? = null,
    /** The home's value as the person typed it in, for friværdi. */
    val homeValue: Double? = null,
    val homeValueUpdatedAt: Long? = null,
    /** The person's share of the home in %, from the skøde; null = not set (show the whole). */
    val ownershipSharePct: Double? = null,
    /** Payments a year: 12 monthly, 4 quarterly (Danish mortgages), 2, 1; null = not said (monthly). */
    val paymentsPerYear: Int? = null,
    /** Older builds: one payment's amount. Now [monthlyPayment] is the cost per month and each
     * payment is the months it covers times that; kept only to read old data. */
    val paymentAmount: Double? = null,
    /** The first (or next) payment date, YYYY-MM-DD; later ones follow every 12 / [paymentsPerYear] months. */
    val firstPaymentDate: String = "",
    /** The account the loan is paid from (the shared bills account plans for it then). */
    val paidFromAccountId: Long? = null,
    /** When the loan was paid out (YYYY-MM-DD): the first payment covers the months since then. */
    val payoutDate: String = "",
    /** The first payment's exact amount, when the payment overview gives it. */
    val firstPaymentAmount: Double? = null,
    /** Shared with the household: the other phone sees and can edit the same loan. */
    val sharedWithHousehold: Boolean = false,
    /** Who shared it, when it came from the partner's phone ("" = added here). */
    val sharedBy: String = "",
    /** When it was last changed (the newer copy wins between the two phones). */
    val updatedAt: Long? = null
)

data class SavingsGoal(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val targetAmount: Double? = null,
    val savedAmount: Double? = null,
    val monthlySaving: Double? = null,
    val targetDate: String = "",
    /** When set, the goal's saved amount is this account's balance, kept up to date by syncs. */
    val accountId: Long? = null,
    /** When tracking started and the amount saved then — the baseline for measuring progress. */
    val startedAt: Long? = null,
    val startAmount: Double? = null
)

/** The user's loans and savings goals, for the AI advisor's loan and goal advice. Stored as JSON
 * in the app's preferences (so they're part of full backups). */
object LoansAndGoals {
    private const val PREFS_NAME = "finance_prefs"
    private const val KEY_LOANS = "advisor_loans"
    private const val KEY_GOALS = "advisor_goals"

    private lateinit var prefs: SharedPreferences
    private val _loans = MutableStateFlow<List<Loan>>(emptyList())
    val loans: StateFlow<List<Loan>> = _loans.asStateFlow()
    private val _goals = MutableStateFlow<List<SavingsGoal>>(emptyList())
    val goals: StateFlow<List<SavingsGoal>> = _goals.asStateFlow()

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _loans.value = runCatching { parseLoans(prefs.getString(KEY_LOANS, null)) }.getOrDefault(emptyList())
        _goals.value = runCatching { parseGoals(prefs.getString(KEY_GOALS, null)) }.getOrDefault(emptyList())
    }

    /** Saves [loan]; [touch] marks it changed now (a copy from the partner's phone keeps its time). */
    fun saveLoan(loan: Loan, touch: Boolean = true) {
        val saved = if (touch) loan.copy(updatedAt = System.currentTimeMillis()) else loan
        _loans.value = _loans.value.map { if (it.id == loan.id) saved else it }.let { list -> if (list.any { it.id == loan.id }) list else list + saved }
        prefs.edit().putString(KEY_LOANS, JSONArray(_loans.value.map(::loanToJson)).toString()).apply()
    }

    fun deleteLoan(id: String) {
        _loans.value = _loans.value.filterNot { it.id == id }
        prefs.edit().putString(KEY_LOANS, JSONArray(_loans.value.map(::loanToJson)).toString()).apply()
    }

    /** Saves [goal]. A new goal (or one switched to a different account) starts tracking now,
     * from [currentAmount] — its saved amount, or the linked account's balance. */
    fun saveGoal(goal: SavingsGoal, currentAmount: Double? = goal.savedAmount) {
        val previous = _goals.value.firstOrNull { it.id == goal.id }
        val restart = goal.startedAt == null || (previous != null && previous.accountId != goal.accountId)
        val saved = if (restart) goal.copy(startedAt = System.currentTimeMillis(), startAmount = currentAmount) else goal
        _goals.value = _goals.value.map { if (it.id == goal.id) saved else it }.let { if (previous == null) it + saved else it }
        prefs.edit().putString(KEY_GOALS, JSONArray(_goals.value.map(::goalToJson)).toString()).apply()
    }

    /** Puts [goal] back exactly as it was (Undo). */
    fun restoreGoal(goal: SavingsGoal) {
        _goals.value = _goals.value.filterNot { it.id == goal.id } + goal
        prefs.edit().putString(KEY_GOALS, JSONArray(_goals.value.map(::goalToJson)).toString()).apply()
    }

    fun deleteGoal(id: String) {
        _goals.value = _goals.value.filterNot { it.id == id }
        prefs.edit().putString(KEY_GOALS, JSONArray(_goals.value.map(::goalToJson)).toString()).apply()
    }

    fun loanToJson(loan: Loan): JSONObject = JSONObject()
        .put("id", loan.id).put("name", loan.name).put("lender", loan.lender).put("kind", loan.kind)
        .put("loan_type", loan.loanType)
        .putOpt("original_amount", loan.originalAmount).putOpt("remaining_debt", loan.remainingDebt)
        .putOpt("interest_rate_pct", loan.interestRatePct).putOpt("contribution_rate_pct", loan.contributionRatePct)
        .putOpt("monthly_payment", loan.monthlyPayment).putOpt("years_left", loan.yearsLeft)
        .put("end_date", loan.endDate).put("interest_only_until", loan.interestOnlyUntil)
        .put("rate_type", loan.rateType).put("next_rate_reset", loan.nextRateReset)
        .put("early_repayment", loan.earlyRepayment).put("other_terms", loan.otherTerms)
        .put("summary", loan.summary)
        .put("payment_merchant", loan.paymentMerchant).putOpt("debt_as_of", loan.debtAsOf)
        .putOpt("home_value", loan.homeValue).putOpt("home_value_updated_at", loan.homeValueUpdatedAt)
        .putOpt("ownership_share_pct", loan.ownershipSharePct)
        .putOpt("payments_per_year", loan.paymentsPerYear).putOpt("payment_amount", loan.paymentAmount)
        .put("first_payment_date", loan.firstPaymentDate).putOpt("paid_from_account_id", loan.paidFromAccountId)
        .put("payout_date", loan.payoutDate).putOpt("first_payment_amount", loan.firstPaymentAmount)
        .put("shared_with_household", loan.sharedWithHousehold).put("shared_by", loan.sharedBy).putOpt("updated_at", loan.updatedAt)

    /** Reads a loan from JSON — the app's own format, or what the document reader returns. */
    fun loanFromJson(json: JSONObject, id: String = json.optString("id").ifBlank { UUID.randomUUID().toString() }): Loan {
        fun num(key: String): Double? = if (json.isNull(key) || !json.has(key)) null else json.optDouble(key).takeIf { !it.isNaN() }
        fun text(key: String): String = if (json.isNull(key)) "" else json.optString(key).trim()
        return Loan(
            id = id,
            name = text("name"), lender = text("lender"), kind = text("kind").ifBlank { "other" },
            loanType = text("loan_type"), originalAmount = num("original_amount"), remainingDebt = num("remaining_debt"),
            interestRatePct = num("interest_rate_pct"), contributionRatePct = num("contribution_rate_pct"),
            monthlyPayment = num("monthly_payment"), yearsLeft = num("years_left"), endDate = text("end_date"),
            interestOnlyUntil = text("interest_only_until"), rateType = text("rate_type"),
            nextRateReset = text("next_rate_reset"), earlyRepayment = text("early_repayment"),
            otherTerms = text("other_terms"), summary = text("summary"),
            paymentMerchant = text("payment_merchant"),
            debtAsOf = num("debt_as_of")?.toLong(),
            homeValue = num("home_value"),
            homeValueUpdatedAt = num("home_value_updated_at")?.toLong(),
            ownershipSharePct = num("ownership_share_pct"),
            paymentsPerYear = num("payments_per_year")?.toInt()?.takeIf { it in 1..12 },
            paymentAmount = num("payment_amount"),
            firstPaymentDate = text("first_payment_date"),
            paidFromAccountId = num("paid_from_account_id")?.toLong(),
            payoutDate = text("payout_date"),
            firstPaymentAmount = num("first_payment_amount"),
            sharedWithHousehold = json.optBoolean("shared_with_household", false),
            sharedBy = text("shared_by"),
            updatedAt = num("updated_at")?.toLong()
        )
    }

    fun goalToJson(goal: SavingsGoal): JSONObject = JSONObject()
        .put("id", goal.id).put("name", goal.name)
        .putOpt("target_amount", goal.targetAmount).putOpt("saved_amount", goal.savedAmount)
        .putOpt("monthly_saving", goal.monthlySaving).put("target_date", goal.targetDate)
        .putOpt("account_id", goal.accountId).putOpt("started_at", goal.startedAt).putOpt("start_amount", goal.startAmount)

    private fun goalFromJson(json: JSONObject): SavingsGoal {
        fun num(key: String): Double? = if (json.isNull(key) || !json.has(key)) null else json.optDouble(key).takeIf { !it.isNaN() }
        return SavingsGoal(
            id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
            name = json.optString("name"), targetAmount = num("target_amount"), savedAmount = num("saved_amount"),
            monthlySaving = num("monthly_saving"), targetDate = json.optString("target_date"),
            accountId = if (json.has("account_id") && !json.isNull("account_id")) json.optLong("account_id") else null,
            startedAt = if (json.has("started_at") && !json.isNull("started_at")) json.optLong("started_at") else null,
            startAmount = num("start_amount")
        )
    }

    private fun parseLoans(raw: String?): List<Loan> {
        val array = JSONArray(raw ?: return emptyList())
        return (0 until array.length()).map { loanFromJson(array.getJSONObject(it)) }
    }

    private fun parseGoals(raw: String?): List<SavingsGoal> {
        val array = JSONArray(raw ?: return emptyList())
        return (0 until array.length()).map { goalFromJson(array.getJSONObject(it)) }
    }
}
