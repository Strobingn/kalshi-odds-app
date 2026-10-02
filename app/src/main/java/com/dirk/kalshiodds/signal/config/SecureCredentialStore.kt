package com.dirk.kalshiodds.signal.config

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * Device-local storage for the live Kalshi API Key ID + private key PEM.
 * PEM is never written to logs.
 *
 * The only write target is EncryptedSharedPreferences. If the Android
 * Keystore cannot open that file, saves are refused and live trading
 * stays off — the key is not copied into plaintext prefs or files.
 * When encryption works, a key left in the old fallback / legacy file
 * is absorbed into the encrypted store once and then removed, so an
 * upgrade can still read it.
 */
class SecureCredentialStore(context: Context) {

    val keystoreInvalidated: Boolean
        get() = vault.keystoreInvalidated

    val canStoreSecurely: Boolean
        get() = vault.encryptedReady

    private val vault: LiveCredentialVault = open(context.applicationContext)

    var apiKeyId: String
        get() = vault.apiKeyId
        set(value) {
            if (!canStoreSecurely) return
            vault.save(value, privateKeyPem)
        }

    var privateKeyPem: String
        get() = PemNormalizer.normalize(vault.privateKeyPem)
        set(value) {
            if (!canStoreSecurely) return
            vault.save(apiKeyId, PemNormalizer.normalize(value))
        }

    val hasCredentials: Boolean
        get() = canStoreSecurely && apiKeyId.isNotBlank() && looksLikePem(privateKeyPem)

    val keyIdWithoutPem: Boolean
        get() = PemNormalizer.onlyKeyIdSaved(apiKeyId, vault.privateKeyPem)

    fun trySave(keyId: String, pem: String): CredentialSave {
        if (!canStoreSecurely) return CredentialSave.Refused(LiveCredentialVault.REFUSE)
        return vault.save(keyId.trim(), PemNormalizer.normalize(pem))
    }

    fun clear() {
        vault.clear()
    }

    fun snapshot(): Pair<String, String> = apiKeyId to privateKeyPem

    private class PrefBucket(private val prefs: SharedPreferences) : LiveCredentialVault.Bucket {
        override fun get(key: String): String = prefs.getString(key, "").orEmpty()
        override fun put(key: String, value: String) {
            prefs.edit().putString(key, value).commit()
        }
        override fun remove(key: String) {
            prefs.edit().remove(key).commit()
        }
    }

    companion object {
        private const val TAG = "DipHunterSecure"
        private const val PREFS_NAME = "kalshi_signal_secrets"
        private const val FALLBACK_NAME = "kalshi_signal_secrets_fallback"
        private const val LEGACY_PLAIN = "kalshi_signal_secrets_legacy"

        fun looksLikePem(pem: String): Boolean = PemNormalizer.looksLikePem(pem)

        private fun open(context: Context): LiveCredentialVault {
            val encrypted = tryCreateEncrypted(context)
            if (encrypted == null) {
                Log.w(TAG, "EncryptedSharedPreferences unavailable — refusing plaintext fallback")
                return LiveCredentialVault(encrypted = null)
            }
            return LiveCredentialVault(
                encrypted = PrefBucket(encrypted),
                legacy = PrefBucket(context.getSharedPreferences(LEGACY_PLAIN, Context.MODE_PRIVATE)),
                fallback = PrefBucket(context.getSharedPreferences(FALLBACK_NAME, Context.MODE_PRIVATE))
            )
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
    }
}
