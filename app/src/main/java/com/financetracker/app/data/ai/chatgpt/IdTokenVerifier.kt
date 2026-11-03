package com.financetracker.app.data.ai.chatgpt

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/**
 * Checks the ID token OpenAI returns after "Sign in with ChatGPT": its signature against OpenAI's
 * published keys (RS256 or ES256), and that it was issued by OpenAI, for this app's client, for
 * this sign-in attempt (nonce), and hasn't expired. Plain JVM code, so it's unit-tested.
 */
internal object IdTokenVerifier {

    data class Claims(val subject: String, val email: String?)

    class InvalidIdTokenException(message: String) : Exception(message)

    fun verify(
        idToken: String,
        jwks: JSONObject,
        issuer: String,
        audience: String,
        nonce: String,
        nowSeconds: Long
    ): Claims {
        val parts = idToken.split('.')
        if (parts.size != 3) fail("not a signed token")
        val header = JSONObject(String(decode(parts[0]), Charsets.UTF_8))
        val payload = JSONObject(String(decode(parts[1]), Charsets.UTF_8))
        val alg = header.optString("alg")
        val key = findKey(jwks, header.optString("kid"), alg)
        val signed = "${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII)
        val signature = decode(parts[2])
        val valid = when (alg) {
            "RS256" -> Signature.getInstance("SHA256withRSA").run {
                initVerify(rsaKey(key)); update(signed); verify(signature)
            }
            "ES256" -> Signature.getInstance("SHA256withECDSA").run {
                initVerify(ecKey(key)); update(signed); verify(rawToDer(signature))
            }
            else -> fail("unsupported signature type $alg")
        }
        if (!valid) fail("signature doesn't match")
        if (payload.optString("iss") != issuer) fail("wrong issuer")
        val audiences = when (val aud = payload.opt("aud")) {
            is String -> listOf(aud)
            is JSONArray -> (0 until aud.length()).map { aud.optString(it) }
            else -> emptyList()
        }
        if (audience !in audiences) fail("issued for another app")
        // A minute of leeway for the phone's clock.
        if (payload.optLong("exp", 0) < nowSeconds - 60) fail("expired")
        if (payload.optString("nonce") != nonce) fail("doesn't belong to this sign-in")
        val subject = payload.optString("sub").ifBlank { fail("no account id") }
        return Claims(subject, payload.optString("email").ifBlank { null })
    }

    private fun findKey(jwks: JSONObject, kid: String, alg: String): JSONObject {
        val keys = jwks.optJSONArray("keys") ?: fail("no published keys")
        val all = (0 until keys.length()).mapNotNull { keys.optJSONObject(it) }
        val wantedType = if (alg == "ES256") "EC" else "RSA"
        return all.firstOrNull { it.optString("kid") == kid && kid.isNotEmpty() }
            ?: all.singleOrNull { it.optString("kty") == wantedType }
            ?: fail("signing key not found")
    }

    private fun rsaKey(jwk: JSONObject): PublicKey = KeyFactory.getInstance("RSA").generatePublic(
        RSAPublicKeySpec(BigInteger(1, decode(jwk.getString("n"))), BigInteger(1, decode(jwk.getString("e"))))
    )

    private fun ecKey(jwk: JSONObject): PublicKey {
        val params = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)
        val point = ECPoint(BigInteger(1, decode(jwk.getString("x"))), BigInteger(1, decode(jwk.getString("y"))))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, params))
    }

    /** JWTs carry an ES256 signature as r‖s (64 bytes); Java's verifier wants it DER-encoded. */
    internal fun rawToDer(raw: ByteArray): ByteArray {
        if (raw.size != 64) fail("malformed signature")
        fun integer(bytes: ByteArray): ByteArray {
            val start = bytes.indexOfFirst { it != 0.toByte() }
            val trimmed = if (start < 0) byteArrayOf(0) else bytes.copyOfRange(start, bytes.size)
            val value = if (trimmed[0] < 0) byteArrayOf(0) + trimmed else trimmed
            return byteArrayOf(0x02, value.size.toByte()) + value
        }
        val body = integer(raw.copyOfRange(0, 32)) + integer(raw.copyOfRange(32, 64))
        return byteArrayOf(0x30, body.size.toByte()) + body
    }

    private fun decode(part: String): ByteArray =
        runCatching { Base64.getUrlDecoder().decode(part) }.getOrElse { fail("unreadable") }

    private fun fail(reason: String): Nothing = throw InvalidIdTokenException("ChatGPT's sign-in answer was rejected: $reason.")
}
