package com.financetracker.app.ui.screens.ai

import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.asStateFlow
import com.financetracker.app.data.ai.chatgpt.ChatGptService
import com.financetracker.app.data.ai.chatgpt.ChatGptModel
import com.financetracker.app.data.ai.chat.SavedMessage
import com.financetracker.app.data.ai.chat.SavedChats
import com.financetracker.app.data.ai.chat.SavedChat
import com.financetracker.app.data.ai.chat.SavedCard
import com.financetracker.app.data.ai.chat.AssistantPrefs
import com.financetracker.app.data.ai.chat.AssistantMemory
import com.financetracker.app.data.ai.ChatAttachment
import com.financetracker.app.data.ai.DocumentAi
import com.financetracker.app.data.ai.WebSource
import android.content.Context
import android.net.Uri
import com.financetracker.app.data.ai.AiSettings
import com.financetracker.app.data.ai.AiProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.financetracker.app.data.ai.AiService
import com.financetracker.app.data.ai.ChatTurn
import com.financetracker.app.data.ai.advisor.AdvisorActions
import com.financetracker.app.data.ai.advisor.AdvisorCard
import com.financetracker.app.data.ai.advisor.AdvisorSnapshot
import com.financetracker.app.data.ai.advisor.AdvisorToolbox
import com.financetracker.app.data.ai.advisor.CardStatus
import com.financetracker.app.data.ai.agent.AiAgent
import com.financetracker.app.data.prefs.CurrencySettings
import com.financetracker.app.data.prefs.LoansAndGoals
import com.financetracker.app.data.repository.FinanceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class AiChatMessage(
    val id: Long,
    val isUser: Boolean,
    val text: String,
    val isError: Boolean = false,
    val cards: List<AdvisorCard> = emptyList(),
    /** Answered on the person's ChatGPT plan (shows "Using ChatGPT plan"). */
    val usedChatGptPlan: Boolean = false,
    /** The answer searched the web; [sources] are the pages it used. */
    val searchedWeb: Boolean = false,
    val sources: List<WebSource> = emptyList(),
    /** The name of the photo or PDF sent with this question. */
    val attachmentName: String? = null
)

/** A photo or PDF picked for the next message: loading, ready, or couldn't be opened. */
data class PendingAttachment(val name: String?, val file: ChatAttachment? = null, val error: String? = null)

data class AskAiUiState(
    val messages: List<AiChatMessage> = emptyList(),
    val isSending: Boolean = false,
    val isConfigured: Boolean = AiService.isConfigured,
    /** The open chat's name (its first question until renamed). */
    val title: String = "New chat",
    /** ChatGPT is the AI tried first, so the model picker applies. */
    val chatGptFirst: Boolean = false,
    /** The chosen ChatGPT model's name, or null for Auto. */
    val chatGptModel: String? = null,
    /** The photo or PDF that goes with the next message. */
    val attachment: PendingAttachment? = null
)

/** How many earlier messages go along with each question (the advisor looks figures up again
 * with its functions, so older turns add little). */
private const val HISTORY_MESSAGES = 16

class AskAiViewModel(private val repository: FinanceRepository) : ViewModel() {

    private val _messages = MutableStateFlow<List<AiChatMessage>>(emptyList())
    private val _isSending = MutableStateFlow(false)
    private val _attachment = MutableStateFlow<PendingAttachment?>(null)
    private var nextId = 1L

    // The open chat, saved on this phone after every change (see SavedChats).
    private var chatId = UUID.randomUUID().toString()
    private var chatCreatedAt = System.currentTimeMillis()
    private val _title = MutableStateFlow<String?>(null)
    private var renamed = false
    private var pinned = false

    val uiState: StateFlow<AskAiUiState> = combine(
        combine(_messages, _isSending, _title, ::Triple), AiSettings.state, AssistantPrefs.chatGptModel, _attachment
    ) { (messages, sending, title), settings, model, attachment ->
        AskAiUiState(
            messages = messages,
            isSending = sending,
            title = title ?: "New chat",
            chatGptFirst = settings.chain.firstOrNull() == AiProvider.CHATGPT,
            chatGptModel = model,
            attachment = attachment
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AskAiUiState())

    /** Past chats, pinned first. */
    val chats: StateFlow<List<SavedChat>> = SavedChats.chats

    private val _models = MutableStateFlow<List<ChatGptModel>?>(null)
    /** The models the ChatGPT plan offers (null until loaded). */
    val models: StateFlow<List<ChatGptModel>?> = _models.asStateFlow()
    private val _modelsError = MutableStateFlow<String?>(null)
    val modelsError: StateFlow<String?> = _modelsError.asStateFlow()

    fun loadModels() {
        if (_models.value != null) return
        _modelsError.value = null
        viewModelScope.launch {
            runCatching { ChatGptService.availableModels() }
                .onSuccess { _models.value = it }
                .onFailure { _modelsError.value = it.message ?: "Couldn't load the models." }
        }
    }

    fun selectModel(slug: String?) = AssistantPrefs.setChatGptModel(slug)

    fun currentChatId(): String = chatId

    /** Opens the photo or PDF the person picked, to send with their next message. */
    fun attach(context: Context, uri: Uri) {
        _attachment.value = PendingAttachment(name = null)
        viewModelScope.launch {
            runCatching { DocumentAi.loadAttachment(context.applicationContext, uri) }
                .onSuccess { file -> if (_attachment.value != null) _attachment.value = PendingAttachment(file.name, file) }
                .onFailure { e ->
                    if (_attachment.value != null) {
                        _attachment.value = PendingAttachment(null, error = e.message ?: "Couldn't open that file.")
                    }
                }
        }
    }

    fun removeAttachment() {
        _attachment.value = null
    }

    /** Starts an empty chat; the current one stays in Chats. */
    fun newChat() {
        if (_isSending.value) return
        chatId = UUID.randomUUID().toString()
        chatCreatedAt = System.currentTimeMillis()
        _title.value = null
        renamed = false
        pinned = false
        _messages.value = emptyList()
    }

    /** Reopens a saved chat. Its changes can't be applied again, so their cards show what happened. */
    fun openChat(id: String) {
        if (_isSending.value) return
        val chat = SavedChats.get(id) ?: return
        viewModelScope.launch {
            val byId = repository.observeTransactions().first().associateBy { it.id }
            chatId = chat.id
            chatCreatedAt = chat.createdAt
            _title.value = chat.title
            renamed = chat.renamed
            pinned = chat.pinned
            var cardId = 1
            _messages.value = chat.messages.map { m ->
                AiChatMessage(
                    id = nextId++, isUser = m.isUser, text = m.text, isError = m.isError, usedChatGptPlan = m.usedChatGptPlan,
                    searchedWeb = m.searchedWeb, sources = m.sources, attachmentName = m.attachmentName,
                    cards = m.cards.map { c ->
                        val status = runCatching { CardStatus.valueOf(c.status) }.getOrDefault(CardStatus.PENDING)
                        AdvisorCard(
                            id = cardId++, title = c.title, lines = c.lines,
                            transactions = c.transactionIds.mapNotNull { byId[it] },
                            status = status,
                            result = c.result ?: if (!c.hadPlan) null else when (status) {
                                CardStatus.APPLIED -> "Applied"
                                CardStatus.DISMISSED -> "Dismissed"
                                CardStatus.UNDONE -> "Applied, then undone"
                                CardStatus.PENDING -> "Not applied. Ask again to prepare it once more."
                            }
                        )
                    }
                )
            }
        }
    }

    fun renameChat(id: String, title: String) {
        SavedChats.rename(id, title)
        if (id == chatId) {
            _title.value = SavedChats.get(id)?.title ?: _title.value
            renamed = true
        }
    }

    fun setPinned(id: String, pinned: Boolean) {
        SavedChats.setPinned(id, pinned)
        if (id == chatId) this.pinned = pinned
    }

    fun deleteChat(id: String) {
        SavedChats.delete(id)
        if (id == chatId) newChat()
    }

    private fun persist() {
        val messages = _messages.value
        if (messages.isEmpty()) return
        if (!renamed && _title.value == null) {
            messages.firstOrNull { it.isUser }?.let { _title.value = SavedChats.titleFrom(it.text) }
        }
        SavedChats.save(
            SavedChat(
                id = chatId,
                title = _title.value ?: "Chat",
                createdAt = chatCreatedAt,
                updatedAt = System.currentTimeMillis(),
                pinned = pinned,
                renamed = renamed,
                messages = messages.map { m ->
                    SavedMessage(
                        isUser = m.isUser, text = m.text, isError = m.isError, usedChatGptPlan = m.usedChatGptPlan,
                        searchedWeb = m.searchedWeb, sources = m.sources, attachmentName = m.attachmentName,
                        cards = m.cards.map { c ->
                            SavedCard(c.title, c.lines, c.status.name, c.result, c.transactions.map { it.id }, hadPlan = c.plan != null || c.result != null)
                        }
                    )
                }
            )
        )
    }

    /** Whether a message can be sent now: not while answering or while a picked file is still
     * opening, and only with some text or a ready file. */
    fun canSend(text: String): Boolean {
        val attachment = _attachment.value
        if (_isSending.value || (attachment != null && attachment.file == null && attachment.error == null)) return false
        return text.isNotBlank() || attachment?.file != null
    }

    fun sendMessage(text: String) {
        if (!canSend(text)) return
        val file = _attachment.value?.file
        _attachment.value = null
        val trimmed = text.trim().ifBlank { "What's in this file?" }

        val history = historyTurns()
        _messages.update { it + AiChatMessage(nextId++, isUser = true, text = trimmed, attachmentName = file?.name) }
        _isSending.value = true
        persist()

        viewModelScope.launch {
            val snapshot = AdvisorSnapshot.load(repository)
            val toolbox = AdvisorToolbox(snapshot)
            AiAgent.run(
                systemPrompt = systemPrompt(snapshot),
                history = history,
                userMessage = trimmed,
                tools = toolbox.tools,
                attachment = file,
                onAttemptStart = toolbox::reset,
                execute = toolbox::execute
            ).onSuccess { run ->
                val cards = toolbox.cards
                val reply = run.text.ifBlank {
                    if (cards.isNotEmpty()) "Here's what I prepared — review it below." else
                        "I couldn't put together an answer for that. Try rephrasing it."
                }
                _messages.update {
                    it + AiChatMessage(
                        nextId++, isUser = false, text = reply, cards = cards,
                        usedChatGptPlan = run.provider == AiProvider.CHATGPT,
                        searchedWeb = run.searchedWeb, sources = run.sources
                    )
                }
            }.onFailure { error ->
                _messages.update {
                    it + AiChatMessage(nextId++, isUser = false, text = error.message ?: "Something went wrong.", isError = true)
                }
            }
            _isSending.value = false
            persist()
        }
    }

    /** Applies a prepared change (Apply on its card). */
    fun apply(messageId: Long, cardId: Int) {
        val card = card(messageId, cardId)?.takeIf { it.status == CardStatus.PENDING } ?: return
        val plan = card.plan ?: return
        viewModelScope.launch {
            runCatching { AdvisorActions.apply(repository, plan) }
                .onSuccess { (result, undo) ->
                    updateCard(messageId, cardId) { it.copy(status = CardStatus.APPLIED, undo = undo, result = result) }
                }
                .onFailure { e -> updateCard(messageId, cardId) { it.copy(result = "Couldn't apply: ${e.message}") } }
        }
    }

    fun undo(messageId: Long, cardId: Int) {
        val card = card(messageId, cardId)?.takeIf { it.status == CardStatus.APPLIED } ?: return
        val undo = card.undo ?: return
        viewModelScope.launch {
            runCatching { AdvisorActions.undo(repository, undo) }
                .onSuccess { result -> updateCard(messageId, cardId) { it.copy(status = CardStatus.UNDONE, undo = null, result = result) } }
                .onFailure { e -> updateCard(messageId, cardId) { it.copy(result = "Couldn't undo: ${e.message}") } }
        }
    }

    fun dismiss(messageId: Long, cardId: Int) {
        updateCard(messageId, cardId) { if (it.status == CardStatus.PENDING) it.copy(status = CardStatus.DISMISSED) else it }
    }

    private fun card(messageId: Long, cardId: Int) =
        _messages.value.firstOrNull { it.id == messageId }?.cards?.firstOrNull { it.id == cardId }

    private fun updateCard(messageId: Long, cardId: Int, change: (AdvisorCard) -> AdvisorCard) {
        _messages.update { list ->
            list.map { message ->
                if (message.id != messageId) message else message.copy(cards = message.cards.map { if (it.id == cardId) change(it) else it })
            }
        }
        persist()
    }

    /** Earlier messages, with a note of each card and what the user did with it, so the advisor
     * knows what's already been prepared or applied. */
    private fun historyTurns(): List<ChatTurn> = _messages.value
        .filter { !it.isError }
        .takeLast(HISTORY_MESSAGES)
        .dropWhile { !it.isUser }
        .map { message ->
            val notes = message.cards.joinToString("") { card ->
                val status = when {
                    card.plan == null -> "shown"
                    card.status == CardStatus.PENDING -> "not applied yet"
                    card.status == CardStatus.APPLIED -> "applied by the user"
                    card.status == CardStatus.UNDONE -> "applied, then undone by the user"
                    else -> "dismissed by the user"
                }
                "\n[Card: ${card.title} — $status]"
            }
            val file = message.attachmentName?.let { "\n[Attached file: $it — it was only sent with this message]" }.orEmpty()
            ChatTurn(message.isUser, message.text + file + notes)
        }

    private fun systemPrompt(snapshot: AdvisorSnapshot): String {
        val today = SimpleDateFormat("EEEE d MMMM yyyy", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(snapshot.now)
        val currency = CurrencySettings.currencyCode.value
        return """
            You are the personal finance advisor inside the user's own finance-tracking app. You know personal finance, budgeting, saving, investing basics, loans and mortgages, and the economy, and you can look at and change the user's data in the app through your functions.

            Today is $today. Amounts are in $currency.
            Accounts: ${snapshot.accounts.joinToString { it.name }.ifBlank { "none yet" }}.
            Saved loans: ${LoansAndGoals.loans.value.size}, saved savings goals: ${LoansAndGoals.goals.value.size}.
            The user is most likely in Denmark (Danish banks and loan documents), so use Danish context where it helps — e.g. realkredit mortgages with a contribution rate (bidragssats), fixed-rate vs. adjustable-rate (F-kort, rentetilpasning) loans, interest-only periods (afdragsfrihed), refinancing (omlægning), the interest tax deduction (rentefradrag), aktiesparekonto, pension savings. When a rule or rate matters for the answer and you aren't sure it's current, say so.

            How to work:
            - Get every figure from your functions. Never guess numbers or do loan/savings maths in your head: use loan_calculator and savings_goal_calculator.
            - Use as many function calls as needed before answering (e.g. get_overview, then get_spending and get_recurring_payments for a savings review).
            - To change anything (categories, budgets, fixed costs, loans, goals, alerts, the weekly summary), call the matching function. When the user asks to be told or warned about something ("tell me if takeaway goes over 1,000 kr"), prepare an alert with create_alert; the app checks it itself, so it costs no AI. It only prepares a card the user reviews and applies with the Apply button — so never say a change is done; say what you've prepared and that they can tap Apply.
            - Before preparing a change, check the data first (e.g. find_transactions) so the card is right. If a request is unclear — which account, which period, which transactions — ask one short question instead.
            - Advice should be specific: name the categories, merchants and amounts, say how much it would save per month/year, and put the biggest wins first. Point out risks (budgets on track to be exceeded, price increases, rising rates, little buffer).
            - You're not a licensed financial adviser. For big decisions (refinancing, investing, pension) give your real analysis, then briefly suggest confirming with their bank or an independent adviser.
            - Always reply in English, even when the question, transaction texts or documents are in Danish. Keep Danish names and terms as they are (merchants, accounts, realkredit, etc.), adding a short English explanation where it helps.
            - Keep it short and easy to read: short paragraphs or "- " bullet lists, **bold** for key figures. Prefer bullet lists to tables, and never use HTML (no <br>).

            Attached files: the user can attach a photo or PDF (e.g. a phone, internet or electricity bill, a contract, an offer). Read it carefully and say what it covers — e.g. which services, how many phone lines, internet speed, TV or streaming, each price, discounts, binding period. The file is only sent with that one message, so put the key facts in your answer. If some facts would help in later chats (provider, what the bill includes, number of lines), ask whether you should remember them.

            ${memoryPrompt()}
        """.trimIndent()
    }

    /** The memories, and how to handle "remember…", for the system prompt. */
    private fun memoryPrompt(): String {
        val memories = AssistantMemory.memories.value
        val list = if (memories.isEmpty()) {
            "Nothing saved yet."
        } else {
            memories.joinToString("\n") { "- (id ${it.id}) ${it.text}" }
        }
        return """
            Memory: the user can ask you to remember things across chats. What they asked you to remember so far:
            $list
            - Use these facts when they matter for the answer, without repeating them all the time.
            - When the user asks you to remember something, in any words ("remember…", "keep in mind…", "note that…", "husk…"), call remember, then confirm in one line what you saved. A question like "do you remember what I spent?" is not a request to save.
            - Never save on your own initiative. If something seems worth remembering, ask first ("Should I remember that you have 3 phone lines?").
            - When asked what you remember, show a numbered list (1, 2, 3 — not the ids). "Change"/"forget" requests use update_memory or forget_memories (map the user's numbers to the ids above); confirm exactly what changed. If a new fact contradicts a saved one, offer to update it.
        """.trimIndent().prependIndent("            ").trimStart()
    }
}
