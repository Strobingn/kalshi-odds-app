package com.dirk.kalshiodds.signal.config

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * Separate encrypted prefs for the optional GitHub token used to download
 * a private-repo edge model. Does **not** touch the Kalshi key files or
 * credential migration path.
 */
class SecureExtraStore(context: Context) {

    private val prefs: SharedPreferences = createPrefs(context.applicationContext)

    var githubToken: String
        get() = prefs.getString(KEY_GITHUB, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_GITHUB, value.trim()).commit()
        }

    var demoApiKeyId: String
        get() = prefs.getString(KEY_DEMO_ID, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_DEMO_ID, value.trim()).commit()
        }

    var demoPrivateKeyPem: String
        get() = prefs.getString(KEY_DEMO_PEM, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_DEMO_PEM, value.trim()).commit()
        }

    val hasDemoCredentials: Boolean
        get() = demoApiKeyId.isNotBlank() && SecureCredentialStore.looksLikePem(demoPrivateKeyPem)

    fun demoSnapshot(): Pair<String, String> = demoApiKeyId to demoPrivateKeyPem

    fun saveDemoCredentials(keyId: String, pem: String) {
        prefs.edit()
            .putString(KEY_DEMO_ID, keyId.trim())
            .putString(KEY_DEMO_PEM, pem.trim())
            .commit()
    }

    fun clearDemoCredentials() {
        prefs.edit().remove(KEY_DEMO_ID).remove(KEY_DEMO_PEM).commit()
    }

    fun clearGithubToken() {
        prefs.edit().remove(KEY_GITHUB).commit()
    }

    companion object {
        private const val TAG = "DipHunterExtra"
        private const val PREFS_NAME = "diphunter_extra_secrets"
        private const val FALLBACK_NAME = "diphunter_extra_secrets_fallback"
        private const val KEY_GITHUB = "github_token"
        private const val KEY_DEMO_ID = "kalshi_demo_key_id"
        private const val KEY_DEMO_PEM = "kalshi_demo_private_key_pem"

        private fun createPrefs(context: Context): SharedPreferences {
            val fallback = context.getSharedPreferences(FALLBACK_NAME, Context.MODE_PRIVATE)
            return try {
                val masterKey = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
                EncryptedSharedPreferences.create(
                    PREFS_NAME,
                    masterKey,
                    context,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (t: Exception) {
                Log.w(TAG, "Encrypted extra prefs unavailable (${t.javaClass.simpleName})")
                fallback
            }
        }
    }
}
