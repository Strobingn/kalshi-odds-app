package com.dirk.kalshiodds.signal.trade

import com.dirk.kalshiodds.domain.MarketLifecycle
import com.dirk.kalshiodds.domain.MarketUiModel
import kotlin.math.abs

/**
 * Experimental, paper-only early-window scalp filter. It requires the market
 * favorite and both cached public-spot returns to agree. It is a hypothesis,
 * not a claim of proven out-of-sample profitability.
 */
object ScalpSignal {
    const val EARLY_START_MS = 5 * 60_000L
    const val EARLY_END_MS = 7 * 60_000L
    private const val FAVORITE_MIN = 0.55
    private const val FIVE_MIN_CONFIRMATION = 0.0004

    data class Candidate(
        val side: String,
        val spotReturn1m: Double,
        val spotReturn5m: Double,
        val elapsedMs: Long
    ) {
        fun note(): String = String.format(
            java.util.Locale.US,
            "Paper-only scalp · favorite + spot confirm · 1m %+.2f%% · 5m %+.2f%%",
            spotReturn1m * 100.0,
            spotReturn5m * 100.0
        )
    }

    fun candidate(market: MarketUiModel, nowMs: Long): Candidate? {
        val open = MarketLifecycle.openTimeEpochMs(market) ?: return null
        val elapsed = nowMs - open
        if (elapsed !in EARLY_START_MS..EARLY_END_MS) return null
        val yes = market.yesProbabilityPercent?.div(100.0)?.takeIf { it.isFinite() } ?: return null
        val r1 = market.spotReturn1m?.takeIf { it.isFinite() } ?: return null
        val r5 = market.spotReturn5m?.takeIf { it.isFinite() } ?: return null
        val side = when {
            yes >= FAVORITE_MIN && r5 >= FIVE_MIN_CONFIRMATION && r1 >= 0.0 -> "YES"
            yes <= 1.0 - FAVORITE_MIN && r5 <= -FIVE_MIN_CONFIRMATION && r1 <= 0.0 -> "NO"
            else -> return null
        }
        if (abs(r5) < FIVE_MIN_CONFIRMATION) return null
        return Candidate(side, r1, r5, elapsed)
    }
}
