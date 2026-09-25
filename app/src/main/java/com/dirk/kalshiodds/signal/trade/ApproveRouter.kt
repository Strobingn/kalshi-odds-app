package com.dirk.kalshiodds.signal.trade

/**
 * Hero Buy / ticket Approve routing. Paper never depends on a Kalshi key,
 * live cash, or the V2 client. Live sells of real positions stay live even
 * when the paper-book toggle is on.
 *
 * 0.3.6 / 0.3.7 bug: `approveTicket` gated on `credentialsConfigured` first,
 * so paper mode with no Kalshi key could not Approve. Fixed here.
 */
object ApproveRouter {

    enum class Intent {
        /** Legacy auto-route: paper toggle still fills the paper book. */
        Auto,
        /** Explicit Live Approve — paper fills must never intercept. */
        Live,
        /** Explicit Paper tap — never hits Kalshi. */
        Paper
    }

    sealed class Decision {
        data object Paper : Decision()
        data object Live : Decision()
        data class Blocked(val reason: String) : Decision()
    }

    fun decide(
        paperTradingEnabled: Boolean,
        paperOnly: Boolean,
        isSell: Boolean,
        liveCredentialsConfigured: Boolean,
        canApprove: Boolean,
        blockedReason: String? = null,
        intent: Intent = Intent.Auto,
        keyIdWithoutPem: Boolean = false
    ): Decision {
        if (paperOnly || intent == Intent.Paper) {
            return Decision.Paper
        }
        if (intent == Intent.Live) {
            if (keyIdWithoutPem) {
                return Decision.Blocked(LiveOrderGates.PEM_ONLY_KEY_ID)
            }
            if (!liveCredentialsConfigured) {
                return Decision.Blocked(
                    "Add Kalshi API Key ID + PEM in Settings before Live Approve — or use the Paper button"
                )
            }
            if (!canApprove) {
                return Decision.Blocked(blockedReason ?: "Ticket cannot be approved")
            }
            return Decision.Live
        }
        if (isSell && !paperOnly) {
            if (!liveCredentialsConfigured) {
                return Decision.Blocked(
                    "Add Kalshi API Key ID + PEM in Settings before Approving a live sell"
                )
            }
            if (!canApprove) {
                return Decision.Blocked(blockedReason ?: "Ticket cannot be approved")
            }
            return Decision.Live
        }
        if (paperTradingEnabled || paperOnly) return Decision.Paper
        if (keyIdWithoutPem) {
            return Decision.Blocked(LiveOrderGates.PEM_ONLY_KEY_ID)
        }
        if (!liveCredentialsConfigured) {
            return Decision.Blocked(
                "Add Kalshi API Key ID + PEM in Settings before Live Approve — or turn on Paper trading"
            )
        }
        if (!canApprove) {
            return Decision.Blocked(blockedReason ?: "Ticket cannot be approved")
        }
        return Decision.Live
    }
}
