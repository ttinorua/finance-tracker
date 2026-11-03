package com.financetracker.app

import com.financetracker.app.data.ai.ChatAttachment
import com.financetracker.app.data.ai.WebSource
import com.financetracker.app.data.ai.advisor.AdvisorSnapshot
import com.financetracker.app.data.ai.advisor.AdvisorToolbox
import com.financetracker.app.data.ai.agent.ChatGptAgentSession
import com.financetracker.app.data.ai.agent.ToolCall
import com.financetracker.app.data.ai.chat.AssistantMemory
import com.financetracker.app.data.ai.chat.SavedChat
import com.financetracker.app.data.ai.chat.SavedChats
import com.financetracker.app.data.ai.chat.SavedMessage
import com.financetracker.app.data.ai.chatgpt.ChatGptService
import com.financetracker.app.ui.screens.ai.stripLinks
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Web search sources, attached files and the advisor's memory. */
class AssistantFeaturesTest {

    @Before
    fun clearMemory() = AssistantMemory.replaceAllForTest(emptyList())

    // ------------------------------------------------------------ web search

    private fun answer(vararg annotations: JSONObject) = JSONObject().put(
        "output",
        JSONArray()
            .put(JSONObject().put("type", "web_search_call").put("id", "ws_1"))
            .put(
                JSONObject().put("type", "message").put(
                    "content",
                    JSONArray().put(
                        JSONObject().put("type", "output_text").put("text", "Telenor is cheaper.")
                            .put("annotations", JSONArray().apply { annotations.forEach { put(it) } })
                    )
                )
            )
    )

    private fun citation(url: String, title: String) =
        JSONObject().put("type", "url_citation").put("url", url).put("title", title)

    @Test
    fun `cited pages become sources, each once, without OpenAI's tracking tag`() {
        val response = answer(
            citation("https://www.telenor.dk/mobil?utm_source=openai", "Mobilabonnementer"),
            citation("https://www.telenor.dk/mobil?utm_source=openai", "Mobilabonnementer"),
            citation("https://telia.dk/tv?a=1&utm_source=openai", "TV"),
            JSONObject().put("type", "file_citation").put("file_id", "f1")
        )
        val sources = ChatGptService.sourcesOf(response)
        assertEquals(
            listOf(WebSource("Mobilabonnementer", "https://www.telenor.dk/mobil"), WebSource("TV", "https://telia.dk/tv?a=1")),
            sources
        )
        assertEquals("telenor.dk", sources[0].site)
        assertEquals("Telenor is cheaper.", ChatGptService.textOf(response))
    }

    @Test
    fun `a plan without web search loses the tool and the AI is told to say so`() {
        val body = JSONObject()
            .put("instructions", "Be helpful.")
            .put("tools", ChatGptAgentSession.toolsJson(emptyList()).put(JSONObject().put("type", "web_search")))
            .put("input", JSONArray().put(JSONObject().put("role", "user").put("content", "Hi")).put(JSONObject().put("type", "web_search_call")))
        val refused = ChatGptService.Outcome.Failure(400, "subscription_sharing_unsupported_capability", "tools[1]", "Not supported")
        assertTrue(ChatGptService.refusesWebSearch(refused))
        assertTrue(ChatGptService.dropWebSearch(body))
        assertEquals(1, body.getJSONArray("tools").length())
        assertEquals("namespace", body.getJSONArray("tools").getJSONObject(0).getString("type"))
        assertEquals(1, body.getJSONArray("input").length())
        assertTrue(body.getString("instructions").contains("couldn't search"))
        // Nothing left to drop: the error is a real one.
        assertFalse(ChatGptService.dropWebSearch(body))
        assertFalse(ChatGptService.refusesWebSearch(ChatGptService.Outcome.Failure(401, null, null, "web_search")))
        assertFalse(ChatGptService.refusesWebSearch(ChatGptService.Outcome.Failure(400, null, "input", "bad input")))
    }

    @Test
    fun `citation links show as their label`() {
        assertEquals("Cheaper at Telia (telia.dk).", stripLinks("Cheaper at Telia ([telia.dk](https://telia.dk/tv?utm_source=openai))."))
    }

    // ------------------------------------------------------------ attachments

    @Test
    fun `an attached PDF and photo go with the question for ChatGPT`() {
        val input = JSONArray().put(JSONObject().put("role", "user").put("content", "What's in my bill?"))
        ChatGptAgentSession.withAttachment(input, ChatAttachment("Telenor.pdf", "application/pdf", "QUJD"))
        val pdf = input.getJSONObject(0).getJSONArray("content")
        assertEquals("input_file", pdf.getJSONObject(0).getString("type"))
        assertEquals("Telenor.pdf", pdf.getJSONObject(0).getString("filename"))
        assertEquals("data:application/pdf;base64,QUJD", pdf.getJSONObject(0).getString("file_data"))
        assertEquals("What's in my bill?", pdf.getJSONObject(1).getString("text"))

        val photo = JSONArray().put(JSONObject().put("role", "user").put("content", "And this?"))
        ChatGptAgentSession.withAttachment(photo, ChatAttachment("Photo.jpg", "image/jpeg", "QUJD"))
        assertEquals("data:image/jpeg;base64,QUJD", photo.getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("image_url"))
    }

    @Test
    fun `saved chats keep sources and the file's name`() {
        val chat = SavedChat(
            id = "a", title = "Telenor", createdAt = 1, updatedAt = 2,
            messages = listOf(
                SavedMessage(isUser = true, text = "What's in it?", attachmentName = "Telenor.pdf"),
                SavedMessage(
                    isUser = false, text = "2 lines", usedChatGptPlan = true, searchedWeb = true,
                    sources = listOf(WebSource("Telenor", "https://telenor.dk"))
                )
            )
        )
        assertEquals(listOf(chat), SavedChats.decode(SavedChats.encode(listOf(chat))))
    }

    // ------------------------------------------------------------ memory

    @Test
    fun `memories are added once, changed and removed`() {
        val first = AssistantMemory.add("  3 phones +   fiber on Telenor ", now = 10) as AssistantMemory.AddResult.Saved
        assertEquals("3 phones + fiber on Telenor", first.memory.text)
        assertTrue(AssistantMemory.add("3 PHONES + fiber on telenor") is AssistantMemory.AddResult.AlreadySaved)
        assertTrue(AssistantMemory.add("   ") is AssistantMemory.AddResult.Empty)
        val second = (AssistantMemory.add("Rent is due on the 1st") as AssistantMemory.AddResult.Saved).memory
        assertEquals(first.memory.id + 1, second.id)

        assertTrue(AssistantMemory.edit(first.memory.id, "4 phones + fiber on Telenor", now = 20))
        assertFalse(AssistantMemory.edit(99, "nothing"))
        assertEquals(listOf("4 phones + fiber on Telenor", "Rent is due on the 1st"), AssistantMemory.memories.value.map { it.text })

        assertEquals(listOf(second), AssistantMemory.remove(listOf(second.id, 99)))
        assertEquals(AssistantMemory.memories.value, AssistantMemory.decode(AssistantMemory.encode(AssistantMemory.memories.value)))
    }

    private fun call(box: AdvisorToolbox, name: String, args: JSONObject = JSONObject()) =
        runBlocking { box.execute(ToolCall("1", name, args)) }

    @Test
    fun `the advisor remembers, lists, changes and forgets`() {
        val box = AdvisorToolbox(AdvisorSnapshot(emptyList(), emptyList(), emptyMap(), emptyList(), now = 0))
        assertEquals("Nothing saved yet.", call(box, "list_memories"))
        assertTrue(call(box, "remember", JSONObject().put("text", "3 phones on Telenor")).startsWith("Saved as memory id 1"))
        assertTrue(call(box, "remember", JSONObject().put("text", "Netflix is shared with my sister")).startsWith("Saved as memory id 2"))
        assertTrue(call(box, "remember", JSONObject().put("text", "3 phones on Telenor")).startsWith("Already saved"))

        val list = call(box, "list_memories")
        assertTrue(list, list.contains("id 1") && list.contains("Netflix is shared"))

        val changed = call(box, "update_memory", JSONObject().put("id", 1).put("text", "4 phones on Telenor"))
        assertTrue(changed, changed.contains("to \"4 phones on Telenor\""))

        val forgot = call(box, "forget_memories", JSONObject().put("ids", JSONArray().put(2).put(7)))
        assertTrue(forgot, forgot.contains("Deleted: \"Netflix is shared with my sister\"") && forgot.contains("No memory with id 7"))
        assertEquals(listOf("4 phones on Telenor"), AssistantMemory.memories.value.map { it.text })
        // Nothing is waiting for Apply: memory changes happen straight away.
        assertTrue(box.cards.isEmpty())
    }
}
