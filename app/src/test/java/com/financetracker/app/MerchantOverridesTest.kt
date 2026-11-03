package com.financetracker.app

import com.financetracker.app.data.ai.MerchantLogoLookup
import com.financetracker.app.data.prefs.MerchantOverride
import com.financetracker.app.data.prefs.MerchantOverrides
import com.financetracker.app.util.MerchantLogos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MerchantOverridesTest {
    private val rules = listOf(
        MerchantOverride("hbo", "HBO", "hbo.com", updatedAt = 1),
        MerchantOverride("hbo max", "HBO Max", null, updatedAt = 2),
        MerchantOverride("cafe rosa", null, "", updatedAt = 3)
    )

    @Test
    fun `a choice applies to every transaction containing the word`() {
        assertEquals("HBO", MerchantOverrides.nameFor("MCD 4471 HBO NORDIC", rules))
        assertEquals("HBO", MerchantOverrides.nameFor("Visa HBO 9920", rules))
        assertEquals("hbo.com", MerchantOverrides.domainFor("MCD 4471 HBO NORDIC", rules))
        assertNull(MerchantOverrides.nameFor("Netflix 123", rules))
    }

    @Test
    fun `the longer word wins, and each part falls back separately`() {
        // "hbo max" sets only the name, so the logo still comes from "hbo".
        assertEquals("HBO Max", MerchantOverrides.nameFor("HBO MAX NORDIC 12", rules))
        assertEquals("hbo.com", MerchantOverrides.domainFor("HBO MAX NORDIC 12", rules))
    }

    @Test
    fun `no logo means the category icon, and the person's choice beats the built-in list`() {
        assertNull(MerchantLogoLookup.domainFor("Dankort Cafe Rosa 22", emptyMap(), rules))
        assertEquals("HBO", MerchantLogoLookup.nameFor("Visa HBO 9920", emptyMap(), rules))
        // Without a choice, the built-in name is used.
        assertEquals("HBO Max", MerchantLogoLookup.nameFor("Visa HBO 9920", emptyMap(), emptyList()))
        assertEquals("max.com", MerchantLogoLookup.domainFor("Visa HBO 9920", emptyMap(), emptyList()))
    }

    @Test
    fun `the AI's name is used for unknown companies, else the bank text stays`() {
        val key = MerchantLogos.merchantKey("Dankort-køb SPIRI APS 1234")!!
        assertEquals("Spiri", MerchantLogoLookup.nameFor("Dankort-køb SPIRI APS 1234", mapOf(key to "Spiri"), emptyList()))
        assertNull(MerchantLogoLookup.nameFor("Dankort-køb SPIRI APS 1234", mapOf(key to ""), emptyList()))
    }

    @Test
    fun `choices survive being saved and read back (they travel in backups this way)`() {
        assertEquals(rules, MerchantOverrides.deserialize(MerchantOverrides.serialize(rules)))
        assertEquals(emptyList<MerchantOverride>(), MerchantOverrides.deserialize("not json"))
    }

    @Test
    fun `an own picture replaces the website logo and is kept when saved and read back`() {
        val withPhoto = rules + MerchantOverride("netto", "Netto", null, updatedAt = 4, photo = "AAAA")
        assertEquals("AAAA", MerchantOverrides.photoFor("Dankort NETTO 123", withPhoto))
        // No website logo on top of the picture.
        assertNull(MerchantLogoLookup.domainFor("Dankort NETTO 123", emptyMap(), withPhoto))
        assertNull(MerchantOverrides.photoFor("MCD 4471 HBO NORDIC", withPhoto))
        assertEquals(withPhoto, MerchantOverrides.deserialize(MerchantOverrides.serialize(withPhoto)))
        // A longer word with a website logo wins over a shorter word's picture.
        val both = listOf(
            MerchantOverride("hbo", null, null, updatedAt = 1, photo = "AAAA"),
            MerchantOverride("hbo max", null, "max.com", updatedAt = 2)
        )
        assertNull(MerchantOverrides.photoFor("HBO MAX NORDIC", both))
        assertEquals("max.com", MerchantOverrides.domainFor("HBO MAX NORDIC", both))
    }

    @Test
    fun `suggested word is the company keyword`() {
        assertEquals("hbo", MerchantLogos.suggestedKeyword("MCD 4471 HBO NORDIC"))
        assertEquals("spiri", MerchantLogos.suggestedKeyword("Dankort-køb SPIRI APS 1234"))
    }
}
