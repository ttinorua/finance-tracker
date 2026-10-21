package com.financetracker.app.data.ai

import android.content.Context
import com.financetracker.app.data.db.entity.Category
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * A personal, per-user memory of "the category I actually picked for this note" — separate from
 * [LocalCategoryMatcher] (a hardcoded, shared keyword list) since this is the opposite: nothing
 * here is guessed, it's only ever written from a real manual save (see [learn], called from
 * [com.financetracker.app.ui.screens.transactions.AddEditTransactionSheet]'s callers whenever the
 * user adds or edits a transaction with a category), never from sync or AI categorization.
 *
 * Checked *before* [LocalCategoryMatcher] and the AI everywhere a note gets auto-categorized
 * (bank sync, the AI backfill, and — as an auto-fill while typing — a brand-new manual entry), so
 * a correction you make once is remembered for every future transaction with that same note,
 * whether it arrives by sync or you type it yourself.
 *
 * Matched by exact normalized note (trim + lowercase — the same normalization this app already
 * uses for recurring-bill grouping and duplicate detection), not substring: this is meant to be
 * precise to your own merchants, not a fuzzy guess. Last write wins — recategorizing the same
 * note again simply replaces the old mapping.
 */
object LearnedCategoryRules {
    private const val PREFS_NAME = "finance_prefs"
    private const val KEY_RULES = "learned_category_rules"

    private lateinit var prefs: android.content.SharedPreferences
    private val _rules = MutableStateFlow<Map<String, Long>>(emptyMap())
    val rules: StateFlow<Map<String, Long>> get() = _rules

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _rules.value = deserialize(prefs.getString(KEY_RULES, null))
    }

    private fun normalize(note: String) = note.trim().lowercase()

    /** Records that [note] should map to [categoryId] from now on — call only from a real manual
     * save, never from an automatic categorization path. A blank note is never learned, since it
     * carries no identity to key off. */
    fun learn(note: String, categoryId: Long) {
        val key = normalize(note)
        if (key.isBlank()) return
        val updated = _rules.value + (key to categoryId)
        _rules.value = updated
        if (::prefs.isInitialized) {
            prefs.edit().putString(KEY_RULES, serialize(updated)).apply()
        }
    }

    /** Points every learned note that pointed at [fromId] at [toId] instead (a merged category). */
    fun remapCategory(fromId: Long, toId: Long) {
        if (_rules.value.values.none { it == fromId }) return
        val updated = _rules.value.mapValues { (_, id) -> if (id == fromId) toId else id }
        _rules.value = updated
        if (::prefs.isInitialized) prefs.edit().putString(KEY_RULES, serialize(updated)).apply()
    }

    /** Returns a real [Category] from [categories] for [note]'s learned mapping, or null if there
     * isn't one (or the mapped category no longer exists) — a null here means "fall through to
     * [LocalCategoryMatcher]/AI", never "categorize as nothing". */
    fun suggest(note: String, categories: List<Category>): Category? {
        val key = normalize(note)
        if (key.isBlank()) return null
        val categoryId = _rules.value[key] ?: return null
        return categories.firstOrNull { it.id == categoryId }
    }

    private fun serialize(map: Map<String, Long>): String {
        val obj = JSONObject()
        map.forEach { (note, categoryId) -> obj.put(note, categoryId) }
        return obj.toString()
    }

    private fun deserialize(raw: String?): Map<String, Long> {
        if (raw.isNullOrBlank()) return emptyMap()
        return try {
            val obj = JSONObject(raw)
            obj.keys().asSequence().associateWith { obj.getLong(it) }
        } catch (e: Exception) {
            emptyMap()
        }
    }
}
