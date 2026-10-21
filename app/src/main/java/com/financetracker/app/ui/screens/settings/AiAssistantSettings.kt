package com.financetracker.app.ui.screens.settings

import androidx.compose.material3.Switch
import com.financetracker.app.data.ai.chat.AssistantPrefs
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.financetracker.app.data.ai.AiProvider
import com.financetracker.app.data.ai.AiSettings
import com.financetracker.app.data.ai.AiSettingsState
import com.financetracker.app.data.ai.chatgpt.ChatGptAuth
import com.financetracker.app.ui.components.SetupGuide
import com.financetracker.app.ui.components.SetupGuideDialog
import com.financetracker.app.ui.components.SetupGuides
import com.financetracker.app.ui.theme.IncomeGreen

private val AI_GUIDES = listOf(SetupGuides.GEMINI, SetupGuides.GROQ, SetupGuides.CLAUDE)

/** ChatGPT has no key to get (it's a sign-in), so its "how to" falls back to Gemini's. */
private fun guideFor(provider: AiProvider): SetupGuide = when (provider) {
    AiProvider.GEMINI, AiProvider.CHATGPT -> SetupGuides.GEMINI
    AiProvider.GROQ -> SetupGuides.GROQ
    AiProvider.CLAUDE -> SetupGuides.CLAUDE
}

private fun chipLabel(provider: AiProvider): String = when {
    provider == AiProvider.CHATGPT -> "ChatGPT (your plan)"
    provider.free -> "${provider.label} (free)"
    else -> provider.label
}

/** Settings > General > AI assistant: which AI goes first, the fallback order, and each AI's key. */
@Composable
fun AiAssistantSettings(modifier: Modifier = Modifier) {
    val state by AiSettings.state.collectAsState()
    var editing by remember { mutableStateOf<AiProvider?>(null) }
    var guide by remember { mutableStateOf<SetupGuide?>(null) }

    Column(modifier = modifier) {
        Text("AI assistant", style = MaterialTheme.typography.titleMedium)
        Text(
            "Used for categorizing transactions, dashboard insights and Ask AI. Choose which AI " +
                "to use first — if it fails or its free limit is used up, the next one takes over.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp, top = 4.dp)
        )
        Text("Use first", style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AiProvider.entries.forEach { provider ->
                FilterChip(
                    selected = state.primary == provider,
                    onClick = { AiSettings.setPrimary(provider) },
                    label = { Text(chipLabel(provider)) }
                )
            }
        }
        Text(
            if (state.chain.isEmpty()) {
                "No AI is set up yet — add a key or connect ChatGPT below."
            } else {
                "Order: " + state.chain.joinToString(" → ") { it.label } +
                    if (!state.hasKey(state.primary)) " (${state.primary.label} is skipped until it has a key)" else ""
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (state.chain.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                AiProvider.entries.forEachIndexed { index, provider ->
                    if (provider.usesKey) {
                        KeyRow(provider = provider, state = state, onEdit = { editing = provider })
                    } else {
                        ChatGptRow(state = state)
                    }
                    if (index < AiProvider.entries.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
        TextButton(onClick = { guide = guideFor(state.primary) }) {
            Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = null)
            Text("How to get a key", modifier = Modifier.padding(start = 6.dp))
        }
        Text(
            "Gemini doesn't use your data for training in Europe; Groq doesn't keep it. ChatGPT uses " +
                "your own ChatGPT plan (Go, Plus or Pro) instead of a key. Keys you add and the ChatGPT " +
                "sign-in are stored encrypted on this phone only.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        AssistantChoices(chatGptConnected = state.chatGptEmail != null)
    }

    editing?.let { provider ->
        KeyDialog(
            provider = provider,
            hasOwnKey = state.hasOwnKey(provider),
            onDismiss = { editing = null },
            onShowGuide = { guide = guideFor(provider) }
        )
    }
    guide?.let { SetupGuideDialog(guides = AI_GUIDES, initial = it, onDismiss = { guide = null }) }
}

@Composable
private fun KeyRow(provider: AiProvider, state: AiSettingsState, onEdit: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(provider.label, style = MaterialTheme.typography.bodyLarge)
            StatusLine(ok = state.hasKey(provider), text = keyStatus(provider, state))
        }
        TextButton(onClick = onEdit) { Text(if (state.hasOwnKey(provider)) "Change" else "Add key") }
    }
}

@Composable
private fun KeyDialog(provider: AiProvider, hasOwnKey: Boolean, onDismiss: () -> Unit, onShowGuide: () -> Unit) {
    var key by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${provider.label} key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text(if (hasOwnKey) "New key" else "Paste your key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(onClick = onShowGuide) {
                    Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = null)
                    Text("How to get a ${provider.label} key", modifier = Modifier.padding(start = 6.dp))
                }
                if (hasOwnKey) {
                    TextButton(onClick = {
                        AiSettings.setOwnKey(provider, null)
                        onDismiss()
                    }) { Text("Remove my key") }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    AiSettings.setOwnKey(provider, key)
                    onDismiss()
                },
                enabled = key.isNotBlank()
            ) { Text("Save key") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** The line under each AI's name: whether a key is set, and which one. */
internal fun keyStatus(provider: AiProvider, state: AiSettingsState): String = when {
    state.hasOwnKey(provider) -> "✓ Key set — your own key"
    state.hasBuiltInKey(provider) -> "✓ Key set — built into the app"
    provider.free -> "✗ No key set — skipped until you add one (free to create)"
    else -> "✗ No key set — skipped until you add one (optional, paid)"
}

@Composable
private fun StatusLine(ok: Boolean, text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (ok) IncomeGreen else MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** ChatGPT's row: connected with a sign-in (the person's own ChatGPT plan) instead of a key. */
@Composable
private fun ChatGptRow(state: AiSettingsState) {
    val context = LocalContext.current
    val auth by ChatGptAuth.status.collectAsState()
    val signIn by ChatGptAuth.signIn.collectAsState()
    var confirmDisconnect by remember { mutableStateOf(false) }
    val openUsage = {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ChatGptAuth.USAGE_URL)))
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
        Text(AiProvider.CHATGPT.label, style = MaterialTheme.typography.bodyLarge)
        val email = state.chatGptEmail
        when {
            signIn is ChatGptAuth.SignIn.Waiting -> StatusLine(
                ok = false,
                text = "Waiting for you to sign in to ChatGPT in the browser…"
            )
            signIn is ChatGptAuth.SignIn.Finishing -> StatusLine(
                ok = false,
                text = "Finishing the ChatGPT sign-in…"
            )
            email != null -> StatusLine(ok = true, text = "✓ Connected as $email — uses your ChatGPT plan")
            else -> StatusLine(
                ok = false,
                text = auth.problem?.let { "✗ Not connected — $it" }
                    ?: "✗ Not connected — uses your ChatGPT plan instead of a key"
            )
        }
        Row(
            modifier = Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when {
                signIn is ChatGptAuth.SignIn.Waiting || signIn is ChatGptAuth.SignIn.Finishing ->
                    TextButton(onClick = { ChatGptAuth.cancelSignIn() }) { Text("Cancel") }
                email != null -> {
                    TextButton(onClick = { openUsage() }) { Text("Manage usage") }
                    TextButton(onClick = { confirmDisconnect = true }) { Text("Disconnect") }
                }
                else -> OutlinedButton(onClick = { ChatGptAuth.startSignIn(context) }) { Text("Continue with ChatGPT") }
            }
        }
    }

    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text("Disconnect ChatGPT?") },
            text = { Text("The app stops using your ChatGPT plan. You can connect again at any time.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDisconnect = false
                    val app = context.applicationContext
                    ChatGptAuth.disconnect { confirmed ->
                        if (!confirmed) {
                            Handler(Looper.getMainLooper()).post {
                                Toast.makeText(
                                    app,
                                    "Disconnected on this phone. OpenAI couldn't be told — you can also remove the app in ChatGPT Settings > Security and login.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                }) { Text("Disconnect") }
            },
            dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text("Cancel") } }
        )
    }

    when (val result = signIn) {
        is ChatGptAuth.SignIn.Done -> if (result.firstTime) {
            AlertDialog(
                onDismissRequest = { ChatGptAuth.clearSignInMessage() },
                title = { Text("You're using your ChatGPT plan") },
                text = {
                    Text(
                        "When ChatGPT answers in this app, it counts toward the usage included in your ChatGPT " +
                            "plan. You can see it and set a limit for this app in ChatGPT Settings > Usage."
                    )
                },
                confirmButton = { TextButton(onClick = { ChatGptAuth.clearSignInMessage() }) { Text("Got it") } },
                dismissButton = {
                    TextButton(onClick = {
                        openUsage()
                        ChatGptAuth.clearSignInMessage()
                    }) { Text("Manage usage") }
                }
            )
        } else {
            LaunchedEffect(result) {
                Toast.makeText(context, "ChatGPT connected", Toast.LENGTH_SHORT).show()
                ChatGptAuth.clearSignInMessage()
            }
        }
        is ChatGptAuth.SignIn.Failed -> AlertDialog(
            onDismissRequest = { ChatGptAuth.clearSignInMessage() },
            title = { Text("ChatGPT not connected") },
            text = { Text(result.message) },
            confirmButton = { TextButton(onClick = { ChatGptAuth.clearSignInMessage() }) { Text("OK") } }
        )
        else -> Unit
    }
}

/** The advisor's ChatGPT model and voice switches. */
@Composable
private fun AssistantChoices(chatGptConnected: Boolean) {
    val model by AssistantPrefs.chatGptModel.collectAsState()
    val readAloud by AssistantPrefs.readAloud.collectAsState()
    val handsFree by AssistantPrefs.handsFree.collectAsState()
    if (chatGptConnected) {
        Text("ChatGPT model", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                (model ?: "Auto (recommended)") + " · change it from the model button in the AI advisor chat",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (model != null) TextButton(onClick = { AssistantPrefs.setChatGptModel(null) }) { Text("Use Auto") }
        }
    }
    Text("Voice", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 16.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Read every answer aloud in the chat", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = readAloud, onCheckedChange = AssistantPrefs::setReadAloud)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Hands-free voice mode (listen again after each answer)", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = handsFree, onCheckedChange = AssistantPrefs::setHandsFree)
    }
    Text(
        "Voice uses your phone's own speech recognition and reading voice, so it costs nothing extra.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
