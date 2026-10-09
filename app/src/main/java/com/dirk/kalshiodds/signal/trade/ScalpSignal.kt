package com.dirk.kalshiodds.signal.trade

import com.dirk.kalshiodds.domain.MarketUiModel

/**
 * Experimental, paper-only side selector. It deliberately applies no timing,
 * spot-return, favorite-strength, or model-edge entry filter. The selection
 * only chooses which side to record; [TicketBuilder] still requires an open
 * market and an executable displayed ask so the paper fill has a real quote.
 */
object ScalpSignal {
    const val FORMULA_VERSION = "scalp-v3-multi-track"

    enum class Track(val formulaVersion: String, val label: String) {
        /** Existing settlement-aware forecast direction. */
        DIP_HUNTER("scalp-v3-dip-hunter", "Dip Hunter"),
        /** Follows the most recent one-minute public-spot direction. */
        MOMENTUM_SNIPER("scalp-v3-momentum-sniper", "Momentum Sniper"),
        /** Fades the five-minute public-spot direction. */
        EXTREME_REVERSAL("scalp-v3-extreme-reversal", "Extreme Reversal")
    }

    data class Candidate(
        val track: Track,
        val side: String,
        val selectedFrom: String,
        val spotReturn1m: Double?,
        val spotReturn5m: Double?,
        val timeToCloseSec: Long?
    ) {
        val formulaVersion: String get() = track.formulaVersion

        fun note(): String =
            "Paper-only ${track.label} · $formulaVersion · unrestricted entry · side: $selectedFrom"
    }

    /** All tracks trade each open market independently; none is an entry gate. */
    fun candidates(market: MarketUiModel, nowMs: Long): List<Candidate> = listOf(
        dipHunter(market, nowMs),
        spotDirection(
            track = Track.MOMENTUM_SNIPER,
            returnValue = market.spotReturn1m,
            positiveSide = "YES",
            negativeSide = "NO",
            source = "1m public-spot momentum",
            fallback = market,
            nowMs = nowMs
        ),
        spotDirection(
            track = Track.EXTREME_REVERSAL,
            returnValue = market.spotReturn5m,
            positiveSide = "NO",
            negativeSide = "YES",
            source = "5m public-spot reversal",
            fallback = market,
            nowMs = nowMs
        )
    )

    /** Compatibility selector for callers that request the primary Dip Hunter track. */
    fun candidate(market: MarketUiModel, nowMs: Long): Candidate {
        return dipHunter(market, nowMs)
    }

    private fun dipHunter(market: MarketUiModel, nowMs: Long): Candidate {
        val engineSide = market.predictedSide?.uppercase()?.takeIf { it == "YES" || it == "NO" }
        if (engineSide != null) {
            return candidate(
                track = Track.DIP_HUNTER,
                side = engineSide,
                source = "settlement-aware engine",
                market = market,
                nowMs = nowMs
            )
        }
        val aiYes = market.aiYesPercent?.takeIf { it.isFinite() }
        if (aiYes != null) {
            return candidate(
                track = Track.DIP_HUNTER,
                side = if (aiYes >= 50.0) "YES" else "NO",
                source = "AI fair value fallback",
                market = market,
                nowMs = nowMs
            )
        }
        val marketYes = market.yesProbabilityPercent?.takeIf { it.isFinite() }
        return candidate(
            track = Track.DIP_HUNTER,
            side = if ((marketYes ?: 50.0) >= 50.0) "YES" else "NO",
            source = if (marketYes != null) "market price fallback" else "default YES",
            market = market,
            nowMs = nowMs
        )
    }

    private fun spotDirection(
        track: Track,
        returnValue: Double?,
        positiveSide: String,
        negativeSide: String,
        source: String,
        fallback: MarketUiModel,
        nowMs: Long
    ): Candidate {
        val change = returnValue?.takeIf { it.isFinite() }
        if (change != null) {
            val side = if (change >= 0.0) positiveSide else negativeSide
            return candidate(track, side, "$source (${formatReturn(change)})", fallback, nowMs)
        }
        val dip = dipHunter(fallback, nowMs)
        return candidate(track, dip.side, "$source unavailable; ${dip.selectedFrom} fallback", fallback, nowMs)
    }

    private fun candidate(
        track: Track,
        side: String,
        source: String,
        market: MarketUiModel,
        nowMs: Long
    ): Candidate = Candidate(
        track = track,
        side = side,
        selectedFrom = source,
        spotReturn1m = market.spotReturn1m?.takeIf { it.isFinite() },
        spotReturn5m = market.spotReturn5m?.takeIf { it.isFinite() },
        timeToCloseSec = market.closeTimeEpochMs?.let { close ->
            ((close - nowMs) / 1_000L).takeIf { it >= 0L }
        }
    )

    private fun formatReturn(value: Double): String =
        String.format(java.util.Locale.US, "%+.4f", value)
}
