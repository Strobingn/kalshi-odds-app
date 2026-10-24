package com.dirk.kalshiodds.data.backup

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The settings backup file (1.20.7): every setting, switch, arena setting, the paper record and the API keys
 * (Groq, Cerebras, Gemini, Mistral, Kalshi), as JSON sealed with a passphrase: PBKDF2-HMAC-SHA256 (120,000) and
 * AES-256-GCM. Kept in Downloads/ClaudeBitcoin, so it outlives an uninstall. Pure apart from the crypto.
 *
 * Settings are stored typed, so any SharedPreferences or DataStore value comes back as the type it was:
 * {"t":"b|i|l|f|d|s|ss","v":...}.
 */
object SettingsBackup {
    const val MAGIC = "DHSET1"
    const val VERSION = 1
    const val FILE_NAME = "claude-bitcoin-settings.dhset"
    const val FOLDER = "ClaudeBitcoin"
    private const val ITERATIONS = 120_000
    private const val SALT_LEN = 16
    private const val IV_LEN = 12

    class WrongPassphrase : IllegalArgumentException("wrong passphrase")
    class BadFile : IllegalArgumentException("not a Claude Bitcoin settings backup")

    fun seal(json: String, passphrase: CharArray): ByteArray {
        require(passphrase.size >= 6) { "the passphrase must be at least 6 characters" }
        val salt = ByteArray(SALT_LEN).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(passphrase, salt), GCMParameterSpec(128, iv))
        val ct = c.doFinal(json.toByteArray(Charsets.UTF_8))
        return MAGIC.toByteArray(Charsets.US_ASCII) + byteArrayOf(VERSION.toByte()) + salt + iv + ct
    }

    fun open(bytes: ByteArray, passphrase: CharArray): String {
        val m = MAGIC.toByteArray(Charsets.US_ASCII)
        val head = m.size + 1
        if (bytes.size < head + SALT_LEN + IV_LEN + 16 || !bytes.copyOfRange(0, m.size).contentEquals(m)) throw BadFile()
        if (bytes[m.size].toInt() != VERSION) throw BadFile()
        val salt = bytes.copyOfRange(head, head + SALT_LEN)
        val iv = bytes.copyOfRange(head + SALT_LEN, head + SALT_LEN + IV_LEN)
        val ct = bytes.copyOfRange(head + SALT_LEN + IV_LEN, bytes.size)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(passphrase, salt), GCMParameterSpec(128, iv))
        return try {
            String(c.doFinal(ct), Charsets.UTF_8)
        } catch (e: AEADBadTagException) {
            throw WrongPassphrase()
        }
    }

    private fun key(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, ITERATIONS, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(bytes, "AES")
    }

    // ---------------------------------------------------------------- typed values

    /** A SharedPreferences or DataStore map as typed JSON. Values of other types are left out. */
    fun encode(map: Map<String, Any?>): JsonObject = buildJsonObject {
        for ((k, v) in map.toSortedMap()) {
            val t = when (v) {
                is Boolean -> "b"; is Int -> "i"; is Long -> "l"; is Float -> "f"; is Double -> "d"
                is String -> "s"; is Set<*> -> "ss"; else -> continue
            }
            put(k, buildJsonObject {
                put("t", t)
                when (v) {
                    is Boolean -> put("v", v)
                    is Number -> put("v", v)
                    is String -> put("v", v)
                    is Set<*> -> put("v", JsonArray(v.map { JsonPrimitive(it.toString()) }))
                }
            })
        }
    }

    /** The typed JSON back as a map of Boolean / Int / Long / Float / Double / String / Set<String>. */
    fun decode(o: JsonObject): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        for ((k, el) in o) {
            val e = (el as? JsonObject) ?: continue
            val t = (e["t"] as? JsonPrimitive)?.contentOrNull ?: continue
            val v = e["v"] ?: continue
            val p = v as? JsonPrimitive
            out[k] = when (t) {
                "b" -> p?.booleanOrNull
                "i" -> p?.contentOrNull?.toIntOrNull()
                "l" -> p?.contentOrNull?.toLongOrNull()
                "f" -> p?.contentOrNull?.toFloatOrNull()
                "d" -> p?.doubleOrNull
                "s" -> p?.contentOrNull
                "ss" -> (v as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet()
                else -> null
            } ?: continue
        }
        return out
    }

    /** A DataStore's contents as a map (key name → value). */
    fun fromPreferences(p: Preferences): Map<String, Any?> = p.asMap().mapKeys { it.key.name }

    /** Write [values] into a DataStore being edited, each under a key of its own type. */
    @Suppress("UNCHECKED_CAST")
    fun intoPreferences(p: MutablePreferences, values: Map<String, Any>) {
        for ((k, v) in values) when (v) {
            is Boolean -> p[booleanPreferencesKey(k)] = v
            is Int -> p[intPreferencesKey(k)] = v
            is Long -> p[longPreferencesKey(k)] = v
            is Float -> p[floatPreferencesKey(k)] = v
            is Double -> p[doublePreferencesKey(k)] = v
            is String -> p[stringPreferencesKey(k)] = v
            is Set<*> -> p[stringSetPreferencesKey(k)] = v as Set<String>
        }
    }

    /** Write [values] into SharedPreferences ([clear] first: the file becomes exactly the backup). */
    @Suppress("UNCHECKED_CAST")
    fun intoShared(sp: android.content.SharedPreferences, values: Map<String, Any>, clear: Boolean = true) {
        val e = sp.edit()
        if (clear) e.clear()
        for ((k, v) in values) when (v) {
            is Boolean -> e.putBoolean(k, v)
            is Int -> e.putInt(k, v)
            is Long -> e.putLong(k, v)
            is Float -> e.putFloat(k, v)
            is Double -> e.putFloat(k, v.toFloat())
            is String -> e.putString(k, v)
            is Set<*> -> e.putStringSet(k, v as Set<String>)
        }
        e.commit()
    }

    val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): JsonObject = json.parseToJsonElement(text).jsonObject
    fun str(o: JsonObject, k: String): String? = (o[k] as? JsonPrimitive)?.contentOrNull
    fun obj(o: JsonObject, k: String): JsonObject? = o[k] as? JsonObject
    fun prim(o: JsonObject, k: String): JsonPrimitive? = o[k]?.jsonPrimitive
}
