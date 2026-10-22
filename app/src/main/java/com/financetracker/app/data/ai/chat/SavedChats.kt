package com.financetracker.app.data.ai.chat

import android.content.Context
import com.financetracker.app.data.ai.WebSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class SavedCard(
    val title: String,
    val lines: List<String>,
    /** CardStatus name. */
    val status: String,
    val result: String?,
    val transactionIds: List<Long>,
    /** Whether it was a change with Apply/Undo (those can't be applied again after reopening). */
    val hadPlan: Boolean
)

data class SavedMessage(
    val isUser: Boolean,
    val text: String,
    val isError: Boolean = false,
    val usedChatGptPlan: Boolean = false,
    val cards: List<SavedCard> = emptyList(),
    val searchedWeb: Boolean = false,
    val sources: List<WebSource> = emptyList(),
    /** Only the file's name: the file itself is never kept. */
    val attachmentName: String? = null
)

data class SavedChat(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean = false,
    /** True once the person renamed it, so it isn't renamed automatically any more. */
    val renamed: Boolean = false,
    val messages: List<SavedMessage> = emptyList()
)

/**
 * The AI advisor's past chats, kept on this phone only (a file in the app's private storage,
 * never in a backup) for 30 days after they were last used, or for good when pinned. Reopening
 * one sends only its latest messages to the AI, so old chats cost nothing extra.
 */
object SavedChats {
    const val KEEP_DAYS = 30
    private const val FILE_NAME = "ai_chats.json"
    private const val DAY = 24L * 60 * 60 * 1000

    private var file: File? = null
    private val _chats = MutableStateFlow<List<SavedChat>>(emptyList())
    /** Pinned first, then most recently used first. */
    val chats: StateFlow<List<SavedChat>> = _chats.asStateFlow()

    fun init(context: Context) {
        val f = File(context.applicationContext.filesDir, FILE_NAME)
        file = f
        val loaded = runCatching { if (f.exists()) decode(f.readText()) else emptyList() }.getOrDefault(emptyList())
        _chats.value = sorted(prune(loaded, System.currentTimeMillis()))
    }

    fun get(id: String): SavedChat? = _chats.value.firstOrNull { it.id == id }

    /** Adds or replaces [chat]. A chat with no messages isn't kept. */
    @Synchronized
    fun save(chat: SavedChat) {
        if (chat.messages.isEmpty()) return
        update(_chats.value.filterNot { it.id == chat.id } + chat)
    }

    @Synchronized
    fun rename(id: String, title: String) {
        val clean = title.trim().take(80).ifBlank { return }
        update(_chats.value.map { if (it.id == id) it.copy(title = clean, renamed = true) else it })
    }

    @Synchronized
    fun setPinned(id: String, pinned: Boolean) = update(_chats.value.map { if (it.id == id) it.copy(pinned = pinned) else it })

    @Synchronized
    fun delete(id: String) = update(_chats.value.filterNot { it.id == id })

    private fun update(list: List<SavedChat>) {
        val result = sorted(prune(list, System.currentTimeMillis()))
        _chats.value = result
        file?.let { f -> runCatching { f.writeText(encode(result)) } }
    }

    /** A chat's automatic name: the start of its first question. */
    fun titleFrom(firstQuestion: String): String {
        val oneLine = firstQuestion.trim().replace(Regex("\\s+"), " ")
        if (oneLine.length <= 40) return oneLine.ifBlank { "New chat" }
        val cut = oneLine.take(40)
        return cut.substringBeforeLast(' ', cut).trimEnd(',', '.', ' ') + "…"
    }

    internal fun prune(list: List<SavedChat>, now: Long): List<SavedChat> =
        list.filter { it.pinned || now - it.updatedAt < KEEP_DAYS * DAY }

    internal fun sorted(list: List<SavedChat>): List<SavedChat> =
        list.sortedWith(compareByDescending<SavedChat> { it.pinned }.thenByDescending { it.updatedAt })

    internal fun encode(list: List<SavedChat>): String = JSONArray().apply {
        list.forEach { chat ->
            put(
                JSONObject()
                    .put("id", chat.id).put("title", chat.title)
                    .put("created", chat.createdAt).put("updated", chat.updatedAt)
                    .put("pinned", chat.pinned).put("renamed", chat.renamed)
                    .put("messages", JSONArray().apply {
                        chat.messages.forEach { m ->
                            put(
                                JSONObject().put("user", m.isUser).put("text", m.text).put("error", m.isError)
                                    .put("plan", m.usedChatGptPlan)
                                    .put("web", m.searchedWeb)
                                    .put("sources", JSONArray().apply {
                                        m.sources.forEach { put(JSONObject().put("title", it.title).put("url", it.url)) }
                                    })
                                    .put("file", m.attachmentName ?: JSONObject.NULL)
                                    .put("cards", JSONArray().apply {
                                        m.cards.forEach { c ->
                                            put(
                                                JSONObject().put("title", c.title).put("lines", JSONArray(c.lines))
                                                    .put("status", c.status).put("result", c.result ?: JSONObject.NULL)
                                                    .put("tx", JSONArray(c.transactionIds)).put("hadPlan", c.hadPlan)
                                            )
                                        }
                                    })
                            )
                        }
                    })
            )
        }
    }.toString()

    internal fun decode(raw: String): List<SavedChat> {
        val array = JSONArray(raw)
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            val messages = o.optJSONArray("messages") ?: JSONArray()
            SavedChat(
                id = o.getString("id"),
                title = o.optString("title", "Chat"),
                createdAt = o.optLong("created"),
                updatedAt = o.optLong("updated"),
                pinned = o.optBoolean("pinned"),
                renamed = o.optBoolean("renamed"),
                messages = (0 until messages.length()).map { j ->
                    val m = messages.getJSONObject(j)
                    val cards = m.optJSONArray("cards") ?: JSONArray()
                    val sources = m.optJSONArray("sources") ?: JSONArray()
                    SavedMessage(
                        isUser = m.optBoolean("user"),
                        text = m.optString("text"),
                        isError = m.optBoolean("error"),
                        usedChatGptPlan = m.optBoolean("plan"),
                        searchedWeb = m.optBoolean("web"),
                        sources = (0 until sources.length()).mapNotNull { k ->
                            val src = sources.optJSONObject(k) ?: return@mapNotNull null
                            WebSource(src.optString("title"), src.optString("url").ifBlank { return@mapNotNull null })
                        },
                        attachmentName = if (m.isNull("file")) null else m.optString("file").ifBlank { null },
                        cards = (0 until cards.length()).map { k ->
                            val c = cards.getJSONObject(k)
                            val lines = c.optJSONArray("lines") ?: JSONArray()
                            val tx = c.optJSONArray("tx") ?: JSONArray()
                            SavedCard(
                                title = c.optString("title"),
                                lines = (0 until lines.length()).map { lines.getString(it) },
                                status = c.optString("status", "PENDING"),
                                result = if (c.isNull("result")) null else c.optString("result"),
                                transactionIds = (0 until tx.length()).map { tx.getLong(it) },
                                hadPlan = c.optBoolean("hadPlan")
                            )
                        }
                    )
                }
            )
        }
    }
}
