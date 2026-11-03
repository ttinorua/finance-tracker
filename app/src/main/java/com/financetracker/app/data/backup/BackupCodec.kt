package com.financetracker.app.data.backup

import com.financetracker.app.data.db.entity.Account
import com.financetracker.app.data.db.entity.Category
import com.financetracker.app.data.db.entity.Receipt
import com.financetracker.app.data.db.entity.ReceiptItem
import com.financetracker.app.data.db.entity.Transaction
import com.financetracker.app.data.db.entity.TransactionType
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Everything a full backup holds: the whole database (with its original ids, so every setting
 * that refers to an account or category by id still points at the right one) and the app's
 * settings, budgets, learned rules and — in a password-protected backup — bank connections. */
data class BackupContents(
    val createdAt: Long,
    val accounts: List<Account>,
    val categories: List<Category>,
    val transactions: List<Transaction>,
    val receipts: List<Receipt> = emptyList(),
    val receiptItems: List<ReceiptItem> = emptyList(),
    /** SharedPreferences values: String, Int, Long, Float, Boolean or Set<String>. */
    val prefs: Map<String, Any>,
    val includesBankConnections: Boolean
)

class BackupPasswordRequiredException : Exception("This backup is protected with a password.")
class WrongBackupPasswordException : Exception("Wrong password for this backup.")
class InvalidBackupException(message: String) : Exception(message)

/**
 * Reads and writes the backup file. Without a password it's plain JSON; with one it's the same
 * JSON encrypted with AES-256-GCM under a key derived from the password (PBKDF2-HMAC-SHA256), so
 * the file is useless to anyone who finds it without the password — and a wrong password is
 * detected rather than producing garbage.
 */
object BackupCodec {

    const val FORMAT = "financetracker-backup"
    const val VERSION = 1

    private val ENCRYPTED_MAGIC = "FTBK-ENC1".toByteArray(Charsets.US_ASCII)
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128
    private const val PBKDF2_ITERATIONS = 150_000

    fun isEncrypted(bytes: ByteArray): Boolean =
        bytes.size >= ENCRYPTED_MAGIC.size && bytes.copyOfRange(0, ENCRYPTED_MAGIC.size).contentEquals(ENCRYPTED_MAGIC)

    fun encode(contents: BackupContents, password: CharArray?): ByteArray {
        val json = toJson(contents).toString().toByteArray(Charsets.UTF_8)
        if (password == null || password.isEmpty()) return json
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(TAG_BITS, iv))
        return ENCRYPTED_MAGIC + salt + iv + cipher.doFinal(json)
    }

    fun decode(bytes: ByteArray, password: CharArray?): BackupContents {
        val json = if (isEncrypted(bytes)) {
            if (password == null || password.isEmpty()) throw BackupPasswordRequiredException()
            val headerEnd = ENCRYPTED_MAGIC.size + SALT_BYTES + IV_BYTES
            if (bytes.size <= headerEnd) throw InvalidBackupException("The backup file is damaged.")
            val salt = bytes.copyOfRange(ENCRYPTED_MAGIC.size, ENCRYPTED_MAGIC.size + SALT_BYTES)
            val iv = bytes.copyOfRange(ENCRYPTED_MAGIC.size + SALT_BYTES, headerEnd)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(TAG_BITS, iv))
            try {
                cipher.doFinal(bytes, headerEnd, bytes.size - headerEnd)
            } catch (e: AEADBadTagException) {
                throw WrongBackupPasswordException()
            }
        } else {
            bytes
        }
        val root = try {
            JSONObject(String(json, Charsets.UTF_8))
        } catch (e: Exception) {
            throw InvalidBackupException("This isn't a Finance Tracker backup file.")
        }
        if (root.optString("format") != FORMAT) throw InvalidBackupException("This isn't a Finance Tracker backup file.")
        if (root.optInt("version") > VERSION) {
            throw InvalidBackupException("This backup was made by a newer version of the app. Update the app first.")
        }
        return fromJson(root)
    }

    private fun deriveKey(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, PBKDF2_ITERATIONS, KEY_BITS)
        try {
            val keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(keyBytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun toJson(contents: BackupContents): JSONObject = JSONObject().apply {
        put("format", FORMAT)
        put("version", VERSION)
        put("createdAt", contents.createdAt)
        put("includesBankConnections", contents.includesBankConnections)
        put("accounts", JSONArray().apply {
            contents.accounts.forEach { a ->
                put(JSONObject().apply {
                    put("id", a.id)
                    put("name", a.name)
                    put("initialBalance", a.initialBalance)
                    put("currencyCode", a.currencyCode)
                })
            }
        })
        put("categories", JSONArray().apply {
            contents.categories.forEach { c ->
                put(JSONObject().apply {
                    put("id", c.id)
                    put("name", c.name)
                    put("mainCategory", c.mainCategory)
                    put("type", c.type.name)
                    put("colorHex", c.colorHex)
                })
            }
        })
        put("transactions", JSONArray().apply {
            contents.transactions.forEach { t ->
                put(JSONObject().apply {
                    put("id", t.id)
                    put("amount", t.amount)
                    put("type", t.type.name)
                    put("accountId", t.accountId)
                    put("categoryId", t.categoryId ?: JSONObject.NULL)
                    put("date", t.date)
                    put("note", t.note)
                    put("createdAt", t.createdAt)
                })
            }
        })
        put("receipts", JSONArray().apply {
            contents.receipts.forEach { r ->
                put(JSONObject().apply {
                    put("id", r.id)
                    put("source", r.source)
                    put("externalId", r.externalId)
                    put("storeName", r.storeName)
                    put("purchasedAt", r.purchasedAt)
                    put("total", r.total)
                    put("discount", r.discount)
                    put("transactionId", r.transactionId ?: JSONObject.NULL)
                    put("split", r.split)
                    put("createdAt", r.createdAt)
                })
            }
        })
        put("receiptItems", JSONArray().apply {
            contents.receiptItems.forEach { i ->
                put(JSONObject().apply {
                    put("id", i.id)
                    put("receiptId", i.receiptId)
                    put("position", i.position)
                    put("name", i.name)
                    put("quantity", i.quantity)
                    put("amount", i.amount)
                    put("discount", i.discount)
                    put("categoryId", i.categoryId ?: JSONObject.NULL)
                })
            }
        })
        put("prefs", JSONObject().apply {
            contents.prefs.forEach { (key, value) ->
                val typed = when (value) {
                    is String -> "s" to value
                    is Int -> "i" to value
                    is Long -> "l" to value
                    is Float -> "f" to value.toDouble()
                    is Boolean -> "b" to value
                    is Set<*> -> "ss" to JSONArray(value.map { it.toString() })
                    else -> null
                }
                if (typed != null) put(key, JSONObject().put("t", typed.first).put("v", typed.second))
            }
        })
    }

    private fun fromJson(root: JSONObject): BackupContents {
        fun JSONObject.array(name: String): List<JSONObject> {
            val array = optJSONArray(name) ?: return emptyList()
            return (0 until array.length()).map { array.getJSONObject(it) }
        }
        val accounts = root.array("accounts").map {
            Account(
                id = it.getLong("id"),
                name = it.getString("name"),
                initialBalance = it.optDouble("initialBalance", 0.0),
                currencyCode = it.optString("currencyCode", "DKK")
            )
        }
        val categories = root.array("categories").map {
            Category(
                id = it.getLong("id"),
                name = it.getString("name"),
                mainCategory = it.optString("mainCategory", "Uncategorized"),
                type = TransactionType.valueOf(it.getString("type")),
                colorHex = it.optString("colorHex", "#607D8B")
            )
        }
        val transactions = root.array("transactions").map {
            Transaction(
                id = it.getLong("id"),
                amount = it.getDouble("amount"),
                type = TransactionType.valueOf(it.getString("type")),
                accountId = it.getLong("accountId"),
                categoryId = if (it.isNull("categoryId")) null else it.getLong("categoryId"),
                date = it.getLong("date"),
                note = it.optString("note", ""),
                createdAt = it.optLong("createdAt", it.getLong("date"))
            )
        }
        // Backups from before receipts simply have none.
        val receipts = root.array("receipts").map {
            Receipt(
                id = it.getLong("id"),
                source = it.getString("source"),
                externalId = it.getString("externalId"),
                storeName = it.optString("storeName"),
                purchasedAt = it.getLong("purchasedAt"),
                total = it.getDouble("total"),
                discount = it.optDouble("discount", 0.0),
                transactionId = if (it.isNull("transactionId")) null else it.getLong("transactionId"),
                split = it.optBoolean("split", false),
                createdAt = it.optLong("createdAt", it.getLong("purchasedAt"))
            )
        }
        val receiptItems = root.array("receiptItems").map {
            ReceiptItem(
                id = it.getLong("id"),
                receiptId = it.getLong("receiptId"),
                position = it.optInt("position", 0),
                name = it.optString("name"),
                quantity = it.optDouble("quantity", 1.0),
                amount = it.getDouble("amount"),
                discount = it.optDouble("discount", 0.0),
                categoryId = if (it.isNull("categoryId")) null else it.getLong("categoryId")
            )
        }
        val prefsJson = root.optJSONObject("prefs") ?: JSONObject()
        val prefs = mutableMapOf<String, Any>()
        prefsJson.keys().forEach { key ->
            val entry = prefsJson.getJSONObject(key)
            val value: Any? = when (entry.getString("t")) {
                "s" -> entry.getString("v")
                "i" -> entry.getInt("v")
                "l" -> entry.getLong("v")
                "f" -> entry.getDouble("v").toFloat()
                "b" -> entry.getBoolean("v")
                "ss" -> entry.getJSONArray("v").let { array -> (0 until array.length()).map { array.getString(it) }.toSet() }
                else -> null
            }
            if (value != null) prefs[key] = value
        }
        return BackupContents(
            createdAt = root.optLong("createdAt"),
            accounts = accounts,
            categories = categories,
            transactions = transactions,
            receipts = receipts,
            receiptItems = receiptItems,
            prefs = prefs,
            includesBankConnections = root.optBoolean("includesBankConnections", false)
        )
    }
}
