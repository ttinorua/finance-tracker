package com.financetracker.app.chatgpt

import com.financetracker.app.data.ai.agent.ChatGptAgentSession
import com.financetracker.app.data.ai.agent.ToolSpec
import com.financetracker.app.data.ai.chatgpt.ChatGptService
import com.financetracker.app.data.ai.chatgpt.IdTokenVerifier
import com.financetracker.app.data.ai.chatgpt.LoopbackReceiver
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.concurrent.thread

class ChatGptTest {

    private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun unsigned(n: BigInteger): ByteArray = n.toByteArray().let { if (it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
    private fun pad32(b: ByteArray) = ByteArray(32 - b.size) + b

    private val payload = JSONObject()
        .put("iss", "https://auth.openai.com").put("aud", "oaiapp_1").put("sub", "user-1")
        .put("email", "me@example.com").put("nonce", "n1").put("exp", 2_000_000_000L)

    private fun rsaToken(claims: JSONObject = payload): Pair<String, JSONObject> {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val head = b64("""{"alg":"RS256","kid":"k1"}""".toByteArray())
        val body = b64(claims.toString().toByteArray())
        val sig = Signature.getInstance("SHA256withRSA").run { initSign(pair.private); update("$head.$body".toByteArray()); sign() }
        val pub = pair.public as RSAPublicKey
        val jwk = JSONObject().put("kty", "RSA").put("kid", "k1").put("n", b64(unsigned(pub.modulus))).put("e", b64(unsigned(pub.publicExponent)))
        return "$head.$body.${b64(sig)}" to JSONObject().put("keys", JSONArray().put(jwk))
    }

    @Test
    fun `a correctly signed ID token for this sign-in is accepted`() {
        val (token, jwks) = rsaToken()
        val claims = IdTokenVerifier.verify(token, jwks, "https://auth.openai.com", "oaiapp_1", "n1", 1_900_000_000L)
        assertEquals("user-1", claims.subject)
        assertEquals("me@example.com", claims.email)
    }

    @Test
    fun `wrong nonce, app, issuer, expiry or a tampered token are rejected`() {
        val (token, jwks) = rsaToken()
        fun rejects(block: () -> Unit) = try { block(); fail("should be rejected") } catch (e: IdTokenVerifier.InvalidIdTokenException) { }
        rejects { IdTokenVerifier.verify(token, jwks, "https://auth.openai.com", "oaiapp_1", "other", 1_900_000_000L) }
        rejects { IdTokenVerifier.verify(token, jwks, "https://auth.openai.com", "oaiapp_2", "n1", 1_900_000_000L) }
        rejects { IdTokenVerifier.verify(token, jwks, "https://evil.example", "oaiapp_1", "n1", 1_900_000_000L) }
        rejects { IdTokenVerifier.verify(token, jwks, "https://auth.openai.com", "oaiapp_1", "n1", 2_100_000_000L) }
        val parts = token.split('.')
        val forged = b64(JSONObject(payload.toString()).put("sub", "someone-else").toString().toByteArray())
        rejects { IdTokenVerifier.verify("${parts[0]}.$forged.${parts[2]}", jwks, "https://auth.openai.com", "oaiapp_1", "n1", 1_900_000_000L) }
    }

    @Test
    fun `ES256 tokens are checked too`() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val head = b64("""{"alg":"ES256","kid":"e1"}""".toByteArray())
        val body = b64(payload.toString().toByteArray())
        val der = Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update("$head.$body".toByteArray()); sign() }
        // DER → raw r‖s, as JWTs carry it.
        var i = 2
        val rLen = der[i + 1].toInt(); val r = der.copyOfRange(i + 2, i + 2 + rLen); i += 2 + rLen
        val sLen = der[i + 1].toInt(); val s = der.copyOfRange(i + 2, i + 2 + sLen)
        val raw = pad32(unsigned(BigInteger(r))) + pad32(unsigned(BigInteger(s)))
        val pub = pair.public as ECPublicKey
        val jwk = JSONObject().put("kty", "EC").put("kid", "e1").put("crv", "P-256")
            .put("x", b64(pad32(unsigned(pub.w.affineX)))).put("y", b64(pad32(unsigned(pub.w.affineY))))
        val claims = IdTokenVerifier.verify("$head.$body.${b64(raw)}", JSONObject().put("keys", JSONArray().put(jwk)),
            "https://auth.openai.com", "oaiapp_1", "n1", 1_900_000_000L)
        assertEquals("user-1", claims.subject)
    }

    @Test
    fun `the stream only counts as answered when it completes`() {
        val done = ChatGptService.parseStream(sequenceOf(
            "event: response.output_text.delta", """data: {"type":"response.output_text.delta","delta":"Hi"}""", "",
            """data: {"type":"response.completed","response":{"output":[{"type":"reasoning","id":"rs_1"},""" +
                """{"type":"message","role":"assistant","content":[{"type":"output_text","text":"Hello "},{"type":"output_text","text":"there"}]}]}}"""
        ))
        assertTrue(done is ChatGptService.Outcome.Done)
        assertEquals("Hello there", ChatGptService.textOf((done as ChatGptService.Outcome.Done).response))

        val limit = ChatGptService.parseStream(sequenceOf(
            """data: {"type":"response.failed","response":{"error":{"code":"subscription_sharing_usage_limit_exceeded","message":"limit"}}}"""
        )) as ChatGptService.Outcome.Failure
        assertEquals("subscription_sharing_usage_limit_exceeded", limit.code)
        assertTrue(limit.userMessage().contains("Manage usage"))

        val cut = ChatGptService.parseStream(sequenceOf("""data: {"type":"response.output_text.delta","delta":"Hi"}"""))
        assertTrue(cut is ChatGptService.Outcome.Failure)
    }

    @Test
    fun `an answer is kept when the final event has an empty output`() {
        val call = ChatGptService.parseStream(sequenceOf(
            """data: {"type":"response.output_item.done","output_index":0,"item":{"type":"function_call","call_id":"c1","name":"get_overview","arguments":"{}"}}""",
            """data: {"type":"response.completed","response":{"output":[]}}"""
        )) as ChatGptService.Outcome.Done
        assertEquals("function_call", call.response.getJSONArray("output").getJSONObject(0).getString("type"))

        val streamedText = ChatGptService.parseStream(sequenceOf(
            """data: {"type":"response.output_text.delta","delta":"Your week "}""",
            """data: {"type":"response.output_text.delta","delta":"looks fine."}""",
            """data: {"type":"response.completed","response":{"output":[]}}"""
        )) as ChatGptService.Outcome.Done
        assertEquals("Your week looks fine.", ChatGptService.textOf(streamedText.response))

        val full = ChatGptService.parseStream(sequenceOf(
            """data: {"type":"response.output_text.delta","delta":"ignored"}""",
            """data: {"type":"response.completed","response":{"output":[{"type":"message","content":[{"type":"output_text","text":"Hello"}]}]}}"""
        )) as ChatGptService.Outcome.Done
        assertEquals("Hello", ChatGptService.textOf(full.response))
    }

    @Test
    fun `errors before the stream are read in both shapes`() {
        val structured = ChatGptService.errorOutcome(400,
            """{"error":{"code":"subscription_sharing_unsupported_capability","param":"reasoning.effort","message":"no"}}""")
        assertEquals("subscription_sharing_unsupported_capability", structured.code)
        assertEquals("reasoning.effort", structured.param)
        val detail = ChatGptService.errorOutcome(403, """{"detail":"Region not allowed"}""")
        assertNull(detail.code)
        assertEquals("Region not allowed", detail.message)
        assertTrue(ChatGptService.errorOutcome(403,
            """{"error":{"code":"subscription_sharing_user_not_eligible","message":"x"}}""").userMessage().contains("Plus"))
    }

    @Test
    fun `the first listed model is used`() {
        val json = JSONObject("""{"models":[{"slug":"hidden","visibility":"hide"},{"slug":"gpt-a","visibility":"list"},{"slug":"gpt-b","visibility":"list"}]}""")
        assertEquals("gpt-a", ChatGptService.pickModel(json))
        assertNull(ChatGptService.pickModel(JSONObject("{}")))
    }

    @Test
    fun `requests follow the plan route's rules`() {
        val body = ChatGptService.request("Be brief.", listOf(com.financetracker.app.data.ai.ChatTurn(true, "Hi")), quick = true, model = "gpt-a")
        assertEquals(false, body.getBoolean("store"))
        assertEquals(true, body.getBoolean("stream"))
        assertEquals("Be brief.", body.getString("instructions"))
        assertEquals("user", body.getJSONArray("input").getJSONObject(0).getString("role"))
        assertTrue(!body.has("temperature") && !body.has("max_output_tokens"))
        val tools = ChatGptAgentSession.toolsJson(listOf(ToolSpec("find", "Finds", JSONObject().put("type", "object"))))
        val ns = tools.getJSONObject(0)
        assertEquals("namespace", ns.getString("type"))
        assertEquals("find", ns.getJSONArray("tools").getJSONObject(0).getString("name"))
    }

    @Test
    fun `the in-app receiver catches the browser's return and ignores other pages`() {
        val receiver = LoopbackReceiver.open(0)
        val port = receiver.port
        var result: Map<String, String>? = null
        val waiter = thread { result = receiver.awaitCallback(10_000) { "<p>ok</p>" } }
        fun get(path: String): Int = (URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection).run {
            val code = responseCode; disconnect(); code
        }
        Thread.sleep(200)
        assertEquals(404, get("/favicon.ico"))
        assertEquals(200, get("/auth/callback?code=abc&state=s%201&client_id=oaiapp_9"))
        waiter.join(5_000)
        receiver.close()
        assertEquals(mapOf("code" to "abc", "state" to "s 1", "client_id" to "oaiapp_9"), result)
    }
}
