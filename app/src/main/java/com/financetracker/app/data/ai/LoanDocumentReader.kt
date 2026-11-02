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
    /** Room for a whole payment table (a 30-year quarterly loan has ~120 rows) on top of the terms. */
    private const val MAX_REPLY_TOKENS = 12_000

    private val PROMPT = """
        This is a loan or mortgage document (for example a loan offer, loan agreement, mortgage deed or annual statement — lånetilbud, låneaftale, pantebrev, gældsbrev, årsopgørelse), most likely in Danish. Extract its key terms for the borrower's personal finance app, in English.

        Reply with only a JSON object with these keys, using null when the document doesn't say:
        - "name": a short English name, e.g. "House mortgage", "Car loan"
        - "lender": the bank or mortgage institute
        - "kind": one of "mortgage", "bank_loan", "car_loan", "student_loan", "other"
        - "loan_type": in English with the Danish term in brackets, e.g. "Adjustable-rate F5 (rentetilpasningslån F5)" or "Fixed-rate 30 years (fastforrentet obligationslån)"
        - "original_amount", "remaining_debt": numbers (the debt / hovedstol / restgæld)
        - "interest_rate_pct": the yearly interest rate in % (number); for a Danish mortgage the cash rate (kontantrente). If only given per payment (e.g. "Rente 0,9165% af restgæld" per quarter), multiply up to a year
        - "contribution_rate_pct": the mortgage contribution rate (bidragssats) in % a year (number); if given per quarter (e.g. "Bidrag 0,2186% af restgæld"), multiply by 4
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
        - "early_repayment": the early repayment / redemption terms (indfrielse, kurs, fees) in English; if the loan is funded by bonds, also say the bond debt (obligationshovedstol / obligationsrestgæld) next to the cash debt, since paying off early costs the bonds' market price
        - "other_terms": other important terms in English with Danish terms in brackets (APR / ÅOP, fees, security, conditions)
        - "summary": a plain-English summary of the document in 3 to 6 sentences, explaining any Danish terms
        - "schedule": EVERY row of the payment table (afdragsforløb / ydelsesforløb), across all pages, oldest first, each as an array [date "YYYY-MM-DD", interest and fee (rente og bidrag), repayment (afdrag), payment before tax (ydelse før skat), payment after tax (ydelse efter skat, or null), debt left after it (kontantrestgæld / restgæld)]. Skip marker rows without numbers (e.g. "Rentetilpasning"). Copy the numbers exactly. [] if there is no table.
        - "total_payments": how many payments (terminer) the loan has in all (number)
        - "rate_resets": every interest rate reset (rentetilpasning) as "YYYY-MM-DD", the date it takes effect (e.g. 1 July), [] if none
        - "discount_name", "discount_pct", "discount_until": a discount on the fee that the payment table does NOT include (e.g. Totalkredit's KundeKroner): its name, % a year of the debt, and the date it's guaranteed until as "YYYY-MM-DD" or "YYYY-MM"
        - "tax_deduction_pct": the tax rate used for the after-tax figures (skattesats), in %
        - "setup_costs": total one-off costs of taking the loan (omkostninger ved optagelse af lån)
        - "total_interest_and_fees": total interest and fee over the loan's life (samlet rente og bidrag i lånets løbetid)
        - "total_repayable": the total to pay over the loan's life (det samlede beløb, der skal betales)
        - "aop_pct": the ÅOP (yearly cost in % with all costs)

        Numbers are plain JSON numbers: Danish documents write 1.234.567,89 meaning 1234567.89. Dates in the document are Danish (11.12.2026 is 11 December 2026).
        Never include personal data: no CPR numbers, account, loan or customer numbers, names of people or addresses.
    """.trimIndent()

    /** True when an AI that can read documents (Gemini or Claude) has a key. */
    val isAvailable: Boolean get() = DocumentAi.isAvailable

    suspend fun read(context: Context, uris: List<Uri>): Result<Loan> =
        DocumentAi.read(context, uris.take(MAX_FILES), PROMPT, "Extract the loan terms as described.", MAX_REPLY_TOKENS, readTimeoutMs = 240_000)
            .mapCatching { toLoan(it) }

    internal fun toLoan(text: String): Loan {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) throw AiRequestException("The AI couldn't find loan terms in that document.")
        val json = JSONObject(text.substring(start, end + 1))
        val loan = LoansAndGoals.loanFromJson(json, Loan().id)
        return loan.copy(
            name = scrub(loan.name).ifBlank { "Loan" },
            lender = scrub(loan.lender),
            discountName = scrub(loan.discountName),
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
