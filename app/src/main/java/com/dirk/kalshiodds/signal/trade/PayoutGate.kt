package com.dirk.kalshiodds.signal.trade

import com.dirk.kalshiodds.signal.config.SignalConstants
import kotlin.math.floor
import kotlin.math.min

/**
 * Max-payout gate for $1 binary contracts.
 *
 * ## Formula
 *
 * Kalshi event contracts settle at **$1.00** if the chosen side wins, else **$0**.
 *
 *     contracts             = floor(stakeUsd / conservativeLimitPrice)
 *     maxSettlementPayout   = contracts × $1.00   (fee is paid on the buy, not taken from $1)
 *     propose iff           maxSettlementPayout ≥ minPayoutUsd
 *
 * Equivalently, the conservative limit (average fill) must satisfy
 *
 *     conservativeLimitPrice ≤ stakeUsd / minPayoutUsd
 *
 * Paths:
 * - Configured (default $5 / $100): **price ≤ 5¢**. $5 at 5¢ → 100 ct → $100.
 * - Hunter ($1 / $25): **price ≤ 4¢**. $1 at 4¢ → 25 ct → $25 max payout.
 *   At 5¢, floor(1/0.05) = 20 ct → $20 → **do not auto-propose**.
 *
 * ## Liquidity
 *
 * Even when the price is cheap enough, we also require visible size at or
 * below the limit to cover [contracts]. Price too high **or** book too thin
 * → no ticket. We never walk through expensive levels to “make size.”
 *
 * This object is pure math. It never places an order.
 */
object PayoutGate {

    data class Sizing(
        val ok: Boolean,
        val contracts: Int,
        val limitPrice: Double,
        val estimatedFillUsd: Double,
        val maxPayoutUsd: Double,
        val estimatedAvgFill: Double,
        val fillableContracts: Int,
        val reason: String
    )

    /**
     * Highest limit that can still hit [minPayoutUsd] with [stakeUsd].
     * `$5 / $100 = $0.05`. `$1 / $25 = $0.04`. Returns null when unusable.
     */
    fun maxLimitForPayout(
        stakeUsd: Double,
        minPayoutUsd: Double = SignalConstants.DEFAULT_MIN_PAYOUT_USD
    ): Double? {
        val stake = stakeUsd
        val floorPayout = minPayoutUsd
        if (!stake.isFinite() || !floorPayout.isFinite()) return null
        if (stake < SignalConstants.TICKET_STAKE_MIN_USD - 1e-9) return null
        if (floorPayout <= 0.0) return null
        return (stake / floorPayout).coerceIn(
            com.dirk.kalshiodds.domain.KalshiPrice.MIN_TICK_DOLLARS,
            0.99
        )
    }

    fun contractsFor(stakeUsd: Double, limitPrice: Double): Int {
        val c = limitPrice
        val minTick = com.dirk.kalshiodds.domain.KalshiPrice.MIN_TICK_DOLLARS
        if (!stakeUsd.isFinite() || !c.isFinite() || c < minTick - 1e-12 || c > 0.99 + 1e-12) return 0
        return KalshiFee.contractsForStake(stakeUsd, c)
    }

    fun maxPayoutUsd(contracts: Int): Double = KalshiFee.settlementPayout(contracts)

    /**
     * Walk ask levels cheapest-first, never paying more than [maxPrice].
     * [levels] are `(priceDollars, size)` pairs.
     */
    fun walkAsks(
        levels: List<Pair<Double, Double>>,
        maxPrice: Double,
        contractsNeeded: Int
    ): Walk {
        var filled = 0
        var cost = 0.0
        var worst = 0.0
        val cap = contractsNeeded.coerceAtLeast(0)
        val sorted = levels
            .filter { it.first.isFinite() && it.second.isFinite() && it.first > 0.0 && it.second > 0.0 }
            .sortedBy { it.first }
        for ((price, size) in sorted) {
            if (price > maxPrice + 1e-12) break
            val take = min(size.toInt().coerceAtLeast(0), (cap - filled).coerceAtLeast(0))
            // Allow fractional displayed size by flooring per level.
            val qty = if (take > 0) take else min(floor(size + 1e-9).toInt(), (cap - filled).coerceAtLeast(0))
            if (qty <= 0) continue
            filled += qty
            cost += qty * price
            worst = price
            if (filled >= cap) break
        }
        val avg = if (filled > 0) cost / filled else 0.0
        return Walk(
            fillable = filled,
            vwap = avg,
            worstPrice = if (filled > 0) worst else 0.0,
            costUsd = cost
        )
    }

    data class Walk(
        val fillable: Int,
        val vwap: Double,
        val worstPrice: Double,
        val costUsd: Double
    )

    /**
     * Evaluate a proposed conservative limit against stake, payout floor,
     * and visible size at/under that limit.
     *
     * [bestAsk] is the touch on the chosen side (YES ask or NO ask).
     * [askLevels] optional book walk; when empty we require [bestAsk] ≤
     * the payout-max price and [quotedSize] ≥ needed contracts.
     */
    fun evaluate(
        stakeUsd: Double,
        bestAsk: Double?,
        askLevels: List<Pair<Double, Double>> = emptyList(),
        quotedSize: Double? = null,
        minPayoutUsd: Double = SignalConstants.DEFAULT_MIN_PAYOUT_USD
    ): Sizing {
        val maxPx = maxLimitForPayout(stakeUsd, minPayoutUsd)
            ?: return reject("unusable stake or payout floor")
        val ask = bestAsk
        if (ask == null || !ask.isFinite()) {
            return reject("no ask — cannot size a conservative limit")
        }
        if (ask > maxPx + 1e-12) {
            return reject(
                "price too high (${cents(ask)} > ${cents(maxPx)} max for " +
                    "\$${fmt(stakeUsd)}→≥\$${fmt(minPayoutUsd)})"
            )
        }
        val limit = ask.coerceIn(com.dirk.kalshiodds.domain.KalshiPrice.MIN_TICK_DOLLARS, maxPx)
        val contracts = contractsFor(stakeUsd, limit)
        if (contracts <= 0) {
            return reject("stake too small for a contract at ${cents(limit)}")
        }
        val payout = maxPayoutUsd(contracts)
        if (payout + 1e-9 < minPayoutUsd) {
            return reject(
                "max payout \$${fmt(payout)} < \$${fmt(minPayoutUsd)} " +
                    "($contracts ct × \$1 at ${cents(limit)})"
            )
        }

        val fillable = if (askLevels.isNotEmpty()) {
            walkAsks(askLevels, limit, contracts).fillable
        } else {
            quotedSize?.let { floor(it + 1e-9).toInt().coerceAtLeast(0) }
        }
        if (fillable != null && fillable < contracts) {
            return reject(
                "liquidity too thin ($fillable visible < $contracts needed at ≤${cents(limit)})"
            )
        }

        val estFill = contracts * limit
        return Sizing(
            ok = true,
            contracts = contracts,
            limitPrice = limit,
            estimatedFillUsd = estFill,
            maxPayoutUsd = payout,
            estimatedAvgFill = limit,
            fillableContracts = fillable ?: contracts,
            reason = "$contracts ct · limit ${cents(limit)} · " +
                "${fillable ?: "?"} visible at that price · " +
                "max payout \$${fmt(payout)} on \$${fmt(estFill)} risked"
        )
    }

    fun clipStake(raw: Double): Double =
        raw.coerceIn(SignalConstants.TICKET_STAKE_MIN_USD, SignalConstants.TICKET_STAKE_HARD_CAP_USD)

    fun requiresRaiseConfirm(stakeUsd: Double): Boolean =
        stakeUsd > SignalConstants.TICKET_STAKE_SOFT_CAP_USD + 1e-9

    fun raiseConfirmMatches(typed: String): Boolean =
        typed.trim().equals(SignalConstants.TICKET_RAISE_CONFIRM_PHRASE, ignoreCase = true)

    private fun reject(reason: String) = Sizing(
        ok = false,
        contracts = 0,
        limitPrice = 0.0,
        estimatedFillUsd = 0.0,
        maxPayoutUsd = 0.0,
        estimatedAvgFill = 0.0,
        fillableContracts = 0,
        reason = reason
    )

    private fun cents(p: Double): String = com.dirk.kalshiodds.domain.KalshiQuoteDisplay.formatPriceCents(p)
    private fun fmt(v: Double): String = String.format(java.util.Locale.US, "%.2f", v)
}
