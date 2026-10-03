package com.dirk.kalshiodds.signal.config

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * Device-local storage for Kalshi API Key ID + private key PEM.
 * PEM is never written to logs.
 *
 * EncryptedSharedPreferences is preferred. If the Android Keystore is
 * unavailable, previously saved keys can still be read from the private
 * file, but new live trading keys are refused. Cold start still reads
 * both files so a previous fallback save is not lost when encryption
 * later succeeds.
 */
class SecureCredentialStore(context: Context) {

    val keystoreInvalidated: Boolean
        get() = lastKeystoreInvalidated

    private val opened = openStore(context.applicationContext)
    private val prefs: SharedPreferences = opened.prefs

    /** False when Android Keystore could not encrypt prefs. Live keys must not be written. */
    val encryptionAvailable: Boolean = opened.encrypted

    var apiKeyId: String
        get() = prefs.getString(KEY_ID, "").orEmpty()
        set(value) {
            if (!encryptionAvailable && value.isNotBlank()) return
            prefs.edit().putString(KEY_ID, value.trim()).commit()
        }

    var privateKeyPem: String
        get() = PemNormalizer.normalize(prefs.getString(KEY_PEM, "").orEmpty())
        set(value) {
            if (!encryptionAvailable && value.isNotBlank()) return
            prefs.edit().putString(KEY_PEM, PemNormalizer.normalize(value)).commit()
        }

    /**
     * Writes a live Kalshi key only when EncryptedSharedPreferences is active.
     * Returns [CredentialWriteGuard.REJECT_UNENCRYPTED] and writes nothing otherwise.
     */
    fun saveLiveCredentials(keyId: String, pem: String): String? {
        if (!encryptionAvailable) return CredentialWriteGuard.REJECT_UNENCRYPTED
        prefs.edit()
            .putString(KEY_ID, keyId.trim())
            .putString(KEY_PEM, PemNormalizer.normalize(pem))
            .commit()
        KeystoreRecovery.wipedUndecryptableSecrets = false
        return null
    }

    val hasCredentials: Boolean
        get() = apiKeyId.isNotBlank() && looksLikePem(privateKeyPem)

    val keyIdWithoutPem: Boolean
        get() = PemNormalizer.onlyKeyIdSaved(apiKeyId, prefs.getString(KEY_PEM, "").orEmpty())

    fun clear() {
        prefs.edit().remove(KEY_ID).remove(KEY_PEM).commit()
    }

    fun snapshot(): Pair<String, String> = apiKeyId to privateKeyPem

    companion object {
        private const val TAG = "DipHunterSecure"
        private const val PREFS_NAME = "kalshi_signal_secrets"
        private const val FALLBACK_NAME = "kalshi_signal_secrets_fallback"
        private const val LEGACY_PLAIN = "kalshi_signal_secrets_legacy"
        private const val KEY_ID = "api_key_id"
        private const val KEY_PEM = "private_key_pem"

        @Volatile
        var lastKeystoreInvalidated: Boolean = false
            private set

        fun looksLikePem(pem: String): Boolean = PemNormalizer.looksLikePem(pem)

        private data class Opened(val prefs: SharedPreferences, val encrypted: Boolean)

        private fun openStore(context: Context): Opened {
            val fallback = context.getSharedPreferences(FALLBACK_NAME, Context.MODE_PRIVATE)
            val legacy = context.getSharedPreferences(LEGACY_PLAIN, Context.MODE_PRIVATE)
            val encrypted = tryCreateEncrypted(context)
            if (encrypted != null) {
                migrateIfEmpty(from = fallback, to = encrypted)
                migrateIfEmpty(from = legacy, to = encrypted)
                return Opened(encrypted, encrypted = true)
            }
            Log.w(TAG, "EncryptedSharedPreferences unavailable; live key writes are refused")
            migrateIfEmpty(from = legacy, to = fallback)
            return Opened(fallback, encrypted = false)
        }

        private fun tryCreateEncrypted(context: Context): SharedPreferences? {
            openEncrypted(context)?.let { return it }
            if (!lastKeystoreInvalidated) return null
            if (!KeystoreRecovery.wipeBrokenSecrets(context)) return null
            return openEncrypted(context)?.also { lastKeystoreInvalidated = false }
        }

        private fun openEncrypted(context: Context): SharedPreferences? {
            return try {
                val masterKey = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
                val prefs = EncryptedSharedPreferences.create(
                    PREFS_NAME,
                    masterKey,
                    context,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
                lastKeystoreInvalidated = false
                prefs
            } catch (e: Exception) {
                if (KeystoreRecovery.isInvalidKey(e)) {
                    lastKeystoreInvalidated = true
                    Log.w(TAG, "Encrypted prefs Keystore invalid (${e.javaClass.simpleName}) — resetting")
                    return null
                }
                Log.w(TAG, "EncryptedSharedPreferences unavailable (${e.javaClass.simpleName})")
                null
            }
        }

        private fun migrateIfEmpty(from: SharedPreferences, to: SharedPreferences) {
            val destId = to.getString(KEY_ID, "").orEmpty()
            val destPem = to.getString(KEY_PEM, "").orEmpty()
            val srcId = from.getString(KEY_ID, "").orEmpty()
            val srcPem = from.getString(KEY_PEM, "").orEmpty()
            if (!CredentialMigration.destNeedsSource(destId, destPem, srcId, srcPem)) return
            to.edit().putString(KEY_ID, srcId).putString(KEY_PEM, srcPem).commit()
            from.edit().remove(KEY_ID).remove(KEY_PEM).commit()
        }
    }
}
