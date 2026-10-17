package com.dirk.kalshiodds.signal.trade

import com.dirk.kalshiodds.domain.MarketUiModel

/**
 * Twenty versioned paper-only scalp hypotheses. They are all evaluated for
 * visibility, but only the active V6 tracks may open an automatic paper fill.
 * Each active track has an independently recorded price band, time window,
 * and (where a probability exists) after-fee edge threshold. A hypothesis may
 * still decline an observation when its required input is absent.
 */
object ScalpSignal {
    const val FORMULA_VERSION = "scalp-v6-evidence-gated"
    private const val RETIRED_RATIONALE = "Retired: after-fee whole-day review did not validate this V5 formula"

    /**
     * Per-track controls modelled after Claude's arena settings.  These are
     * execution-quality settings, not bankroll limits: a qualifying paper
     * order still uses all displayed touch liquidity.  A track that failed
     * the after-fee review is retired by default instead of being silently
     * re-tuned under its old formula name.
     */
    data class Settings(
        val enabled: Boolean,
        val entryMinCents: Int = 10,
        val entryMaxCents: Int = 89,
        val fromMinute: Int = 0,
        val untilMinute: Int = 15,
        val minEdgePp: Double = 0.0,
        val rationale: String
    ) {
        init {
            require(entryMinCents in 1..99)
            require(entryMaxCents in entryMinCents..89)
            require(fromMinute in 0..14)
            require(untilMinute in (fromMinute + 1)..15)
            require(minEdgePp >= 0.0)
        }
    }

    enum class Track(
        val formulaVersion: String,
        val label: String,
        val family: String,
        val settings: Settings
    ) {
        DIP_HUNTER("scalp-v6-dip-hunter", "Dip Hunter", "Baseline", Settings(false, rationale = RETIRED_RATIONALE)),
        MOMENTUM_SNIPER("scalp-v6-momentum-sniper", "Momentum Sniper", "Spot momentum", Settings(true, 10, 89, 1, 12, rationale = "Claude-style timed momentum candidate")),
        EXTREME_REVERSAL("scalp-v6-extreme-reversal", "5m Mean Reversion", "Spot reversal", Settings(true, 10, 89, 1, 12, rationale = "Claude-style timed reversal candidate")),
        ONE_MINUTE_REVERSAL("scalp-v6-one-minute-reversal", "1m Mean Reversion", "Spot reversal", Settings(false, rationale = RETIRED_RATIONALE)),
        FIVE_MINUTE_TREND("scalp-v6-five-minute-trend", "5m Trend Rider", "Spot momentum", Settings(false, rationale = RETIRED_RATIONALE)),
        FAIR_VALUE_FOLLOWER("scalp-v6-fair-value-follower", "Fair Value Follower", "Fair value", Settings(false, rationale = RETIRED_RATIONALE)),
        BOOK_PRESSURE("scalp-v6-book-pressure", "Best-Ask Contrarian", "Order book", Settings(false, rationale = RETIRED_RATIONALE)),
        MARKET_FAVORITE("scalp-v6-market-favorite", "Market Favorite", "Market baseline", Settings(false, rationale = RETIRED_RATIONALE)),
        DUAL_HORIZON_MOMENTUM("scalp-v6-dual-horizon-momentum", "Dual-Horizon Momentum", "Spot momentum", Settings(false, rationale = RETIRED_RATIONALE)),
        DUAL_HORIZON_REVERSAL("scalp-v6-dual-horizon-reversal", "Dual-Horizon Reversal", "Spot reversal", Settings(false, rationale = "Duplicate of the 5m reversal signal in the observed ledger")),
        FAIR_VALUE_DISLOCATION("scalp-v6-fair-value-dislocation", "Fair-Value Dislocation", "Fair value", Settings(true, 10, 89, 0, 15, 4.0, "Claude-style fair-value dislocation; 4pp after-fee buffer")),
        MODEL_DISLOCATION("scalp-v6-model-dislocation", "Model Dislocation", "Model comparison", Settings(false, rationale = RETIRED_RATIONALE)),
        IMPORTED_MODEL("scalp-v6-imported-model", "Imported Edge Model", "Model comparison", Settings(false, rationale = RETIRED_RATIONALE)),
        MLP_CHANNEL("scalp-v6-mlp-channel", "MLP Channel", "Model channel", Settings(false, rationale = RETIRED_RATIONALE)),
        CNN_CHANNEL("scalp-v6-cnn-channel", "CNN Channel", "Model channel", Settings(false, rationale = RETIRED_RATIONALE)),
        GBM_CHANNEL("scalp-v6-gbm-channel", "GBM Channel", "Model channel", Settings(true, 10, 89, 0, 15, 4.0, "Claude-style model channel; 4pp after-fee buffer")),
        SURVIVAL_CHANNEL("scalp-v6-survival-channel", "Survival Channel", "Path model", Settings(false, rationale = RETIRED_RATIONALE)),
        MM_SHADOW("scalp-v6-mm-shadow", "MM Shadow", "Microstructure", Settings(false, rationale = RETIRED_RATIONALE)),
        ODDS_TAPE("scalp-v6-odds-tape", "Odds Tape", "Market momentum", Settings(false, rationale = RETIRED_RATIONALE)),
        SPOT_TARGET("scalp-v6-spot-target", "Spot vs Target", "Settlement proxy", Settings(false, rationale = RETIRED_RATIONALE));
    }

    data class Candidate(
        val track: Track,
        /** Null means this hypothesis has no valid observation, never a hidden fallback. */
        val side: String?,
        val selectedFrom: String,
        val spotReturn1m: Double?,
        val spotReturn5m: Double?,
        val timeToCloseSec: Long?,
        /** Probability for this track's own exit check, frozen in the paper ledger. */
        val entryProbability: Double? = null
    ) {
        val formulaVersion: String get() = track.formulaVersion

        /**
         * This deliberately remains readable rather than hashed. It is proof
         * of which signal selected an entry and makes accidental duplicate
         * strategies visible in exports and the Supabase ledger.
         */
        val fingerprint: String = buildString {
            append(formulaVersion)
            append('|').append(side ?: "NO_OBSERVATION")
            append('|').append(selectedFrom)
            append("|r1=").append(spotReturn1m?.let(::fingerprintNumber) ?: "na")
            append("|r5=").append(spotReturn5m?.let(::fingerprintNumber) ?: "na")
            append("|tte=").append(timeToCloseSec ?: "na")
            append("|p=").append(entryProbability?.let(::fingerprintNumber) ?: "na")
        }

        private fun fingerprintNumber(value: Double): String =
            String.format(java.util.Locale.US, "%.5f", value)

        fun note(): String =
            if (side == null) {
                "Paper-only ${track.label} · ${track.family} · $formulaVersion · no observation: $selectedFrom"
            } else {
                "Paper-only ${track.label} · ${track.family} · $formulaVersion · profile-gated entry · side: $selectedFrom"
            }
    }

    /** All twenty hypotheses are evaluated every open market; entry settings are enforced separately. */
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

    /**
     * Final paper-entry check, performed against the displayed side ask and
     * fee-inclusive all-in cost by [TicketBuilder]. This prevents a
     * direction-only signal from turning into a high-price or fee-negative
     * automatic fill.
     */
    fun allowsEntry(candidate: Candidate, sideAsk: Double, allInPerContract: Double): Boolean {
        val settings = candidate.track.settings
        if (!settings.enabled || !sideAsk.isFinite() || !allInPerContract.isFinite()) return false
        val cents = sideAsk * 100.0
        if (cents < settings.entryMinCents || cents > settings.entryMaxCents) return false
        val tte = candidate.timeToCloseSec ?: return false
        val elapsedMinute = ((15 * 60L - tte).coerceIn(0L, 15 * 60L) / 60L).toInt()
        if (elapsedMinute !in settings.fromMinute until settings.untilMinute) return false
        val probability = candidate.entryProbability ?: return settings.minEdgePp <= 0.0
        return (probability - allInPerContract) * 100.0 >= settings.minEdgePp
    }

    /** Compatibility selector for callers that request the original baseline. */
    fun candidate(market: MarketUiModel, nowMs: Long): Candidate = dipHunter(market, nowMs)

    /**
     * The same track-specific probability that justified an entry must power
     * its fair-value exit. Returning null means the position relies on the
     * loss/trailing exits rather than borrowing the generic AI forecast.
     */
    fun exitProbability(strategyVersion: String?, market: MarketUiModel, side: String): Double? {
        val track = Track.values().firstOrNull { it.formulaVersion == strategyVersion } ?: return null
        val yes = when (track) {
            Track.DIP_HUNTER, Track.MODEL_DISLOCATION -> percent(market.aiYesPercent)
            Track.FAIR_VALUE_FOLLOWER -> percent(market.finalMinuteFairPp)
            Track.FAIR_VALUE_DISLOCATION, Track.SPOT_TARGET -> percent(market.digitalFairPp)
            Track.MARKET_FAVORITE, Track.BOOK_PRESSURE -> percent(market.yesProbabilityPercent)
            Track.IMPORTED_MODEL -> percent(market.importedModelPp)
            Track.MLP_CHANNEL -> percent(market.mlpPp)
            Track.CNN_CHANNEL -> percent(market.cnnPp)
            Track.GBM_CHANNEL -> percent(market.gbmPp)
            Track.SURVIVAL_CHANNEL -> percent(market.survivalYesPp)
            Track.MM_SHADOW -> percent(market.mmShadowPp)
            Track.MOMENTUM_SNIPER,
            Track.EXTREME_REVERSAL,
            Track.ONE_MINUTE_REVERSAL,
            Track.FIVE_MINUTE_TREND,
            Track.DUAL_HORIZON_MOMENTUM,
            Track.DUAL_HORIZON_REVERSAL,
            Track.ODDS_TAPE -> null
        } ?: return null
        return if (side.equals("NO", ignoreCase = true)) 1.0 - yes / 100.0 else yes / 100.0
    }

    private fun dipHunter(market: MarketUiModel, nowMs: Long): Candidate {
        val engineSide = market.predictedSide?.uppercase()?.takeIf { it == "YES" || it == "NO" }
        if (engineSide != null) return candidate(
            Track.DIP_HUNTER,
            engineSide,
            "settlement-aware engine",
            market,
            nowMs,
            sideProbability(engineSide, percent(market.aiYesPercent))
        )
        val aiYes = percent(market.aiYesPercent)
        if (aiYes != null) {
            val selected = side(aiYes)
            return candidate(Track.DIP_HUNTER, selected, "AI fair value", market, nowMs, sideProbability(selected, aiYes))
        }
        val marketYes = percent(market.yesProbabilityPercent)
        return candidate(
            Track.DIP_HUNTER,
            side(marketYes ?: 50.0),
            if (marketYes != null) "market price fallback" else "default YES",
            market,
            nowMs,
            marketYes?.div(100.0)
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
        return unavailable(track, "$source unavailable", market, nowMs)
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
            if (priorFour == null) return unavailable(track, "non-overlapping spot return unavailable", market, nowMs)
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
        return unavailable(track, "dual-horizon spot unavailable", market, nowMs)
    }

    private fun fairValueFollower(market: MarketUiModel, nowMs: Long): Candidate {
        val lockedFair = percent(market.finalMinuteFairPp)
        if (lockedFair != null && (market.finalMinuteSamples ?: 0) in 1 until 60) {
            return candidate(
                Track.FAIR_VALUE_FOLLOWER,
                side(lockedFair),
                "CF 60s settlement average (${market.finalMinuteSamples} samples, ${formatPercent(lockedFair)})",
                market,
                nowMs,
                sideProbability(side(lockedFair), lockedFair)
            )
        }
        return unavailable(Track.FAIR_VALUE_FOLLOWER, "CF final-minute average unavailable", market, nowMs)
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
        return unavailable(Track.BOOK_PRESSURE, "executable asks unavailable", market, nowMs)
    }

    private fun marketFavorite(market: MarketUiModel, nowMs: Long): Candidate {
        val yesMid = percent(market.yesProbabilityPercent)
        if (yesMid != null) return candidate(Track.MARKET_FAVORITE, side(yesMid), "displayed market midpoint (${formatPercent(yesMid)} YES)", market, nowMs)
        return unavailable(Track.MARKET_FAVORITE, "market midpoint unavailable", market, nowMs)
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
            val selected = if (gap >= 0.0) "YES" else "NO"
            return candidate(track, selected, "$source minus market ${formatPp(gap)}", market, nowMs, sideProbability(selected, fair))
        }
        return unavailable(track, "$source or market midpoint unavailable", market, nowMs)
    }

    private fun probability(
        track: Track,
        yesPp: Double?,
        source: String,
        market: MarketUiModel,
        nowMs: Long
    ): Candidate {
        val yes = percent(yesPp)
        if (yes != null) {
            val selected = side(yes)
            return candidate(track, selected, "$source (${formatPercent(yes)} YES)", market, nowMs, sideProbability(selected, yes))
        }
        return unavailable(track, "$source unavailable", market, nowMs)
    }

    private fun oddsTape(market: MarketUiModel, nowMs: Long): Candidate {
        val first = market.oddsHistory.firstOrNull()?.toDouble()?.takeIf { it.isFinite() }
        val last = market.oddsHistory.lastOrNull()?.toDouble()?.takeIf { it.isFinite() }
        if (first != null && last != null && market.oddsHistory.size >= 2) {
            val move = last - first
            return candidate(Track.ODDS_TAPE, if (move >= 0.0) "YES" else "NO", "YES odds tape ${formatPp(move)}", market, nowMs)
        }
        return unavailable(Track.ODDS_TAPE, "odds tape unavailable", market, nowMs)
    }

    private fun spotTarget(market: MarketUiModel, nowMs: Long): Candidate {
        val distance = market.spotVsTargetUsd?.takeIf { it.isFinite() }
        if (distance != null) return candidate(Track.SPOT_TARGET, if (distance >= 0.0) "YES" else "NO", "spot versus target ${formatUsd(distance)}", market, nowMs)
        return unavailable(Track.SPOT_TARGET, "spot-target distance unavailable", market, nowMs)
    }

    private fun unavailable(track: Track, reason: String, market: MarketUiModel, nowMs: Long): Candidate =
        candidate(track, null, reason, market, nowMs)

    private fun candidate(
        track: Track,
        side: String?,
        source: String,
        market: MarketUiModel,
        nowMs: Long,
        entryProbability: Double? = null
    ): Candidate = Candidate(
        track = track,
        side = side,
        selectedFrom = source,
        spotReturn1m = market.spotReturn1m?.takeIf { it.isFinite() },
        spotReturn5m = market.spotReturn5m?.takeIf { it.isFinite() },
        timeToCloseSec = market.closeTimeEpochMs?.let { close -> ((close - nowMs) / 1_000L).takeIf { it >= 0L } },
        entryProbability = entryProbability
    )

    private fun side(yesPp: Double): String = if (yesPp >= 50.0) "YES" else "NO"
    private fun sideProbability(side: String, yesPp: Double?): Double? =
        yesPp?.let { if (side.equals("NO", ignoreCase = true)) (100.0 - it) / 100.0 else it / 100.0 }
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
