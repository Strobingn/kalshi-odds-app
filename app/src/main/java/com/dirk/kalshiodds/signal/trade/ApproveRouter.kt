package com.dirk.kalshiodds.signal.trade

/**
 * Hero Buy / ticket Approve routing. Paper never depends on a Kalshi key,
 * live cash, or the V2 client. Live sells of real positions stay live even
 * when the paper-book toggle is on.
 *
 * 0.3.6 / 0.3.7 bug: `approveTicket` gated on `credentialsConfigured` first,
 * so paper mode with no Kalshi key could not Approve. Fixed here.
 *
 * 0.3.9 bug: paper-on + key routed Approve to Paper. 0.3.10 (PR #20) makes
 * a keyed Approve go Live even when the paper toggle is ON. Paper fills use
 * the separate Paper button. Explicit [Intent.Live] / [Intent.Paper] never
 * cross modes.
 */
object ApproveRouter {

    enum class Intent {
        /** Legacy auto-route: keyed Approve is Live even if paper toggle is on. */
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
        if (paperOnly || intent == Intent.Paper) return Decision.Paper
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
        if (isSell) {
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
        // Keyed Live Approve is never swallowed by the paper toggle. Paper
        // fills use the separate Paper button. 0.3.9 routed paper-on + key
        // to Paper, so Dirk could not place a real order after pasting API.
        if (liveCredentialsConfigured) {
            if (keyIdWithoutPem) {
                return Decision.Blocked(LiveOrderGates.PEM_ONLY_KEY_ID)
            }
            if (!canApprove) {
                return Decision.Blocked(blockedReason ?: "Ticket cannot be approved")
            }
            return Decision.Live
        }
        if (paperTradingEnabled) return Decision.Paper
        return Decision.Blocked(
            "Add Kalshi API Key ID + PEM in Settings before Live Approve — or turn on Paper trading"
        )
    }
}
