package com.financetracker.app.data.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.Base64PdfSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.DocumentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.TextBlockParam
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Sends PDFs or photos to Gemini or Claude — the AIs here that can read documents — with a prompt,
 * and returns the reply text. Used for loan documents and shopping receipts. Photos are scaled down
 * first so they upload quickly; nothing is stored.
 */
object DocumentAi {

    private const val MAX_PDF_BYTES = 15_000_000
    private const val MAX_IMAGE_SIDE = 2000

    private class Part(val mimeType: String, val base64: String)

    /** Opens a photo or PDF the person picked, ready to send with a chat message (photos scaled
     * down like for documents). Runs on the IO thread. */
    suspend fun loadAttachment(context: Context, uri: Uri): ChatAttachment = withContext(Dispatchers.IO) {
        val part = load(context, uri)
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: if (part.mimeType == "application/pdf") "Document.pdf" else "Photo.jpg"
        ChatAttachment(name, part.mimeType, part.base64)
    }

    /** True when an AI that can read documents (Gemini or Claude) has a key. */
    val isAvailable: Boolean get() = readers().isNotEmpty()

    private fun readers() = AiSettings.chain.filter { it == AiProvider.GEMINI || it == AiProvider.CLAUDE }

    /** Reads [uris] with [prompt] as the instructions and [ask] as the request; tries each AI in
     * the user's order until one answers. */
    suspend fun read(context: Context, uris: List<Uri>, prompt: String, ask: String, maxTokens: Int): Result<String> = withContext(Dispatchers.IO) {
        val providers = readers()
        if (providers.isEmpty()) {
            return@withContext Result.failure(AiRequestException("Reading documents needs Gemini or Claude. Add a key in Settings > AI assistant."))
        }
        val parts = try {
            uris.map { load(context, it) }
        } catch (e: Exception) {
            return@withContext Result.failure(AiRequestException(e.message ?: "Couldn't open that file.", e))
        }
        var firstError: Throwable? = null
        for (provider in providers) {
            try {
                val text = when (provider) {
                    AiProvider.GEMINI -> readWithGemini(AiSettings.keyFor(provider) ?: continue, parts, prompt, ask, maxTokens)
                    else -> readWithClaude(parts, prompt, ask, maxTokens)
                }
                return@withContext Result.success(text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (firstError == null) firstError = e as? AiRequestException ?: AiRequestException("${provider.label} couldn't read it: ${e.message}", e)
            }
        }
        Result.failure(firstError ?: AiRequestException("Couldn't read the document."))
    }

    private fun load(context: Context, uri: Uri): Part {
        val resolver = context.contentResolver
        val type = resolver.getType(uri).orEmpty()
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalArgumentException("Couldn't open that file.")
        val isPdf = type == "application/pdf" || (bytes.size > 4 && String(bytes, 0, 4, Charsets.US_ASCII) == "%PDF")
        if (isPdf) {
            require(bytes.size <= MAX_PDF_BYTES) { "That PDF is too large (max 15 MB)." }
            return Part("application/pdf", Base64.encodeToString(bytes, Base64.NO_WRAP))
        }
        // A photo: scaled down so it uploads quickly and fits the AI's limits.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0) { "That file isn't a PDF or an image." }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_IMAGE_SIDE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IllegalArgumentException("That image couldn't be read.")
        val scale = MAX_IMAGE_SIDE.toFloat() / maxOf(decoded.width, decoded.height)
        val bitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true)
        } else {
            decoded
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return Part("image/jpeg", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
    }

    private fun readWithGemini(key: String, parts: List<Part>, prompt: String, ask: String, maxTokens: Int): String {
        val body = GeminiService.request(prompt, listOf(ChatTurn(isUser = true, text = prompt)), maxTokens, quick = false)
        val content = JSONArray()
        parts.forEach { content.put(JSONObject().put("inlineData", JSONObject().put("mimeType", it.mimeType).put("data", it.base64))) }
        content.put(JSONObject().put("text", ask))
        body.put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", content)))
        body.getJSONObject("generationConfig").put("responseMimeType", "application/json")
        val response = GeminiService.send(key, body)
        return GeminiService.parts(response).filter { !it.optBoolean("thought") }.joinToString("") { it.optString("text") }
    }

    private fun readWithClaude(parts: List<Part>, prompt: String, ask: String, maxTokens: Int): String {
        val client = ClaudeService.client() ?: throw AiNotConfiguredException()
        val blocks = parts.map { part ->
            if (part.mimeType == "application/pdf") {
                ContentBlockParam.ofDocument(DocumentBlockParam.builder().source(Base64PdfSource.builder().data(part.base64).build()).build())
            } else {
                ContentBlockParam.ofImage(
                    ImageBlockParam.builder()
                        .source(Base64ImageSource.builder().data(part.base64).mediaType(Base64ImageSource.MediaType.IMAGE_JPEG).build())
                        .build()
                )
            }
        } + ContentBlockParam.ofText(TextBlockParam.builder().text(ask).build())
        val params = ClaudeService.baseParams(maxTokens.toLong(), OutputConfig.Effort.MEDIUM)
            .system(prompt)
            .addUserMessageOfBlockParams(blocks)
            .build()
        val message = try {
            ClaudeService.send(client, params)
        } catch (e: Exception) {
            throw ClaudeService.mapError(e)
        }
        return message.content().filter { it.isText() }.joinToString("") { it.asText().text() }
    }
}
