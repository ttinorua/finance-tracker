package com.financetracker.app.util

/** Turns an AI answer into something pleasant to read aloud: no Markdown marks, bullets or links. */
fun speakableText(text: String): String =
    text.lines()
        .map { line ->
            line.trim()
                .replace(Regex("""^#+\s*"""), "")
                .replace(Regex("""^([-*•]|\d+[.)])\s+"""), "")
                .replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")
                .replace(Regex("""[*_`|]"""), "")
                .trim()
        }
        .filter { it.isNotEmpty() && !it.matches(Regex("""[-:\s]+""")) }
        .joinToString(". ") { it.trimEnd('.', ':') }
        .let { if (it.isEmpty()) it else "$it." }
