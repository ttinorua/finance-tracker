package com.financetracker.app.ui.components

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Store chains' own logos. The app doesn't ship them: the first time a chain's logo is needed it
 * is fetched once (the store's website icon, through Google's site-icon service, which only gets
 * the store's web address) and kept on the phone. Until then, or if it can't be fetched, the
 * screen shows its usual icon instead.
 */
object StoreLogos {
    /** Words in a store name and the chain's website. More specific names come first, so
     * "SuperBrugsen" isn't taken for "Brugsen" and "Coop 365" isn't taken for "Coop". */
    private val chains = listOf(
        listOf("superbrugsen") to "superbrugsen.dk",
        listOf("365") to "coop365.dk",
        listOf("kvickly") to "kvickly.dk",
        listOf("brugsen") to "brugsen.dk",
        listOf("irma") to "irma.dk",
        listOf("coop") to "coop.dk",
        listOf("netto") to "netto.dk",
        listOf("føtex", "foetex", "fotex") to "foetex.dk",
        listOf("bilka") to "bilka.dk",
        listOf("salling") to "sallinggroup.com",
        listOf("lidl") to "lidl.dk",
        listOf("nemlig") to "nemlig.com",
        listOf("rema") to "rema1000.dk",
        listOf("meny") to "meny.dk",
        listOf("spar") to "spar.dk",
        listOf("løvbjerg", "lovbjerg") to "lovbjerg.dk"
    )

    /** The chain's website for a store name ("Netto Taastrup" → netto.dk), or null. */
    fun domainFor(storeName: String): String? {
        val name = storeName.lowercase()
        return chains.firstOrNull { (words, _) -> words.any { name.contains(it) } }?.second
    }

    private const val RETRY_AFTER_MS = 86_400_000L
    private val memory = ConcurrentHashMap<String, ImageBitmap>()
    private val failedAt = ConcurrentHashMap<String, Long>()

    suspend fun load(context: Context, domain: String): ImageBitmap? {
        memory[domain]?.let { return it }
        if ((failedAt[domain] ?: 0L) > System.currentTimeMillis() - RETRY_AFTER_MS) return null
        return withContext(Dispatchers.IO) {
            val file = File(File(context.filesDir, "store_logos").apply { mkdirs() }, "$domain.png")
            val bytes = if (file.exists()) file.readBytes() else download(domain)?.also { file.writeBytes(it) }
            val bitmap = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            if (bitmap == null || bitmap.width < MIN_SIDE) {
                file.delete()
                failedAt[domain] = System.currentTimeMillis()
                null
            } else {
                bitmap.asImageBitmap().also { memory[domain] = it }
            }
        }
    }

    /** The site's icon, only when the service actually has one (an unknown site gets a 404
     * with a small generic globe). */
    private fun download(domain: String): ByteArray? = runCatching {
        val connection = URL("https://www.google.com/s2/favicons?domain=$domain&sz=128").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            if (connection.responseCode != 200) return null
            connection.inputStream.use { it.readBytes() }.takeIf { it.size in 100..500_000 }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private const val MIN_SIDE = 32
}

/** The chain's logo for [storeName] (or its website, [domain]) in a rounded tile; [fallback]
 * while it loads or when there's none. */
@Composable
fun StoreLogo(storeName: String, size: Dp, domain: String? = null, fallback: @Composable () -> Unit) {
    val context = LocalContext.current
    val site = domain ?: StoreLogos.domainFor(storeName)
    var image by remember(site) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(site) {
        image = if (site == null) null else StoreLogos.load(context.applicationContext, site)
    }
    val logo = image
    if (logo == null) {
        fallback()
    } else {
        Box(
            Modifier.size(size).clip(RoundedCornerShape(size * .3f)).background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            Image(logo, contentDescription = storeName, contentScale = ContentScale.Fit, modifier = Modifier.size(size))
        }
    }
}
