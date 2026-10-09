package com.dirk.kalshiodds.signal.trade

import com.dirk.kalshiodds.domain.MarketUiModel

/**
 * Experimental, paper-only side selector. It deliberately applies no timing,
 * spot-return, favorite-strength, or model-edge entry filter. The selection
 * only chooses which side to record; [TicketBuilder] still requires an open
 * market and an executable displayed ask so the paper fill has a real quote.
 */
object ScalpSignal {
    data class Candidate(
        val side: String,
        val selectedFrom: String
    ) {
        fun note(): String = "Paper-only scalp · unrestricted entry · side: $selectedFrom"
    }

    fun candidate(market: MarketUiModel): Candidate {
        val aiYes = market.aiYesPercent?.takeIf { it.isFinite() }
        if (aiYes != null) {
            return Candidate(
                side = if (aiYes >= 50.0) "YES" else "NO",
                selectedFrom = "AI fair value"
            )
        }
        val marketYes = market.yesProbabilityPercent?.takeIf { it.isFinite() }
        return Candidate(
            side = if ((marketYes ?: 50.0) >= 50.0) "YES" else "NO",
            selectedFrom = if (marketYes != null) "market price" else "default YES"
        )
    }
}
