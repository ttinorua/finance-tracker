package com.financetracker.app.util

import com.financetracker.app.data.household.HouseholdHistory
import com.financetracker.app.data.household.HouseholdMath
import com.financetracker.app.data.household.HouseholdSplit
import com.financetracker.app.data.household.HouseholdState
import com.financetracker.app.data.household.MemberReport
import com.financetracker.app.data.household.SharedBillsMemberReport
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * The household as the AI advisor reads it: the shared budget lines, what each of you (and joint
 * accounts) spent per line and month, and the shared bills. Built only from what the two phones
 * already share (category totals for the household lines), so the partner's own transactions
 * never reach the advisor. A month a phone has no history for is marked as missing, never as 0.
 */
object HouseholdSummary {

    /** One household line in one month. [mine]/[theirs]: whether each phone shared that month. */
    data class MonthLine(val month: String, val me: Double, val partner: Double, val joint: Double, val mine: Boolean, val theirs: Boolean) {
        val total: Double get() = me + partner + joint
    }

    /** A line only one phone has spending on, which often means the other files it under another name. */
    data class OneSided(val mainCategory: String, val onlyMine: Boolean)

    fun months(reports: List<MemberReport>, myUid: String, partnerUid: String?, mainCategory: String, months: List<String>): List<MonthLine> =
        months.map { month ->
            val inMonth = reports.filter { it.month == month }
            val split = HouseholdMath.splits(inMonth, myUid)[mainCategory] ?: HouseholdSplit()
            MonthLine(
                month, split.me, split.partner, split.joint,
                mine = inMonth.any { it.uid == myUid },
                theirs = partnerUid == null || inMonth.any { it.uid == partnerUid }
            )
        }

    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    /** A typical month: the median of full months (not [thisMonth]) that both phones shared, so
     * one big purchase doesn't pull it up and a missing month doesn't pull it down. */
    fun typical(lines: List<MonthLine>, thisMonth: String): Double? =
        median(lines.filter { it.month < thisMonth && it.mine && it.theirs }.map { it.total })

    /** Months from [from] to [to] ("yyyy-MM" or "yyyy-MM-dd"); by default the last [defaultMonths] including this one. */
    fun monthRange(from: String?, to: String?, thisMonth: String, defaultMonths: Int = 12): List<String> {
        val end = to?.take(7)?.takeIf(::isMonth)?.let { minOf(it, thisMonth) } ?: thisMonth
        val start = from?.take(7)?.takeIf(::isMonth) ?: generateSequence(end) { previous(it) }.drop(defaultMonths - 1).first()
        if (start > end) return emptyList()
        return generateSequence(start) { HouseholdHistory.next(it) }.takeWhile { it <= end }.toList()
    }

    /**
     * Lines where, over the last [window] full months that both phones shared, only one phone had
     * spending, every one of those months, and no joint account did. A hint for the Household
     * screen: the other phone may file these purchases under a different category name.
     */
    fun oneSided(state: HouseholdState, thisMonth: String, window: Int = 3): List<OneSided> {
        val me = state.myUid ?: return emptyList()
        val partner = state.partner?.uid ?: return emptyList()
        val full = generateSequence(previous(thisMonth)) { previous(it) }.take(window).toList()
        return state.lines.mapNotNull { line ->
            val months = months(state.reports, me, partner, line.mainCategory, full).filter { it.mine && it.theirs }
            if (months.size < window || months.any { it.joint > 0 }) return@mapNotNull null
            when {
                months.all { it.me > 0 && it.partner == 0.0 } -> OneSided(line.mainCategory, onlyMine = true)
                months.all { it.partner > 0 && it.me == 0.0 } -> OneSided(line.mainCategory, onlyMine = false)
                else -> null
            }
        }
    }

    /** Everything the advisor needs about the household, as plain text. [money] formats amounts. */
    fun advisorText(state: HouseholdState, now: Long, from: String?, to: String?, money: (Double) -> String): String {
        if (!state.joined) {
            return "The user hasn't set up a household. In the app: Household > Set up household, then the partner joins with the code."
        }
        val me = state.myUid ?: return "The household is still loading. Try again in a moment."
        val partnerName = state.partner?.let { state.partnerName }
        val thisMonth = monthKey(now)
        val range = monthRange(from, to, thisMonth)
        fun amount(v: Double) = money(v)
        return buildString {
            appendLine(
                if (partnerName == null) "Household: only the user has joined so far (no partner yet)."
                else "Household: the user and $partnerName. $partnerName's phone last shared " +
                    (state.partnerUpdatedAt?.let { day(it) } ?: "nothing yet") + "; " +
                    (if (state.partner?.sharesDetails == true) "it shares the transactions behind its totals." else "it shares totals only, not transactions.")
            )
            appendLine(
                "Only spending in the household lines below is shared, and only from the accounts each person ticked in Household. " +
                    "Months marked 'no data' are missing from that phone (no history there), NOT zero spending."
            )
            if (state.lines.isEmpty()) {
                appendLine("No household budget lines yet, so nothing is shared. Add categories under Household > Edit budget.")
                return@buildString
            }
            if (range.isEmpty()) {
                appendLine("No months in that period.")
                return@buildString
            }
            appendLine("Months: ${range.first()} to ${range.last()}" + if (range.last() == thisMonth) " ($thisMonth is so far, not a full month)." else ".")
            val who = partnerName ?: "partner"
            state.lines.forEach { line ->
                val months = months(state.reports, me, state.partner?.uid, line.mainCategory, range)
                val typical = typical(months, thisMonth)
                appendLine()
                appendLine(
                    "${line.mainCategory} — household budget ${amount(line.limit)} a month" +
                        (typical?.let { "; typical full month ${amount(it)} (median of months both phones shared)" } ?: "")
                )
                appendLine("  month: you / $who / joint = total")
                months.asReversed().forEach { m ->
                    val partnerPart = if (m.theirs) amount(m.partner) else "no data"
                    val minePart = if (m.mine) amount(m.me) else "no data"
                    val flag = when {
                        !m.mine && !m.theirs -> " (no data from either phone)"
                        m.month == thisMonth -> " (so far)"
                        m.total > line.limit -> " (over by ${amount(m.total - line.limit)})"
                        else -> ""
                    }
                    appendLine("  ${m.month}: $minePart / $partnerPart / ${amount(m.joint)} = ${amount(m.total)}$flag")
                }
            }
            oneSided(state, thisMonth).takeIf { it.isNotEmpty() }?.let { list ->
                appendLine()
                list.forEach {
                    appendLine(
                        "Note: only ${if (it.onlyMine) "the user's" else "$who's"} phone has ${it.mainCategory} spending in the last 3 months. " +
                            "The other phone may file these purchases under a different category name."
                    )
                }
            }
        }.trim()
    }

    /** The shared bills (fixed costs from the joint bills account) as one phone shares them. */
    fun sharedBillsText(reports: List<SharedBillsMemberReport>, myUid: String?, partnerName: String, money: (Double) -> String): String? {
        val report = reports.firstOrNull { it.uid == myUid && it.snapshot != null } ?: reports.firstOrNull { it.snapshot != null } ?: return null
        val s = report.snapshot ?: return null
        val mine = report.uid == myUid
        fun my(publisher: Double, other: Double) = if (mine) publisher else other
        fun their(publisher: Double, other: Double) = if (mine) other else publisher
        val myShare = if (mine) s.publisherShare else 1 - s.publisherShare
        return buildString {
            appendLine("Shared bills (fixed costs from the joint account \"${s.accountName}\", as of ${s.month}):")
            appendLine("- Bills per month: ${money(s.monthlyBills)}, split by net income: user ${pct(myShare)}, $partnerName ${pct(1 - myShare)}.")
            appendLine("- Each month's share: user ${money(my(s.publisherTarget, s.otherTarget))}, $partnerName ${money(their(s.publisherTarget, s.otherTarget))}.")
            appendLine("- Paid in this month: user ${money(my(s.publisherPaidIn, s.otherPaidIn))}, $partnerName ${money(their(s.publisherPaidIn, s.otherPaidIn))}; " +
                "still to transfer: user ${money(my(s.publisherToTransfer, s.otherToTransfer))}, $partnerName ${money(their(s.publisherToTransfer, s.otherToTransfer))}.")
            appendLine("- Ahead (+) / behind (−) since the split started: user ${money(my(s.publisherBalance, s.otherBalance))}, $partnerName ${money(their(s.publisherBalance, s.otherBalance))}.")
            if (s.payees.isNotEmpty()) appendLine("- Paid from the bills account per month: " + s.payees.take(15).joinToString("; ") { "${it.name} ${money(it.monthly)}" })
            val myCard = if (mine) s.publisherBills else s.otherBills
            val theirCard = if (mine) s.otherBills else s.publisherBills
            if (myCard.isNotEmpty()) appendLine("- Shared bills the user pays by own card: " + myCard.joinToString("; ") { "${it.name} ${money(it.monthly)}" })
            if (theirCard.isNotEmpty()) appendLine("- Shared bills $partnerName pays by own card: " + theirCard.joinToString("; ") { "${it.name} ${money(it.monthly)}" })
        }.trim()
    }

    private fun isMonth(text: String) = Regex("""\d{4}-\d{2}""").matches(text)

    private fun previous(month: String): String {
        val (y, m) = month.split('-').map { it.toInt() }
        val (py, pm) = if (m == 1) (y - 1) to 12 else y to (m - 1)
        return py.toString().padStart(4, '0') + "-" + pm.toString().padStart(2, '0')
    }

    private fun pct(v: Double) = "${Math.round(v * 100)}%"

    private val utc = TimeZone.getTimeZone("UTC")
    private fun monthKey(time: Long) = SimpleDateFormat("yyyy-MM", Locale.US).apply { timeZone = utc }.format(time)
    private fun day(time: Long) = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = utc }.format(time)
}
