package com.financetracker.app.data.ai.voice

import android.util.Base64
import com.financetracker.app.data.ai.AiProvider
import com.financetracker.app.data.ai.AiRequestException
import com.financetracker.app.data.ai.AiSettings
import com.financetracker.app.util.VoiceAudio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** The female Gemini voices offered in Settings, with Google's own one-word description. */
enum class AdvisorVoice(val id: String, val description: String) {
    SULAFAT("Sulafat", "Warm"),
    AOEDE("Aoede", "Breezy"),
    ACHERNAR("Achernar", "Soft"),
    KORE("Kore", "Firm"),
    LEDA("Leda", "Youthful"),
    VINDEMIATRIX("Vindemiatrix", "Gentle");

    companion object {
        fun byId(id: String?) = entries.firstOrNull { it.id.equals(id, true) } ?: SULAFAT
    }
}

/**
 * Gemini text-to-speech: natural voices instead of the phone's. Returns 16-bit mono PCM at
 * [SAMPLE_RATE]. Always asks for a calm, unhurried pace. Tries the current model first and, if
 * Google rejects it (an older key or region), the older preview model with the older request
 * shape, so voice keeps working.
 */
object GeminiVoice {
    const val SAMPLE_RATE = 24_000
    private const val MODEL = "gemini-3.8-flash-tts"
    private const val FALLBACK_MODEL = "gemini-2.5-flash-preview-tts"
    private const val STYLE = "warm, calm and natural, at a relaxed, unhurried pace, like a friendly advisor"

    val isAvailable: Boolean get() = !AiSettings.keyFor(AiProvider.GEMINI).isNullOrBlank()

    /** Set once the current model is refused, so later sentences go straight to the older one. */
    @Volatile private var useOlderModel = false

    suspend fun synthesize(text: String, voice: AdvisorVoice): ByteArray = withContext(Dispatchers.IO) {
        val key = AiSettings.keyFor(AiProvider.GEMINI) ?: throw AiRequestException("No Gemini key for the voice.")
        val current = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(
                JSONObject().put("text", text).put("speech_metadata", JSONObject().put("style", STYLE))
            ))))
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("voice", voice.id))))
        val (status, body) = if (useOlderModel) 0 to "" else post(MODEL, key, current)
        val reply = if (status in 200..299) body else {
            if (status in 400..404) useOlderModel = true
            val older = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(
                    JSONObject().put("text", "Say this in a $STYLE: $text")
                ))))
                .put("generationConfig", JSONObject()
                    .put("responseModalities", JSONArray().put("AUDIO"))
                    .put("speechConfig", JSONObject().put("voiceConfig", JSONObject()
                        .put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice.id)))))
            val (s2, b2) = post(FALLBACK_MODEL, key, older)
            if (s2 !in 200..299) throw AiRequestException(if (s2 == 429) "Gemini's free voice limit is used up for now." else "Gemini voice failed ($s2).")
            b2
        }
        val parts = JSONObject(reply).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            ?: throw AiRequestException("Gemini sent no audio.")
        val data = (0 until parts.length()).mapNotNull { parts.optJSONObject(it)?.optJSONObject("inlineData")?.optString("data")?.takeIf { d -> d.isNotBlank() } }
            .firstOrNull() ?: throw AiRequestException("Gemini sent no audio.")
        VoiceAudio.pcmOf(Base64.decode(data, Base64.DEFAULT))
    }

    private fun post(model: String, key: String, body: JSONObject): Pair<Int, String> {
        val connection = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("x-goog-api-key", key)
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            return status to (stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "")
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * Groq's Whisper speech-to-text: better than the phone's at accents, mixed languages and amounts.
 * Takes a WAV recording and returns what was said.
 */
object GroqTranscriber {
    private const val URL_TRANSCRIBE = "https://api.groq.com/openai/v1/audio/transcriptions"
    private const val MODEL = "whisper-large-v3-turbo"

    val isAvailable: Boolean get() = !AiSettings.keyFor(AiProvider.GROQ).isNullOrBlank()

    suspend fun transcribe(wav: ByteArray): String = withContext(Dispatchers.IO) {
        val key = AiSettings.keyFor(AiProvider.GROQ) ?: throw AiRequestException("No Groq key for listening.")
        val boundary = "----ft" + UUID.randomUUID().toString().replace("-", "")
        val body = ByteArrayOutputStream().apply {
            fun field(name: String, value: String) {
                write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
            }
            field("model", MODEL)
            field("response_format", "json")
            field("temperature", "0")
            // Money and dates come up a lot; a hint helps Whisper spell them the way the app writes them.
            field("prompt", "A question about personal finances, spending, bills, budgets or a mortgage, with amounts in kroner.")
            write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\nContent-Type: audio/wav\r\n\r\n".toByteArray())
            write(wav)
            write("\r\n--$boundary--\r\n".toByteArray())
        }.toByteArray()
        val connection = URL(URL_TRANSCRIBE).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $key")
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (status !in 200..299) throw AiRequestException(if (status == 429) "Groq's free listening limit is used up for now." else "Groq listening failed ($status).")
            JSONObject(text).optString("text").trim()
        } finally {
            connection.disconnect()
        }
    }
}
