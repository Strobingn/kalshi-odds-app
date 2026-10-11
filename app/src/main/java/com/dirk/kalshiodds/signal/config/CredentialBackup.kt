package com.dirk.kalshiodds.signal.config

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Passphrase-encrypted credential file for SAF backup / restore.
 * Format: magic | version | salt(16) | iv(12) | ciphertext+tag
 * PBKDF2-HMAC-SHA256 (120_000) + AES-256-GCM.
 *
 * Payload is `keyId\\npem` plus an optional demo block
 * (`\\n\\nDHDEMO1\\ndemoId\\ndemoPem`) so a Settings export can restore
 * both the live Kalshi key and the demo key after uninstall.
 */
object CredentialBackup {
    const val MAGIC = "DHCRED1"
    const val VERSION: Byte = 1
    const val ITERATIONS = 120_000
    const val DEMO_MARK = "\n\nDHDEMO1\n"
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val KEY_LEN_BITS = 256

    class WrongPassphrase : IllegalArgumentException("wrong passphrase")
    class BadFile : IllegalArgumentException("not a Claude Bitcoin credential backup")

    data class Contents(
        val keyId: String,
        val pem: String,
        val demoKeyId: String = "",
        val demoPem: String = ""
    )

    fun encrypt(
        keyId: String,
        pem: String,
        passphrase: CharArray,
        demoKeyId: String = "",
        demoPem: String = ""
    ): ByteArray {
        require(passphrase.isNotEmpty()) { "passphrase required" }
        val live = "${keyId.trim()}\n${pem.trim()}"
        val payloadText = if (demoKeyId.isNotBlank() && demoPem.isNotBlank()) {
            live + DEMO_MARK + "${demoKeyId.trim()}\n${demoPem.trim()}"
        } else {
            live
        }
        val payload = payloadText.toByteArray(Charsets.UTF_8)
        val salt = ByteArray(SALT_LEN).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        val key = derive(passphrase, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val ct = cipher.doFinal(payload)
        val magic = MAGIC.toByteArray(Charsets.US_ASCII)
        return magic + byteArrayOf(VERSION) + salt + iv + ct
    }

    fun decrypt(bytes: ByteArray, passphrase: CharArray): Pair<String, String> {
        val c = decryptAll(bytes, passphrase)
        return c.keyId to c.pem
    }

    fun decryptAll(bytes: ByteArray, passphrase: CharArray): Contents {
        val magic = MAGIC.toByteArray(Charsets.US_ASCII)
        if (bytes.size < magic.size + 1 + SALT_LEN + IV_LEN + 16) throw BadFile()
        if (!bytes.copyOfRange(0, magic.size).contentEquals(magic)) throw BadFile()
        var i = magic.size
        val ver = bytes[i]; i += 1
        if (ver != VERSION) throw BadFile()
        val salt = bytes.copyOfRange(i, i + SALT_LEN); i += SALT_LEN
        val iv = bytes.copyOfRange(i, i + IV_LEN); i += IV_LEN
        val ct = bytes.copyOfRange(i, bytes.size)
        val key = derive(passphrase, salt)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            parsePayload(cipher.doFinal(ct).toString(Charsets.UTF_8))
        } catch (_: AEADBadTagException) {
            throw WrongPassphrase()
        } catch (e: Exception) {
            if (e is WrongPassphrase || e is BadFile) throw e
            throw WrongPassphrase()
        }
    }

    fun parsePayload(raw: String): Contents {
        val parts = raw.split(DEMO_MARK, limit = 2)
        val live = parts[0]
        val nl = live.indexOf('\n')
        if (nl <= 0) throw BadFile()
        val demo = if (parts.size > 1) {
            val d = parts[1]
            val dnl = d.indexOf('\n')
            if (dnl <= 0) throw BadFile()
            d.substring(0, dnl) to d.substring(dnl + 1)
        } else {
            "" to ""
        }
        return Contents(
            keyId = live.substring(0, nl),
            pem = live.substring(nl + 1),
            demoKeyId = demo.first,
            demoPem = demo.second
        )
    }

    fun maskedKeyId(keyId: String): String {
        val t = keyId.trim()
        if (t.length < 4) return if (t.isEmpty()) "(none)" else "····"
        return "····${t.takeLast(4)}"
    }

    private fun derive(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, ITERATIONS, KEY_LEN_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val raw = factory.generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(raw, "AES")
    }
}
