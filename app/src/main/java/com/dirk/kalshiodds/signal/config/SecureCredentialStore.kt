package com.dirk.kalshiodds.signal.config

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * Device-local storage for Kalshi API Key ID + private key PEM.
 * PEM is never written to logs.
 */
class SecureCredentialStore(context: Context) {

    private val prefs: SharedPreferences = createPrefs(context.applicationContext)

    @Volatile
    var apiKeyId: String
        get() = prefs.getString(KEY_ID, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_ID, value.trim()).apply()
        }

    @Volatile
    var privateKeyPem: String
        get() = prefs.getString(KEY_PEM, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_PEM, value.trim()).apply()
        }

    val hasCredentials: Boolean
        get() = apiKeyId.isNotBlank() && looksLikePem(privateKeyPem)

    fun clear() {
        prefs.edit().remove(KEY_ID).remove(KEY_PEM).apply()
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
                Log.w(TAG, "EncryptedSharedPreferences unavailable; using private prefs (${e.javaClass.simpleName})")
                context.getSharedPreferences(FALLBACK_NAME, Context.MODE_PRIVATE)
            }
        }
    }
}
