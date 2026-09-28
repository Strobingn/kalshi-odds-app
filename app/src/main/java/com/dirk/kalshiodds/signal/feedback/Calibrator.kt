package com.dirk.kalshiodds.signal.feedback

import com.dirk.kalshiodds.prediction.PredictionLogEntry
import com.dirk.kalshiodds.signal.config.SignalConstants
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlinx.serialization.Serializable

/**
 * One probability calibrator, fit on the **raw** blend vs outcome,
 * split by time-left bucket. A bucket applies only after
 * [SignalConstants.MIN_CALIBRATION_SAMPLES] settled samples.
 *
 * Displayed (already-calibrated) probabilities are never the training
 * target — that was the feedback loop.
 */
object Calibrator {
    enum class TteBucket(val key: String, val label: String) {
        GT_10M("gt10m", ">10m"),
        M10_5("m10_5", "10–5m"),
        M5_2("m5_2", "5–2m"),
        LT_2M("lt2m", "<2m");

        companion object {
            fun of(tteSeconds: Long?): TteBucket {
                val s = tteSeconds ?: return GT_10M
                return when {
                    s > 600 -> GT_10M
                    s > 300 -> M10_5
                    s > 120 -> M5_2
                    else -> LT_2M
                }
            }

            fun fromKey(key: String?): TteBucket =
                entries.firstOrNull { it.key.equals(key, true) || it.name.equals(key, true) }
                    ?: GT_10M
        }
    }

    @Serializable
    data class ReliabilityBin(
        val lo: Double,
        val hi: Double,
        val meanPredicted: Double = 0.0,
        val meanOutcome: Double = 0.0,
        val n: Int = 0
    )

    @Serializable
    data class BucketState(
        val temperature: Double = 1.0,
        val bins: List<ReliabilityBin> = emptyList(),
        val sampleCount: Int = 0,
        val ready: Boolean = false
    )

    @Serializable
    data class State(
        val temperature: Double = 1.0,
        val bins: List<ReliabilityBin> = emptyList(),
        val sampleCount: Int = 0,
        val ready: Boolean = false,
        val fittedAtMs: Long = 0L,
        val buckets: Map<String, BucketState> = emptyMap()
    )

    data class Sample(
        val pYes: Double,
        val outcomeYes: Boolean,
        val tteSeconds: Long? = null
    )

    fun fit(
        samples: List<Sample>,
        nowMs: Long = System.currentTimeMillis(),
        minSamples: Int = SignalConstants.MIN_CALIBRATION_SAMPLES
    ): State {
        val byBucket = TteBucket.entries.associateWith { bucket ->
            samples.filter { TteBucket.of(it.tteSeconds) == bucket }
        }
        val fitted = byBucket.mapValues { (_, rows) -> fitBucket(rows, minSamples) }
        val total = fitted.values.sumOf { it.sampleCount }
        val anyReady = fitted.values.any { it.ready }
        val legacy = fitted[TteBucket.GT_10M] ?: BucketState()
        return State(
            temperature = legacy.temperature,
            bins = legacy.bins,
            sampleCount = total,
            ready = anyReady,
            fittedAtMs = nowMs,
            buckets = fitted.mapKeys { it.key.key }
        )
    }

    fun fitEntries(entries: List<PredictionLogEntry>, nowMs: Long = System.currentTimeMillis()): State {
        val samples = entries.flatMap { e ->
            val y = when (e.outcome?.lowercase()) {
                "yes" -> true
                "no" -> false
                else -> return@flatMap emptyList()
            }
            val fromLog = e.calSamples.map { s ->
                Sample(s.rawYes, y, s.tteSeconds)
            }
            if (fromLog.isNotEmpty()) return@flatMap fromLog
            val raw = e.rawPredictedYes
            if (raw != null && raw.isFinite()) {
                listOf(Sample(raw, y, e.tteSeconds))
            } else {
                emptyList()
            }
        }
        return fit(samples, nowMs)
    }

    fun apply(pYes: Double, state: State, tteSeconds: Long? = null): Double {
        val p = pYes.coerceIn(0.02, 0.98)
        val bucket = TteBucket.of(tteSeconds)
        val b = state.buckets[bucket.key]
        if (b == null || !b.ready) return p
        val warmed = applyTemperature(p, b.temperature)
        val bin = b.bins.firstOrNull { p >= it.lo && p < it.hi || (it.hi >= 1.0 && p >= it.lo) }
        return if (bin != null && bin.n >= 5) {
            (0.70 * warmed + 0.30 * bin.meanOutcome).coerceIn(0.02, 0.98)
        } else {
            warmed.coerceIn(0.02, 0.98)
        }
    }

    fun applyPp(pYesPp: Double, state: State, tteSeconds: Long? = null): Double =
        apply(pYesPp / 100.0, state, tteSeconds) * 100.0

    fun applyTemperature(p: Double, temperature: Double): Double {
        val t = temperature.coerceIn(0.35, 3.0)
        if (abs(t - 1.0) < 1e-6) return p.coerceIn(0.02, 0.98)
        return sigmoid(logit(p) / t)
    }

    fun logit(p: Double): Double {
        val x = p.coerceIn(1e-4, 1.0 - 1e-4)
        return ln(x / (1.0 - x))
    }

    fun sigmoid(z: Double): Double {
        val e = exp(-z.coerceIn(-30.0, 30.0))
        return 1.0 / (1.0 + e)
    }

    private fun fitBucket(usableIn: List<Sample>, minSamples: Int): BucketState {
        val usable = usableIn.mapNotNull { s ->
            val p = s.pYes.coerceIn(0.02, 0.98)
            if (p.isFinite()) s.copy(pYes = p) else null
        }
        if (usable.size < minSamples) {
            return BucketState(sampleCount = usable.size, ready = false)
        }
        var bestT = 1.0
        var bestNll = Double.POSITIVE_INFINITY
        var t = 0.50
        while (t <= 2.50 + 1e-9) {
            var nll = 0.0
            for (s in usable) {
                val p = applyTemperature(s.pYes, t).coerceIn(1e-4, 1.0 - 1e-4)
                nll += if (s.outcomeYes) -ln(p) else -ln(1.0 - p)
            }
            if (nll < bestNll) {
                bestNll = nll
                bestT = t
            }
            t += 0.05
        }
        return BucketState(
            temperature = bestT,
            bins = reliabilityBins(usable),
            sampleCount = usable.size,
            ready = true
        )
    }

    private fun reliabilityBins(samples: List<Sample>): List<ReliabilityBin> {
        val nBins = SignalConstants.RELIABILITY_BINS
        return (0 until nBins).map { i ->
            val lo = i / nBins.toDouble()
            val hi = (i + 1) / nBins.toDouble()
            val inBin = samples.filter { s ->
                if (i == nBins - 1) s.pYes >= lo else s.pYes >= lo && s.pYes < hi
            }
            ReliabilityBin(
                lo = lo,
                hi = hi,
                meanPredicted = if (inBin.isEmpty()) (lo + hi) / 2.0 else inBin.map { it.pYes }.average(),
                meanOutcome = if (inBin.isEmpty()) 0.0 else inBin.count { it.outcomeYes }.toDouble() / inBin.size,
                n = inBin.size
            )
        }
    }
}
