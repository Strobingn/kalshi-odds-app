package com.dirk.kalshiodds.signal.config

/**
 * Decide whether a destination credential store should copy from a
 * source (plain prefs / fallback). Destination wins if it already has
 * a usable key. Never overwrites a good encrypted save.
 */
object CredentialMigration {
    fun destNeedsSource(destId: String, destPem: String, srcId: String, srcPem: String): Boolean {
        if (destId.isNotBlank() && SecureCredentialStore.looksLikePem(destPem)) return false
        return srcId.isNotBlank() || SecureCredentialStore.looksLikePem(srcPem)
    }
}
