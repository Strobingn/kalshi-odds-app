package com.dirk.kalshiodds.signal.trade

import com.dirk.kalshiodds.signal.config.SignalSettings

/**
 * PAPER / LIVE $ on the Approve path. Must match [ApproveRouter.decide]
 * with [ApproveRouter.Intent.Live] — the same decision
 * [com.dirk.kalshiodds.ui.OddsViewModel.approveTicket] uses.
 *
 * Paper-toggle ON + key saved is LIVE $, not PAPER. The separate Paper
 * button stays labeled PAPER and is not this helper.
 */
object TradeModeLabel {
    const val LIVE = "LIVE $"
    const val PAPER = "PAPER"
    const val NO_KEY = "NO KEY"

    fun of(decision: ApproveRouter.Decision): String = when (decision) {
        ApproveRouter.Decision.Live -> LIVE
        ApproveRouter.Decision.Paper -> PAPER
        is ApproveRouter.Decision.Blocked -> blockedLabel(decision.reason)
    }

    fun forApprove(
        settings: SignalSettings,
        ticket: TradeTicket? = null,
        canApprove: Boolean = ticket?.canApprove ?: true,
        blockedReason: String? = ticket?.blockedReason
    ): String = of(
        ApproveRouter.decide(
            paperTradingEnabled = settings.paperTradingEnabled,
            paperOnly = ticket?.paperOnly == true,
            isSell = ticket?.isSell == true,
            liveCredentialsConfigured = settings.tradingCredentialsConfigured(),
            canApprove = canApprove,
            blockedReason = blockedReason,
            intent = ApproveRouter.Intent.Live,
            keyIdWithoutPem = settings.keyIdWithoutPem()
        )
    )

    fun forApprove(
        paperTradingEnabled: Boolean,
        liveCredentialsConfigured: Boolean,
        paperOnly: Boolean = false,
        isSell: Boolean = false,
        canApprove: Boolean = true,
        blockedReason: String? = null,
        keyIdWithoutPem: Boolean = false
    ): String = of(
        ApproveRouter.decide(
            paperTradingEnabled = paperTradingEnabled,
            paperOnly = paperOnly,
            isSell = isSell,
            liveCredentialsConfigured = liveCredentialsConfigured,
            canApprove = canApprove,
            blockedReason = blockedReason,
            intent = ApproveRouter.Intent.Live,
            keyIdWithoutPem = keyIdWithoutPem
        )
    )

    private fun blockedLabel(reason: String): String {
        val lower = reason.lowercase()
        return if (
            lower.contains("api key") ||
            lower.contains("pem") ||
            lower.contains("credentials") ||
            lower.contains("no key")
        ) {
            NO_KEY
        } else {
            reason
        }
    }
}
