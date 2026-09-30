package com.dirk.kalshiarb.scan

/**
 * Kalshi taker fee for one buy order, in exact integer money.
 *
 * Same math as Dip Hunter's `KalshiFee.totalCost` and
 * `tools/backtest/pipeline.py::kalshi_total_cost` (copied, not shared):
 *
 *     trade_fee   = ceil_6dp(0.07 × C × P × (1 − P))
 *     total_debit = ceil_cent(C × P + trade_fee)
 *     order_fee   = total_debit − C × P
 *
 * Prices are Kalshi FixedPointDollars in 1/10 000 dollar units ("E4",
 * so $0.45 = 4500). An order that sweeps several book levels is modeled as
 * one order: each level's model fee is rounded up to $0.000001 on its own
 * (slightly conservative versus a single rounding), then the order's total
 * debit is rounded up to the cent once. For one level this is exactly
 * `KalshiFee.totalCost(C, P)`.
 *
 * Pure math, never trades.
 */
object ArbFee {

    /** 0.07 taker coefficient as 7/100. */
    private const val TAKER_NUM = 7L

    /** Total cash debit in cents to buy all of [fills] as one taker order. */
    fun debitCents(fills: List<Level>): Long {
        var positionE4 = 0L
        var tradeMicro = 0L
        for (f in fills) {
            if (f.qty <= 0L) continue
            val p = f.priceE4.toLong().coerceIn(1L, 9_999L)
            positionE4 += f.qty * p
            // 0.07·C·P·(1−P) dollars = 7·C·p·(10000−p) / 1e10 dollars
            //                        = 7·C·p·(10000−p) / 1e4 micro-dollars.
            tradeMicro += ceilDiv(TAKER_NUM * f.qty * p * (10_000L - p), 10_000L)
        }
        if (positionE4 <= 0L) return 0L
        val totalMicro = positionE4 * 100L + tradeMicro
        return ceilDiv(totalMicro, 10_000L)
    }

    /** Debit for [contracts] at a single price. */
    fun debitCents(contracts: Long, priceE4: Int): Long =
        debitCents(listOf(Level(priceE4, contracts)))

    /** Fee part of the debit, in 1/10 000 dollars (debit − C·P). */
    fun feeE4(fills: List<Level>): Long {
        val position = fills.sumOf { if (it.qty > 0) it.qty * it.priceE4.coerceIn(1, 9_999) else 0L }
        if (position <= 0L) return 0L
        return (debitCents(fills) * 100L - position).coerceAtLeast(0L)
    }

    fun feeE4(contracts: Long, priceE4: Int): Long = feeE4(listOf(Level(priceE4, contracts)))

    internal fun ceilDiv(a: Long, b: Long): Long {
        require(b > 0)
        if (a <= 0L) return -((-a) / b)
        return (a + b - 1) / b
    }
}
