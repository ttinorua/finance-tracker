package com.financetracker.app.data.bank

import com.financetracker.app.data.ai.LearnedCategoryRules
import com.financetracker.app.data.prefs.BankScheduledPayments
import com.financetracker.app.data.prefs.BudgetLimits
import com.financetracker.app.data.prefs.CategoryIconChoices
import com.financetracker.app.data.prefs.FixedExpenseCategories
import com.financetracker.app.data.repository.FinanceRepository
import com.financetracker.app.util.CategoryNames

/**
 * Finds and merges duplicate categories (same name and type, in any main category — see
 * [CategoryNames]). Merging moves everything that points at the extra copy onto the one kept:
 * transactions, receipt lines, budgets, the Fixe mark, a chosen icon, learned notes and upcoming
 * bank payments. Only then is the extra copy deleted, so nothing ends up uncategorized.
 */
object CategoryMerge {

    private val builtIn: Set<String> by lazy {
        SupportedBanks.ALL.flatMap { it.defaultCategories }.map { "${it.mainCategory}|${it.name}|${it.type}" }.toSet()
    }

    suspend fun findDuplicates(repository: FinanceRepository): List<CategoryNames.Duplicate> {
        val counts = repository.getAllTransactions().mapNotNull { it.categoryId }.groupingBy { it }.eachCount()
        return CategoryNames.duplicates(repository.getCategories(), counts, builtIn)
    }

    /** Merges every duplicate found right now. Returns how many extra copies were removed. */
    suspend fun mergeAll(repository: FinanceRepository): Int {
        var removed = 0
        for (duplicate in findDuplicates(repository)) {
            val keepId = duplicate.keep.id
            for (extra in duplicate.remove) {
                moveSettings(extra.id, keepId)
                repository.mergeCategoryInto(extra, keepId)
                removed++
            }
        }
        return removed
    }

    private fun moveSettings(fromId: Long, toId: Long) {
        BudgetLimits.categoryBudgets.value.filterKeys { it.first == fromId }.forEach { (key, amount) ->
            val accountId = key.second
            if (BudgetLimits.categoryBudgetFor(toId, accountId) == null) BudgetLimits.setCategoryBudget(toId, accountId, amount)
            BudgetLimits.setCategoryBudget(fromId, accountId, null)
        }
        if (fromId in FixedExpenseCategories.fixedCategoryIds.value) {
            FixedExpenseCategories.setFixed(toId, true)
            FixedExpenseCategories.setFixed(fromId, false)
        }
        CategoryIconChoices.icons.value[fromId]?.let { icon ->
            if (CategoryIconChoices.icons.value[toId] == null) CategoryIconChoices.set(toId, icon)
            CategoryIconChoices.set(fromId, null)
        }
        LearnedCategoryRules.remapCategory(fromId, toId)
        BankScheduledPayments.remapCategory(fromId, toId)
    }
}
