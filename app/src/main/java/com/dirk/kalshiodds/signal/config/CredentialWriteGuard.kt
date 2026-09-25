package com.dirk.kalshiodds.signal.config

/**
 * Settings must never overwrite a stored key with empty drafts.
 * Both Key ID and a PEM-shaped private key are required to write.
 */
object CredentialWriteGuard {
    const val REJECT_BLANK = "Need Key ID + PEM private key (BEGIN/END PRIVATE KEY)"
    const val REJECT_DEMO = "Need demo Key ID + PEM (BEGIN/END PRIVATE KEY)"

    fun rejectReason(keyId: String, pem: String, demo: Boolean = false): String? {
        if (keyId.isBlank() || !SecureCredentialStore.looksLikePem(pem)) {
            return if (demo) REJECT_DEMO else REJECT_BLANK
        }
        return null
    }

    /** Banner, not a silent empty field, when Keystore died and nothing loaded. */
    fun needsReenterBanner(hasCredentials: Boolean, keystoreInvalidated: Boolean): Boolean =
        !hasCredentials && keystoreInvalidated
}
