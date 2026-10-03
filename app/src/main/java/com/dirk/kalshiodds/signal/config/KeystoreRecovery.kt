package com.dirk.kalshiodds.signal.config

import android.content.Context
import java.io.File
import java.security.KeyStore

/**
 * A broken Android Keystore entry used to leave EncryptedSharedPreferences
 * unreadable and refuse every new live key until the user cleared app data.
 * Recovery deletes that entry and the ciphertext that cannot be opened, then
 * the caller creates a fresh encrypted store. Plaintext storage stays refused.
 */
object KeystoreRecovery {
    const val LIVE_PREFS = "kalshi_signal_secrets"
    const val EXTRA_PREFS = "diphunter_extra_secrets"

    const val REENTER_AFTER_RESET =
        "The device Keystore could not decrypt the saved live key. The broken Keystore entry and the undecryptable data were deleted. Re-enter the Kalshi key — it is never written to plain storage."

    @Volatile
    var wipedUndecryptableSecrets: Boolean = false

    fun isInvalidKey(error: Throwable): Boolean {
        var current: Throwable? = error
        var hops = 0
        while (current != null && hops < 8) {
            val name = current.javaClass.name
            if (current is javax.crypto.AEADBadTagException) return true
            if (current is java.security.KeyStoreException) return true
            if (current is java.security.InvalidKeyException) return true
            if (name.contains("AEADBadTag", ignoreCase = true)) return true
            if (name.contains("KeyPermanentlyInvalidated", ignoreCase = true)) return true
            if (name.contains("KeyStore", ignoreCase = true)) return true
            current = current.cause
            hops++
        }
        return false
    }

    /**
     * Deletes the master-key alias and the encrypted preference files that
     * can no longer be opened. Returns true when at least one of those
     * steps ran, so the caller can create a new encrypted store.
     */
    fun wipeBrokenSecrets(context: Context): Boolean {
        var wiped = false
        listOf(LIVE_PREFS, EXTRA_PREFS).forEach { name ->
            val deletedPrefs = runCatching { context.deleteSharedPreferences(name) }.isSuccess
            val file = File(context.applicationInfo.dataDir, "shared_prefs/$name.xml")
            val deletedFile = runCatching { !file.exists() || file.delete() }.getOrDefault(false)
            if (deletedPrefs || deletedFile) wiped = true
        }
        val deletedKey = runCatching {
            val ks = KeyStore.getInstance("AndroidKeyStore")
            ks.load(null)
            // androidx.security.crypto.MasterKeys default alias. The field is
            // package-private, and MasterKeys.getOrCreate uses this same name.
            val alias = "_androidx_security_master_key_"
            if (ks.containsAlias(alias)) ks.deleteEntry(alias)
            true
        }.getOrDefault(false)
        if (deletedKey) wiped = true
        if (wiped) wipedUndecryptableSecrets = true
        return wiped
    }
}
