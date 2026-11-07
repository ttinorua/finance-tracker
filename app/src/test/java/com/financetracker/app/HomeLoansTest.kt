package com.financetracker.app

import com.financetracker.app.data.prefs.Loan
import com.financetracker.app.data.prefs.LoansAndGoals
import com.financetracker.app.util.Mortgage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A realkredit mortgage and a bank loan that together pay for the home. */
class HomeLoansTest {

    private val realkredit = Loan(name = "Realkredit", kind = "mortgage", homeValue = 3_900_000.0)
    private val bank = Loan(name = "Sydbank lån", kind = "bank_loan")

    @Test
    fun `a bank loan is part of the home when switched on`() {
        assertTrue(Mortgage.isHomeLoan(realkredit))
        assertFalse(Mortgage.isHomeLoan(bank))
        assertTrue(Mortgage.isHomeLoan(bank.copy(partOfHome = true)))
        assertFalse(Mortgage.isHomeLoan(Loan(kind = "car_loan", partOfHome = true)))
    }

    @Test
    fun `a home-sounding name suggests it, until switched off`() {
        val boliglan = bank.copy(name = "Boliglån")
        assertTrue(Mortgage.isHomeLoan(boliglan))
        assertFalse(Mortgage.isHomeLoan(boliglan.copy(partOfHome = false)))
        assertTrue(Mortgage.isHomeLoan(bank.copy(loanType = "Pantebrev")))
    }

    @Test
    fun `the mortgage holds the home value, and friværdi takes off both debts`() {
        val home = listOf(bank.copy(partOfHome = true), realkredit)
        assertEquals(realkredit, Mortgage.mainHomeLoan(home))
        assertEquals(630_000.0, Mortgage.homeEquity(3_900_000.0, listOf(2_850_000.0, 420_000.0))!!, 0.0)
        assertNull(Mortgage.homeEquity(null, listOf(1.0)))
    }

    @Test
    fun `the choice is saved and shared with the loan`() {
        listOf(true, false, null).forEach { choice ->
            val back = LoansAndGoals.loanFromJson(LoansAndGoals.loanToJson(bank.copy(partOfHome = choice)))
            assertEquals(choice, back.partOfHome)
        }
    }
}
