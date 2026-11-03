package com.financetracker.app.data.ai

/** A photo or PDF sent along with one chat message (e.g. a phone bill). Only sent with that
 * message; never stored. [base64] holds the file's bytes. */
class ChatAttachment(val name: String, val mimeType: String, val base64: String) {
    val isPdf: Boolean get() = mimeType == "application/pdf"
}

/** A web page the AI used for an answer (from ChatGPT's web search). */
data class WebSource(val title: String, val url: String) {
    /** "telenor.dk", for showing the source compactly. */
    val site: String get() = url.substringAfter("://").substringBefore('/').substringBefore('?').removePrefix("www.")
}
