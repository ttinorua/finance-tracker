package com.financetracker.app.util

import com.financetracker.app.data.db.entity.Category
import com.financetracker.app.data.db.entity.TransactionType

/**
 * Decides when two category names mean the same thing, so neither a person nor the AI ends up
 * with "Takeaway" and "Take away" (or "Food" and "food") side by side. Names are compared
 * ignoring capitals, spaces, punctuation, "&" vs "and" and a plural "s" on the last word.
 */
object CategoryNames {

    /** The comparison key for a name: "Take-aways & Co" → "takeawayandco". */
    fun key(name: String): String {
        val words = Regex("""[^\W_]+""").findAll(name.lowercase().replace("&", " and ")).map { it.value }.toMutableList()
        if (words.isNotEmpty()) {
            val last = words.last()
            if (last.length > 3 && last.endsWith("s") && !last.endsWith("ss")) words[words.lastIndex] = last.dropLast(1)
        }
        return words.joinToString("")
    }

    fun same(a: String, b: String): Boolean = key(a) == key(b)

    /** An existing category with the same name and type, in any main category. */
    fun existing(categories: List<Category>, name: String, type: TransactionType): Category? =
        categories.firstOrNull { it.type == type && same(it.name, name) }

    /** [main] spelled the way an existing main category already is ("food" → "Food"). */
    fun canonicalMain(categories: List<Category>, main: String): String =
        categories.firstOrNull { same(it.mainCategory, main) }?.mainCategory ?: main.trim()

    /** One set of duplicates: [keep] stays, [remove] are merged into it. */
    data class Duplicate(val keep: Category, val remove: List<Category>)

    /**
     * Every group of categories with the same name and type (in any main category). The one kept
     * is a built-in category if there is one ([builtIn] holds "main|name|TYPE" keys, so it isn't
     * created again on the next start), else the one with the most transactions, else the oldest.
     */
    fun duplicates(categories: List<Category>, transactionCounts: Map<Long, Int>, builtIn: Set<String>): List<Duplicate> =
        categories.groupBy { it.type to key(it.name) }
            .values
            .filter { it.size > 1 }
            .map { group ->
                val keep = group.sortedWith(
                    compareByDescending<Category> { "${it.mainCategory}|${it.name}|${it.type}" in builtIn }
                        .thenByDescending { transactionCounts[it.id] ?: 0 }
                        .thenBy { it.id }
                ).first()
                Duplicate(keep, group.filter { it.id != keep.id })
            }
            .sortedBy { it.keep.name.lowercase() }
}
