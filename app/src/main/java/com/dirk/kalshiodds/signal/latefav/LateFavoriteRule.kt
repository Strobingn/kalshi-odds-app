package com.dirk.kalshiodds.signal.latefav

import com.dirk.kalshiodds.domain.CryptoMarkets
import com.dirk.kalshiodds.domain.KalshiPrice
import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.trade.KalshiFee
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * "Late favorite" rule from the pre-registered walk-forward search
 * (tools/research/edge_search.py, family H2). **PAPER ONLY.** Nothing here
 * places an order; the tracker only logs what this rule would have done.
 *
 * Parameters were picked in sample and are fixed here on purpose (no
 * sliders): OOS 543 bets, 96.5% wins at ~94.9¢, +$0.059/bet, 99% CI
 * [−0.017, +0.141] — not proven. The tracker exists to collect ~2,000 real
 * windows before anyone trusts it.
 *
 * Rule, once per market (first qualifying moment):
 * - watched live (KXBTC15M) market, 0 < time left ≤ [MAX_TTE_SECONDS]
 * - z = ln(spot / strike) / (σ₁ₘ · √(tte_s / 60)) with |z| ≥ [MIN_ABS_Z],
 *   σ₁ₘ = std of 1-minute log returns (AssetSpotFeatures.realizedVol15m,
 *   **not** annualized)
 * - side = the side spot is on (z > 0 → YES / UP, else NO / DOWN)
 * - fill at that side's best ask only if [MIN_ASK] ≤ ask ≤ [MAX_ASK]
 * - $5 all-in with the exact Kalshi taker fee (mirrors
 *   tools/backtest/pipeline.py `size_all_in` / [KalshiFee.totalCost])
 * - "worse fill" stress: same bet sized at ask + [WORSE_FILL_SLIPPAGE]
 */
object LateFavoriteRule {

    const val MAX_TTE_SECONDS = 300L
    const val MIN_ABS_Z = 1.5
    const val MIN_ASK = 0.80
    const val MAX_ASK = 0.97
    const val STAKE_USD = SignalConstants.LIVE_ALL_IN_CAP_USD
    const val WORSE_FILL_SLIPPAGE = 0.01
    const val FEE_RATE = SignalConstants.DEFAULT_FEE_RATE

    private const val EPS = 1e-9

    data class Inputs(
        val ticker: String,
        val nowMs: Long,
        /** Seconds until close; null when the close time is unknown. */
        val tteSeconds: Long?,
        val spotUsd: Double?,
        val strikeUsd: Double?,
        /** Std of 1-minute log returns (not annualized). */
        val sigma1m: Double?,
        val yesAsk: Double?,
        val noAsk: Double?
    )

    data class Sized(val contracts: Int, val costUsd: Double, val feeUsd: Double)

    /** What the rule would buy right now. */
    data class Decision(
        val ticker: String,
        val nowMs: Long,
        val tteSeconds: Long,
        val z: Double,
        val side: String,
        val ask: Double,
        val sized: Sized,
        val worseAsk: Double,
        /** Null when ask + 1¢ is unusable or cannot buy a contract under $5. */
        val worse: Sized?
    )

    /** z-distance of spot from strike in 1-minute σ units scaled to time left. */
    fun z(spotUsd: Double?, strikeUsd: Double?, sigma1m: Double?, tteSeconds: Long?): Double? {
        val s = spotUsd ?: return null
        val k = strikeUsd ?: return null
        val sig = sigma1m ?: return null
        val tte = tteSeconds ?: return null
        if (!s.isFinite() || !k.isFinite() || s <= 0.0 || k <= 0.0) return null
        if (!sig.isFinite() || sig <= 0.0 || tte <= 0L) return null
        val denom = sig * sqrt(tte / 60.0)
        if (!denom.isFinite() || denom <= 0.0) return null
        val z = ln(s / k) / denom
        return z.takeIf { it.isFinite() }
    }

    fun inAskBand(ask: Double?): Boolean {
        val p = KalshiPrice.usable(ask) ?: return false
        return p + EPS >= MIN_ASK && p - EPS <= MAX_ASK
    }

    /**
     * Largest C with KalshiFee.totalCost(C, P) ≤ [stakeUsd]; mirrors
     * pipeline.py `size_all_in`. Null when the price is unusable or no
     * contract fits.
     */
    fun sizeAllIn(price: Double?, stakeUsd: Double = STAKE_USD, feeRate: Double = FEE_RATE): Sized? {
        val p = KalshiPrice.usable(price) ?: return null
        if (!stakeUsd.isFinite() || stakeUsd <= 0.0) return null
        var c = floor(stakeUsd / p + EPS).toInt()
        while (c > 0 && KalshiFee.totalCost(c, p, feeRate) > stakeUsd + EPS) c--
        if (c <= 0) return null
        val cost = KalshiFee.totalCost(c, p, feeRate)
        return Sized(contracts = c, costUsd = cost, feeUsd = cost - c * KalshiFee.clipPrice(p))
    }

    /** Settled P&L for [sized]: win pays C × $1 minus cost, loss loses cost, void refunds. */
    fun pnl(sized: Sized?, won: Boolean?): Double? {
        val s = sized ?: return null
        return when (won) {
            null -> 0.0
            true -> s.contracts * SignalConstants.CONTRACT_SETTLEMENT_USD - s.costUsd
            false -> -s.costUsd
        }
    }

    /**
     * The paper bet the rule takes now, or null. [alreadyEntered] enforces
     * one entry per market.
     */
    fun evaluate(inputs: Inputs, alreadyEntered: Boolean): Decision? {
        if (alreadyEntered) return null
        if (!CryptoMarkets.isLiveTicker(inputs.ticker)) return null
        val tte = inputs.tteSeconds ?: return null
        if (tte <= 0L || tte > MAX_TTE_SECONDS) return null
        val z = z(inputs.spotUsd, inputs.strikeUsd, inputs.sigma1m, tte) ?: return null
        if (abs(z) + EPS < MIN_ABS_Z) return null
        val side = if (z > 0.0) "YES" else "NO"
        val ask = KalshiPrice.usable(if (side == "YES") inputs.yesAsk else inputs.noAsk) ?: return null
        if (!inAskBand(ask)) return null
        val sized = sizeAllIn(ask) ?: return null
        val worseAsk = ask + WORSE_FILL_SLIPPAGE
        return Decision(
            ticker = inputs.ticker,
            nowMs = inputs.nowMs,
            tteSeconds = tte,
            z = z,
            side = side,
            ask = ask,
            sized = sized,
            worseAsk = worseAsk,
            worse = sizeAllIn(worseAsk)
        )
    }
}
