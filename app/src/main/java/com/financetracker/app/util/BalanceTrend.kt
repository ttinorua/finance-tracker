package com.financetracker.app.util

import kotlin.math.abs

private const val DAY_MS = 86_400_000L

/**
 * The balance at the end of each of the last [days] days, oldest first and ending with
 * [currentBalance] — for the dashboard's sparkline. Worked backwards from the current balance, so
 * it always ends on the figure shown above it.
 *
 * @param movements each transaction's date (UTC midnight) and signed amount (income positive).
 * @param today today's UTC midnight.
 */
fun dailyBalances(currentBalance: Double, movements: List<Pair<Long, Double>>, today: Long, days: Int = 30): List<Double> {
    if (days < 2) return listOf(currentBalance)
    val sorted = movements.sortedByDescending { it.first }
    val points = ArrayList<Double>(days)
    points += currentBalance
    var balance = currentBalance
    var i = 0
    // Step back one day at a time, undoing everything dated after the end of that day.
    for (back in 1 until days) {
        val dayStart = today - (back - 1) * DAY_MS
        while (i < sorted.size && sorted[i].first >= dayStart) {
            balance -= sorted[i].second
            i++
        }
        points += balance
    }
    return points.reversed()
}

/** The change across [points] in percent, or null when the starting balance is too close to zero
 * for a percentage to mean anything. */
fun percentChange(points: List<Double>): Double? {
    val first = points.firstOrNull() ?: return null
    val last = points.last()
    if (abs(first) < 1.0) return null
    return (last - first) / abs(first) * 100
}
