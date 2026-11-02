package com.financetracker.app.data.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.Base64PdfSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.DocumentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.TextBlockParam
import com.financetracker.app.data.prefs.Loan
import com.financetracker.app.data.prefs.LoansAndGoals
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Reads a loan or mortgage document (PDF or photos of its pages, usually in Danish) with Gemini or
 * Claude — the AIs here that can read documents — and returns its key terms in English, for the
 * user to check before saving. Only the extracted fields are kept; the document itself is never
 * stored, and personal details (CPR, account numbers, names, addresses) are left out.
 */
object LoanDocumentReader {

    private const val MAX_PDF_BYTES = 15_000_000
    private const val MAX_IMAGE_SIDE = 2000
    const val MAX_FILES = 6
    /** Room for a full payment table (a 30-year quarterly loan has ~120 rows) on top of the terms. */
    private const val MAX_REPLY_TOKENS = 12_000

    private class Part(val mimeType: String, val base64: String)

    private val PROMPT = """
        This is a loan or mortgage document (for example a loan offer, loan agreement, mortgage deed or annual statement — lånetilbud, låneaftale, pantebrev, gældsbrev, årsopgørelse), most likely in Danish. Extract its key terms for the borrower's personal finance app, in English.

        Reply with only a JSON object with these keys, using null when the document doesn't say:
        - "name": a short English name, e.g. "House mortgage", "Car loan"
        - "lender": the bank or mortgage institute
        - "kind": one of "mortgage", "bank_loan", "car_loan", "student_loan", "other"
        - "loan_type": in English with the Danish term in brackets, e.g. "Adjustable-rate F5 (rentetilpasningslån F5)" or "Fixed-rate 30 years (fastforrentet obligationslån)"
        - "original_amount", "remaining_debt": numbers (the debt / hovedstol / restgæld)
        - "interest_rate_pct": the yearly interest rate in % (number) — for a Danish mortgage the cash rate (kontantrente); if only given per payment period (e.g. "Rente 0,9165% af restgæld" per quarter), multiply up to a year
        - "contribution_rate_pct": the mortgage contribution rate (bidragssats) in % a year (number); if given per quarter (e.g. "Bidrag 0,2186% af restgæld"), multiply by 4
        - "monthly_payment": the payment per month (number); if payments are quarterly, divide by 3 and say so in other_terms
        - "years_left": number of years left (number)
        - "end_date": when the loan ends (YYYY-MM-DD if known)
        - "interest_only_until": end of any interest-only period (afdragsfrihed)
        - "rate_type": "fixed" or "variable"
        - "next_rate_reset": the next interest rate reset (rentetilpasning), if any
        - "early_repayment": the early repayment / redemption terms (indfrielse, kurs, fees) in English; if the loan is funded by bonds, say what the bond debt is (obligationshovedstol / obligationsrestgæld) next to the cash debt, since paying off early costs the bonds' market price
        - "other_terms": other important terms in English with Danish terms in brackets (APR / ÅOP, fees, security, conditions)
        - "summary": a plain-English summary of the document in 3 to 6 sentences, explaining any Danish terms
        - "schedule": EVERY row of the payment table (afdragsforløb / ydelsesforløb), across all pages, oldest first, each as an array [date "YYYY-MM-DD", interest and fee (rente og bidrag), principal (afdrag), payment before tax (ydelse før skat), payment after tax (ydelse efter skat, or null), debt left after it (kontantrestgæld / restgæld)]. Skip marker rows without numbers (e.g. "Rentetilpasning"). Copy the numbers exactly. [] if there is no table.
        - "total_payments": how many payments (terminer) the loan has in all (number)
        - "rate_resets": every interest rate reset date (rentetilpasning) as "YYYY-MM-DD", using the date it takes effect (e.g. 1 July), [] if none
        - "payment_day": the day of the month payments are due (number), and "payment_months": the months they're due in, as numbers 1–12
        - "discount_name", "discount_pct", "discount_until": a discount on the fee that the payment table does NOT include (e.g. Totalkredit's KundeKroner: name, % a year of the debt, the date it's guaranteed until as "YYYY-MM-DD" or "YYYY-MM")
        - "tax_deduction_pct": the tax rate used for the after-tax figures (skattesats), in %
        - "setup_costs": total one-off costs of taking the loan (omkostninger ved optagelse af lån)
        - "total_interest_and_fees": total interest and fee over the loan's life (samlet rente og bidrag i lånets løbetid)
        - "total_repayable": the total amount to pay over the loan's life (det samlede beløb der skal betales)
        - "aop_pct": the ÅOP (yearly cost in % including all costs)

        Numbers are plain JSON numbers: Danish documents write 1.234.567,89 meaning 1234567.89. Dates in the document are Danish (11.12.2026 is 11 December 2026).
        Never include personal data: no CPR numbers, account, loan or customer numbers, names of people or addresses.
    """.trimIndent()

    /** True when an AI that can read documents (Gemini or Claude) has a key. */
    val isAvailable: Boolean get() = readers().isNotEmpty()

    private fun readers() = AiSettings.chain.filter { it == AiProvider.GEMINI || it == AiProvider.CLAUDE }

    suspend fun read(context: Context, uris: List<Uri>): Result<Loan> = withContext(Dispatchers.IO) {
        val providers = readers()
        if (providers.isEmpty()) {
            return@withContext Result.failure(AiRequestException("Reading documents needs Gemini or Claude. Add a key in Settings > AI assistant."))
        }
        val parts = try {
            uris.take(MAX_FILES).map { load(context, it) }
        } catch (e: Exception) {
            return@withContext Result.failure(AiRequestException(e.message ?: "Couldn't open that file.", e))
        }
        var firstError: Throwable? = null
        for (provider in providers) {
            try {
                val text = when (provider) {
                    AiProvider.GEMINI -> readWithGemini(AiSettings.keyFor(provider) ?: continue, parts)
                    else -> readWithClaude(parts)
                }
                return@withContext Result.success(toLoan(text))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (firstError == null) firstError = e as? AiRequestException ?: AiRequestException("${provider.label} couldn't read it: ${e.message}", e)
            }
        }
        Result.failure(firstError ?: AiRequestException("Couldn't read the document."))
    }

    private fun load(context: Context, uri: Uri): Part {
        val resolver = context.contentResolver
        val type = resolver.getType(uri).orEmpty()
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalArgumentException("Couldn't open that file.")
        val isPdf = type == "application/pdf" || (bytes.size > 4 && String(bytes, 0, 4, Charsets.US_ASCII) == "%PDF")
        if (isPdf) {
            require(bytes.size <= MAX_PDF_BYTES) { "That PDF is too large (max 15 MB)." }
            return Part("application/pdf", Base64.encodeToString(bytes, Base64.NO_WRAP))
        }
        // A photo: scaled down so it uploads quickly and fits the AI's limits.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0) { "That file isn't a PDF or an image." }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_IMAGE_SIDE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IllegalArgumentException("That image couldn't be read.")
        val scale = MAX_IMAGE_SIDE.toFloat() / maxOf(decoded.width, decoded.height)
        val bitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true)
        } else {
            decoded
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return Part("image/jpeg", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
    }

    private fun readWithGemini(key: String, parts: List<Part>): String {
        val body = GeminiService.request(PROMPT, listOf(ChatTurn(isUser = true, text = PROMPT)), MAX_REPLY_TOKENS, quick = false)
        val content = JSONArray()
        parts.forEach { content.put(JSONObject().put("inlineData", JSONObject().put("mimeType", it.mimeType).put("data", it.base64))) }
        content.put(JSONObject().put("text", "Extract the loan terms as described."))
        body.put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", content)))
        body.getJSONObject("generationConfig").put("responseMimeType", "application/json")
        val response = GeminiService.send(key, body, readTimeoutMs = 240_000)
        return GeminiService.parts(response).filter { !it.optBoolean("thought") }.joinToString("") { it.optString("text") }
    }

    private fun readWithClaude(parts: List<Part>): String {
        val client = ClaudeService.client() ?: throw AiNotConfiguredException()
        val blocks = parts.map { part ->
            if (part.mimeType == "application/pdf") {
                ContentBlockParam.ofDocument(DocumentBlockParam.builder().source(Base64PdfSource.builder().data(part.base64).build()).build())
            } else {
                ContentBlockParam.ofImage(
                    ImageBlockParam.builder()
                        .source(Base64ImageSource.builder().data(part.base64).mediaType(Base64ImageSource.MediaType.IMAGE_JPEG).build())
                        .build()
                )
            }
        } + ContentBlockParam.ofText(TextBlockParam.builder().text("Extract the loan terms as described.").build())
        val params = ClaudeService.baseParams(MAX_REPLY_TOKENS.toLong(), OutputConfig.Effort.MEDIUM)
            .system(PROMPT)
            .addUserMessageOfBlockParams(blocks)
            .build()
        val message = try {
            ClaudeService.send(client, params)
        } catch (e: Exception) {
            throw ClaudeService.mapError(e)
        }
        return message.content().filter { it.isText() }.joinToString("") { it.asText().text() }
    }

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
