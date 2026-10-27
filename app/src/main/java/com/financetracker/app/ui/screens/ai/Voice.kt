package com.financetracker.app.ui.screens.ai

import android.app.Activity
import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.compose.runtime.rememberCoroutineScope
import androidx.core.content.ContextCompat
import com.financetracker.app.data.ai.voice.AdvisorVoice
import com.financetracker.app.data.ai.voice.GeminiVoice
import com.financetracker.app.data.ai.voice.GroqTranscriber
import com.financetracker.app.util.VoiceAudio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
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
 * Speech in the AI advisor. Answers are read by a natural Gemini voice (picked in Settings > AI
 * advisor) at a calm pace, and questions are recorded in the app and written out by Groq's
 * Whisper. Whenever either isn't available (no key, no internet, free limit used up) the phone's
 * own speech recognition and text-to-speech take over, so voice always works.
 *
 * The voice never talks over you: listening always stops the voice first, the microphone only
 * opens once an answer has finished (hands-free), and an answer's sentences play one after
 * another on a single audio stream, never on top of each other.
 */

/** Voice input for the advisor: [start] listens and hands back what was said (null when nothing
 * was caught); while recording, tapping [finish] sends what's been said so far. */
class SpeechInput internal constructor(
    private val onStart: () -> Unit,
    private val onFinish: () -> Unit,
    listeningState: androidx.compose.runtime.State<Boolean>,
    transcribingState: androidx.compose.runtime.State<Boolean>
) {
    val listening by listeningState
    val transcribing by transcribingState
    fun start() = onStart()
    fun finish() = onFinish()
}

@Composable
fun rememberSpeechInput(onResult: (String?) -> Unit): SpeechInput {
    val latest by rememberUpdatedState(onResult)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listening = remember { mutableStateOf(false) }
    val transcribing = remember { mutableStateOf(false) }
    val recorder = remember { VoiceRecorder() }
    DisposableEffect(recorder) { onDispose { recorder.cancel() } }

    // The phone's own "speak now" prompt: the fallback.
    val phonePrompt = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.takeIf { it.isNotBlank() }
        } else null
        latest(text)
    }
    fun usePhone() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask your finances")
        try {
            phonePrompt.launch(intent)
        } catch (e: ActivityNotFoundException) {
            latest(null)
        }
    }
    fun record() {
        if (listening.value || transcribing.value) return
        listening.value = true
        scope.launch {
            val wav = recorder.record()
            listening.value = false
            if (wav == null) { latest(null); return@launch }
            transcribing.value = true
            val text = runCatching { GroqTranscriber.transcribe(wav) }.getOrNull()
            transcribing.value = false
            // Groq failed (internet, limit): ask again the phone's way rather than lose the question.
            if (text == null) usePhone() else latest(text.takeIf { it.isNotBlank() })
        }
    }
    val askMicrophone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) record() else usePhone()
    }
    return remember {
        SpeechInput(
            onStart = {
                when {
                    !GroqTranscriber.isAvailable -> usePhone()
                    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> record()
                    else -> askMicrophone.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            onFinish = { recorder.finishEarly() },
            listeningState = listening,
            transcribingState = transcribing
        )
    }
}

/** Records one question from the microphone, stopping by itself once you've gone quiet. */
private class VoiceRecorder {
    private val rate = 16_000
    @Volatile private var finishRequested = false
    @Volatile private var cancelled = false

    fun finishEarly() { finishRequested = true }
    fun cancel() { cancelled = true }

    /** The recording as WAV, or null when nothing was said (or recording isn't possible). */
    @SuppressLint("MissingPermission")
    suspend fun record(): ByteArray? = withContext(Dispatchers.IO) {
        finishRequested = false
        cancelled = false
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return@withContext null
        val audio = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate))
        }.getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED } ?: return@withContext null
        val block = ShortArray(rate / 10)
        val out = java.io.ByteArrayOutputStream()
        val end = VoiceAudio.EndOfSpeech()
        try {
            audio.startRecording()
            while (isActive && !cancelled) {
                val n = audio.read(block, 0, block.size)
                if (n <= 0) break
                val bytes = ByteArray(n * 2)
                for (i in 0 until n) { bytes[2 * i] = (block[i].toInt() and 0xff).toByte(); bytes[2 * i + 1] = (block[i].toInt() shr 8).toByte() }
                out.write(bytes)
                if (end.feed(VoiceAudio.rms(block, n), n * 1000L / rate) || finishRequested) break
            }
        } finally {
            runCatching { audio.stop() }
            audio.release()
        }
        if (cancelled || !end.heardSpeech) null else VoiceAudio.wav(out.toByteArray(), rate)
    }
}

/**
 * Reads answers aloud: the chosen Gemini voice when available, the phone's voice otherwise (or for
 * the rest of an answer if Gemini stops halfway). The next sentence is made while the current one
 * plays, so speaking starts after the first sentence instead of the whole answer.
 */
class Speaker(context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    @Volatile private var track: AudioTrack? = null
    private val phone = PhoneVoice(context)

    /** Speaks [text] (Markdown removed) in [voice] (default: the one picked in Settings), then calls [done]. */
    fun speak(text: String, voice: AdvisorVoice? = null, done: () -> Unit = {}) {
        stop()
        val clean = speakableText(text)
        if (!GeminiVoice.isAvailable) {
            phone.speak(clean, done)
            return
        }
        job = scope.launch {
            val chosen = voice ?: AdvisorVoice.byId(AssistantPrefs.voice.value)
            val pieces = VoiceAudio.sentences(clean)
            var next = pieces.firstOrNull()?.let { first -> async(Dispatchers.IO) { runCatching { GeminiVoice.synthesize(first, chosen) }.getOrNull() } }
            written = 0
            val out = withContext(Dispatchers.IO) { openTrack() }
            track = out
            try {
                for (i in pieces.indices) {
                    val pcm = next?.await()
                    next = pieces.getOrNull(i + 1)?.let { p -> async(Dispatchers.IO) { runCatching { GeminiVoice.synthesize(p, chosen) }.getOrNull() } }
                    if (pcm == null || out == null) {
                        next?.cancel()
                        withContext(Dispatchers.IO) { drain(out) }
                        phoneSpeak(pieces.drop(i).joinToString(" "))
                        break
                    }
                    withContext(Dispatchers.IO) { write(out, pcm) }
                }
                withContext(Dispatchers.IO) { drain(out) }
                done()
            } finally {
                track = null
                out?.let { runCatching { it.stop() }; it.release() }
            }
        }
    }

    private suspend fun phoneSpeak(text: String) = suspendCancellableCoroutine { cont ->
        phone.speak(text) { if (cont.isActive) cont.resume(Unit) }
        cont.invokeOnCancellation { main.post { phone.stop() } }
    }

    private fun openTrack(): AudioTrack? = runCatching {
        val rate = GeminiVoice.SAMPLE_RATE
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(min, rate))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build().also { it.play() }
    }.getOrNull()

    private var written = 0L

    private suspend fun write(out: AudioTrack, pcm: ByteArray) {
        var pos = 0
        while (pos < pcm.size && currentCoroutineContext().isActive) {
            val n = out.write(pcm, pos, minOf(8192, pcm.size - pos))
            if (n <= 0) break
            pos += n
        }
        written += pos / 2
    }

    /** Waits until everything written so far has been heard. */
    private suspend fun drain(out: AudioTrack?) {
        if (out == null) return
        // Never wait longer than the audio lasts plus a little, in case the stream stalls.
        val deadline = System.currentTimeMillis() + written * 1000 / GeminiVoice.SAMPLE_RATE + 2_000
        while (currentCoroutineContext().isActive && (out.playbackHeadPosition.toLong() and 0xffffffffL) < written &&
            System.currentTimeMillis() < deadline
        ) delay(50)
        written = 0
    }

    fun stop() {
        job?.cancel()
        job = null
        track?.let { runCatching { it.pause(); it.flush() } }
        phone.stop()
    }

    fun shutdown() {
        stop()
        phone.shutdown()
    }
}

/** The phone's own text-to-speech, a little slower than its default for a calmer read. */
private class PhoneVoice(context: Context) {
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

    fun speak(text: String, done: () -> Unit = {}) {
        if (!ready) {
            queued = text to done
            return
        }
        onDone = done
        val language = Locale.getDefault()
        if (tts.isLanguageAvailable(language) >= TextToSpeech.LANG_AVAILABLE) tts.language = language
        tts.setSpeechRate(0.92f)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "advisor-${System.nanoTime()}")
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
    val input = rememberSpeechInput { said ->
        if (said != null) {
            note = null
            onAsk(said)
        } else {
            note = "Didn't catch that. Tap the microphone to try again."
        }
    }
    // Never talk over the person: the voice always stops before the microphone opens.
    fun listen() {
        speaker.stop()
        speaking = false
        input.start()
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
            input.listening -> "Listening… tap the microphone when you're done"
            input.transcribing -> "Got it…"
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
            FilledIconButton(
                onClick = { if (input.listening) input.finish() else listen() },
                enabled = !isThinking && !input.transcribing,
                modifier = Modifier.size(76.dp)
            ) {
                Icon(
                    if (input.listening) Icons.Filled.Stop else Icons.Filled.Mic,
                    contentDescription = if (input.listening) "Done talking" else "Tap to talk",
                    modifier = Modifier.size(32.dp)
                )
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
