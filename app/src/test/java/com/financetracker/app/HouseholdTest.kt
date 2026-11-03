package com.financetracker.app

import com.financetracker.app.data.household.AccountTotal
import com.financetracker.app.data.household.HouseholdCrypto
import com.financetracker.app.data.household.HouseholdMath
import com.financetracker.app.data.household.MemberReport
import com.financetracker.app.data.household.Payer
import com.financetracker.app.data.household.SharedTransaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HouseholdTest {

    @Test
    fun `the household key travels in the invite and only the right code opens it`() {
        val key = HouseholdCrypto.newHouseholdKey()
        val code = HouseholdCrypto.newInviteCode()
        assertTrue(code.matches(Regex("[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{2}")))
        val wrapped = HouseholdCrypto.wrapKey(key, code)
        // Typed in lower case without dashes still works.
        assertTrue(key.contentEquals(HouseholdCrypto.unwrapKey(wrapped, code.lowercase().replace("-", ""))))
        assertNull(HouseholdCrypto.unwrapKey(wrapped, "AAAA-AAAA-AA"))
        assertEquals(HouseholdCrypto.inviteId(code), HouseholdCrypto.inviteId(code.replace("-", " ")))
        assertTrue(!HouseholdCrypto.inviteId(code).contains(code.replace("-", "")))
    }

    @Test
    fun `shared data is encrypted and tags are private but stable`() {
        val key = HouseholdCrypto.newHouseholdKey()
        val sealed = HouseholdCrypto.encryptText(key, "Netto 312.50")
        assertTrue(!sealed.contains("Netto"))
        assertEquals("Netto 312.50", HouseholdCrypto.decryptText(key, sealed))
        assertNotEquals(sealed, HouseholdCrypto.encryptText(key, "Netto 312.50"))
        val iban = "DK6967480001248366"
        assertEquals(HouseholdCrypto.tag(key, iban), HouseholdCrypto.tag(key, iban))
        assertNotEquals(HouseholdCrypto.tag(key, iban), HouseholdCrypto.tag(HouseholdCrypto.newHouseholdKey(), iban))
    }

    private fun tx(id: String, account: String, main: String, amount: Double, date: Long) =
        SharedTransaction(id, account, main, main, amount, date, id)

    @Test
    fun `spending is split by who paid and a joint account is counted once`() {
        val me = MemberReport(
            "a", "2026-10",
            listOf(AccountTotal("joint", "Food", 1000.0), AccountTotal("mine", "Food", 300.0), AccountTotal("joint", "Home", 6000.0)),
            listOf(tx("j1", "joint", "Food", 1000.0, 3), tx("m1", "mine", "Food", 300.0, 2)), 0
        )
        val partner = MemberReport(
            "b", "2026-10",
            listOf(AccountTotal("joint", "Food", 1000.0), AccountTotal("hers", "Food", 450.0), AccountTotal("joint", "Home", 6000.0)),
            listOf(tx("j1", "joint", "Food", 1000.0, 3), tx("h1", "hers", "Food", 450.0, 1)), 0
        )
        val splits = HouseholdMath.splits(listOf(me, partner), myUid = "a")
        assertEquals(300.0, splits.getValue("Food").me, 0.0)
        assertEquals(450.0, splits.getValue("Food").partner, 0.0)
        assertEquals(1000.0, splits.getValue("Food").joint, 0.0)
        assertEquals(1750.0, splits.getValue("Food").total, 0.0)
        assertEquals(6000.0, splits.getValue("Home").joint, 0.0)

        // The partner's phone sees the same numbers the other way round.
        val theirs = HouseholdMath.splits(listOf(partner, me), myUid = "b")
        assertEquals(450.0, theirs.getValue("Food").me, 0.0)
        assertEquals(1750.0, theirs.getValue("Food").total, 0.0)

        val food = HouseholdMath.transactions(listOf(me, partner), "a", "Food")
        assertEquals(listOf("j1", "m1", "h1"), food.map { it.transaction.id })
        assertEquals(listOf(Payer.JOINT, Payer.ME, Payer.PARTNER), food.map { it.payer })
    }

    @Test
    fun `a partner sharing totals only adds amounts but no transactions`() {
        val partner = MemberReport("b", "2026-10", listOf(AccountTotal("hers", "Food", 450.0)), null, 0)
        assertEquals(450.0, HouseholdMath.splits(listOf(partner), "a").getValue("Food").partner, 0.0)
        assertTrue(HouseholdMath.transactions(listOf(partner), "a").isEmpty())
    }
}
