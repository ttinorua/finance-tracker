package com.financetracker.app

import com.financetracker.app.data.prefs.SharedBillsSettings
import com.financetracker.app.data.prefs.SharedBillsSetup
import com.financetracker.app.util.FixedTransfers
import com.financetracker.app.util.SharedBills
import com.financetracker.app.util.SharedBillsStatus
import com.financetracker.app.util.SharedLine
import com.financetracker.app.util.advisor.FinanceAnalysis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** The mockup's example: SJF Budget pays 26,880 a month, you pay 1,200 of shared bills from your
 * own account and Kjestine 900, split 57% / 43%. */
class FixedTransfersTest {

    private fun utc(year: Int, month: Int, day: Int): Long = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(year, month - 1, day)
    }.timeInMillis

    private fun money(v: Double) = Math.round(v).toString()

    private fun status(
        budget: Double = 26880.0,
        share: Double = 0.57,
        missing: List<SharedLine> = emptyList(),
        fixed: Pair<Double, Double>? = null,
        locked: Boolean = false
    ): SharedBillsStatus {
        val myBills = listOf(SharedLine("Internet", 1200.0))
        val partnerBills = listOf(SharedLine("Phone", 900.0))
        val all = budget + 2100.0
        return SharedBillsStatus(
            myShare = share, shareLocked = locked, myIncome = 40000.0, partnerIncome = 30000.0, partnerConnected = true,
            budgetMonthly = budget, budgetHistoryMonths = 12.0, budgetPayees = emptyList(), myBills = myBills, movedToBudget = emptyList(),
            partnerBills = partnerBills, monthlyBills = all, myTarget = all * share, partnerTarget = all * (1 - share),
            myCardThisMonth = 0.0, partnerCardThisMonth = 0.0, myPaidInThisMonth = 0.0, partnerPaidInThisMonth = 0.0,
            depositsThisMonth = emptyList(), startMonth = 0L, myBalance = 0.0, partnerBalance = 0.0, monthsCounted = 1,
            handOver = null, warnings = emptyList(), fixed = fixed, missingThisMonth = missing
        )
    }

    private val agreed = FixedTransfers.Agreed(FixedTransfers.Plan(15400.0, 11600.0), "2026-11", 40000.0, 30000.0)

    private fun check(s: SharedBillsStatus, salaryNow: Double = 0.0, partnerIncomeNow: Double = 30000.0, kept: String? = null, month: String = "2026-12") =
        FixedTransfers.check(agreed, s, salaryNow, partnerIncomeNow, "Kjestine", month, kept, ::money)

    @Test
    fun `each transfer is the share minus own-account bills, rounded up so the account is covered`() {
        val plan = FixedTransfers.suggest(status())
        assertEquals(15400.0, plan.mine, 0.0)
        assertEquals(11600.0, plan.partner, 0.0)
        assertTrue(plan.total >= 26880.0)
    }

    @Test
    fun `the fixed amounts are what each person transfers`() {
        val s = status(fixed = 15400.0 to 11600.0)
        assertEquals(15400.0, s.myToTransfer, 0.0)
        assertEquals(11600.0, s.partnerToTransfer, 0.0)
    }

    @Test
    fun `nothing big changed, so no question`() {
        assertNull(check(status()))
        // 400 kr more a month is under the 500 kr threshold.
        assertNull(check(status(budget = 27400.0)))
    }

    @Test
    fun `500 kr or more off asks with new amounts`() {
        // Insurance up 900: the account needs 27,780 against 27,000 coming in.
        val change = check(status(budget = 27780.0))!!
        assertTrue(change.reasons.first().contains("780 more"))
        assertEquals(15900.0, change.suggested.mine, 0.0)
        assertTrue(change.suggested.total >= 27780.0)
    }

    @Test
    fun `a regular bill not paid this month asks, assuming it ended`() {
        val change = check(status(missing = listOf(SharedLine("Netflix", 149.0))))!!
        assertTrue(change.reasons.any { it.contains("Netflix wasn't paid") })
        assertTrue(change.suggested.total <= agreed.plan.total)
    }

    @Test
    fun `a lower salary this month moves the split`() {
        val change = check(status(), salaryNow = 30000.0)!!
        assertTrue(change.reasons.any { it.contains("Your salary this month is lower") })
        assertTrue(change.reasons.any { it.contains("split moves to 50% / 50%") })
        assertTrue(change.suggested.mine < agreed.plan.mine)
        // A slightly lower month (under 10% down) doesn't count.
        assertNull(check(status(), salaryNow = 37000.0))
    }

    @Test
    fun `the partner's lower income counts too, but not when the split is locked`() {
        assertNotNull(check(status(), partnerIncomeNow = 20000.0))
        val locked = check(status(locked = true), partnerIncomeNow = 20000.0)
        // Still asked about (the income changed), but the split stays as locked.
        assertTrue(locked == null || locked.reasons.none { it.contains("split moves") })
    }

    @Test
    fun `keep current waits for next month`() {
        val off = status(budget = 27780.0)
        assertNull(check(off, kept = "2026-12", month = "2026-12"))
        assertNotNull(check(off, kept = "2026-12", month = "2027-01"))
    }

    @Test
    fun `a monthly bill paid last month but not by now this month is missing`() {
        val now = utc(2026, 12, 20)
        val netflix = FinanceAnalysis.RecurringPayment("netflix", FinanceAnalysis.Cadence.MONTHLY, 149.0, utc(2026, 11, 8), null, 6, null)
        val rent = FinanceAnalysis.RecurringPayment("insurance", FinanceAnalysis.Cadence.MONTHLY, 900.0, utc(2026, 11, 28), null, 6, null)
        val paidThisMonth = FinanceAnalysis.RecurringPayment("power", FinanceAnalysis.Cadence.MONTHLY, 700.0, utc(2026, 12, 3), null, 6, null)
        val missing = SharedBills.missingThisMonth(listOf(netflix, rent, paidThisMonth), now)
        // Netflix (due ~8th) is late; insurance (due ~28th) isn't due yet; power was paid.
        assertEquals(listOf(149.0), missing.map { it.monthly })
    }

    @Test
    fun `the fixed amounts are saved with the settings`() {
        val setup = SharedBillsSetup(accountId = 3, fixed = agreed, keptMonth = "2026-12")
        val back = SharedBillsSettings.decode(SharedBillsSettings.encode(setup))!!
        assertEquals(agreed, back.fixed)
        assertEquals("2026-12", back.keptMonth)
    }
}
