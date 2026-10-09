package com.dirk.kalshiodds.signal.trade

import com.dirk.kalshiodds.domain.MarketUiModel

/**
 * Experimental, paper-only side selector. It deliberately applies no timing,
 * spot-return, favorite-strength, or model-edge entry filter. The selection
 * only chooses which side to record; [TicketBuilder] still requires an open
 * market and an executable displayed ask so the paper fill has a real quote.
 */
object ScalpSignal {
    const val FORMULA_VERSION = "scalp-v2-settlement-aware"

    data class Candidate(
        val side: String,
        val selectedFrom: String,
        val spotReturn1m: Double?,
        val spotReturn5m: Double?,
        val timeToCloseSec: Long?
    ) {
        fun note(): String = "Paper-only scalp · $FORMULA_VERSION · unrestricted entry · side: $selectedFrom"
    }

    fun candidate(market: MarketUiModel, nowMs: Long): Candidate {
        val engineSide = market.predictedSide?.uppercase()?.takeIf { it == "YES" || it == "NO" }
        if (engineSide != null) {
            return candidate(
                side = engineSide,
                source = "settlement-aware engine",
                market = market,
                nowMs = nowMs
            )
        }
        val aiYes = market.aiYesPercent?.takeIf { it.isFinite() }
        if (aiYes != null) {
            return candidate(
                side = if (aiYes >= 50.0) "YES" else "NO",
                source = "AI fair value fallback",
                market = market,
                nowMs = nowMs
            )
        }
        val marketYes = market.yesProbabilityPercent?.takeIf { it.isFinite() }
        return candidate(
            side = if ((marketYes ?: 50.0) >= 50.0) "YES" else "NO",
            source = if (marketYes != null) "market price fallback" else "default YES",
            market = market,
            nowMs = nowMs
        )
    }

    private fun candidate(side: String, source: String, market: MarketUiModel, nowMs: Long): Candidate = Candidate(
        side = side,
        selectedFrom = source,
        spotReturn1m = market.spotReturn1m?.takeIf { it.isFinite() },
        spotReturn5m = market.spotReturn5m?.takeIf { it.isFinite() },
        timeToCloseSec = market.closeTimeEpochMs?.let { close ->
            ((close - nowMs) / 1_000L).takeIf { it >= 0L }
        }
    )
}
