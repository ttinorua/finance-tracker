package com.financetracker.app

import com.financetracker.app.util.speakableText
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechTextTest {

    @Test
    fun `markdown marks and bullets are not read out`() {
        val answer = """
            ## Your week
            You spent **1,200 kr** on food:
            - Groceries 900 kr
            - Takeaway 300 kr
            See [details](https://example.com).
        """.trimIndent()
        assertEquals(
            "Your week. You spent 1,200 kr on food. Groceries 900 kr. Takeaway 300 kr. See details.",
            speakableText(answer)
        )
    }

    @Test
    fun `empty text stays empty`() {
        assertEquals("", speakableText("  \n "))
    }
}
