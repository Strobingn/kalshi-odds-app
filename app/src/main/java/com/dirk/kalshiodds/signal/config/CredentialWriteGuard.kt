package com.dirk.kalshiodds.signal.config

/**
 * Settings must never overwrite a stored key with empty drafts.
 * Both Key ID and a PEM-shaped private key are required to write.
 */
object CredentialWriteGuard {
    const val REJECT_BLANK = "Need Key ID + PEM private key (BEGIN RSA PRIVATE KEY or BEGIN PRIVATE KEY)"
    const val REJECT_DEMO = "Need demo Key ID + PEM (BEGIN RSA PRIVATE KEY or BEGIN PRIVATE KEY)"
    const val REJECT_KEY_ONLY =
        "Key ID alone cannot trade — paste the PEM Kalshi downloaded (BEGIN RSA PRIVATE KEY or BEGIN PRIVATE KEY). One-line / CRLF pastes are OK."
    const val REJECT_PEM_ONLY =
        "Need the API Key ID shown next to the PEM on kalshi.com → Account → API Keys"
    const val REJECT_UNENCRYPTED =
        "Android Keystore encryption is unavailable, so this live trading key was not saved. " +
            "Turn on a screen lock and try again. The key was not written to plain storage."

    fun rejectReason(keyId: String, pem: String, demo: Boolean = false): String? {
        val id = keyId.trim()
        val pemOk = SecureCredentialStore.looksLikePem(pem)
        return when {
            id.isBlank() && !pemOk -> if (demo) REJECT_DEMO else REJECT_BLANK
            id.isBlank() -> REJECT_PEM_ONLY
            !pemOk -> REJECT_KEY_ONLY
            else -> null
        }
    }

    /** Banner, not a silent empty field, when Keystore died and nothing loaded. */
    fun needsReenterBanner(hasCredentials: Boolean, keystoreInvalidated: Boolean): Boolean =
        !hasCredentials && keystoreInvalidated
}
