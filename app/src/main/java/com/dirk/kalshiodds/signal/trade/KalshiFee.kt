package com.dirk.kalshiodds.signal.trade

import com.dirk.kalshiodds.domain.KalshiPrice
import com.dirk.kalshiodds.signal.config.SignalConstants
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.floor

/**
 * Official Kalshi **order-level** taker fee for a single equivalent fill.
 *
 * ## Model (taker)
 *
 * Published schedule (https://kalshi.com/docs/kalshi-fee-schedule.pdf,
 * last updated 2026-07-07):
 *
 *     fees = round_up(M × 0.07 × C × P × (1 − P))
 *
 * - `P` = contract price in dollars (50¢ is 0.5)
 * - `C` = contracts in the order
 * - `M` = series multiplier (default 1)
 * - Maker formula uses 0.0175, and `M` defaults to **0** unless the series
 *   is in the Maker Fees table. This app sizes taker (lift-the-ask) tickets
 *   only, so maker fees are not applied.
 *
 * KXBTC15M / KXETH15M / KXSOL15M return `fee_type=quadratic`,
 * `fee_multiplier=1`, and `GET /series/fee_changes` is empty (checked
 * 2026-09-25). They are not on the Non-Standard Fees table.
 *
 * ## Rounding (https://docs.kalshi.com/getting_started/fee_rounding)
 *
 * 1. `trade_fee = ceil_6dp(model_fee)` — nearest $0.000001, always up.
 * 2. A rounding fee realigns the balance to the user's precision
 *    ($0.01 for non-direct members, $0.0001 for direct members).
 * 3. An accumulator carries rounding across fills so the **order** total
 *    converges to one equivalent fill. This object models that single
 *    equivalent fill for a non-direct member ($0.01).
 *
 * For a buy, signed revenue is `−C×P`:
 *
 *     aligned = floor_cent(−C×P − trade_fee)
 *     total_debit = −aligned = ceil_cent(C×P + trade_fee)
 *     order_fee = total_debit − C×P
 *
 * The fee is paid **on top of** purchase cost at buy time. Settlement is
 * still `C × $1.00`. It is not deducted from the $1 payout.
 *
 * Money math uses [BigDecimal] so binary `0.07 × C × P × (1−P)` cannot
 * trip `ceil_6dp` over an exact cent (e.g. 1.12 → 1.120001 → $1.13).
 *
 * Pure math — never places an order.
 */
object KalshiFee {

    const val TAKER_COEFFICIENT = 0.07
    const val MAKER_COEFFICIENT = 0.0175

    /**
     * 0.3.43: series on Kalshi's Maker Fees table (maker M = 1). KXBTC15M / KXETH15M / KXSOL15M and the daily
     * crypto series are not listed, so a resting (maker) paper fill pays $0 there.
     */
    val MAKER_FEE_SERIES: Set<String> = emptySet()

    /**
     * Maker fee coefficient from GET /series fee_type (docs.kalshi.com get-series, checked 2026-10-09):
     * quadratic → no maker fee; quadratic_with_maker_fees → 0.25 × 0.07; quadratic_with_combo_maker_fees → 0.5 × 0.07.
     * KXBTC15M / KXETH15M / KXSOL15M returned fee_type=quadratic, fee_multiplier=1 on 2026-10-09 → maker $0.
     */
    fun makerCoefficientFor(feeType: String?, feeMultiplier: Double = 1.0): Double = feeMultiplier * when (feeType?.lowercase()) {
        "quadratic_with_maker_fees" -> 0.25 * TAKER_COEFFICIENT
        "quadratic_with_combo_maker_fees" -> 0.5 * TAKER_COEFFICIENT
        else -> 0.0
    }

    fun makerMultiplier(series: String): Double = if (series.uppercase() in MAKER_FEE_SERIES) 1.0 else 0.0

    /** Maker fee for one fill: round_up_cent(M × 0.0175 × C × P × (1 − P)); $0 when M = 0. */
    fun makerFee(contracts: Int, price: Double, series: String): Double {
        val m = makerMultiplier(series)
        if (contracts <= 0 || m <= 0.0) return 0.0
        val p = price.coerceIn(0.0, 1.0)
        return ceilCent(m * MAKER_COEFFICIENT * contracts * p * (1.0 - p))
    }

    /** Taker fee for one fill (dollars, rounded up to the cent). */
    fun takerFee(contracts: Int, price: Double, feeRate: Double = SignalConstants.DEFAULT_FEE_RATE): Double =
        if (contracts <= 0) 0.0 else total(contracts, price, feeRate)
    const val NON_DIRECT_BALANCE_PRECISION_USD = 0.01
    const val DIRECT_BALANCE_PRECISION_USD = 0.0001

    /**
     * 0.3.43: the ONE balance-rounding knob. docs.kalshi.com/getting_started/fee_rounding (checked 2026-10-09):
     * fees are ceil_6dp, then the balance change is aligned to $0.0001 for DIRECT members and $0.01 for everyone
     * else. A retail app account is non-direct, so the default stays $0.01; set 0.0001 only for a direct member.
     */
    @Volatile var balancePrecisionUsd: Double = NON_DIRECT_BALANCE_PRECISION_USD

    private fun precisionScale(): Int = if (balancePrecisionUsd <= DIRECT_BALANCE_PRECISION_USD + 1e-12) 4 else 2

    /**
     * Ticket line: expected fee ≈ 7% × (1 − P) of stake (since fee = 0.07·C·P·(1−P) and stake = C·P), plus the
     * exact rounded order fee.
     */
    fun expectedFeeLine(contracts: Int, price: Double, feeRate: Double = SignalConstants.DEFAULT_FEE_RATE): String {
        val p = price.coerceIn(0.0, 1.0)
        val stake = contracts * p
        return String.format(
            java.util.Locale.US, "Fee ≈ %.0f%% × (1 − %s) of $%.2f stake = $%.4f → charged $%.2f (rounded up, taker)",
            feeRate * 100, com.dirk.kalshiodds.domain.KalshiQuoteDisplay.formatPriceCents(p), stake,
            feeRate * (1 - p) * stake, total(contracts, p, feeRate)
        )
    }

    fun clipPrice(price: Double): Double =
        price.coerceIn(KalshiPrice.MIN_TICK_DOLLARS, KalshiPrice.MAX_TICK_DOLLARS)

    fun contractsForStake(stakeUsd: Double, price: Double): Int {
        val p = KalshiPrice.usable(price) ?: return 0
        if (!stakeUsd.isFinite() || stakeUsd <= 0.0) return 0
        return floor((stakeUsd / p) + 1e-9).toInt().coerceAtLeast(0)
    }

    /** Unrounded model fee `coef × C × P × (1 − P)`. */
    fun raw(contracts: Int, price: Double, feeRate: Double = SignalConstants.DEFAULT_FEE_RATE): Double =
        modelFeeBd(contracts, price, feeRate).toDouble()

    /** Fee-model trade fee, rounded up to $0.000001. */
    fun ceil6dp(dollars: Double): Double {
        if (!dollars.isFinite() || dollars <= 0.0) return 0.0
        return bd(dollars).setScale(6, RoundingMode.CEILING).toDouble()
    }

    /** Round up so the dollar amount sits on a $0.01 grid. */
    fun ceilCent(dollars: Double): Double {
        if (!dollars.isFinite() || dollars <= 0.0) return 0.0
        return bd(dollars).setScale(2, RoundingMode.CEILING).toDouble()
    }

    /** @deprecated Use [ceilCent]; kept for existing call sites / tests. */
    fun roundUpToCent(dollars: Double): Double = ceilCent(dollars)

    /**
     * Order-level net fee for [contracts] at [price] (non-direct member).
     * Not a per-contract next-cent round-up.
     */
    fun total(contracts: Int, price: Double, feeRate: Double = SignalConstants.DEFAULT_FEE_RATE): Double {
        val n = contracts.coerceAtLeast(0)
        if (n <= 0) return 0.0
        val position = positionBd(n, price)
        return debitBd(n, price, feeRate).subtract(position).max(BigDecimal.ZERO).toDouble()
    }

    fun totalCost(contracts: Int, price: Double, feeRate: Double = SignalConstants.DEFAULT_FEE_RATE): Double {
        val n = contracts.coerceAtLeast(0)
        if (n <= 0) return 0.0
        return debitBd(n, price, feeRate).toDouble()
    }

    fun settlementPayout(contracts: Int): Double =
        contracts.coerceAtLeast(0) * SignalConstants.CONTRACT_SETTLEMENT_USD

    /**
     * Settlement dollars if the side wins. Fee is not subtracted here —
     * it was already paid on the buy. [price] / [feeRate] are unused and
     * kept so existing call sites compile.
     */
    @Suppress("UNUSED_PARAMETER")
    fun netPayout(contracts: Int, price: Double, feeRate: Double = SignalConstants.DEFAULT_FEE_RATE): Double =
        settlementPayout(contracts)

    /** Profit if the side wins: settlement − (C×P + order fee). */
    fun netProfit(contracts: Int, vwap: Double, feeRate: Double = SignalConstants.DEFAULT_FEE_RATE): Double {
        val n = contracts.coerceAtLeast(0)
        if (n <= 0) return 0.0
        return settlementPayout(n) - totalCost(n, vwap, feeRate)
    }

    /**
     * `C × $1 / (C×P + orderFee)` at [stakeUsd]. `C = floor(stake / P)`.
     * Null when the ask is unusable or cannot buy one contract.
     */
    fun payoutMultiple(
        price: Double,
        stakeUsd: Double = SignalConstants.DEFAULT_TICKET_STAKE_USD,
        feeRate: Double = SignalConstants.DEFAULT_FEE_RATE
    ): Double? {
        val p = KalshiPrice.usable(price) ?: return null
        val c = contractsForStake(stakeUsd, p)
        if (c <= 0) return null
        val cost = totalCost(c, p, feeRate)
        if (!cost.isFinite() || cost <= 0.0) return null
        return settlementPayout(c) / cost
    }

    /**
     * Order fee amortized across the contracts a [stakeUsd] ticket buys.
     * Net-EV, edge gates, and auto-tune use this so they match the card
     * multiple. One-contract fee is [total] `(1, price)`.
     */
    fun perContract(
        price: Double,
        feeRate: Double = SignalConstants.DEFAULT_FEE_RATE,
        stakeUsd: Double = SignalConstants.DEFAULT_TICKET_STAKE_USD
    ): Double {
        val p = clipPrice(price)
        val c = contractsForStake(stakeUsd, p).coerceAtLeast(1)
        return total(c, p, feeRate) / c.toDouble()
    }

    private fun bd(dollars: Double): BigDecimal = BigDecimal.valueOf(dollars)

    /** Snap to Kalshi FixedPointDollars (4 decimal places). */
    private fun priceBd(price: Double): BigDecimal =
        bd(clipPrice(price)).setScale(4, RoundingMode.HALF_UP)

    private fun modelFeeBd(contracts: Int, price: Double, feeRate: Double): BigDecimal {
        val n = BigDecimal.valueOf(contracts.coerceAtLeast(0).toLong())
        val p = priceBd(price)
        val r = bd(feeRate.coerceIn(0.0, 0.25))
        return r.multiply(n).multiply(p).multiply(BigDecimal.ONE.subtract(p)).max(BigDecimal.ZERO)
    }

    private fun positionBd(contracts: Int, price: Double): BigDecimal =
        priceBd(price).multiply(BigDecimal.valueOf(contracts.coerceAtLeast(0).toLong()))

    private fun debitBd(contracts: Int, price: Double, feeRate: Double): BigDecimal {
        val trade = modelFeeBd(contracts, price, feeRate).setScale(6, RoundingMode.CEILING)
        return positionBd(contracts, price).add(trade).setScale(precisionScale(), RoundingMode.CEILING)
    }
}
