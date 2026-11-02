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
    /** The lender's payment table (afdragsforløb), oldest first. Empty when not read from a document. */
    val schedule: List<LoanPayment> = emptyList(),
    /** How many payments the document says the loan has in all (e.g. 119), to check the schedule is complete. */
    val totalPayments: Int? = null,
    /** Every interest rate reset (rentetilpasning), YYYY-MM-DD. Schedule rows after the first are estimates. */
    val rateResets: List<String> = emptyList(),
    /** Day of the month payments are due, and the months (1–12) they're due in. */
    val paymentDay: Int? = null,
    val paymentMonths: List<Int> = emptyList(),
    /** A discount on the fee that the lender's table leaves out, e.g. Totalkredit's KundeKroner:
     * % a year of the debt, and the date it's guaranteed until. */
    val discountName: String = "",
    val discountPct: Double? = null,
    val discountUntil: String = "",
    /** Share of interest and fee the user gets back through tax (e.g. 25.83). */
    val taxDeductionPct: Double? = null,
    /** One-off costs of taking the loan, and the totals over its whole life (at today's rate). */
    val setupCosts: Double? = null,
    val totalInterestAndFees: Double? = null,
    val totalRepayable: Double? = null,
    /** ÅOP: yearly cost with all fees included. */
    val aopPct: Double? = null,
    val remindPayments: Boolean = true,
    val remindRateResets: Boolean = true
)

/** One row of a lender's payment table. Dates are YYYY-MM-DD. */
data class LoanPayment(
    val date: String,
    /** Interest and fee together (rente og bidrag) — what the tax deduction applies to. */
    val interestAndFee: Double,
    /** The part that pays down the debt (afdrag). */
    val principal: Double,
    /** The whole payment before tax (ydelse før skat). */
    val payment: Double,
    val paymentAfterTax: Double?,
    /** Debt left after this payment (kontantrestgæld). */
    val debtAfter: Double
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

    fun saveLoan(loan: Loan) {
        _loans.value = _loans.value.filterNot { it.id == loan.id } + loan
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
        .put("schedule", JSONArray(loan.schedule.map(::paymentToJson)))
        .putOpt("total_payments", loan.totalPayments)
        .put("rate_resets", JSONArray(loan.rateResets))
        .putOpt("payment_day", loan.paymentDay)
        .put("payment_months", JSONArray(loan.paymentMonths))
        .put("discount_name", loan.discountName).putOpt("discount_pct", loan.discountPct)
        .put("discount_until", loan.discountUntil).putOpt("tax_deduction_pct", loan.taxDeductionPct)
        .putOpt("setup_costs", loan.setupCosts).putOpt("total_interest_and_fees", loan.totalInterestAndFees)
        .putOpt("total_repayable", loan.totalRepayable).putOpt("aop_pct", loan.aopPct)
        .put("remind_payments", loan.remindPayments).put("remind_rate_resets", loan.remindRateResets)

    /** A schedule row as a compact array: [date, interest and fee, principal, payment, after tax, debt after]. */
    private fun paymentToJson(row: LoanPayment): JSONArray = JSONArray()
        .put(row.date).put(row.interestAndFee).put(row.principal).put(row.payment)
        .put(row.paymentAfterTax ?: JSONObject.NULL).put(row.debtAfter)

    /** Reads a schedule row from the compact array (or an object with the same names). Rows
     * without a date or the core numbers (e.g. a "Rentetilpasning" marker) are skipped. */
    private fun paymentFromJson(value: Any?): LoanPayment? {
        fun num(v: Any?): Double? = when (v) {
            is Number -> v.toDouble()
            is String -> v.trim().takeIf { it.isNotEmpty() }?.let { t ->
                (if (t.contains(',')) t.replace(".", "").replace(',', '.') else t).toDoubleOrNull()
            }
            else -> null
        }?.takeIf { !it.isNaN() }
        val fields: List<Any?> = when (value) {
            is JSONArray -> (0 until 6).map { if (it < value.length() && !value.isNull(it)) value.get(it) else null }
            is JSONObject -> listOf("date", "interest_and_fee", "principal", "payment", "payment_after_tax", "debt_after")
                .map { if (value.has(it) && !value.isNull(it)) value.get(it) else null }
            else -> return null
        }
        val date = (fields[0] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return LoanPayment(
            date = date,
            interestAndFee = num(fields[1]) ?: return null,
            principal = num(fields[2]) ?: return null,
            payment = num(fields[3]) ?: return null,
            paymentAfterTax = num(fields[4]),
            debtAfter = num(fields[5]) ?: return null
        )
    }

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
            schedule = json.optJSONArray("schedule")?.let { rows -> (0 until rows.length()).mapNotNull { paymentFromJson(rows.opt(it)) } }.orEmpty(),
            totalPayments = num("total_payments")?.toInt(),
            rateResets = json.optJSONArray("rate_resets")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).trim().takeIf { d -> d.isNotEmpty() && d != "null" } } }.orEmpty(),
            paymentDay = num("payment_day")?.toInt()?.takeIf { it in 1..31 },
            paymentMonths = json.optJSONArray("payment_months")?.let { a -> (0 until a.length()).mapNotNull { a.optInt(it, 0).takeIf { m -> m in 1..12 } } }.orEmpty(),
            discountName = text("discount_name"), discountPct = num("discount_pct"), discountUntil = text("discount_until"),
            taxDeductionPct = num("tax_deduction_pct"), setupCosts = num("setup_costs"),
            totalInterestAndFees = num("total_interest_and_fees"), totalRepayable = num("total_repayable"), aopPct = num("aop_pct"),
            remindPayments = json.optBoolean("remind_payments", true), remindRateResets = json.optBoolean("remind_rate_resets", true)
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
