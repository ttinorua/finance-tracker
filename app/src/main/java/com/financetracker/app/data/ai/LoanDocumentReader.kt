package com.financetracker.app.data.ai

import android.content.Context
import android.net.Uri
import com.financetracker.app.data.prefs.Loan
import com.financetracker.app.data.prefs.LoansAndGoals
import org.json.JSONObject

/**
 * Reads a loan or mortgage document (PDF or photos of its pages, usually in Danish) with Gemini or
 * Claude — the AIs here that can read documents — and returns its key terms in English, for the
 * user to check before saving. Only the extracted fields are kept; the document itself is never
 * stored, and personal details (CPR, account numbers, names, addresses) are left out.
 */
object LoanDocumentReader {

    const val MAX_FILES = 6

    private val PROMPT = """
        This is a loan or mortgage document (for example a loan offer, loan agreement, mortgage deed or annual statement — lånetilbud, låneaftale, pantebrev, gældsbrev, årsopgørelse), most likely in Danish. Extract its key terms for the borrower's personal finance app, in English.

        Reply with only a JSON object with these keys, using null when the document doesn't say:
        - "name": a short English name, e.g. "House mortgage", "Car loan"
        - "lender": the bank or mortgage institute
        - "kind": one of "mortgage", "bank_loan", "car_loan", "student_loan", "other"
        - "loan_type": in English with the Danish term in brackets, e.g. "Adjustable-rate F5 (rentetilpasningslån F5)" or "Fixed-rate 30 years (fastforrentet obligationslån)"
        - "original_amount", "remaining_debt": numbers (the debt / hovedstol / restgæld)
        - "interest_rate_pct": the nominal yearly interest rate in % (number)
        - "contribution_rate_pct": the mortgage contribution rate (bidragssats) in % a year (number)
        - "monthly_payment": the payment per month (number); if payments are quarterly, divide by 3
        - "payments_per_year": how many payments a year (number: 12 monthly, 4 quarterly, 2, 1); Danish mortgages are usually 4 (terminer)
        - "first_payment_date": the first payment date (første betaling / første termin), YYYY-MM-DD
        - "payout_date": when the loan is paid out (udbetaling), YYYY-MM-DD
        - "first_payment_amount": the first payment's amount if the document gives it (number)
        - "years_left": number of years left (number)
        - "end_date": when the loan ends (YYYY-MM-DD if known)
        - "interest_only_until": end of any interest-only period (afdragsfrihed)
        - "rate_type": "fixed" or "variable"
        - "next_rate_reset": the next interest rate reset (rentetilpasning), if any
        - "early_repayment": the early repayment / redemption terms (indfrielse, kurs, fees) in English
        - "other_terms": other important terms in English with Danish terms in brackets (APR / ÅOP, fees, security, conditions)
        - "summary": a plain-English summary of the document in 3 to 6 sentences, explaining any Danish terms

        Numbers are plain JSON numbers: Danish documents write 1.234.567,89 meaning 1234567.89.
        Never include personal data: no CPR numbers, account, loan or customer numbers, names of people or addresses.
    """.trimIndent()

    /** True when an AI that can read documents (Gemini or Claude) has a key. */
    val isAvailable: Boolean get() = DocumentAi.isAvailable

    suspend fun read(context: Context, uris: List<Uri>): Result<Loan> =
        DocumentAi.read(context, uris.take(MAX_FILES), PROMPT, "Extract the loan terms as described.", 3_000).mapCatching { toLoan(it) }

    internal fun toLoan(text: String): Loan {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) throw AiRequestException("The AI couldn't find loan terms in that document.")
        val json = JSONObject(text.substring(start, end + 1))
        val loan = LoansAndGoals.loanFromJson(json, Loan().id)
        return loan.copy(
            name = scrub(loan.name).ifBlank { "Loan" },
            lender = scrub(loan.lender),
            loanType = scrub(loan.loanType),
            earlyRepayment = scrub(loan.earlyRepayment),
            otherTerms = scrub(loan.otherTerms),
            summary = scrub(loan.summary)
        )
    }

    /** Belt and braces on top of the prompt: drops anything shaped like a CPR number or a long
     * account/loan number. */
    internal fun scrub(text: String): String = text
        .replace(Regex("\\b\\d{6}-?\\d{4}\\b"), "[removed]")
        .replace(Regex("\\b\\d{4}[ -]?\\d{6,}\\b"), "[removed]")
        .replace(Regex("\\b\\d{9,}\\b"), "[removed]")
        .trim()
}
