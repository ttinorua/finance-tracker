package com.financetracker.app.ui.screens.ai

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.financetracker.app.data.ai.chat.AssistantPrefs
import com.financetracker.app.ui.components.AiOrb
import com.financetracker.app.ui.components.GlassCard
import com.financetracker.app.util.speakableText
import java.util.Locale

/**
 * Speech in the AI advisor, all done by the phone itself (Android's own speech recognition and
 * text-to-speech), so the AI only ever sees and writes text and voice costs nothing extra.
 */

/** Returns a function that opens the phone's "speak now" prompt and hands back what was said
 * (null when nothing was recognized or the phone has no speech recognition). */
@Composable
fun rememberSpeechInput(onResult: (String?) -> Unit): () -> Unit {
    val latest by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.takeIf { it.isNotBlank() }
        } else null
        latest(text)
    }
    return {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask your finances")
        try {
            launcher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            latest(null)
        }
    }
}

/** Reads text aloud with the phone's text-to-speech. */
class Speaker(context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private var queued: Pair<String, () -> Unit>? = null
    private var onDone: (() -> Unit)? = null

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        queued?.let { (text, done) -> queued = null; if (ready) speak(text, done) else done() }
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = finish()
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finish()
        })
    }

    private fun finish() {
        val callback = onDone
        onDone = null
        main.post { callback?.invoke() }
    }

    /** Speaks [text] (Markdown removed), then calls [done]. */
    fun speak(text: String, done: () -> Unit = {}) {
        if (!ready) {
            queued = text to done
            return
        }
        onDone = done
        val language = Locale.getDefault()
        if (tts.isLanguageAvailable(language) >= TextToSpeech.LANG_AVAILABLE) tts.language = language
        tts.speak(speakableText(text), TextToSpeech.QUEUE_FLUSH, null, "advisor-${System.nanoTime()}")
    }

    fun stop() {
        onDone = null
        queued = null
        tts.stop()
    }

    fun shutdown() {
        stop()
        tts.shutdown()
    }
}

@Composable
fun rememberSpeaker(): Speaker {
    val context = LocalContext.current
    val speaker = remember { Speaker(context) }
    DisposableEffect(speaker) { onDispose { speaker.shutdown() } }
    return speaker
}

/**
 * Talk to the advisor: the orb, what you said, and the answer while it's read aloud. With
 * hands-free on it listens again after each answer.
 */
@Composable
fun VoiceMode(
    lastQuestion: String?,
    lastAnswer: String?,
    isThinking: Boolean,
    speaker: Speaker,
    onAsk: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val handsFree by AssistantPrefs.handsFree.collectAsState()
    var speaking by remember { mutableStateOf(false) }
    var spokenAnswer by remember { mutableStateOf(lastAnswer) }
    var note by remember { mutableStateOf<String?>(null) }
    val listen = rememberSpeechInput { said ->
        if (said != null) {
            note = null
            onAsk(said)
        } else {
            note = "Didn't catch that. Tap the microphone to try again."
        }
    }

    // Listen straight away when voice mode opens.
    LaunchedEffect(Unit) { listen() }

    // Read each new answer aloud, then listen again when hands-free is on.
    LaunchedEffect(lastAnswer, isThinking) {
        if (!isThinking && lastAnswer != null && lastAnswer != spokenAnswer) {
            spokenAnswer = lastAnswer
            speaking = true
            speaker.speak(lastAnswer) {
                speaking = false
                if (AssistantPrefs.handsFree.value) listen()
            }
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        lastQuestion?.let {
            Column {
                Text("You said", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(it, style = MaterialTheme.typography.titleMedium)
            }
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            AiOrb(140.dp, contentDescription = if (speaking) "AI is speaking" else "AI advisor")
        }
        val status = when {
            isThinking -> "Thinking…"
            speaking -> "Speaking…"
            else -> note
        }
        if (status != null || lastAnswer != null) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                    status?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                    if (!isThinking) lastAnswer?.let { Text(speakableText(it), style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedIconButton(onClick = { speaker.stop(); speaking = false }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.Stop, contentDescription = "Stop speaking")
            }
            FilledIconButton(onClick = { speaker.stop(); speaking = false; listen() }, enabled = !isThinking, modifier = Modifier.size(76.dp)) {
                Icon(Icons.Filled.Mic, contentDescription = "Tap to talk", modifier = Modifier.size(32.dp))
            }
            OutlinedIconButton(onClick = { speaker.stop(); onClose() }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.Chat, contentDescription = "Show as text chat")
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 16.dp)) {
            Text("Hands-free: listen again after each answer", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Switch(checked = handsFree, onCheckedChange = AssistantPrefs::setHandsFree)
        }
    }
}
