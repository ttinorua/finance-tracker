package com.financetracker.app.util

import com.financetracker.app.data.db.entity.TransactionType
import com.financetracker.app.data.db.entity.TransactionWithDetails

/**
 * The account to make "main" when none is set (a new install, or the main account was deleted).
 * Having no main account makes every single-account view count transfers in as income, including
 * the everyday account, so the app always keeps one.
 *
 * Picks the account the most salary landed in over the last [days] days, else the busiest
 * account in that time, else the first account. Null only when there are no accounts.
 */
fun pickMainAccount(
    accountIds: List<Long>,
    transactions: List<TransactionWithDetails>,
    now: Long,
    days: Int = 180
): Long? {
    if (accountIds.isEmpty()) return null
    val since = now - days * 24L * 60 * 60 * 1000
    val recent = transactions.filter { it.accountId in accountIds && it.date >= since }
    val salary = recent
        .filter { it.type == TransactionType.INCOME && isSalaryCategory(it.mainCategoryName, it.categoryName) }
        .groupBy { it.accountId }
        .mapValues { (_, txs) -> txs.sumOf { it.amount } }
    salary.maxByOrNull { it.value }?.let { return it.key }
    recent.groupingBy { it.accountId }.eachCount().maxByOrNull { it.value }?.let { return it.key }
    return accountIds.first()
}
