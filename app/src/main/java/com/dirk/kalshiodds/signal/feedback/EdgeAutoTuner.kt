package com.dirk.kalshiodds.signal.feedback

import com.dirk.kalshiodds.prediction.PredictionLogEntry
import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.trade.KalshiFee
import kotlin.math.ln

/**
 * Pick the minimum edge (after fees) that maximizes realized / expected
 * value on settled signals, and decide whether the model may say BET at all.
 *
 * It may only when its own settled record shows it: enough calls, better
 * than Kalshi's prices on both scores, and an average result per call that
 * is above zero by two standard errors. With no record, a losing record or a
 * record within chance, it sits out. (Until 1.8.8 a model with no record was
 * allowed to call bets, and any positive total counted.)
 */
object EdgeAutoTuner {

    data class Sample(
        val modelYes: Double,
        val marketMid: Double,
        val outcomeYes: Boolean,
        val edgeAfterFeesPp: Double,
        val stakeUsd: Double = 1.0,
        /** UTC day of the market close (closeTimeMs / 86400000) — bootstrap block. */
        val closeDay: Long = 0L
    )

    data class Result(
        val sitOut: Boolean,
        val thresholdPp: Double,
        val n: Int,
        val enoughSamples: Boolean,
        val modelBrier: Double?,
        val marketBrier: Double?,
        val modelLogLoss: Double?,
        val marketLogLoss: Double?,
        val evAtThreshold: Double?,
        /** Day-block bootstrap: 10th percentile of per-bet EV across runs. */
        val evP10: Double? = null,
        val reason: String
    ) {
        val beatsMarket: Boolean
            get() {
                val brierOk = modelBrier != null && marketBrier != null && modelBrier < marketBrier
                val llOk = modelLogLoss != null && marketLogLoss != null && modelLogLoss < marketLogLoss
                return brierOk && llOk
            }
    }

    val CANDIDATE_PP: DoubleArray = doubleArrayOf(
        0.5, 1.0, 1.5, 2.0, 3.0, 4.0, 5.0, 6.0, 8.0, 10.0, 12.0, 15.0
    )

    fun fromEntries(
        entries: List<PredictionLogEntry>,
        feeRate: Double = SignalConstants.DEFAULT_FEE_RATE,
        minSamples: Int = SignalConstants.AUTO_TUNE_MIN_SAMPLES
    ): Result {
        val samples = entries.mapNotNull { e ->
            val outcome = e.outcome?.lowercase() ?: return@mapNotNull null
            if (outcome != "yes" && outcome != "no") return@mapNotNull null
            val mid = e.marketMid.coerceIn(0.02, 0.98)
            val model = e.predictedYes.coerceIn(0.02, 0.98)
            val fee = KalshiFee.perContract(mid, feeRate, 1.0)
            val rawEdge = kotlin.math.abs(model - mid)
            val afterFees = (rawEdge - fee) * 100.0
            Sample(
                modelYes = model,
                marketMid = mid,
                outcomeYes = outcome == "yes",
                edgeAfterFeesPp = afterFees,
                stakeUsd = 1.0,
                closeDay = (e.closeTimeMs ?: e.timestampMs) / 86_400_000L
            )
        }
        return tune(samples, minSamples)
    }

    fun tune(
        samples: List<Sample>,
        minSamples: Int = SignalConstants.AUTO_TUNE_MIN_SAMPLES
    ): Result {
        if (samples.size < minSamples) {
            // No record is not a reason to bet: the model stays quiet until it has one.
            return Result(
                sitOut = true,
                thresholdPp = SignalConstants.DEFAULT_EDGE_THRESHOLD_PP,
                n = samples.size,
                enoughSamples = false,
                modelBrier = brier(samples) { it.modelYes },
                marketBrier = brier(samples) { it.marketMid },
                modelLogLoss = logLoss(samples) { it.modelYes },
                marketLogLoss = logLoss(samples) { it.marketMid },
                evAtThreshold = null,
                reason = "No bet calls yet: the model has ${samples.size} settled calls and needs $minSamples before its record means anything."
            )
        }
        val modelBrier = brier(samples) { it.modelYes }!!
        val marketBrier = brier(samples) { it.marketMid }!!
        val modelLl = logLoss(samples) { it.modelYes }!!
        val marketLl = logLoss(samples) { it.marketMid }!!
        val beats = modelBrier < marketBrier && modelLl < marketLl

        var bestThreshold = CANDIDATE_PP.last()
        var bestEv = Double.NEGATIVE_INFINITY
        var bestN = 0
        var bestTaken: List<Sample> = emptyList()
        for (t in CANDIDATE_PP) {
            val taken = samples.filter { it.edgeAfterFeesPp + 1e-12 >= t }
            // A threshold is only a candidate if enough bets fire at it: an EV built
            // from a handful of settlements is one lucky day.
            if (taken.size < SignalConstants.AUTO_TUNE_MIN_BETS) continue
            val ev = expectedValue(taken)
            if (ev > bestEv + 1e-12 || (kotlin.math.abs(ev - bestEv) <= 1e-12 && t < bestThreshold)) {
                bestEv = ev
                bestThreshold = t
                bestN = taken.size
                bestTaken = taken
            }
        }

        val negativeEv = bestEv.isFinite() && bestEv <= 0.0
        // A positive total is not enough: a handful of lucky calls gives one too. The average result per
        // call has to be above zero by two standard errors before the model is allowed to say BET, and
        // when the record spans several days its EV must also survive day resampling (one lucky day).
        val withinChance = !negativeEv && bestTaken.isNotEmpty() && lowerBound(bestTaken) <= 0.0
        val bootP10 = bootstrapEvP10(samples, bestThreshold)
        val failsResampling = bootP10 != null && bootP10 <= 0.0
        val sitOut = !beats || negativeEv || failsResampling || withinChance || bestEv == Double.NEGATIVE_INFINITY
        val reason = when {
            !beats && negativeEv ->
                "The model hasn't beaten Kalshi's prices in testing, and this bet's expected value is negative."
            !beats ->
                "The model hasn't beaten Kalshi's prices in testing."
            negativeEv ->
                "This bet's expected value is negative."
            bestEv == Double.NEGATIVE_INFINITY ->
                "Not enough similar bets after fees to size a threshold."
            failsResampling ->
                "EV is positive but does not survive day resampling (10th pct ${fmt(bootP10 ?: 0.0)}) — sitting out."
            withinChance ->
                "The model's $bestN settled calls came out ahead, but by no more than luck would give. No bet calls until the record is clear of chance."
            else ->
                "Auto-tune ${fmt(bestThreshold)} pp · EV ${fmt(bestEv)}${bootP10?.let { " (10th pct ${fmt(it)})" } ?: ""} on $bestN / ${samples.size} signals."
        }
        return Result(
            sitOut = sitOut,
            thresholdPp = if (sitOut) bestThreshold else bestThreshold,
            n = samples.size,
            enoughSamples = true,
            modelBrier = modelBrier,
            marketBrier = marketBrier,
            modelLogLoss = modelLl,
            marketLogLoss = marketLl,
            evAtThreshold = bestEv.takeIf { it.isFinite() && it != Double.NEGATIVE_INFINITY },
            evP10 = bootP10,
            reason = reason
        )
    }

    private fun expectedValue(taken: List<Sample>): Double {
        if (taken.isEmpty()) return Double.NEGATIVE_INFINITY
        return taken.sumOf { pnlOf(it) }
    }

    /** What one call made per contract: bought at the market's price on the side the model leaned to, fee paid. */
    private fun pnlOf(s: Sample): Double {
        val sideYes = s.modelYes >= s.marketMid
        val won = sideYes == s.outcomeYes
        val price = if (sideYes) s.marketMid else 1.0 - s.marketMid
        val fee = KalshiFee.perContract(price, SignalConstants.DEFAULT_FEE_RATE, s.stakeUsd)
        val pnl = if (won) (1.0 - price - fee) else (-price - fee)
        return pnl * s.stakeUsd
    }

    /** Average result per call minus two standard errors. Above zero = ahead by more than chance. */
    internal fun lowerBound(taken: List<Sample>): Double {
        if (taken.size < 2) return Double.NEGATIVE_INFINITY
        val pnl = taken.map { pnlOf(it) }
        val mean = pnl.average()
        val variance = pnl.sumOf { (it - mean) * (it - mean) } / (pnl.size - 1)
        return mean - 2.0 * kotlin.math.sqrt(variance / pnl.size)
    }

    private fun brier(samples: List<Sample>, p: (Sample) -> Double): Double? {
        if (samples.isEmpty()) return null
        return samples.map { s ->
            val y = if (s.outcomeYes) 1.0 else 0.0
            val d = p(s) - y
            d * d
        }.average()
    }

    private fun logLoss(samples: List<Sample>, p: (Sample) -> Double): Double? {
        if (samples.isEmpty()) return null
        return samples.map { s ->
            val y = if (s.outcomeYes) 1.0 else 0.0
            val q = p(s).coerceIn(1e-6, 1.0 - 1e-6)
            -(y * ln(q) + (1.0 - y) * ln(1.0 - q))
        }.average()
    }

    private fun fmt(v: Double): String = String.format(java.util.Locale.US, "%+.2f", v)
    /**
     * Day-block bootstrap EV at [thresholdPp]: resample UTC close days with
     * replacement, re-take the bets at the threshold, and return the 10th
     * percentile of mean per-bet EV across runs. Null when too few days or
     * bets to resample.
     */
    private fun bootstrapEvP10(samples: List<Sample>, thresholdPp: Double): Double? {
        val taken = samples.filter { it.edgeAfterFeesPp + 1e-12 >= thresholdPp }
        if (taken.isEmpty()) return null
        val byDay = taken.groupBy { it.closeDay }.values.toList()
        if (byDay.size < 2) return null
        val rng = java.util.Random(2026L)
        val runs = SignalConstants.AUTO_TUNE_BOOTSTRAP_RUNS
        val means = DoubleArray(runs) {
            var pnl = 0.0
            var stake = 0.0
            repeat(byDay.size) {
                val day = byDay[rng.nextInt(byDay.size)]
                for (s in day) {
                    val sideYes = s.modelYes >= s.marketMid
                    val won = sideYes == s.outcomeYes
                    val price = if (sideYes) s.marketMid else 1.0 - s.marketMid
                    val fee = KalshiFee.perContract(price, SignalConstants.DEFAULT_FEE_RATE, s.stakeUsd)
                    pnl += (if (won) (1.0 - price - fee) else (-price - fee)) * s.stakeUsd
                    stake += s.stakeUsd
                }
            }
            if (stake <= 0.0) Double.NaN else pnl / stake
        }.filter { !it.isNaN() }
        if (means.isEmpty()) return null
        val sorted = means.sorted()
        return sorted[(sorted.size * 0.10).toInt().coerceIn(0, sorted.size - 1)]
    }

    private fun fmt3(v: Double): String = String.format(java.util.Locale.US, "%.3f", v)
}
