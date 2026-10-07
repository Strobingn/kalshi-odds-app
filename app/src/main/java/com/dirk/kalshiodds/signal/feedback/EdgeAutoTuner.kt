package com.dirk.kalshiodds.signal.feedback

import com.dirk.kalshiodds.prediction.PredictionLogEntry
import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.trade.KalshiFee
import kotlin.math.ln
import kotlin.math.max
import java.util.Random

/**
 * Pick the minimum edge (after fees) that maximizes realized / expected
 * value on settled signals. When the model is not beating the market,
 * sit out instead of forcing a threshold.
 */
object EdgeAutoTuner {

    data class Sample(
        val modelYes: Double,
        val marketMid: Double,
        val outcomeYes: Boolean,
        val edgeAfterFeesPp: Double,
        val stakeUsd: Double = 1.0,
        /** UTC event time. Required to prevent row-level leakage in tuning. */
        val timestampMs: Long = 0L
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
        val reason: String,
        val distinctDays: Int = 0,
        val brierAdvantageLower95: Double? = null,
        val evPerTradeLower95: Double? = null
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
        minSamples: Int = SignalConstants.AUTO_TUNE_MIN_SAMPLES,
        minDays: Int = SignalConstants.AUTO_TUNE_MIN_DAYS,
        bootstrapReps: Int = SignalConstants.AUTO_TUNE_BOOTSTRAP_REPS
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
                timestampMs = e.timestampMs
            )
        }
        return tune(samples, minSamples, minDays, bootstrapReps)
    }

    fun tune(
        samples: List<Sample>,
        minSamples: Int = SignalConstants.AUTO_TUNE_MIN_SAMPLES,
        minDays: Int = SignalConstants.AUTO_TUNE_MIN_DAYS,
        bootstrapReps: Int = SignalConstants.AUTO_TUNE_BOOTSTRAP_REPS
    ): Result {
        val dayCount = samples.map { utcDay(it.timestampMs) }.distinct().size
        if (samples.size < minSamples) {
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
                reason = "Sitting out: need $minSamples settled paper signals (${samples.size}/$minSamples).",
                distinctDays = dayCount
            )
        }
        if (dayCount < minDays) {
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
                reason = "Sitting out: need $minDays independent UTC days ($dayCount/$minDays).",
                distinctDays = dayCount
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
        for (t in CANDIDATE_PP) {
            val taken = samples.filter { it.edgeAfterFeesPp + 1e-12 >= t }
            if (taken.size < minSamples) continue
            val ev = expectedValue(taken)
            if (ev > bestEv + 1e-12 || (kotlin.math.abs(ev - bestEv) <= 1e-12 && t < bestThreshold)) {
                bestEv = ev
                bestThreshold = t
                bestN = taken.size
            }
        }

        val taken = samples.filter { it.edgeAfterFeesPp + 1e-12 >= bestThreshold }
        val evidence = bootstrapByDay(samples, taken, bootstrapReps)
        val stableBrier = evidence.brierLower95 >= SignalConstants.AUTO_TUNE_MIN_BRIER_ADVANTAGE
        val stableEv = evidence.evPerTradeLower95 > 0.0
        val negativeEv = bestEv.isFinite() && bestEv <= 0.0
        val sitOut = !beats || !stableBrier || !stableEv || negativeEv || bestEv == Double.NEGATIVE_INFINITY
        val reason = when {
            !beats && negativeEv ->
                "The model hasn't beaten Kalshi's prices in testing, and this bet's expected value is negative."
            !beats ->
                "The model hasn't beaten Kalshi's prices in testing."
            !stableBrier ->
                "Sitting out: daily-resampled Brier advantage ${fmt3(evidence.brierLower95)} is below ${fmt3(SignalConstants.AUTO_TUNE_MIN_BRIER_ADVANTAGE)}."
            !stableEv ->
                "Sitting out: daily-resampled paper EV is not positive after fees."
            negativeEv ->
                "This bet's expected value is negative."
            bestEv == Double.NEGATIVE_INFINITY ->
                "Not enough similar bets after fees to size a threshold."
            else ->
                "Auto-tune ${fmt(bestThreshold)} pp · resampled EV ${fmt(evidence.evPerTradeLower95)} per trade on $bestN / ${samples.size} signals across $dayCount days."
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
            reason = reason,
            distinctDays = dayCount,
            brierAdvantageLower95 = evidence.brierLower95,
            evPerTradeLower95 = evidence.evPerTradeLower95
        )
    }

    private data class BootstrapEvidence(val brierLower95: Double, val evPerTradeLower95: Double)

    /**
     * Resample whole UTC days, not individual rows. Rows in a 15-minute
     * session are correlated; row-level bootstrap makes weak runs look real.
     */
    private fun bootstrapByDay(
        all: List<Sample>,
        taken: List<Sample>,
        reps: Int
    ): BootstrapEvidence {
        val allByDay = all.groupBy { sample -> utcDay(sample.timestampMs) }.values.toList()
        val takenByDay = taken.groupBy { sample -> utcDay(sample.timestampMs) }
        if (allByDay.isEmpty() || takenByDay.isEmpty()) return BootstrapEvidence(0.0, Double.NEGATIVE_INFINITY)
        val days = allByDay.map { utcDay(it.first().timestampMs) }
        val rng = Random(0xD1A10L)
        val brierDeltas = ArrayList<Double>(reps)
        val ev = ArrayList<Double>(reps)
        repeat(reps.coerceAtLeast(100)) {
            val sampledAll = ArrayList<Sample>()
            val sampledTaken = ArrayList<Sample>()
            repeat(days.size) {
                val day = days[rng.nextInt(days.size)]
                sampledAll += allByDay.first { utcDay(it.first().timestampMs) == day }
                sampledTaken += takenByDay[day].orEmpty()
            }
            val delta = (brier(sampledAll) { it.marketMid } ?: 0.0) - (brier(sampledAll) { it.modelYes } ?: 0.0)
            brierDeltas += delta
            ev += if (sampledTaken.isEmpty()) Double.NEGATIVE_INFINITY else expectedValue(sampledTaken) / sampledTaken.size
        }
        // MutableList.sort() is a stdlib extension and has failed CI as an unresolved reference.
        java.util.Collections.sort(brierDeltas)
        java.util.Collections.sort(ev)
        val q = max(0, ((brierDeltas.size - 1) * 0.05).toInt())
        return BootstrapEvidence(brierDeltas[q], ev[q])
    }

    private fun utcDay(timestampMs: Long): Long = timestampMs.coerceAtLeast(0L) / 86_400_000L

    private fun expectedValue(taken: List<Sample>): Double {
        if (taken.isEmpty()) return Double.NEGATIVE_INFINITY
        return taken.sumOf { s ->
            val sideYes = s.modelYes >= s.marketMid
            val won = sideYes == s.outcomeYes
            val price = if (sideYes) s.marketMid else 1.0 - s.marketMid
            val fee = KalshiFee.perContract(price, SignalConstants.DEFAULT_FEE_RATE, s.stakeUsd)
            val pnl = if (won) (1.0 - price - fee) else (-price - fee)
            pnl * s.stakeUsd
        }
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
    private fun fmt3(v: Double): String = String.format(java.util.Locale.US, "%.3f", v)
}
