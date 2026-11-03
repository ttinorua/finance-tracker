package com.financetracker.app.ui.screens.receipts

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.financetracker.app.util.ReceiptSplits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import java.io.File

/** Where receipt screens are opened from outside the navigation graph: a receipt shared from
 * another app, or a tap on a transaction that has one. MainActivity follows [events]. */
object ReceiptNav {
    sealed interface Event {
        /** Read and import these files (already copied into the app's cache). */
        data class Import(val files: List<Uri>, val source: String) : Event
        data class OpenForTransaction(val transactionId: Long) : Event
    }

    private val channel = Channel<Event>(Channel.BUFFERED)
    val events = channel.receiveAsFlow()

    private val _withReceipt = MutableStateFlow<Set<Long>>(emptySet())
    /** Bank transactions that have a receipt. */
    val withReceipt: StateFlow<Set<Long>> = _withReceipt

    fun setTransactionsWithReceipt(ids: Collection<Long>) {
        _withReceipt.value = ids.toSet()
    }

    fun hasReceipt(transactionId: Long): Boolean =
        ReceiptSplits.isPart(transactionId) || ReceiptSplits.originalId(transactionId) in _withReceipt.value

    fun openForTransaction(transactionId: Long) {
        channel.trySend(Event.OpenForTransaction(ReceiptSplits.originalId(transactionId)))
    }

    fun import(files: List<Uri>, source: String) {
        if (files.isNotEmpty()) channel.trySend(Event.Import(files, source))
    }

    /** Files waiting for the import screen (read there once). */
    @Volatile var pendingImport: Event.Import? = null
}

/** Receipt files live briefly in the app's cache: shared files are copied there straight away
 * (the sharing app's permission to read them doesn't last), and the camera saves photos there. */
object ReceiptFiles {
    private const val DIR = "receipts"
    private const val MAX_BYTES = 20_000_000

    private fun dir(context: Context) = File(context.cacheDir, DIR).apply { mkdirs() }

    /** Copies [uris] into the cache; files that can't be read are skipped. */
    suspend fun copyIn(context: Context, uris: List<Uri>): List<Uri> = withContext(Dispatchers.IO) {
        clearOld(context)
        uris.take(com.financetracker.app.data.ai.ReceiptReader.MAX_FILES).mapIndexedNotNull { i, uri ->
            runCatching {
                val file = File(dir(context), "shared_${System.currentTimeMillis()}_$i")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { out ->
                        val copied = input.copyTo(out)
                        require(copied in 1..MAX_BYTES)
                    }
                } ?: return@runCatching null
                Uri.fromFile(file)
            }.getOrNull()
        }
    }

    /** A new file for the camera to save a photo in, as a content Uri it may write to. */
    fun newPhoto(context: Context): Uri {
        val file = File(dir(context), "photo_${System.currentTimeMillis()}.jpg")
        return FileProvider.getUriForFile(context, "${context.packageName}.receipts", file)
    }

    /** Drops files from earlier imports; a receipt is read straight away and never kept. */
    fun clearOld(context: Context, olderThanMs: Long = 3_600_000L) {
        val cutoff = System.currentTimeMillis() - olderThanMs
        dir(context).listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    }
}
