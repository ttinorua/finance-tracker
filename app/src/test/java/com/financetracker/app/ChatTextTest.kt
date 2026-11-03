package com.financetracker.app

import com.financetracker.app.ui.screens.ai.ChatBlock
import com.financetracker.app.ui.screens.ai.chatMarkdown
import com.financetracker.app.ui.screens.ai.cleanHtml
import com.financetracker.app.ui.screens.ai.parseChatBlocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTextTest {

    @Test
    fun `a markdown table becomes a table, with br as line breaks inside cells`() {
        val reply = """
            **Workflow you can follow**

            | Situation | Action |
            |-----------|--------|
            | Every month | Transfer 2 000 DKK from Sydbank to Lunar (automatic). |
            | Plan a trip | 1️⃣ Create a **Trip Goal**.<br>2️⃣ Transfer the amount.<br/>3️⃣ Add more later. |

            That's it.
        """.trimIndent()
        val blocks = parseChatBlocks(reply)
        assertEquals(3, blocks.size)
        assertEquals(ChatBlock.Paragraph("**Workflow you can follow**"), blocks[0])
        val table = blocks[1] as ChatBlock.Table
        assertEquals(listOf("Situation", "Action"), table.header)
        assertEquals(2, table.rows.size)
        assertEquals("1️⃣ Create a **Trip Goal**.\n2️⃣ Transfer the amount.\n3️⃣ Add more later.", table.rows[1][1])
        assertEquals(ChatBlock.Paragraph("That's it."), blocks[2])
    }

    @Test
    fun `rows with missing cells are padded and a pipe line without a rule stays text`() {
        val table = parseChatBlocks("| A | B | C |\n|:--|--:|---|\n| 1 | 2 |")[0] as ChatBlock.Table
        assertEquals(listOf("1", "2", ""), table.rows[0])
        assertTrue(parseChatBlocks("| not a table |")[0] is ChatBlock.Paragraph)
    }

    @Test
    fun `html is cleaned out of running text`() {
        assertEquals("a\nb c & d", cleanHtml("a<br>b <b>c</b> &amp; d"))
        val text = (parseChatBlocks("Spot subscriptions <br>• Compare months\n\n---\n\nDone")[0] as ChatBlock.Paragraph).text
        assertFalse(text.contains("<br>"))
        assertFalse(text.contains("---"))
    }

    @Test
    fun `markdown styling is applied and its symbols removed`() {
        val styled = chatMarkdown("# Plan\n- You don't care *why* the money was spent, only **that** you stay under")
        assertEquals("Plan\n•  You don't care why the money was spent, only that you stay under", styled.text)
        assertEquals(3, styled.spanStyles.size)
        // A lone ** with no partner doesn't show up either.
        assertEquals("Save 200 kr.", chatMarkdown("Save **200 kr.").text)
    }
}
