package com.dirk.kalshiodds.decision

import com.dirk.kalshiodds.signal.paper.PaperFill
import com.dirk.kalshiodds.signal.trade.KalshiFee
import java.util.Locale
import kotlin.math.abs

/**
 * Headline paper P&L excludes sub-10¢ fills. Those are shown separately
 * as longshot, unscored. The raw total stays available. Money never
 * invents a $10 clip from an ask that has no fill.
 */
object HonestScorecard {
    const val LONGSHOT_ASK = 0.10

    data class Row(val ask: Double, val pnlUsd: Double, val stakeUsd: Double)

    data class View(
        val rawPnlUsd: Double,
        val scoredPnlUsd: Double,
        val excludingBiggestWinUsd: Double,
        val longshotUnscoredUsd: Double,
        val longshotCount: Int,
        val roiPerDollar: Double?,
        val roiCi95: Pair<Double, Double>?,
        val byPrice: List<PriceBucket>
    )

    data class PriceBucket(val label: String, val n: Int, val pnlUsd: Double)

    fun fromFills(fills: List<PaperFill>): View {
        val settled = fills.filter { it.settled && it.pnlUsd != null && it.limitPrice > 0.0 }
        val rows = settled.map { Row(it.limitPrice, it.pnlUsd ?: 0.0, it.stakeUsd) }
        return of(rows)
    }

    fun of(rows: List<Row>): View {
        val raw = rows.sumOf { it.pnlUsd }
        val longshots = rows.filter { it.ask + 1e-12 < LONGSHOT_ASK }
        val scored = rows.filter { it.ask + 1e-12 >= LONGSHOT_ASK }
        val scoredPnl = scored.sumOf { it.pnlUsd }
        val biggest = scored.filter { it.pnlUsd > 0.0 }.maxOfOrNull { it.pnlUsd } ?: 0.0
        val ratios = scored.mapNotNull { row ->
            if (row.stakeUsd <= 1e-9) null else row.pnlUsd / row.stakeUsd
        }
        val roi = ratios.takeIf { it.isNotEmpty() }?.average()
        return View(
            rawPnlUsd = raw,
            scoredPnlUsd = scoredPnl,
            excludingBiggestWinUsd = scoredPnl - biggest,
            longshotUnscoredUsd = longshots.sumOf { it.pnlUsd },
            longshotCount = longshots.size,
            roiPerDollar = roi,
            roiCi95 = ratios.takeIf { it.isNotEmpty() }?.let { DecisionMath.meanCi95(it) },
            byPrice = priceBuckets(scored)
        )
    }

    fun lines(view: View): List<String> {
        val roi = view.roiPerDollar?.let { String.format(Locale.US, "%+.3f", it) } ?: "—"
        val ci = view.roiCi95?.let { (lo, hi) ->
            String.format(Locale.US, "[%+.3f, %+.3f]", lo, hi)
        } ?: "—"
        return listOf(
            String.format(Locale.US, "Scored P&L %+.2f", view.scoredPnlUsd),
            String.format(Locale.US, "Excluding biggest win %+.2f", view.excludingBiggestWinUsd),
            "Per-dollar ROI $roi  95% CI $ci",
            String.format(
                Locale.US,
                "Longshot, unscored %+.2f · %d fills under 10¢",
                view.longshotUnscoredUsd,
                view.longshotCount
            ),
            String.format(Locale.US, "Raw P&L %+.2f", view.rawPnlUsd)
        ) + view.byPrice.map { b ->
            String.format(Locale.US, "%s  n=%d  %+.2f", b.label, b.n, b.pnlUsd)
        }
    }

    private fun priceBuckets(rows: List<Row>): List<PriceBucket> {
        val bands = listOf(
            "<10¢" to { a: Double -> a < 0.10 },
            "10–30¢" to { a: Double -> a >= 0.10 && a < 0.30 },
            "30–70¢" to { a: Double -> a >= 0.30 && a < 0.70 },
            "70–90¢" to { a: Double -> a >= 0.70 && a <= 0.90 },
            ">90¢" to { a: Double -> a > 0.90 }
        )
        return bands.map { (label, pred) ->
            val hit = rows.filter { pred(it.ask) }
            PriceBucket(label, hit.size, hit.sumOf { it.pnlUsd })
        }
    }

    /** Fee included in realized P&L. If stake already contains the fee, do not add it twice. */
    fun pnlAfterFee(contracts: Int, price: Double, stakeUsd: Double, won: Boolean?, voided: Boolean, feeRate: Double = 0.07): Double {
        if (voided) return 0.0
        val fee = KalshiFee.total(contracts, price, feeRate)
        val premium = contracts * price
        val cost = if (stakeUsd + 1e-6 >= premium + fee) stakeUsd else stakeUsd + fee
        val payout = if (won == true) contracts * 1.0 else 0.0
        return payout - cost
    }
}

/**
 * Paper promotion ladder. Live still needs Approve + REAL MONEY.
 * The 15m directional model is a filter unless the champion gate passed.
 */
object StrategyLadder {
    enum class Id { V060, V150, FAV15, DIRECTIONAL_15M }
    enum class Stage { PAPER, SHADOW_ELIGIBLE, LIMITED_LIVE_ELIGIBLE }

    data class Stats(val filled: Int, val settled: Int, val meanAfterFees: Double, val ciLow: Double)

    data class Status(val id: Id, val stage: Stage, val reason: String)

    fun v060(stats: Stats): Status = promote(Id.V060, stats, needFilled = 100, useFilled = true)
    fun v150(stats: Stats): Status = promote(Id.V150, stats, needFilled = 100, useFilled = true)
    fun fav15(stats: Stats): Status = promote(Id.FAV15, stats, needFilled = 300, useFilled = false)

    fun directional(championGatePassed: Boolean): Status = if (championGatePassed) {
        Status(Id.DIRECTIONAL_15M, Stage.LIMITED_LIVE_ELIGIBLE, "Champion gate passed — filter may send after Approve + REAL MONEY")
    } else {
        Status(Id.DIRECTIONAL_15M, Stage.PAPER, "15m model is a filter only until the champion gate passes")
    }

    private fun promote(id: Id, stats: Stats, needFilled: Int, useFilled: Boolean): Status {
        val n = if (useFilled) stats.filled else stats.settled
        val ok = n >= needFilled && stats.meanAfterFees > 0.0 && stats.ciLow > 0.0
        val noun = if (useFilled) "filled bets" else "settled first entries"
        return if (ok) {
            Status(id, Stage.LIMITED_LIVE_ELIGIBLE, "$n $noun, mean ${fmt(stats.meanAfterFees)}, CI low ${fmt(stats.ciLow)} — eligible for shadow, then limited live after Approve + REAL MONEY")
        } else {
            Status(id, Stage.PAPER, "Need $needFilled $noun with positive mean and CI low > 0 (now $n, mean ${fmt(stats.meanAfterFees)}, CI low ${fmt(stats.ciLow)})")
        }
    }

    private fun fmt(v: Double) = String.format(java.util.Locale.US, "%+.4f", v)

    fun rulesText(): String = buildString {
        append("v060 daily BTC favourite at the ask: 100 filled bets. ")
        append("v150 D3 maker bid 85–97¢ from 2–4 PM: 100 filled bets. ")
        append("fav15 buy the favourite when the cheaper ask is 2–15¢ with ≥5 min left, first entry per market: 300 settled. ")
        append("Positive mean after fees and 95% CI lower bound > 0 promotes paper → shadow → limited live. ")
        append("Live still requires Approve + REAL MONEY. No hard dollar cap.")
    }
}

/** Coin-race / liquidity-reward study is off. The screen must not offer it. */
object CoinRaceStudy {
    const val ENABLED = false
    const val HIDDEN = true
}

object CfSettlementFair {
    /**
     * Last 60–120s feature: blend the live index toward the running
     * 60-second settlement average. Used as a feature and a guard, not a bet.
     */
    fun reference(spot: Double, runningAverage: Double?, secondsRemaining: Double): Double {
        if (runningAverage == null || !runningAverage.isFinite() || secondsRemaining > 120.0) return spot
        if (secondsRemaining <= 0.0) return runningAverage
        val w = ((120.0 - secondsRemaining) / 120.0).coerceIn(0.0, 1.0)
        return spot * (1.0 - w) + runningAverage * w
    }
}

fun absOrZero(v: Double?): Double = if (v == null || !v.isFinite()) 0.0 else abs(v)
