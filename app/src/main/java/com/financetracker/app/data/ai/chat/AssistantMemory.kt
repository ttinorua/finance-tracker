package com.financetracker.app.data.ai.chat

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** One thing the person asked the AI advisor to remember, e.g. "3 phones + fiber internet on Telenor". */
data class Memory(val id: Int, val text: String, val savedAt: Long)

/**
 * What the AI advisor remembers across chats: facts the person asked it to keep (in their own
 * words, through the chat) or typed in Settings > AI assistant. Kept on this phone only (app
 * preferences, never in a phone backup) and sent to the AI with each question, like the
 * transactions are.
 */
object AssistantMemory {
    const val MAX_ITEMS = 100
    const val MAX_LENGTH = 300
    private const val PREFS_NAME = "finance_prefs"
    private const val KEY = "assistant_memories"

    private var prefs: android.content.SharedPreferences? = null
    private val _memories = MutableStateFlow<List<Memory>>(emptyList())
    /** Oldest first. */
    val memories: StateFlow<List<Memory>> = _memories.asStateFlow()

    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = p
        _memories.value = runCatching { decode(p.getString(KEY, null)) }.getOrDefault(emptyList())
    }

    sealed interface AddResult {
        data class Saved(val memory: Memory) : AddResult
        data class AlreadySaved(val memory: Memory) : AddResult
        data object Full : AddResult
        data object Empty : AddResult
    }

    @Synchronized
    fun add(text: String, now: Long = System.currentTimeMillis()): AddResult {
        val clean = clean(text) ?: return AddResult.Empty
        _memories.value.firstOrNull { it.text.equals(clean, ignoreCase = true) }?.let { return AddResult.AlreadySaved(it) }
        if (_memories.value.size >= MAX_ITEMS) return AddResult.Full
        val memory = Memory((_memories.value.maxOfOrNull { it.id } ?: 0) + 1, clean, now)
        update(_memories.value + memory)
        return AddResult.Saved(memory)
    }

    /** Replaces the text of memory [id]; false if there's no such memory or [text] is empty. */
    @Synchronized
    fun edit(id: Int, text: String, now: Long = System.currentTimeMillis()): Boolean {
        val clean = clean(text) ?: return false
        if (_memories.value.none { it.id == id }) return false
        update(_memories.value.map { if (it.id == id) it.copy(text = clean, savedAt = now) else it })
        return true
    }

    /** Removes the memories with these ids and returns the ones it removed. */
    @Synchronized
    fun remove(ids: Collection<Int>): List<Memory> {
        val removed = _memories.value.filter { it.id in ids }
        if (removed.isNotEmpty()) update(_memories.value.filterNot { it.id in ids })
        return removed
    }

    fun get(id: Int): Memory? = _memories.value.firstOrNull { it.id == id }

    /** For tests: start from these memories without touching storage. */
    internal fun replaceAllForTest(list: List<Memory>) {
        _memories.value = list
    }

    private fun clean(text: String): String? =
        text.trim().replace(Regex("\\s+"), " ").take(MAX_LENGTH).ifBlank { null }

    private fun update(list: List<Memory>) {
        _memories.value = list
        prefs?.edit()?.putString(KEY, encode(list))?.apply()
    }

    internal fun encode(list: List<Memory>): String = JSONArray().apply {
        list.forEach { put(JSONObject().put("id", it.id).put("text", it.text).put("at", it.savedAt)) }
    }.toString()

    internal fun decode(raw: String?): List<Memory> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = JSONArray(raw)
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val text = o.optString("text").ifBlank { return@mapNotNull null }
            Memory(o.optInt("id"), text, o.optLong("at"))
        }
    }
}
