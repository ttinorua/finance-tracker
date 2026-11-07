package com.financetracker.app

import com.financetracker.app.data.household.AccountTotal
import com.financetracker.app.data.household.HouseholdBudgetLine
import com.financetracker.app.data.household.HouseholdHistory
import com.financetracker.app.data.household.HouseholdMember
import com.financetracker.app.data.household.HouseholdState
import com.financetracker.app.data.household.MemberReport
import com.financetracker.app.data.household.SharedBillsMemberReport
import com.financetracker.app.data.household.SharedBillsSnapshot
import com.financetracker.app.util.HouseholdSummary
import com.financetracker.app.util.PartnerReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class HouseholdSummaryTest {

    private fun utc(year: Int, month: Int, day: Int): Long = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(year, month - 1, day)
    }.timeInMillis

    private val now = utc(2026, 11, 15)
    private fun money(v: Double) = Math.round(v).toString()

    private fun report(uid: String, month: String, vararg totals: Triple<String, String, Double>) =
        MemberReport(uid, month, totals.map { AccountTotal(it.first, it.second, it.third) }, null, 0L)

    private fun state(reports: List<MemberReport>, lines: List<HouseholdBudgetLine> = listOf(HouseholdBudgetLine("l", "Food", 6000.0, "me", 0L))) =
        HouseholdState(
            householdId = "h", myUid = "me", myName = "Tino",
            members = listOf(HouseholdMember("me", "Tino", true), HouseholdMember("her", "Kjestine", false)),
            lines = lines, reports = reports
        )

    // ---------------------------------------------------------------- which months a phone shares

    @Test
    fun `a phone shares every month of its history, quiet months included`() {
        val months = HouseholdHistory.monthsToShare(setOf("2026-07", "2026-09"), emptySet(), "2026-11", "2026-10")
        assertEquals(listOf("2026-07", "2026-08", "2026-09", "2026-10", "2026-11"), months)
    }

    @Test
    fun `months shared before are written again, and a new phone still shares two months`() {
        assertEquals(listOf("2026-10", "2026-11"), HouseholdHistory.monthsToShare(emptySet(), emptySet(), "2026-11", "2026-10"))
        assertTrue("2025-01" in HouseholdHistory.monthsToShare(setOf("2026-10"), setOf("2025-01"), "2026-11", "2026-10"))
        assertEquals("2027-01", HouseholdHistory.next("2026-12"))
    }

    // ---------------------------------------------------------------- periods and typical month

    @Test
    fun `the default period is the last 12 months, and a named period is used as given`() {
        val default = HouseholdSummary.monthRange(null, null, "2026-11")
        assertEquals(12, default.size)
        assertEquals("2025-12", default.first())
        assertEquals(listOf("2026-01", "2026-02", "2026-03"), HouseholdSummary.monthRange("2026-01-05", "2026-03", "2026-11"))
        assertEquals("2026-11", HouseholdSummary.monthRange("2026-10", "2027-05", "2026-11").last())
    }

    @Test
    fun `the typical month is the median of full months both phones shared`() {
        val lines = listOf(
            HouseholdSummary.MonthLine("2026-08", 2000.0, 3000.0, 0.0, mine = true, theirs = true),
            HouseholdSummary.MonthLine("2026-09", 2500.0, 3000.0, 0.0, mine = true, theirs = true),
            // One big month doesn't pull the typical month up.
            HouseholdSummary.MonthLine("2026-10", 9000.0, 3000.0, 0.0, mine = true, theirs = true),
            // A month the partner's phone has no data for is left out, not counted as low.
            HouseholdSummary.MonthLine("2026-07", 2000.0, 0.0, 0.0, mine = true, theirs = false),
            // This month isn't over yet.
            HouseholdSummary.MonthLine("2026-11", 100.0, 0.0, 0.0, mine = true, theirs = true)
        )
        assertEquals(5500.0, HouseholdSummary.typical(lines, "2026-11")!!, 0.0)
        assertNull(HouseholdSummary.median(emptyList()))
    }

    // ---------------------------------------------------------------- who spent what

    @Test
    fun `spending splits into you, the partner and a joint account both phones report`() {
        val reports = listOf(
            report("me", "2026-10", Triple("mine", "Food", 2000.0), Triple("joint", "Food", 800.0)),
            report("her", "2026-10", Triple("hers", "Food", 2500.0), Triple("joint", "Food", 800.0))
        )
        val m = HouseholdSummary.months(reports, "me", "her", "Food", listOf("2026-10")).single()
        assertEquals(2000.0, m.me, 0.0)
        assertEquals(2500.0, m.partner, 0.0)
        // The joint account is counted once.
        assertEquals(800.0, m.joint, 0.0)
        assertEquals(5300.0, m.total, 0.0)
    }

    @Test
    fun `the advisor sees missing months as no data, not as zero`() {
        val reports = listOf(
            report("me", "2026-09", Triple("mine", "Food", 2000.0)),
            report("me", "2026-10", Triple("mine", "Food", 2100.0)),
            report("her", "2026-10", Triple("hers", "Food", 2600.0))
        )
        val text = HouseholdSummary.advisorText(state(reports), now, "2026-09", "2026-10", ::money)
        assertTrue(text.contains("2026-09: 2000 / no data / 0 = 2000"))
        assertTrue(text.contains("2026-10: 2100 / 2600 / 0 = 4700"))
        assertTrue(text.contains("Kjestine"))
        assertTrue(text.contains("totals only"))
    }

    @Test
    fun `a month over the household budget says by how much`() {
        val reports = listOf(report("me", "2026-10", Triple("mine", "Food", 4000.0)), report("her", "2026-10", Triple("hers", "Food", 2500.0)))
        val text = HouseholdSummary.advisorText(state(reports), now, "2026-10", "2026-10", ::money)
        assertTrue(text.contains("over by 500"))
    }

    @Test
    fun `without a household or lines the advisor is told what to set up`() {
        assertTrue(HouseholdSummary.advisorText(HouseholdState(), now, null, null, ::money).contains("hasn't set up a household"))
        assertTrue(HouseholdSummary.advisorText(state(emptyList(), lines = emptyList()), now, null, null, ::money).contains("No household budget lines"))
    }

    // ---------------------------------------------------------------- the one-phone hint

    @Test
    fun `a line only one phone spends on for three months is flagged`() {
        val months = listOf("2026-08", "2026-09", "2026-10")
        val reports = months.flatMap { listOf(report("me", it, Triple("mine", "Food", 2000.0)), report("her", it, Triple("hers", "Home", 300.0))) }
        val flagged = HouseholdSummary.oneSided(state(reports), "2026-11").single()
        assertEquals("Food", flagged.mainCategory)
        assertTrue(flagged.onlyMine)
    }

    @Test
    fun `no hint when both spend, a joint account pays, or the partner's phone hasn't shared`() {
        val months = listOf("2026-08", "2026-09", "2026-10")
        val both = months.flatMap { listOf(report("me", it, Triple("mine", "Food", 2000.0)), report("her", it, Triple("hers", "Food", 100.0))) }
        assertTrue(HouseholdSummary.oneSided(state(both), "2026-11").isEmpty())
        val onlyMyPhone = months.map { report("me", it, Triple("mine", "Food", 2000.0)) }
        assertTrue(HouseholdSummary.oneSided(state(onlyMyPhone), "2026-11").isEmpty())
    }

    // ---------------------------------------------------------------- shared bills

    @Test
    fun `shared bills read from the partner's phone are turned round to the user's side`() {
        val snapshot = SharedBillsSnapshot(
            accountName = "SJF Budget", month = "2026-11", publisherShare = 0.6, monthlyBills = 30000.0,
            publisherTarget = 18000.0, otherTarget = 12000.0, publisherToTransfer = 0.0, otherToTransfer = 4000.0,
            publisherPaidIn = 18000.0, otherPaidIn = 8000.0, publisherBalance = 500.0, otherBalance = -4000.0
        )
        val theirs = SharedBillsMemberReport("her", PartnerReport(0.0, emptyList(), emptyList(), emptyList()), snapshot, 0L)
        val text = HouseholdSummary.sharedBillsText(listOf(theirs), "me", "Kjestine", ::money)!!
        assertTrue(text.contains("user 40%, Kjestine 60%"))
        assertTrue(text.contains("Each month's share: user 12000, Kjestine 18000"))
        assertTrue(text.contains("still to transfer: user 4000, Kjestine 0"))
        assertNull(HouseholdSummary.sharedBillsText(emptyList(), "me", "Kjestine", ::money))
        assertFalse(text.contains("own card"))
    }
}
