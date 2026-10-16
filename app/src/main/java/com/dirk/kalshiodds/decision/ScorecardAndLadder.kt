package com.dirk.kalshiodds.decision

import com.dirk.kalshiodds.signal.paper.PaperFill
import com.dirk.kalshiodds.signal.trade.KalshiFee
import java.util.Locale

/**
 * Honest paper scorecard: every settled fill, fee included, no synthetic
 * clip from an ask. Shows P&L excluding the single biggest win, ROI per
 * dollar with a market-clustered bootstrap CI, and a price-bucket
 * breakdown with sub-10¢ fills in their own bucket (and out of the
 * headline).
 */
object HonestScorecard {
    const val LONGSHOT_ASK = 0.10

    data class Row(val ask: Double, val pnlUsd: Double, val stakeUsd: Double, val market: String = "")

    data class PriceBucket(val label: String, val n: Int, val pnlUsd: Double, val stakeUsd: Double)

    data class View(
        val rawPnlUsd: Double,
        val scoredPnlUsd: Double,
        val excludingBiggestWinUsd: Double,
        val biggestWinUsd: Double,
        val longshotUnscoredUsd: Double,
        val longshotCount: Int,
        val scoredCount: Int,
        val roiPerDollar: Double?,
        val roiCi95: Pair<Double, Double>?,
        val byPrice: List<PriceBucket>
    )

    fun fromFills(fills: List<PaperFill>): View {
        val settled = fills.filter { it.settled && it.pnlUsd != null && it.limitPrice > 0.0 && it.outcome != "void" }
        return of(settled.map { Row(it.limitPrice, it.pnlUsd ?: 0.0, it.stakeUsd, it.ticker.uppercase()) })
    }

    /** Full history: persisted rows merged with in-memory fills (memory wins per fill id). */
    fun fromHistory(persisted: List<Pair<String, Row>>, fills: List<PaperFill>): View {
        val byId = LinkedHashMap<String, Row>()
        persisted.forEach { (id, r) -> byId[id] = r }
        fills.filter { it.settled && it.pnlUsd != null && it.limitPrice > 0.0 && it.outcome != "void" }
            .forEach { byId[it.id] = Row(it.limitPrice, it.pnlUsd ?: 0.0, it.stakeUsd, it.ticker.uppercase()) }
        return of(byId.values.toList())
    }

    fun of(rows: List<Row>): View {
        val raw = rows.sumOf { it.pnlUsd }
        val longshots = rows.filter { it.ask + 1e-12 < LONGSHOT_ASK }
        val scored = rows.filter { it.ask + 1e-12 >= LONGSHOT_ASK }
        val scoredPnl = scored.sumOf { it.pnlUsd }
        val biggest = scored.filter { it.pnlUsd > 0.0 }.maxOfOrNull { it.pnlUsd } ?: 0.0
        val stake = scored.sumOf { it.stakeUsd }
        val roi = if (stake > 1e-9) scoredPnl / stake else null
        // ROI CI: bootstrap the per-market P&L/stake by resampling markets.
        val perMarket = scored.groupBy { it.market.ifBlank { it.hashCode().toString() } }
            .map { (m, rs) -> m to (rs.sumOf { it.pnlUsd } to rs.sumOf { it.stakeUsd }) }
        val ci = roiCi(perMarket.map { it.second })
        return View(
            rawPnlUsd = raw,
            scoredPnlUsd = scoredPnl,
            excludingBiggestWinUsd = scoredPnl - biggest,
            biggestWinUsd = biggest,
            longshotUnscoredUsd = longshots.sumOf { it.pnlUsd },
            longshotCount = longshots.size,
            scoredCount = scored.size,
            roiPerDollar = roi,
            roiCi95 = ci,
            byPrice = priceBuckets(rows)
        )
    }

    /** Ratio-of-sums bootstrap over markets (seeded). */
    fun roiCi(perMarket: List<Pair<Double, Double>>, resamples: Int = 2000): Pair<Double, Double>? {
        if (perMarket.size < 2) return null
        val rnd = java.util.Random(ClusteredBootstrap.SEED)
        val stats = DoubleArray(resamples)
        for (b in 0 until resamples) {
            var pnl = 0.0
            var stake = 0.0
            repeat(perMarket.size) {
                val m = perMarket[rnd.nextInt(perMarket.size)]
                pnl += m.first
                stake += m.second
            }
            stats[b] = if (stake > 1e-9) pnl / stake else 0.0
        }
        stats.sort()
        return stats[(0.025 * resamples).toInt()] to stats[(0.975 * resamples).toInt().coerceAtMost(resamples - 1)]
    }

    fun lines(view: View): List<String> {
        val roi = view.roiPerDollar?.let { String.format(Locale.US, "%+.1f%%", it * 100) } ?: "—"
        val ci = view.roiCi95?.let { (lo, hi) -> String.format(Locale.US, "[%+.1f%%, %+.1f%%]", lo * 100, hi * 100) } ?: "— (need ≥2 markets)"
        return listOf(
            String.format(Locale.US, "Scored P&L (≥10¢, fees in) %+.2f on %d fills", view.scoredPnlUsd, view.scoredCount),
            String.format(Locale.US, "Excluding the top win (%+.2f): %+.2f", view.biggestWinUsd, view.excludingBiggestWinUsd),
            "ROI $roi  95% CI $ci (market-clustered bootstrap)",
            String.format(Locale.US, "Sub-10¢ shown separately: %+.2f · %d fills (not in headline)", view.longshotUnscoredUsd, view.longshotCount),
            String.format(Locale.US, "All settled fills %+.2f", view.rawPnlUsd)
        ) + view.byPrice.map { b ->
            String.format(Locale.US, "%s  n=%d  %+.2f on $%.2f", b.label, b.n, b.pnlUsd, b.stakeUsd)
        }
    }

    fun priceBuckets(rows: List<Row>): List<PriceBucket> {
        val bands = listOf(
            "<10¢ (separate)" to { a: Double -> a < 0.10 },
            "10–30¢" to { a: Double -> a >= 0.10 && a < 0.30 },
            "30–50¢" to { a: Double -> a >= 0.30 && a < 0.50 },
            "50–70¢" to { a: Double -> a >= 0.50 && a < 0.70 },
            "70–90¢" to { a: Double -> a >= 0.70 && a < 0.90 },
            "≥90¢" to { a: Double -> a >= 0.90 }
        )
        return bands.map { (label, pred) ->
            val hit = rows.filter { pred(it.ask) }
            PriceBucket(label, hit.size, hit.sumOf { it.pnlUsd }, hit.sumOf { it.stakeUsd })
        }
    }

    /**
     * Realized paper P&L with the fee on every fill. If the stored stake
     * already includes the fee it is not added twice.
     */
    fun pnlAfterFee(contracts: Int, price: Double, stakeUsd: Double, won: Boolean?, voided: Boolean, feeRate: Double = 0.07): Double {
        val fee = KalshiFee.total(contracts, price, feeRate)
        val premium = contracts * price
        val cost = if (stakeUsd + 1e-6 >= premium + fee) stakeUsd else premium + fee
        if (voided) return 0.0
        val payout = if (won == true) contracts * 1.0 else 0.0
        return payout - cost
    }
}

/**
 * CF 60-second-average fair value: Kalshi settles on the simple average of
 * the 60 CF index prints before close. Inside the final minute the part
 * already printed is fixed (CF's running final-minute average); only the
 * remaining seconds can move.
 */
object CfFairValue {
    fun expectedSettlement(spot: Double, runningFinalAvg: Double?, secondsRemaining: Double): Double {
        if (secondsRemaining >= 60.0 || runningFinalAvg == null || !runningFinalAvg.isFinite()) return spot
        if (secondsRemaining <= 0.0) return runningFinalAvg
        val fixed = 60.0 - secondsRemaining
        return (runningFinalAvg * fixed + spot * secondsRemaining) / 60.0
    }
}

/** LIP (liquidity-incentive / coin-race) study is stopped. No screen offers it. */
object LipStudy {
    const val ENABLED = false
    const val NOTE = "LIP liquidity-reward study stopped by owner 2026-10-06 — hidden."
}
