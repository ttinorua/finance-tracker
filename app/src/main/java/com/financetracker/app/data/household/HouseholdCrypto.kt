package com.financetracker.app.data.household

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The household's end-to-end encryption. Everything the two phones share is encrypted with one
 * random household key (AES-256-GCM) before it leaves the phone, so the server only ever stores
 * data it can't read. The key reaches the partner inside the invite: wrapped with a key derived
 * from the invite code, and filed under a hash of the code, so the server never sees the code
 * itself either.
 */
object HouseholdCrypto {
    private val random = SecureRandom()

    /** Letters and digits that can't be mixed up when read aloud or typed (no 0/O, 1/I/L). */
    private const val CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    private const val PBKDF2_ROUNDS = 150_000

    fun newHouseholdKey(): ByteArray = ByteArray(32).also(random::nextBytes)

    /** A fresh invite code, e.g. "K7QM-2XPA-9F" (10 characters, about 50 bits). */
    fun newInviteCode(): String {
        val chars = (1..10).map { CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)] }.joinToString("")
        return "${chars.substring(0, 4)}-${chars.substring(4, 8)}-${chars.substring(8)}"
    }

    /** A code as typed: upper-cased, without spaces or dashes. (Codes never contain the
     * look-alikes 0/O/1/I/L, so there's nothing to confuse.) */
    fun normalizeCode(code: String): String = code.uppercase().filter { it.isLetterOrDigit() }

    /** Where the invite is filed: a hash of the code, so the code itself never reaches the server. */
    fun inviteId(code: String): String = sha256Hex("household-invite:" + normalizeCode(code))

    /** The household key, encrypted with a key derived from the invite code. */
    fun wrapKey(householdKey: ByteArray, code: String): WrappedKey {
        val salt = ByteArray(16).also(random::nextBytes)
        return WrappedKey(b64(salt), encrypt(deriveKey(code, salt), householdKey))
    }

    /** The household key from an invite, or null when the code doesn't fit. */
    fun unwrapKey(wrapped: WrappedKey, code: String): ByteArray? =
        runCatching { decrypt(deriveKey(code, unb64(wrapped.salt)), wrapped.data) }.getOrNull()

    /** [plain] encrypted with [key]: base64 of a random 12-byte IV followed by the ciphertext. */
    fun encrypt(key: ByteArray, plain: ByteArray): String {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return b64(iv + cipher.doFinal(plain))
    }

    fun decrypt(key: ByteArray, sealed: String): ByteArray {
        val bytes = unb64(sealed)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, bytes, 0, 12))
        return cipher.doFinal(bytes, 12, bytes.size - 12)
    }

    fun encryptText(key: ByteArray, text: String): String = encrypt(key, text.toByteArray(Charsets.UTF_8))
    fun decryptText(key: ByteArray, sealed: String): String = String(decrypt(key, sealed), Charsets.UTF_8)

    /** A stable private tag for [value] (an IBAN, a budget line's category), the same on both
     * phones but meaningless to the server. */
    fun tag(key: ByteArray, value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(value.toByteArray(Charsets.UTF_8)).take(16).joinToString("") { "%02x".format(it) }
    }

    private fun deriveKey(code: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(normalizeCode(code).toCharArray(), salt, PBKDF2_ROUNDS, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    private fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    fun unb64(text: String): ByteArray = Base64.getDecoder().decode(text)
}

/** The household key as stored in an invite. */
data class WrappedKey(val salt: String, val data: String)
