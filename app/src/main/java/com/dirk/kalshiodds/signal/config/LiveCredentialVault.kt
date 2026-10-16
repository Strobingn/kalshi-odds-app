package com.dirk.kalshiodds.signal.config

/**
 * Live Kalshi keys live only in encrypted storage.
 *
 * If the Android Keystore / EncryptedSharedPreferences cannot be opened,
 * [save] refuses and writes nothing — not to the legacy file, not to the
 * fallback file. Live trading stays off because [hasCredentials] is false.
 * When encryption works, a previous plaintext copy is absorbed into the
 * encrypted bucket once and then removed, so an upgrade can still read a
 * key that an older build saved.
 */
class LiveCredentialVault(
    private val encrypted: Bucket?,
    private val legacy: Bucket? = null,
    private val fallback: Bucket? = null
) {
    val encryptedReady: Boolean = encrypted != null
    val keystoreInvalidated: Boolean = encrypted == null

    init {
        absorbPlaintext()
    }

    val apiKeyId: String
        get() = encrypted?.get(KEY_ID).orEmpty()

    val privateKeyPem: String
        get() = encrypted?.get(KEY_PEM).orEmpty()

    val hasCredentials: Boolean
        get() = encryptedReady &&
            apiKeyId.isNotBlank() &&
            SecureCredentialStore.looksLikePem(privateKeyPem)

    fun save(keyId: String, pem: String): CredentialSave {
        val dest = encrypted ?: return CredentialSave.Refused(REFUSE)
        dest.put(KEY_ID, keyId.trim())
        dest.put(KEY_PEM, pem)
        return if (hasCredentials) CredentialSave.Stored else CredentialSave.Refused(REFUSE)
    }

    fun clear() {
        encrypted?.remove(KEY_ID)
        encrypted?.remove(KEY_PEM)
    }

    private fun absorbPlaintext() {
        val dest = encrypted ?: return
        listOfNotNull(legacy, fallback).forEach { src -> absorb(dest, src) }
    }

    private fun absorb(dest: Bucket, src: Bucket) {
        val destId = dest.get(KEY_ID)
        val destPem = dest.get(KEY_PEM)
        val srcId = src.get(KEY_ID)
        val srcPem = src.get(KEY_PEM)
        if (!CredentialMigration.destNeedsSource(destId, destPem, srcId, srcPem)) return
        if (srcId.isNotBlank()) dest.put(KEY_ID, srcId)
        if (srcPem.isNotBlank()) dest.put(KEY_PEM, srcPem)
        src.remove(KEY_ID)
        src.remove(KEY_PEM)
    }

    interface Bucket {
        fun get(key: String): String
        fun put(key: String, value: String)
        fun remove(key: String)
    }

    companion object {
        const val KEY_ID = "api_key_id"
        const val KEY_PEM = "private_key_pem"
        const val REFUSE =
            "Android Keystore failed — live key was not saved. Live trading stays off. The key was not written in plaintext."
        const val UNREADABLE =
            "Android Keystore failed — live trading stays off. Existing keys were not copied to plaintext. Try again when encrypted storage works, or import a passphrase backup."

        fun memoryBucket(seed: Map<String, String> = emptyMap()): RecordingBucket =
            RecordingBucket(seed)
    }

    class RecordingBucket(seed: Map<String, String> = emptyMap()) : Bucket {
        val values = seed.toMutableMap()
        var writes = 0
            private set

        override fun get(key: String): String = values[key].orEmpty()

        override fun put(key: String, value: String) {
            writes += 1
            values[key] = value
        }

        override fun remove(key: String) {
            writes += 1
            values.remove(key)
        }
    }
}

sealed class CredentialSave {
    data object Stored : CredentialSave()
    data class Refused(val reason: String) : CredentialSave()

    val stored: Boolean get() = this is Stored
}
