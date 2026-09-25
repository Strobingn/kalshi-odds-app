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
        blockedReason: String? = null
    ): Decision {
        if (paperOnly) return Decision.Paper
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
