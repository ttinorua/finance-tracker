package com.financetracker.app.util

import com.financetracker.app.util.advisor.FinanceAnalysis
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.ceil

/** One month of the forecast: money in, the bills due (by name), and the balance after. */
data class AheadMonth(val start: Long, val moneyIn: Double, val out: Double, val bills: List<Pair<String, Double>>, val endBalance: Double)

data class AheadForecast(
    val months: List<AheadMonth>,
    /** The month (index into [months]) with the lowest end balance. */
    val lowestIndex: Int,
    /** Full months today's balance pays for if no money came in; null = all of [months]. */
    val runwayMonths: Int?,
    val totalOut: Double,
    val totalIn: Double,
    val start: Double,
    /** The smallest total monthly transfer that keeps every month's end balance at or above the buffer. */
    val neededPerMonth: Double
) {
    val lowest: AheadMonth get() = months[lowestIndex]
    val leftOver: Double get() = start + totalIn - totalOut
}

/**
 * "Bills ahead" for the shared bills account: month by month, the money coming in (your combined
 * transfers) and the bills due on their real dates (a yearly insurance in its month, not spread out),
 * so you can see whether the account stays covered, when it's lowest, how long today's balance
 * lasts on its own, and what monthly transfer keeps it above a safety buffer.
 */
object BillsAhead {
    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    private fun addMonths(time: Long, n: Int): Long = Calendar.getInstance(utc).apply { timeInMillis = time; add(Calendar.MONTH, n) }.timeInMillis

    /** The bills per month on average, quarterly and yearly ones spread out. */
    fun monthlyAverage(bills: List<UpcomingBill>): Double = bills.sumOf { it.amount / it.everyMonths.coerceAtLeast(1) }

    /** Months [balance] pays for at [monthlyBills] a month with nothing coming in. */
    fun lastsMonths(balance: Double, monthlyBills: Double): Double = if (monthlyBills <= 0) Double.POSITIVE_INFINITY else (balance / monthlyBills).coerceAtLeast(0.0)

    /** Months until [balance] reaches [goal] when [net] is added each month; 0 = already there, null = never. */
    fun monthsToGoal(balance: Double, goal: Double, net: Double): Int? = when {
        balance >= goal -> 0
        net <= 0 -> null
        else -> ceil((goal - balance) / net).toInt()
    }

    /**
     * [months] months starting with [now]'s month. [extra] is a what-if bill: an amount in the month
     * at that index (0 = this month).
     */
    fun forecast(
        bills: List<UpcomingBill>,
        balance: Double,
        monthlyIn: Double,
        now: Long,
        months: Int,
        buffer: Double = 0.0,
        extra: Pair<Double, Int>? = null,
        /** Money still to come this month (what's left of the transfers); null = a full month's. */
        firstMonthIn: Double? = null
    ): AheadForecast {
        val rows = mutableListOf<AheadMonth>()
        // Each bill's due dates, starting no earlier than this month.
        val thisMonth = FinanceAnalysis.monthStart(now)
        val due = bills.associateWith { b ->
            var d = b.nextDue
            while (d < thisMonth) d = addMonths(d, b.everyMonths.coerceAtLeast(1))
            d
        }.toMutableMap()
        var bal = balance
        var dry = balance
        var runway: Int? = null
        var cumOut = 0.0
        var needed = 0.0
        for (i in 0 until months) {
            val from = FinanceAnalysis.monthStart(now, i)
            val to = FinanceAnalysis.monthStart(now, i + 1)
            val items = mutableListOf<Pair<String, Double>>()
            for (b in bills) {
                var d = due.getValue(b)
                while (d < to) {
                    if (d >= from) items += b.name to b.amount
                    d = addMonths(d, b.everyMonths.coerceAtLeast(1))
                }
                due[b] = d
            }
            if (extra != null && extra.second == i && extra.first > 0) items += "New bill" to extra.first
            val out = items.sumOf { it.second }
            val moneyIn = if (i == 0 && firstMonthIn != null) firstMonthIn else monthlyIn
            bal += moneyIn - out
            dry -= out
            if (runway == null && dry < 0) runway = i
            cumOut += out
            needed = maxOf(needed, (buffer + cumOut - balance) / (i + 1))
            rows += AheadMonth(from, moneyIn, out, items.sortedByDescending { it.second }, bal)
        }
        val lowest = rows.indices.minByOrNull { rows[it].endBalance } ?: 0
        return AheadForecast(
            months = rows,
            lowestIndex = lowest,
            runwayMonths = runway,
            totalOut = cumOut,
            totalIn = rows.sumOf { it.moneyIn },
            start = balance,
            neededPerMonth = ceil(needed.coerceAtLeast(0.0) / 10) * 10
        )
    }
}
