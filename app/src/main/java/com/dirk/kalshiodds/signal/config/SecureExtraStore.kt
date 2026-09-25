package com.dirk.kalshiodds.signal.config

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * Separate encrypted prefs for the demo Kalshi key and optional GitHub
 * token. Does **not** touch the live `kalshi_signal_secrets` vault.
 * Decrypt / Keystore failure falls back to the private file and sets
 * [keystoreInvalidated] so Settings can ask for a re-enter / import
 * instead of silently showing empty demo fields.
 */
class SecureExtraStore(context: Context) {

    val keystoreInvalidated: Boolean
        get() = lastKeystoreInvalidated

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

        @Volatile
        var lastKeystoreInvalidated: Boolean = false
            private set

        private fun createPrefs(context: Context): SharedPreferences {
            val fallback = context.getSharedPreferences(FALLBACK_NAME, Context.MODE_PRIVATE)
            val encrypted = tryCreateEncrypted(context)
            if (encrypted != null) {
                migrateIfEmpty(from = fallback, to = encrypted)
                return encrypted
            }
            Log.w(TAG, "Encrypted extra prefs unavailable; using private prefs")
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
                Log.w(TAG, "Encrypted extra prefs Keystore tag invalid — using fallback")
                null
            } catch (e: java.security.KeyStoreException) {
                lastKeystoreInvalidated = true
                Log.w(TAG, "Keystore unavailable for extra prefs (${e.javaClass.simpleName})")
                null
            } catch (e: Exception) {
                val name = e.javaClass.simpleName
                if (name.contains("AEAD", true) || name.contains("KeyStore", true) ||
                    e.cause is javax.crypto.AEADBadTagException
                ) {
                    lastKeystoreInvalidated = true
                    Log.w(TAG, "Encrypted extra prefs invalidated ($name) — using fallback")
                    return null
                }
                Log.w(TAG, "Encrypted extra prefs unavailable ($name)")
                null
            }
        }

        private fun migrateIfEmpty(from: SharedPreferences, to: SharedPreferences) {
            val destId = to.getString(KEY_DEMO_ID, "").orEmpty()
            val destPem = to.getString(KEY_DEMO_PEM, "").orEmpty()
            val srcId = from.getString(KEY_DEMO_ID, "").orEmpty()
            val srcPem = from.getString(KEY_DEMO_PEM, "").orEmpty()
            if (CredentialMigration.destNeedsSource(destId, destPem, srcId, srcPem)) {
                to.edit().putString(KEY_DEMO_ID, srcId).putString(KEY_DEMO_PEM, srcPem).commit()
                from.edit().remove(KEY_DEMO_ID).remove(KEY_DEMO_PEM).commit()
            }
            val destTok = to.getString(KEY_GITHUB, "").orEmpty()
            val srcTok = from.getString(KEY_GITHUB, "").orEmpty()
            if (destTok.isBlank() && srcTok.isNotBlank()) {
                to.edit().putString(KEY_GITHUB, srcTok).commit()
                from.edit().remove(KEY_GITHUB).commit()
            }
        }
    }
}
