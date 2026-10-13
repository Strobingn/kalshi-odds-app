package com.dirk.kalshiodds.data.supabase

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Stores the per-installation RLS capability encrypted by Android Keystore.
 *
 * The value is not a Kalshi credential and is only used to scope the paper
 * ledger row. There is deliberately no plaintext SharedPreferences fallback:
 * if Keystore is unavailable, cloud sync remains off rather than weakening
 * the row's access control. MinSdk 26 supports this AES-GCM configuration.
 */
data class PaperScalpIdentity(
    val deviceId: String,
    val secretHash: String
)

class PaperScalpIdentityStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadOrCreate(): Result<PaperScalpIdentity> = runCatching {
        val saved = prefs.getString(KEY_BLOB, null)
        val identity = saved?.let(::decrypt)?.let(::parse)
        identity ?: newIdentity().also { created ->
            check(prefs.edit().putString(KEY_BLOB, encrypt(encode(created))).commit()) {
                "paper scalp identity could not be persisted"
            }
        }
    }

    private fun newIdentity(): PaperScalpIdentity {
        val rawSecret = ByteArray(32).also(SecureRandom()::nextBytes)
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(rawSecret)
            .joinToString(separator = "") { "%02x".format(it) }
        return PaperScalpIdentity(UUID.randomUUID().toString(), hash)
    }

    private fun encode(identity: PaperScalpIdentity): String = "${identity.deviceId}|${identity.secretHash}"

    private fun parse(value: String): PaperScalpIdentity? {
        val pieces = value.split('|')
        if (pieces.size != 2) return null
        return runCatching { UUID.fromString(pieces[0]) }.getOrNull()?.let {
            pieces[1].takeIf { hash -> HASH.matches(hash) }?.let { hash ->
                PaperScalpIdentity(it.toString(), hash)
            }
        }
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val cipherText = cipher.doFinal(plain.encodeToByteArray())
        return Base64.encodeToString(cipher.iv + cipherText, Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): String? = runCatching {
        val packed = Base64.decode(blob, Base64.NO_WRAP)
        check(packed.size > IV_BYTES) { "paper scalp identity blob too short" }
        val iv = packed.copyOfRange(0, IV_BYTES)
        val cipherText = packed.copyOfRange(IV_BYTES, packed.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), javax.crypto.spec.GCMParameterSpec(128, iv))
        cipher.doFinal(cipherText).decodeToString()
    }.getOrNull()

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS = "paper_scalp_identity"
        const val KEY_BLOB = "encrypted_identity"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "chat_bitcoin_paper_scalp_sync_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        val HASH = Regex("^[0-9a-f]{64}$")
    }
}
