package com.financetracker.app.ui.screens.ai

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/** A piece of an advisor reply: running text, or a table. */
sealed class ChatBlock {
    data class Paragraph(val text: String) : ChatBlock()
    data class Table(val header: List<String>, val rows: List<List<String>>) : ChatBlock()
}

private val BREAK = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
private val TAG = Regex("""</?[a-zA-Z][a-zA-Z0-9]*(\s[^<>]*)?/?>""")
private val TABLE_RULE = Regex("""^\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?$""")
private val HORIZONTAL_RULE = Regex("""^\s*([-*_])(\s*\1){2,}\s*$""")

/** Removes the HTML the AIs sometimes slip in: line breaks become real ones, other tags go. */
fun cleanHtml(text: String): String = text
    .replace(BREAK, "\n")
    .replace(TAG, "")
    .replace("&nbsp;", " ")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&amp;", "&")

private fun cells(line: String): List<String> =
    line.trim().removePrefix("|").removeSuffix("|").split("|").map { cleanHtml(it).trim() }

/** Splits a reply into paragraphs and Markdown tables (a header row, a |---| row, then rows). */
fun parseChatBlocks(text: String): List<ChatBlock> {
    val blocks = mutableListOf<ChatBlock>()
    val paragraph = mutableListOf<String>()
    fun flush() {
        val joined = paragraph.joinToString("\n").trim('\n')
        if (joined.isNotBlank()) blocks += ChatBlock.Paragraph(joined)
        paragraph.clear()
    }

    val lines = text.replace("\r", "").lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val next = lines.getOrNull(i + 1)?.trim()
        if (line.trim().startsWith("|") && next != null && TABLE_RULE.matches(next)) {
            flush()
            val header = cells(line)
            val rows = mutableListOf<List<String>>()
            i += 2
            while (i < lines.size && lines[i].trim().startsWith("|")) {
                val row = cells(lines[i])
                rows += List(header.size) { row.getOrElse(it) { "" } }
                i++
            }
            blocks += ChatBlock.Table(header, rows)
            continue
        }
        if (!HORIZONTAL_RULE.matches(line)) paragraph += cleanHtml(line)
        i++
    }
    flush()
    return blocks
}

private val INLINE = Regex("""\*\*(.+?)\*\*|__(.+?)__|(?<![\w*])\*(?!\s)(.+?)(?<!\s)\*(?![\w*])|`([^`]+)`""")

/** A Markdown link, e.g. the "([telenor.dk](https://…))" citations web search answers carry. */
private val LINK = Regex("""\[([^\]\n]+)]\((https?://[^)\s]+)\)""")

/** Markdown links shown as their label only: the pages are listed under the answer. */
fun stripLinks(text: String): String = text.replace(LINK) { it.groupValues[1] }

private fun AnnotatedString.Builder.appendInline(raw: String) {
    val text = stripLinks(raw)
    var last = 0
    for (m in INLINE.findAll(text)) {
        append(text.substring(last, m.range.first).replace("**", ""))
        val (bold, boldAlt, italic, code) = m.destructured
        when {
            bold.isNotEmpty() || boldAlt.isNotEmpty() ->
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(bold.ifEmpty { boldAlt }) }
            italic.isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(italic) }
            else -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(code) }
        }
        last = m.range.last + 1
    }
    append(text.substring(last).replace("**", ""))
}

/** The little Markdown the advisor uses, as styled text: **bold**, *italic*, `code`, "#" headings
 * (shown bold) and "- " / "* " bullets (shown as •). */
fun chatMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    text.lines().forEachIndexed { index, rawLine ->
        if (index > 0) append('\n')
        var line = rawLine
        val heading = Regex("""^\s*#{1,6}\s+""").find(line)
        if (heading != null) {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { appendInline(line.substring(heading.range.last + 1)) }
            return@forEachIndexed
        }
        val bullet = Regex("""^(\s*)[-*•]\s+""").find(line)
        if (bullet != null) {
            append(bullet.groupValues[1] + "•  ")
            line = line.substring(bullet.range.last + 1)
        }
        appendInline(line)
    }
}

/** An advisor reply: its paragraphs as styled text and its tables as tables. */
@Composable
fun ChatReply(text: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        parseChatBlocks(text).forEachIndexed { i, block ->
            val top = if (i == 0) 0.dp else 8.dp
            when (block) {
                is ChatBlock.Paragraph -> Text(
                    chatMarkdown(block.text),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = top)
                )
                is ChatBlock.Table -> ChatTable(block, Modifier.padding(top = top))
            }
        }
    }
}

@Composable
private fun ChatTable(table: ChatBlock.Table, modifier: Modifier = Modifier) {
    // Two columns: a narrower label column and a wider one for the explanation.
    val weights = if (table.header.size == 2) listOf(.38f, .62f) else List(table.header.size) { 1f }
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            table.header.forEachIndexed { c, cell ->
                Text(
                    chatMarkdown(cell),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(weights[c]).padding(end = 8.dp)
                )
            }
        }
        table.rows.forEach { row ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                row.forEachIndexed { c, cell ->
                    Text(
                        chatMarkdown(cell),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (c == 0 && table.header.size == 2) FontWeight.Medium else null,
                        modifier = Modifier.weight(weights[c]).padding(end = 8.dp)
                    )
                }
            }
        }
    }
}
