package com.financetracker.app.data.ai

import android.content.Context
import android.net.Uri
import com.financetracker.app.data.db.entity.Category
import com.financetracker.app.data.db.entity.TransactionType
import org.json.JSONObject
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.roundToLong

/** A receipt as the AI read it, before it is saved. [amount] on each line is what was paid for it
 * after the line's own discount. */
data class ParsedReceipt(
    val storeName: String,
    val purchasedAt: Long,
    val total: Double,
    val discount: Double,
    val receiptNumber: String?,
    val items: List<ParsedReceiptItem>
) {
    /** The store's receipt number when known, else a fingerprint of store, minute and total, so the
     * same receipt is never imported twice. */
    val externalId: String
        get() = receiptNumber?.let { "no:$it" }
            ?: "fp:${storeName.lowercase().trim()}|${purchasedAt / 60_000}|${(total * 100).roundToLong()}"
}

data class ParsedReceiptItem(
    val name: String,
    val quantity: Double,
    val amount: Double,
    val discount: Double,
    val categoryId: Long?
)

/**
 * Reads a shopping receipt — a photo, a screenshot or a PDF shared from a store app (Netto+, Coop,
 * Lidl Plus, REMA 1000…) — with Gemini or Claude, and sorts each line into one of the user's own
 * expense categories. Nothing personal is kept: only store, time, lines and totals.
 */
object ReceiptReader {

    const val MAX_FILES = 4

    val isAvailable: Boolean get() = DocumentAi.isAvailable

    internal fun prompt(categories: List<Category>): String {
        val list = categories.filter { it.type == TransactionType.EXPENSE }
            .joinToString("\n") { "${it.id}|${it.mainCategory}|${it.name}" }
        return """
            This is a shopping receipt (kvittering / bon), most likely Danish. It may be a photo, a screenshot from a store app or a PDF, and may span several images.

            Reply with only a JSON object with these keys:
            - "store": the store's name, short, e.g. "Netto", "Føtex", "Coop 365", "Lidl", "REMA 1000", "Bilka"
            - "date": the purchase date as YYYY-MM-DD
            - "time": the purchase time as HH:MM, or null
            - "receipt_number": the receipt / transaction number (bon nr., kvitteringsnr.) if printed, else null
            - "total": the amount actually paid (at betale / total / i alt), a number
            - "discount": all discounts on the receipt together (rabat, medlemsrabat, besparelse) as a positive number, or 0
            - "items": one object per product line, in receipt order, each with
              - "name": the product as printed, tidied up (normal capitalisation, no codes), e.g. "Arla letmælk 1 l"
              - "quantity": number of units or the weight in kg (number, 1 if not shown)
              - "amount": what was paid for the line after its own discounts (number)
              - "discount": the discount on that line as a positive number, or 0
              - "category_id": the id of the best fitting category from the list below, or null if none fits

            Bottle deposits (pant) are their own line. Leave out payment lines, change, VAT summaries and loyalty points.
            Numbers are plain JSON numbers: Danish receipts write 1.234,50 meaning 1234.50.
            Never include personal data: no card numbers, member numbers, names or addresses.

            The user's expense categories, as id|Main category|Category:
            $list
        """.trimIndent()
    }

    suspend fun read(context: Context, uris: List<Uri>, categories: List<Category>): Result<ParsedReceipt> =
        DocumentAi.read(context, uris.take(MAX_FILES), prompt(categories), "Read the receipt as described.", 8_000)
            .mapCatching { parse(it, categories.filter { c -> c.type == TransactionType.EXPENSE }.map { c -> c.id }.toSet()) }

    /** Turns the AI's reply into a receipt; categories not in [validCategoryIds] are dropped. */
    internal fun parse(text: String, validCategoryIds: Set<Long>, now: Long = System.currentTimeMillis()): ParsedReceipt {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) throw AiRequestException("The AI couldn't find a receipt in that.")
        val json = JSONObject(text.substring(start, end + 1))
        val itemsJson = json.optJSONArray("items")
        val items = (0 until (itemsJson?.length() ?: 0)).mapNotNull { i ->
            val o = itemsJson!!.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name").takeUnless { it == "null" }.orEmpty().trim()
            val amount = number(o, "amount") ?: return@mapNotNull null
            if (name.isEmpty()) return@mapNotNull null
            val categoryId = if (o.isNull("category_id")) null else o.optLong("category_id", -1).takeIf { it in validCategoryIds }
            ParsedReceiptItem(
                name = LoanDocumentReader.scrub(name).take(80),
                quantity = number(o, "quantity")?.takeIf { it > 0 } ?: 1.0,
                amount = amount,
                discount = abs(number(o, "discount") ?: 0.0),
                categoryId = categoryId
            )
        }
        val total = number(json, "total") ?: items.sumOf { it.amount }
        if (items.isEmpty() && total <= 0) throw AiRequestException("The AI couldn't find a receipt in that.")
        val receiptNumber = json.optString("receipt_number").trim()
            .takeUnless { it.isEmpty() || it == "null" || it.length > 40 }
        return ParsedReceipt(
            storeName = json.optString("store").trim().takeUnless { it.isEmpty() || it == "null" }?.take(40) ?: "Store",
            purchasedAt = parseDate(json.optString("date"), json.optString("time"), now),
            total = total,
            discount = abs(number(json, "discount") ?: 0.0),
            receiptNumber = receiptNumber,
            items = items
        )
    }

    /** A number, also when the AI wrote it as a Danish string ("1.234,50"). */
    private fun number(o: JSONObject, key: String): Double? {
        if (o.isNull(key) || !o.has(key)) return null
        val v = o.opt(key)
        if (v is Number) return v.toDouble()
        val s = v.toString().replace(Regex("[^0-9,.-]"), "")
        if (s.isEmpty()) return null
        val normalised = if (s.contains(',')) s.replace(".", "").replace(',', '.') else s
        return normalised.toDoubleOrNull()
    }

    /** The purchase time; today (at noon) when the date is missing or impossible. */
    internal fun parseDate(date: String, time: String, now: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val d = Regex("(\\d{4})-(\\d{1,2})-(\\d{1,2})").find(date)
        val t = Regex("(\\d{1,2})[:.](\\d{2})").find(time)
        if (d == null) {
            cal.set(Calendar.HOUR_OF_DAY, 12); cal.set(Calendar.MINUTE, 0)
        } else {
            val (y, m, day) = d.destructured
            cal.set(y.toInt(), m.toInt() - 1, day.toInt(), t?.groupValues?.get(1)?.toInt() ?: 12, t?.groupValues?.get(2)?.toInt() ?: 0)
        }
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        // A misread year or a future date: assume the receipt is from today.
        return if (cal.timeInMillis > now + 86_400_000L || cal.timeInMillis < now - 3L * 365 * 86_400_000L) {
            Calendar.getInstance().apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        } else {
            cal.timeInMillis
        }
    }
}
