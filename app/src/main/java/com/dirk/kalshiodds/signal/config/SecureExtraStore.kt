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

    fun clearGithubToken() {
        prefs.edit().remove(KEY_GITHUB).commit()
    }

    companion object {
        private const val TAG = "DipHunterExtra"
        private const val PREFS_NAME = "diphunter_extra_secrets"
        private const val FALLBACK_NAME = "diphunter_extra_secrets_fallback"
        private const val KEY_GITHUB = "github_token"

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
