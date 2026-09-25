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
 * unavailable we fall back to a private prefs file. Cold start must read
 * **both** files so a previous fallback save is not lost when encryption
 * later succeeds (or the reverse).
 */
class SecureCredentialStore(context: Context) {

    val keystoreInvalidated: Boolean
        get() = lastKeystoreInvalidated

    private val prefs: SharedPreferences = createPrefs(context.applicationContext)

    var apiKeyId: String
        get() = prefs.getString(KEY_ID, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_ID, value.trim()).commit()
        }

    var privateKeyPem: String
        get() = prefs.getString(KEY_PEM, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_PEM, PemNormalizer.normalize(value)).commit()
        }

    val hasCredentials: Boolean
        get() = apiKeyId.isNotBlank() && looksLikePem(privateKeyPem)

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

        private fun createPrefs(context: Context): SharedPreferences {
            val fallback = context.getSharedPreferences(FALLBACK_NAME, Context.MODE_PRIVATE)
            val legacy = context.getSharedPreferences(LEGACY_PLAIN, Context.MODE_PRIVATE)
            val encrypted = tryCreateEncrypted(context)
            if (encrypted != null) {
                migrateIfEmpty(from = fallback, to = encrypted)
                migrateIfEmpty(from = legacy, to = encrypted)
                return encrypted
            }
            Log.w(TAG, "EncryptedSharedPreferences unavailable; using private prefs")
            migrateIfEmpty(from = legacy, to = fallback)
            return fallback
        }

        private fun tryCreateEncrypted(context: Context): SharedPreferences? {
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
            } catch (e: javax.crypto.AEADBadTagException) {
                lastKeystoreInvalidated = true
                Log.w(TAG, "Encrypted prefs Keystore tag invalid — using fallback, re-enter key")
                null
            } catch (e: java.security.KeyStoreException) {
                lastKeystoreInvalidated = true
                Log.w(TAG, "Keystore unavailable (${e.javaClass.simpleName})")
                null
            } catch (e: Exception) {
                val name = e.javaClass.simpleName
                if (name.contains("AEAD", true) || name.contains("KeyStore", true) ||
                    e.cause is javax.crypto.AEADBadTagException
                ) {
                    lastKeystoreInvalidated = true
                    Log.w(TAG, "Encrypted prefs invalidated ($name) — using fallback")
                    return null
                }
                Log.w(TAG, "EncryptedSharedPreferences unavailable ($name)")
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
