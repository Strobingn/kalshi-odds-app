package com.dirk.kalshiodds.signal.trade

import com.dirk.kalshiodds.domain.MarketUiModel

/**
 * Twenty independent, paper-only scalp hypotheses. They deliberately have no
 * timing, stake, favorite-strength, or model-edge entry gate: each selector
 * chooses a side for the same executable quote so their results can be
 * compared fairly. [TicketBuilder] still requires an open market and an
 * executable displayed ask; that is quote integrity, not a financial cap.
 */
object ScalpSignal {
    const val FORMULA_VERSION = "scalp-v5-twenty-track"

    enum class Track(
        val formulaVersion: String,
        val label: String,
        val family: String
    ) {
        DIP_HUNTER("scalp-v3-dip-hunter", "Dip Hunter", "Baseline"),
        MOMENTUM_SNIPER("scalp-v3-momentum-sniper", "Momentum Sniper", "Spot momentum"),
        EXTREME_REVERSAL("scalp-v3-extreme-reversal", "5m Mean Reversion", "Spot reversal"),
        ONE_MINUTE_REVERSAL("scalp-v4-one-minute-reversal", "1m Mean Reversion", "Spot reversal"),
        FIVE_MINUTE_TREND("scalp-v4-five-minute-trend", "5m Trend Rider", "Spot momentum"),
        FAIR_VALUE_FOLLOWER("scalp-v4-fair-value-follower", "Fair Value Follower", "Fair value"),
        BOOK_PRESSURE("scalp-v4-book-pressure", "Best-Ask Contrarian", "Order book"),
        MARKET_FAVORITE("scalp-v4-market-favorite", "Market Favorite", "Market baseline"),
        DUAL_HORIZON_MOMENTUM("scalp-v5-dual-horizon-momentum", "Dual-Horizon Momentum", "Spot momentum"),
        DUAL_HORIZON_REVERSAL("scalp-v5-dual-horizon-reversal", "Dual-Horizon Reversal", "Spot reversal"),
        FAIR_VALUE_DISLOCATION("scalp-v5-fair-value-dislocation", "Fair-Value Dislocation", "Fair value"),
        MODEL_DISLOCATION("scalp-v5-model-dislocation", "Model Dislocation", "Model comparison"),
        IMPORTED_MODEL("scalp-v5-imported-model", "Imported Edge Model", "Model comparison"),
        MLP_CHANNEL("scalp-v5-mlp-channel", "MLP Channel", "Model channel"),
        CNN_CHANNEL("scalp-v5-cnn-channel", "CNN Channel", "Model channel"),
        GBM_CHANNEL("scalp-v5-gbm-channel", "GBM Channel", "Model channel"),
        SURVIVAL_CHANNEL("scalp-v5-survival-channel", "Survival Channel", "Path model"),
        MM_SHADOW("scalp-v5-mm-shadow", "MM Shadow", "Microstructure"),
        ODDS_TAPE("scalp-v5-odds-tape", "Odds Tape", "Market momentum"),
        SPOT_TARGET("scalp-v5-spot-target", "Spot vs Target", "Settlement proxy")
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
            "Paper-only ${track.label} · ${track.family} · $formulaVersion · unrestricted entry · side: $selectedFrom"
    }

    /** All twenty tracks trade each open market independently; none is an entry gate. */
    fun candidates(market: MarketUiModel, nowMs: Long): List<Candidate> = listOf(
        dipHunter(market, nowMs),
        spotDirection(Track.MOMENTUM_SNIPER, market.spotReturn1m, "YES", "NO", "1m public-spot momentum", market, nowMs),
        spotDirection(Track.EXTREME_REVERSAL, market.spotReturn5m, "NO", "YES", "5m public-spot reversal", market, nowMs),
        spotDirection(Track.ONE_MINUTE_REVERSAL, market.spotReturn1m, "NO", "YES", "1m public-spot reversal", market, nowMs),
        spotDirection(Track.FIVE_MINUTE_TREND, market.spotReturn5m, "YES", "NO", "5m public-spot trend", market, nowMs),
        fairValueFollower(market, nowMs),
        bookPressure(market, nowMs),
        marketFavorite(market, nowMs),
        dualHorizon(Track.DUAL_HORIZON_MOMENTUM, market, follow = true, nowMs = nowMs),
        dualHorizon(Track.DUAL_HORIZON_REVERSAL, market, follow = false, nowMs = nowMs),
        dislocation(Track.FAIR_VALUE_DISLOCATION, market.digitalFairPp, "digital fair", market, nowMs),
        dislocation(Track.MODEL_DISLOCATION, market.aiYesPercent, "on-device fair", market, nowMs),
        probability(Track.IMPORTED_MODEL, market.importedModelPp, "imported edge model", market, nowMs),
        probability(Track.MLP_CHANNEL, market.mlpPp, "MLP channel", market, nowMs),
        probability(Track.CNN_CHANNEL, market.cnnPp, "CNN channel", market, nowMs),
        probability(Track.GBM_CHANNEL, market.gbmPp, "GBM channel", market, nowMs),
        probability(Track.SURVIVAL_CHANNEL, market.survivalYesPp, "survival probability", market, nowMs),
        probability(Track.MM_SHADOW, market.mmShadowPp, "MM shadow", market, nowMs),
        oddsTape(market, nowMs),
        spotTarget(market, nowMs)
    )

    /** Compatibility selector for callers that request the original baseline. */
    fun candidate(market: MarketUiModel, nowMs: Long): Candidate = dipHunter(market, nowMs)

    private fun dipHunter(market: MarketUiModel, nowMs: Long): Candidate {
        val engineSide = market.predictedSide?.uppercase()?.takeIf { it == "YES" || it == "NO" }
        if (engineSide != null) return candidate(Track.DIP_HUNTER, engineSide, "settlement-aware engine", market, nowMs)
        val aiYes = percent(market.aiYesPercent)
        if (aiYes != null) return candidate(Track.DIP_HUNTER, side(aiYes), "AI fair value fallback", market, nowMs)
        val marketYes = percent(market.yesProbabilityPercent)
        return candidate(
            Track.DIP_HUNTER,
            side(marketYes ?: 50.0),
            if (marketYes != null) "market price fallback" else "default YES",
            market,
            nowMs
        )
    }

    private fun spotDirection(
        track: Track,
        returnValue: Double?,
        positiveSide: String,
        negativeSide: String,
        source: String,
        market: MarketUiModel,
        nowMs: Long
    ): Candidate {
        val change = returnValue?.takeIf { it.isFinite() }
        if (change != null) return candidate(track, if (change >= 0.0) positiveSide else negativeSide, "$source (${formatReturn(change)})", market, nowMs)
        return fallback(track, "$source unavailable", market, nowMs)
    }

    private fun dualHorizon(track: Track, market: MarketUiModel, follow: Boolean, nowMs: Long): Candidate {
        val one = market.spotReturn1m?.takeIf { it.isFinite() && it > -1.0 }
        val five = market.spotReturn5m?.takeIf { it.isFinite() && it > -1.0 }
        if (one != null && five != null) {
            // `five` already contains the newest minute. Adding `one + five`
            // counted that minute twice and gave the two-horizon tracks a
            // misleadingly large short-term tilt. Recover the preceding four
            // minutes first, then use the stronger non-overlapping segment.
            val priorFour = ((1.0 + five) / (1.0 + one) - 1.0).takeIf { it.isFinite() }
            if (priorFour == null) return fallback(track, "non-overlapping spot return unavailable", market, nowMs)
            val combined = when {
                sameDirection(one, priorFour) -> one + priorFour
                kotlin.math.abs(priorFour) > kotlin.math.abs(one) -> priorFour
                else -> one
            }
            val trendSide = if (combined >= 0.0) "YES" else "NO"
            val side = if (follow) trendSide else opposite(trendSide)
            val name = if (follow) "1m / prior-4m spot momentum" else "1m / prior-4m spot reversal"
            return candidate(
                track,
                side,
                "$name (1m ${formatReturn(one)}, prior 4m ${formatReturn(priorFour)})",
                market,
                nowMs
            )
        }
        return fallback(track, "dual-horizon spot unavailable", market, nowMs)
    }

    private fun fairValueFollower(market: MarketUiModel, nowMs: Long): Candidate {
        val digitalFair = percent(market.digitalFairPp)
        if (digitalFair != null) return candidate(Track.FAIR_VALUE_FOLLOWER, side(digitalFair), "digital fair value (${formatPercent(digitalFair)})", market, nowMs)
        val aiFair = percent(market.aiYesPercent)
        if (aiFair != null) return candidate(Track.FAIR_VALUE_FOLLOWER, side(aiFair), "AI fair value (${formatPercent(aiFair)})", market, nowMs)
        return fallback(Track.FAIR_VALUE_FOLLOWER, "fair value unavailable", market, nowMs)
    }

    private fun bookPressure(market: MarketUiModel, nowMs: Long): Candidate {
        val yesAsk = unit(market.yesAsk)
        val noAsk = unit(market.noAsk)
        if (yesAsk != null && noAsk != null) {
            return candidate(
                Track.BOOK_PRESSURE,
                if (yesAsk <= noAsk) "YES" else "NO",
                "cheaper executable ask (YES ${formatPrice(yesAsk)} / NO ${formatPrice(noAsk)})",
                market,
                nowMs
            )
        }
        return fallback(Track.BOOK_PRESSURE, "executable asks unavailable", market, nowMs)
    }

    private fun marketFavorite(market: MarketUiModel, nowMs: Long): Candidate {
        val yesMid = percent(market.yesProbabilityPercent)
        if (yesMid != null) return candidate(Track.MARKET_FAVORITE, side(yesMid), "displayed market midpoint (${formatPercent(yesMid)} YES)", market, nowMs)
        return fallback(Track.MARKET_FAVORITE, "market midpoint unavailable", market, nowMs)
    }

    private fun dislocation(
        track: Track,
        fairPp: Double?,
        source: String,
        market: MarketUiModel,
        nowMs: Long
    ): Candidate {
        val fair = percent(fairPp)
        val mid = percent(market.yesProbabilityPercent)
        if (fair != null && mid != null) {
            val gap = fair - mid
            return candidate(track, if (gap >= 0.0) "YES" else "NO", "$source minus market ${formatPp(gap)}", market, nowMs)
        }
        return fallback(track, "$source or market midpoint unavailable", market, nowMs)
    }

    private fun probability(
        track: Track,
        yesPp: Double?,
        source: String,
        market: MarketUiModel,
        nowMs: Long
    ): Candidate {
        val yes = percent(yesPp)
        if (yes != null) return candidate(track, side(yes), "$source (${formatPercent(yes)} YES)", market, nowMs)
        return fallback(track, "$source unavailable", market, nowMs)
    }

    private fun oddsTape(market: MarketUiModel, nowMs: Long): Candidate {
        val first = market.oddsHistory.firstOrNull()?.toDouble()?.takeIf { it.isFinite() }
        val last = market.oddsHistory.lastOrNull()?.toDouble()?.takeIf { it.isFinite() }
        if (first != null && last != null && market.oddsHistory.size >= 2) {
            val move = last - first
            return candidate(Track.ODDS_TAPE, if (move >= 0.0) "YES" else "NO", "YES odds tape ${formatPp(move)}", market, nowMs)
        }
        return fallback(Track.ODDS_TAPE, "odds tape unavailable", market, nowMs)
    }

    private fun spotTarget(market: MarketUiModel, nowMs: Long): Candidate {
        val distance = market.spotVsTargetUsd?.takeIf { it.isFinite() }
        if (distance != null) return candidate(Track.SPOT_TARGET, if (distance >= 0.0) "YES" else "NO", "spot versus target ${formatUsd(distance)}", market, nowMs)
        return fallback(Track.SPOT_TARGET, "spot-target distance unavailable", market, nowMs)
    }

    private fun fallback(track: Track, unavailable: String, market: MarketUiModel, nowMs: Long): Candidate {
        val baseline = dipHunter(market, nowMs)
        return candidate(track, baseline.side, "$unavailable; ${baseline.selectedFrom} fallback", market, nowMs)
    }

    private fun candidate(track: Track, side: String, source: String, market: MarketUiModel, nowMs: Long): Candidate = Candidate(
        track = track,
        side = side,
        selectedFrom = source,
        spotReturn1m = market.spotReturn1m?.takeIf { it.isFinite() },
        spotReturn5m = market.spotReturn5m?.takeIf { it.isFinite() },
        timeToCloseSec = market.closeTimeEpochMs?.let { close -> ((close - nowMs) / 1_000L).takeIf { it >= 0L } }
    )

    private fun side(yesPp: Double): String = if (yesPp >= 50.0) "YES" else "NO"
    private fun opposite(side: String): String = if (side == "YES") "NO" else "YES"
    private fun sameDirection(a: Double, b: Double): Boolean =
        (a >= 0.0 && b >= 0.0) || (a < 0.0 && b < 0.0)
    private fun percent(value: Double?): Double? = value?.takeIf { it.isFinite() && it in 0.0..100.0 }
    private fun unit(value: Double?): Double? = value?.takeIf { it.isFinite() && it in 0.0..1.0 }
    private fun formatReturn(value: Double): String = String.format(java.util.Locale.US, "%+.4f", value)
    private fun formatPercent(value: Double): String = String.format(java.util.Locale.US, "%.1f%%", value)
    private fun formatPrice(value: Double): String = String.format(java.util.Locale.US, "%.1f¢", value * 100.0)
    private fun formatPp(value: Double): String = String.format(java.util.Locale.US, "%+.1f pp", value)
    private fun formatUsd(value: Double): String = String.format(java.util.Locale.US, "%+,.2f USD", value)
}
