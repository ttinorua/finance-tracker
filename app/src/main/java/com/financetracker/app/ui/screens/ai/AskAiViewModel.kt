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
    val usedChatGptPlan: Boolean = false
)

data class AskAiUiState(
    val messages: List<AiChatMessage> = emptyList(),
    val isSending: Boolean = false,
    val isConfigured: Boolean = AiService.isConfigured,
    /** The open chat's name (its first question until renamed). */
    val title: String = "New chat",
    /** ChatGPT is the AI tried first, so the model picker applies. */
    val chatGptFirst: Boolean = false,
    /** The chosen ChatGPT model's name, or null for Auto. */
    val chatGptModel: String? = null
)

/** How many earlier messages go along with each question (the advisor looks figures up again
 * with its functions, so older turns add little). */
private const val HISTORY_MESSAGES = 16

class AskAiViewModel(private val repository: FinanceRepository) : ViewModel() {

    private val _messages = MutableStateFlow<List<AiChatMessage>>(emptyList())
    private val _isSending = MutableStateFlow(false)
    private var nextId = 1L

    // The open chat, saved on this phone after every change (see SavedChats).
    private var chatId = UUID.randomUUID().toString()
    private var chatCreatedAt = System.currentTimeMillis()
    private val _title = MutableStateFlow<String?>(null)
    private var renamed = false
    private var pinned = false

    val uiState: StateFlow<AskAiUiState> = combine(
        _messages, _isSending, _title, AiSettings.state, AssistantPrefs.chatGptModel
    ) { messages, sending, title, settings, model ->
        AskAiUiState(
            messages = messages,
            isSending = sending,
            title = title ?: "New chat",
            chatGptFirst = settings.chain.firstOrNull() == AiProvider.CHATGPT,
            chatGptModel = model
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
                        cards = m.cards.map { c ->
                            SavedCard(c.title, c.lines, c.status.name, c.result, c.transactions.map { it.id }, hadPlan = c.plan != null || c.result != null)
                        }
                    )
                }
            )
        )
    }

    fun sendMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank() || _isSending.value) return

        val history = historyTurns()
        _messages.update { it + AiChatMessage(nextId++, isUser = true, text = trimmed) }
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
                        usedChatGptPlan = run.provider == AiProvider.CHATGPT
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
            ChatTurn(message.isUser, message.text + notes)
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
            - To change anything (categories, budgets, fixed costs, loans, goals), call the matching function. It only prepares a card the user reviews and applies with the Apply button — so never say a change is done; say what you've prepared and that they can tap Apply.
            - Before preparing a change, check the data first (e.g. find_transactions) so the card is right. If a request is unclear — which account, which period, which transactions — ask one short question instead.
            - Advice should be specific: name the categories, merchants and amounts, say how much it would save per month/year, and put the biggest wins first. Point out risks (budgets on track to be exceeded, price increases, rising rates, little buffer).
            - You're not a licensed financial adviser. For big decisions (refinancing, investing, pension) give your real analysis, then briefly suggest confirming with their bank or an independent adviser.
            - Always reply in English, even when the question, transaction texts or documents are in Danish. Keep Danish names and terms as they are (merchants, accounts, realkredit, etc.), adding a short English explanation where it helps.
            - Keep it short and easy to read: short paragraphs or "- " bullet lists, **bold** for key figures. Prefer bullet lists to tables, and never use HTML (no <br>).
        """.trimIndent()
    }
}
