package com.dirk.kalshiodds.signal.sizing

import com.dirk.kalshiodds.signal.config.SignalConstants
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Advisory position size. **Never executes.**
 *
 * ## Kelly for a $1 binary
 *
 * Buying a contract at price `c` that pays $1 with fair probability `p`:
 *
 *     f* = (p − c) / (1 − c)
 *
 * That is the bankroll fraction that maximizes log-growth in the
 * textbook binary case. We then apply:
 *
 *  1. [kellyFraction] (default 0.5 = half-Kelly; paper AI uses [com.dirk.kalshiodds.signal.paper.PaperKellySizer])
 *  2. [maxFraction] hard cap (default 5% of bankroll)
 *  3. Liquidity / depth clip so we do not suggest walking the book
 *  4. Spread haircut (wider book → smaller clip)
 *
 * Fixed-fraction mode skips Kelly and uses [fixedFraction] of bankroll.
 *
 * Suggested contracts = floor(dollars_at_risk / c). Zero if no edge
 * after fees/spread, or if inputs are unusable.
 */
object PositionSizer {

    enum class Mode {
        KELLY,
        FIXED_FRACTION
    }

    data class Advice(
        val contracts: Int,
        val dollarsAtRisk: Double,
        val kellyFull: Double,
        val fractionUsed: Double,
        val mode: Mode,
        val reason: String
    )

    fun suggest(
        fairSide: Double,
        contractPrice: Double,
        bankrollUsd: Double,
        mode: Mode = Mode.KELLY,
        kellyFraction: Double = SignalConstants.DEFAULT_KELLY_FRACTION,
        fixedFraction: Double = SignalConstants.DEFAULT_FIXED_FRACTION,
        maxFraction: Double = SignalConstants.DEFAULT_MAX_BANKROLL_FRACTION,
        liquidity: Double? = null,
        depthNearMid: Double? = null,
        spreadDollars: Double? = null,
        maxSpreadCents: Double = SignalConstants.DEFAULT_MAX_SPREAD_CENTS,
        netEvPositive: Boolean = true
    ): Advice {
        val bankroll = bankrollUsd.coerceIn(10.0, 1_000_000.0)
        val c = contractPrice.coerceIn(com.dirk.kalshiodds.domain.KalshiPrice.MIN_TICK_DOLLARS, 0.99)
        val p = fairSide.coerceIn(0.02, 0.98)
        if (!netEvPositive) {
            return Advice(0, 0.0, 0.0, 0.0, mode, "no edge after fees/spread")
        }
        val fullKelly = fullKelly(p, c)
        if (fullKelly <= 0.0) {
            return Advice(0, 0.0, fullKelly, 0.0, mode, "Kelly ≤ 0 — no size")
        }
        val rawFrac = when (mode) {
            Mode.KELLY -> fullKelly * kellyFraction.coerceIn(0.05, 1.0)
            Mode.FIXED_FRACTION -> fixedFraction.coerceIn(0.002, 0.25)
        }
        var frac = min(rawFrac, maxFraction.coerceIn(0.005, 0.25))

        val spreadCents = (spreadDollars ?: 0.0) * 100.0
        if (spreadCents > 0.0 && maxSpreadCents > 0.0) {
            val haircut = (1.0 - (spreadCents / (maxSpreadCents * 2.0)).coerceIn(0.0, 0.70))
            frac *= haircut
        }

        var dollars = frac * bankroll
        val liqCap = liquidityCap(liquidity, depthNearMid)
        if (liqCap != null) {
            dollars = min(dollars, liqCap * c)
        }
        val contracts = floor((dollars / c) + 1e-9).toInt().coerceAtLeast(0)
        val used = if (bankroll > 0) (contracts * c) / bankroll else 0.0
        val reason = buildString {
            append(if (mode == Mode.KELLY) "¼-Kelly-style" else "fixed-fraction")
            append(" · ")
            append("$contracts contracts max")
            if (liqCap != null && contracts >= liqCap) append(" · liquidity-capped")
        }
        return Advice(
            contracts = contracts,
            dollarsAtRisk = contracts * c,
            kellyFull = fullKelly,
            fractionUsed = used,
            mode = mode,
            reason = reason
        )
    }

    /** f* = (p − c) / (1 − c). Negative when there is no edge. */
    fun fullKelly(p: Double, c: Double): Double {
        val price = c.coerceIn(com.dirk.kalshiodds.domain.KalshiPrice.MIN_TICK_DOLLARS, 0.99)
        val fair = p.coerceIn(0.0, 1.0)
        val denom = 1.0 - price
        if (denom <= 1e-9) return 0.0
        return (fair - price) / denom
    }

    /**
     * Do not suggest more than ~10% of observed volume/OI or 25% of
     * near-mid depth — a crude fillability cap, not an order.
     */
    fun liquidityCap(liquidity: Double?, depthNearMid: Double?): Int? {
        val fromBook = listOfNotNull(
            liquidity?.let { floor(it * 0.10).toInt() },
            depthNearMid?.let { floor(it * 0.25).toInt() }
        ).filter { it > 0 }
        if (fromBook.isEmpty()) return null
        return max(1, fromBook.min())
    }

    fun modeOf(kelly: Boolean): Mode = if (kelly) Mode.KELLY else Mode.FIXED_FRACTION
}
