package com.financetracker.app

import com.financetracker.app.data.prefs.ClosedAccounts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClosedAccountsTest {

    @Test
    fun `closing and reopening an account`() {
        ClosedAccounts.setClosed(7, true)
        assertTrue(ClosedAccounts.isClosed(7))
        assertFalse(ClosedAccounts.isClosed(8))
        ClosedAccounts.setClosed(7, false)
        assertFalse(ClosedAccounts.isClosed(7))
        assertFalse(ClosedAccounts.isClosed(null))
    }

    @Test
    fun `the saved list reads back, ignoring anything broken`() {
        assertEquals(setOf(3L, 12L), ClosedAccounts.parse("3,12, x,"))
        assertEquals(emptySet<Long>(), ClosedAccounts.parse(null))
    }
}
