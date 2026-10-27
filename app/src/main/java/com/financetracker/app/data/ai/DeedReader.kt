package com.financetracker.app.data.ai

import android.content.Context
import android.net.Uri
import org.json.JSONObject

/**
 * Reads the owner's share of a home from its skøde (title deed, from tinglysning.dk), with the
 * same document AIs as [LoanDocumentReader]. Only the share is kept; the document itself and any
 * personal details in it are never stored.
 */
object DeedReader {

    val isAvailable: Boolean get() = DocumentAi.isAvailable

    private val PROMPT = """
        This is a Danish property deed (skøde), possibly with several owners (købere). Find how the
        property is shared between the owners (ejerandel / andel, for example "1/2", "50%" or
        "60/100").

        Reply with only a JSON object: {"shares_pct": [numbers], "note": "short English explanation"}
        where shares_pct lists each owner's share in % in the order the deed lists them, e.g. [50, 50].
        Use an empty list when the deed doesn't say. Never include names, CPR numbers or addresses.
    """.trimIndent()

    /** Each owner's share in %, in the order the deed lists them (often [50.0, 50.0]). */
    suspend fun read(context: Context, uris: List<Uri>): Result<List<Double>> =
        DocumentAi.read(context, uris.take(LoanDocumentReader.MAX_FILES), PROMPT, "Find the owners' shares.", 800)
            .mapCatching { parse(it) }

    internal fun parse(text: String): List<Double> {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) throw AiRequestException("The AI couldn't find the ownership shares in that document.")
        val array = JSONObject(text.substring(start, end + 1)).optJSONArray("shares_pct")
            ?: throw AiRequestException("The deed doesn't say how the home is shared.")
        val shares = (0 until array.length()).map { array.optDouble(it) }.filter { !it.isNaN() && it > 0 && it <= 100 }
        if (shares.isEmpty()) throw AiRequestException("The deed doesn't say how the home is shared.")
        return shares
    }
}
