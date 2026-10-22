package com.financetracker.app.util

import com.financetracker.app.data.db.entity.TransactionType
import com.financetracker.app.data.db.entity.TransactionWithDetails
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Narrows a screen to everything, one main category (all of its subcategories), one category, or
 * only uncategorized transactions. */
sealed class CategoryFilter {
    data object All : CategoryFilter()
    data object Uncategorized : CategoryFilter()
    data class Main(val name: String) : CategoryFilter()
    data class Single(val categoryId: Long, val name: String) : CategoryFilter()

    val label: String
        get() = when (this) {
            All -> "All categories"
            Uncategorized -> "Uncategorized"
            is Main -> name
            is Single -> name
        }

    fun matches(tx: TransactionWithDetails): Boolean = when (this) {
        All -> true
        Uncategorized -> tx.categoryId == null
        is Main -> tx.mainCategoryName == name
        is Single -> tx.categoryId == categoryId
    }

    /** A navigation-safe form, read back by [decode]. */
    fun encode(): String = when (this) {
        All -> "all"
        Uncategorized -> "none"
        is Main -> "main:$name"
        is Single -> "cat:$categoryId:$name"
    }

    companion object {
        fun decode(value: String): CategoryFilter = when {
            value == "none" -> Uncategorized
            value.startsWith("main:") -> Main(value.removePrefix("main:"))
            value.startsWith("cat:") -> {
                val rest = value.removePrefix("cat:")
                val id = rest.substringBefore(':').toLongOrNull()
                if (id == null) All else Single(id, rest.substringAfter(':', ""))
            }
            else -> All
        }
    }
}

enum class TrendGranularity(val label: String) {
    MONTH("Month"),
    YEAR("Year")
}

/** One bar of a trend chart: [from, to) in epoch millis. [subLabel] is the year under a month's
 * name (null for a year bar); a chart shows it only where the year changes, so a 12-month chart
 * stays readable without repeating the year on every bar. */
data class TrendPoint(
    val from: Long,
    val to: Long,
    val label: String,
    val subLabel: String?,
    val longLabel: String,
    val income: Double,
    val expense: Double
) {
    val net: Double get() = income - expense
}

/**
 * Income/expense per calendar month (the last [monthCount] months, ending with the current one)
 * or per calendar year (from the year of the oldest transaction through the current one, at most
 * [maxYears]), using the same reporting-date shift and transfer rules as every other total.
 *
 * [transactions] must already be narrowed to the account and [categoryFilter] is applied here.
 */
fun buildTrend(
    transactions: List<TransactionWithDetails>,
    granularity: TrendGranularity,
    categoryFilter: CategoryFilter,
    shiftSalary: Boolean,
    excludeTransfers: Boolean,
    transfersInAreIncome: Boolean,
    monthCount: Int = 12,
    maxYears: Int = 10,
    now: Long = System.currentTimeMillis()
): List<TrendPoint> {
    val relevant = transactions.filter {
        categoryFilter.matches(it) &&
            countsTowardTotals(it.type, it.mainCategoryName, it.categoryName, excludeTransfers, transfersInAreIncome)
    }
    val dated = relevant.map {
        effectiveReportingDate(it.date, it.type, it.mainCategoryName, it.categoryName, shiftSalary) to it
    }
    val buckets = when (granularity) {
        TrendGranularity.MONTH -> monthBuckets(monthCount, now)
        TrendGranularity.YEAR -> yearBuckets(dated.minOfOrNull { it.first }, maxYears, now)
    }
    return buckets.map { bucket ->
        val inBucket = dated.filter { (date, _) -> date >= bucket.from && date < bucket.to }.map { it.second }
        bucket.copy(
            income = inBucket.filter { it.type == TransactionType.INCOME }.sumOf { it.amount },
            expense = inBucket.filter { it.type == TransactionType.EXPENSE }.sumOf { it.amount }
        )
    }
}

private val UTC: TimeZone = TimeZone.getTimeZone("UTC")

private fun startOfMonth(now: Long): Calendar = Calendar.getInstance(UTC).apply {
    timeInMillis = now
    set(Calendar.DAY_OF_MONTH, 1)
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}

/** Oldest to newest, ending with the current calendar month. */
private fun monthBuckets(count: Int, now: Long): List<TrendPoint> {
    val cal = startOfMonth(now).apply { add(Calendar.MONTH, -(count - 1)) }
    val monthFormat = SimpleDateFormat("MMM", Locale.US).apply { timeZone = UTC }
    val longFormat = SimpleDateFormat("MMM yyyy", Locale.US).apply { timeZone = UTC }
    return (0 until count).map {
        val from = cal.timeInMillis
        val label = monthFormat.format(cal.time)
        val longLabel = longFormat.format(cal.time)
        val year = cal.get(Calendar.YEAR)
        cal.add(Calendar.MONTH, 1)
        TrendPoint(from, cal.timeInMillis, label, "$year", longLabel, 0.0, 0.0)
    }
}

/** Oldest to newest, ending with the current calendar year; just the current year with no data. */
private fun yearBuckets(oldestDate: Long?, maxYears: Int, now: Long): List<TrendPoint> {
    val currentYear = Calendar.getInstance(UTC).apply { timeInMillis = now }.get(Calendar.YEAR)
    val oldestYear = oldestDate?.let { Calendar.getInstance(UTC).apply { timeInMillis = it }.get(Calendar.YEAR) }
        ?: currentYear
    val firstYear = oldestYear.coerceIn(currentYear - maxYears + 1, currentYear)
    return (firstYear..currentYear).map { year ->
        val from = Calendar.getInstance(UTC).apply {
            clear()
            set(year, Calendar.JANUARY, 1)
        }
        val to = (from.clone() as Calendar).apply { add(Calendar.YEAR, 1) }
        TrendPoint(from.timeInMillis, to.timeInMillis, "$year", null, "$year", 0.0, 0.0)
    }
}
