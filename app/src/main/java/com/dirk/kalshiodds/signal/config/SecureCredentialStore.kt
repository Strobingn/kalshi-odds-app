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

    private val prefs: SharedPreferences = createPrefs(context.applicationContext)

    var apiKeyId: String
        get() = prefs.getString(KEY_ID, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_ID, value.trim()).commit()
        }

    var privateKeyPem: String
        get() = prefs.getString(KEY_PEM, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_PEM, value.trim()).commit()
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
        private const val KEY_ID = "api_key_id"
        private const val KEY_PEM = "private_key_pem"

        fun looksLikePem(pem: String): Boolean {
            val t = pem.trim()
            return t.contains("BEGIN") && t.contains("PRIVATE") && t.contains("END") && t.length > 80
        }

        private fun createPrefs(context: Context): SharedPreferences {
            val fallback = context.getSharedPreferences(FALLBACK_NAME, Context.MODE_PRIVATE)
            val encrypted = tryCreateEncrypted(context)
            if (encrypted != null) {
                migrateIfEmpty(from = fallback, to = encrypted)
                if (encrypted.getString(KEY_ID, "").isNullOrBlank()) {
                    migrateIfEmpty(from = fallback, to = encrypted)
                }
                return encrypted
            }
            Log.w(TAG, "EncryptedSharedPreferences unavailable; using private prefs")
            return fallback
        }

        private fun tryCreateEncrypted(context: Context): SharedPreferences? {
            return try {
                val masterKey = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
                EncryptedSharedPreferences.create(
                    PREFS_NAME,
                    masterKey,
                    context,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Exception) {
                Log.w(TAG, "EncryptedSharedPreferences unavailable (${e.javaClass.simpleName})")
                null
            }
        }

        private fun migrateIfEmpty(from: SharedPreferences, to: SharedPreferences) {
            val destId = to.getString(KEY_ID, "").orEmpty()
            val destPem = to.getString(KEY_PEM, "").orEmpty()
            if (destId.isNotBlank() && looksLikePem(destPem)) return
            val srcId = from.getString(KEY_ID, "").orEmpty()
            val srcPem = from.getString(KEY_PEM, "").orEmpty()
            if (srcId.isBlank() && !looksLikePem(srcPem)) return
            to.edit().putString(KEY_ID, srcId).putString(KEY_PEM, srcPem).commit()
            from.edit().remove(KEY_ID).remove(KEY_PEM).commit()
        }
    }
}
