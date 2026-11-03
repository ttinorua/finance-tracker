package com.financetracker.app.ui.screens.ai

import com.financetracker.app.ui.components.ScreenHeader
import com.financetracker.app.ui.components.HeaderButton
import com.financetracker.app.data.ai.chat.AssistantPrefs
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Language
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.financetracker.app.data.ai.WebSource
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.border
import androidx.activity.compose.BackHandler
import com.financetracker.app.data.ai.chatgpt.ChatGptAuth
import androidx.compose.ui.platform.LocalContext
import android.net.Uri
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.financetracker.app.ui.theme.LocalAppPalette
import com.financetracker.app.ui.theme.gradientBrush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.financetracker.app.data.ai.MerchantLogoLookup
import com.financetracker.app.data.prefs.MerchantOverrides
import com.financetracker.app.data.ai.advisor.AdvisorCard
import com.financetracker.app.data.ai.advisor.CardStatus
import com.financetracker.app.data.db.entity.TransactionType
import com.financetracker.app.data.db.entity.TransactionWithDetails
import com.financetracker.app.data.prefs.CurrencySettings
import com.financetracker.app.ui.components.CompanyLogo
import com.financetracker.app.ui.components.EmptyState
import com.financetracker.app.util.Formatters

private val SUGGESTIONS = listOf(
    "Categorize my uncategorized transactions",
    "Where can I save money?",
    "How am I doing on my budgets this month?",
    "Review my subscriptions",
    "Suggest a monthly budget for me",
    "Should I pay extra on my loan or save instead?"
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AskAiScreen(viewModel: AskAiViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    val chats by viewModel.chats.collectAsState()
    val readAloud by AssistantPrefs.readAloud.collectAsState()
    // Saveable, so a half-written message survives turning the phone.
    var input by rememberSaveable { mutableStateOf("") }
    var showChats by rememberSaveable { mutableStateOf(false) }
    var showVoice by rememberSaveable { mutableStateOf(false) }
    var showModels by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val speaker = rememberSpeaker()
    val context = LocalContext.current
    // A bill or other document for the next message: a photo or a PDF.
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.attach(context, uri)
    }

    BackHandler(enabled = showChats || showVoice) {
        if (showVoice) speaker.stop()
        showChats = false
        showVoice = false
    }

    // "Read every answer aloud" (Settings > AI advisor): speak each new answer in the text chat.
    val lastAnswer = state.messages.lastOrNull()?.takeIf { !it.isUser && !it.isError }
    LaunchedEffect(lastAnswer?.id) {
        if (readAloud && !showVoice && lastAnswer != null && state.messages.size > 1) speaker.speak(lastAnswer.text)
    }

    // Opened from a "Needs your attention" card: ask its question straight away.
    LaunchedEffect(Unit) {
        AdvisorLaunch.take()?.let(viewModel::sendMessage)
    }

    val lastIndex = state.messages.size - 1 + if (state.isSending) 1 else 0
    LaunchedEffect(state.messages.size, state.isSending) {
        if (lastIndex >= 0) listState.animateScrollToItem(lastIndex)
    }
    // The keyboard takes the bottom of the screen: keep the latest message in view above it.
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible) {
        if (imeVisible && lastIndex >= 0) listState.scrollToItem(lastIndex)
    }

    Scaffold(
        topBar = {
            Column(modifier = Modifier.statusBarsPadding().padding(end = 16.dp)) {
                ScreenHeader(
                    smallLine = when {
                        showChats -> "AI advisor"
                        showVoice -> "AI advisor · voice"
                        else -> "AI advisor"
                    },
                    title = when {
                        showChats -> "Chats"
                        showVoice -> "Voice"
                        else -> state.title
                    },
                    onBack = {
                        when {
                            showVoice -> { speaker.stop(); showVoice = false }
                            showChats -> showChats = false
                            else -> onBack()
                        }
                    }
                ) {
                    if (!showChats && !showVoice) {
                        HeaderButton(Icons.Filled.History, "Saved chats") { showChats = true }
                        HeaderButton(Icons.Filled.Add, "New chat") { speaker.stop(); viewModel.newChat() }
                    }
                }
                if (!showChats && !showVoice && state.chatGptFirst) {
                    Row(
                        modifier = Modifier.padding(start = 16.dp, top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AssistChip(
                            onClick = { viewModel.loadModels(); showModels = true },
                            label = { Text("ChatGPT · " + (state.chatGptModel ?: "Auto")) },
                            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) }
                        )
                    }
                }
            }
        },
        bottomBar = {
            if (state.isConfigured && !showChats && !showVoice) {
                val palette = LocalAppPalette.current
                val attachment = state.attachment
                val fileOpening = attachment != null && attachment.file == null && attachment.error == null
                val canSend = !state.isSending && !fileOpening && (input.isNotBlank() || attachment?.file != null)
                Column(modifier = Modifier.background(palette.navBar.compositeOver(palette.base)).navigationBarsPadding().imePadding()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    if (attachment != null) {
                        AttachmentChip(attachment, onRemove = viewModel::removeAttachment)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        IconButton(
                            onClick = { pickFile.launch(arrayOf("image/*", "application/pdf")) },
                            enabled = !state.isSending,
                            modifier = Modifier.padding(bottom = 4.dp)
                        ) {
                            Icon(Icons.Filled.AttachFile, contentDescription = "Attach a photo or PDF")
                        }
                        // Grows to four lines, then scrolls inside, so the conversation stays visible.
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            placeholder = { Text("Ask, or tell me what to do…") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(22.dp),
                            minLines = 1,
                            maxLines = 4,
                            enabled = !state.isSending
                        )
                        Box(
                            modifier = Modifier
                                .padding(start = 8.dp, bottom = 4.dp)
                                .size(48.dp)
                                .clip(CircleShape)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                                .clickable(enabled = !state.isSending, role = Role.Button) { showVoice = true }
                                .semantics { contentDescription = "Talk" },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(22.dp))
                        }
                        Box(
                            modifier = Modifier
                                .padding(start = 8.dp, bottom = 4.dp)
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(palette.gradientBrush(), alpha = if (canSend) 1f else .4f)
                                .clickable(enabled = canSend, role = Role.Button) {
                                    viewModel.sendMessage(input)
                                    input = ""
                                }
                                .semantics { contentDescription = "Send" },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.ArrowUpward, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                        }
                    }
                }
            }
        }
    ) { padding ->
        when {
            showChats -> ChatsPanel(
                chats = chats,
                currentId = viewModel.currentChatId(),
                onNewChat = { viewModel.newChat(); showChats = false },
                onOpen = { viewModel.openChat(it); showChats = false },
                onRename = viewModel::renameChat,
                onPin = viewModel::setPinned,
                onDelete = viewModel::deleteChat,
                modifier = Modifier.padding(padding)
            )
            showVoice -> VoiceMode(
                lastQuestion = state.messages.lastOrNull { it.isUser }?.text,
                lastAnswer = state.messages.lastOrNull { !it.isUser }?.text,
                isThinking = state.isSending,
                speaker = speaker,
                onAsk = viewModel::sendMessage,
                onClose = { showVoice = false },
                modifier = Modifier.padding(padding)
            )
            !state.isConfigured -> EmptyState(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                message = "No AI is set up yet. Add a key in Settings > General > AI assistant."
            )
            state.messages.isEmpty() -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Your personal finance advisor. Ask about your spending, budgets, loans and savings — or ask it to " +
                        "do things like categorizing transactions or setting budgets. Changes are shown first, and only " +
                        "happen when you tap Apply.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SUGGESTIONS.forEach { suggestion ->
                    AssistChip(onClick = { viewModel.sendMessage(suggestion) }, label = { Text(suggestion) })
                }
            }
            else -> LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(state.messages, key = { it.id }) { message ->
                    ChatBubble(
                        message,
                        onApply = { viewModel.apply(message.id, it) },
                        onDismiss = { viewModel.dismiss(message.id, it) },
                        onUndo = { viewModel.undo(message.id, it) },
                        onReadAloud = { speaker.speak(message.text) }
                    )
                }
                if (state.isSending) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(
                                "Looking into it…",
                                modifier = Modifier.padding(start = 8.dp),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
    }

    if (showModels) {
        val models by viewModel.models.collectAsState()
        val modelsError by viewModel.modelsError.collectAsState()
        ModalBottomSheet(onDismissRequest = { showModels = false }) {
            Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Model", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Loaded from your ChatGPT plan. Bigger models use more of it. Your choice is kept for new chats too.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ModelOption("Auto (recommended)", "The model ChatGPT offers apps first", selected = state.chatGptModel == null) {
                    viewModel.selectModel(null); showModels = false
                }
                when {
                    modelsError != null -> Text(modelsError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    models == null -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    else -> models!!.forEach { model ->
                        ModelOption(model.name, model.slug, selected = state.chatGptModel == model.slug) {
                            viewModel.selectModel(model.slug); showModels = false
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelOption(name: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else CardDefaults.outlinedCardBorder()
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleSmall)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (selected) Icon(Icons.Filled.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun ChatBubble(
    message: AiChatMessage,
    onApply: (Int) -> Unit,
    onDismiss: (Int) -> Unit,
    onUndo: (Int) -> Unit,
    onReadAloud: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(if (message.isUser) 0.85f else 0.95f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (message.searchedWeb) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp)) {
                    Icon(
                        Icons.Filled.Language, contentDescription = null, modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Searched the web" + if (message.sources.isNotEmpty()) {
                            " · ${message.sources.size} ${if (message.sources.size == 1) "site" else "sites"}"
                        } else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
            }
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = when {
                        message.isError -> MaterialTheme.colorScheme.errorContainer
                        message.isUser -> MaterialTheme.colorScheme.primaryContainer
                        else -> MaterialTheme.colorScheme.surfaceContainerHighest
                    }
                ),
                shape = RoundedCornerShape(18.dp),
                border = if (message.isUser || message.isError) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                if (message.isUser) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        message.attachmentName?.let { name ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.AttachFile, contentDescription = null, modifier = Modifier.size(16.dp))
                                Text(
                                    name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(start = 4.dp)
                                )
                            }
                        }
                        Text(message.text, style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    Column(Modifier.padding(12.dp)) {
                        ChatReply(message.text)
                        if (message.sources.isNotEmpty()) SourceList(message.sources)
                    }
                }
            }
            message.cards.forEach { card ->
                AdvisorCardView(card, onApply = { onApply(card.id) }, onDismiss = { onDismiss(card.id) }, onUndo = { onUndo(card.id) })
            }
            if (!message.isUser && !message.isError) {
                TextButton(onClick = onReadAloud, contentPadding = PaddingValues(horizontal = 4.dp)) {
                    Icon(Icons.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("Read aloud", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 4.dp))
                }
            }
            if (message.usedChatGptPlan) ChatGptPlanNote()
        }
    }
}

@Composable
private fun AdvisorCardView(card: AdvisorCard, onApply: () -> Unit, onDismiss: () -> Unit, onUndo: () -> Unit) {
    var showAll by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        border = CardDefaults.outlinedCardBorder(),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(card.title, style = MaterialTheme.typography.titleSmall)
            card.lines.forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (card.transactions.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                val visible = if (showAll) card.transactions else card.transactions.take(8)
                visible.forEach { TransactionLine(it) }
                if (card.transactions.size > 8) {
                    TextButton(onClick = { showAll = !showAll }) {
                        Text(if (showAll) "Show less" else "Show all ${card.transactions.size}")
                    }
                }
            }
            card.result?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            if (card.plan != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    when (card.status) {
                        CardStatus.PENDING -> {
                            Button(onClick = onApply) { Text("Apply") }
                            OutlinedButton(onClick = onDismiss) { Text("Dismiss") }
                        }
                        CardStatus.APPLIED -> OutlinedButton(onClick = onUndo) { Text("Undo") }
                        CardStatus.UNDONE -> {}
                        CardStatus.DISMISSED -> Text(
                            "Dismissed",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TransactionLine(tx: TransactionWithDetails) {
    val currencyCode by CurrencySettings.currencyCode.collectAsState()
    val cachedDomains by MerchantLogoLookup.domains.collectAsState()
    LaunchedEffect(tx.note) { MerchantLogoLookup.request(tx.note) }
    // These lines had no icon before, so only a found logo is shown — never an empty gap.
    val cachedNames by MerchantLogoLookup.names.collectAsState()
    val overrides by MerchantOverrides.rules.collectAsState()
    val domain = MerchantLogoLookup.domainFor(tx.note, cachedDomains, overrides)
    val photo = MerchantOverrides.photoFor(tx.note, overrides)
    val name = MerchantLogoLookup.nameFor(tx.note, cachedNames, overrides)
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (domain != null || photo != null) {
            CompanyLogo(domain = domain, contentDescription = null, modifier = Modifier.padding(end = 8.dp).size(20.dp), photo = photo) {}
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(name ?: tx.note.ifBlank { "(no text)" }, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${Formatters.date(tx.date)} · ${tx.categoryName ?: "Uncategorized"} · ${tx.accountName}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            (if (tx.type == TransactionType.INCOME) "+" else "-") + Formatters.currency(tx.amount, currencyCode),
            style = MaterialTheme.typography.bodySmall,
            color = if (tx.type == TransactionType.INCOME) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

/** The photo or PDF waiting to go with the next message, above the text field. */
@Composable
private fun AttachmentChip(attachment: PendingAttachment, onRemove: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp)
    ) {
        when {
            attachment.error != null -> Text(
                attachment.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f)
            )
            attachment.file == null -> {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("Opening the file…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(start = 8.dp))
            }
            else -> {
                Icon(Icons.Filled.AttachFile, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    attachment.file.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 6.dp)
                )
            }
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Close, contentDescription = "Remove the attached file")
        }
    }
}

/** The web pages an answer used, numbered; tapping one opens it in the browser. */
@Composable
private fun SourceList(sources: List<WebSource>) {
    val context = LocalContext.current
    var showAll by remember { mutableStateOf(false) }
    Column(modifier = Modifier.padding(top = 10.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            "Sources", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
        )
        val visible = if (showAll) sources else sources.take(5)
        visible.forEachIndexed { index, source ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button) {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(source.url))) }
                    }
                    .padding(vertical = 8.dp)
            ) {
                Text(
                    "${index + 1}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp).clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = .14f)).padding(top = 3.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Text(
                    source.site + if (source.title.isNotBlank() && source.title != source.site) " · ${source.title}" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
        if (sources.size > 5) {
            TextButton(onClick = { showAll = !showAll }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                Text(if (showAll) "Show less" else "Show all ${sources.size}", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** OpenAI asks apps to say when an answer used the person's ChatGPT plan, with a way to manage it. */
@Composable
private fun ChatGptPlanNote() {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Using ChatGPT plan",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(onClick = {
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(ChatGptAuth.USAGE_URL))
                )
            }
        }) { Text("Manage usage", style = MaterialTheme.typography.labelSmall) }
    }
}
