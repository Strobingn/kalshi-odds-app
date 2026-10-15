package com.dirk.kalshiodds.signal.feedback

import com.dirk.kalshiodds.prediction.PredictionLogEntry
import com.dirk.kalshiodds.signal.config.SignalConstants
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlinx.serialization.Serializable

/**
 * Rolling probability calibration of the **raw** blend, one fit per
 * time-to-expiry bucket (EARLY / LATE).
 *
 * Fitting uses [PredictionLogEntry.rawFairYes] / [PredictionLogEntry.rawFairEarly]
 * — the blend *before* calibration — because that is the number [apply] is
 * applied to. (The old fit used the logged final fair, which was already
 * calibrated, so each refit calibrated its own output.) Entries without raw
 * values (logged before 0.3.18) are ignored.
 *
 * A bucket with too few settlements stays identity; late-window accuracy is
 * never applied to early-window predictions.
 */
object Calibrator {
    const val EARLY = "EARLY"
    const val LATE = "LATE"
    const val ALL = "ALL"

    /** Same bounds as the scored fair (0.5–99.5%); a 2% floor faked longshot EV. */
    const val P_MIN = 0.005
    const val P_MAX = 0.995

    /** A reliability bin only nudges the output once it has this many outcomes. */
    const val MIN_BIN_SAMPLES = 30

    @Serializable
    data class ReliabilityBin(
        val lo: Double,
        val hi: Double,
        val meanPredicted: Double = 0.0,
        val meanOutcome: Double = 0.0,
        val n: Int = 0
    )

    @Serializable
    data class Bucket(
        val temperature: Double = 1.0,
        val bins: List<ReliabilityBin> = emptyList(),
        val sampleCount: Int = 0,
        val ready: Boolean = false
    )

    /**
     * [temperature] / [bins] / [sampleCount] summarize the largest ready
     * bucket for the scorecard; [apply] only ever uses [byTte].
     */
    @Serializable
    data class State(
        val temperature: Double = 1.0,
        val bins: List<ReliabilityBin> = emptyList(),
        val sampleCount: Int = 0,
        val ready: Boolean = false,
        val fittedAtMs: Long = 0L,
        val byTte: Map<String, Bucket> = emptyMap()
    )

    data class Sample(val pYes: Double, val outcomeYes: Boolean, val tte: String = ALL)

    fun fit(
        samples: List<Sample>,
        nowMs: Long = System.currentTimeMillis(),
        minSamples: Int = SignalConstants.MIN_CALIBRATION_SAMPLES
    ): State {
        val usable = samples.mapNotNull { s ->
            if (!s.pYes.isFinite()) null else s.copy(pYes = s.pYes.coerceIn(P_MIN, P_MAX))
        }
        val byTte = usable.groupBy { it.tte }.mapValues { (_, xs) -> fitBucket(xs, minSamples) }
        val summary = byTte.values.filter { it.ready }.maxByOrNull { it.sampleCount }
        return State(
            temperature = summary?.temperature ?: 1.0,
            bins = summary?.bins.orEmpty(),
            sampleCount = usable.size,
            ready = summary != null,
            fittedAtMs = nowMs,
            byTte = byTte
        )
    }

    /** One EARLY sample (last early-window raw blend) and one LATE sample per settled entry. */
    fun fitEntries(entries: List<PredictionLogEntry>, nowMs: Long = System.currentTimeMillis()): State {
        val samples = buildList {
            for (e in entries) {
                val y = when (e.outcome?.lowercase()) {
                    "yes" -> true
                    "no" -> false
                    else -> continue
                }
                e.rawFairEarly?.let { add(Sample(it, y, EARLY)) }
                val raw = e.rawFairYes ?: continue
                if (e.tteBucket.equals(LATE, ignoreCase = true)) add(Sample(raw, y, LATE))
            }
        }
        return fit(samples, nowMs)
    }

    /** Calibrate a raw YES probability (0–1). Identity when [tte]'s bucket is cold. */
    fun apply(pYes: Double, state: State, tte: String? = null): Double {
        val p = pYes.coerceIn(P_MIN, P_MAX)
        val bucket = state.byTte[tte?.uppercase() ?: ALL]?.takeIf { it.ready } ?: return p
        val warmed = applyTemperature(p, bucket.temperature)
        val bin = bucket.bins.firstOrNull { p >= it.lo && p < it.hi || (it.hi >= 1.0 && p >= it.lo) }
        return if (bin != null && bin.n >= MIN_BIN_SAMPLES) {
            (0.70 * warmed + 0.30 * bin.meanOutcome).coerceIn(P_MIN, P_MAX)
        } else {
            warmed.coerceIn(P_MIN, P_MAX)
        }
    }

    fun applyPp(pYesPp: Double, state: State, tte: String? = null): Double = apply(pYesPp / 100.0, state, tte) * 100.0

    fun applyTemperature(p: Double, temperature: Double): Double {
        val t = temperature.coerceIn(0.35, 3.0)
        if (abs(t - 1.0) < 1e-6) return p.coerceIn(P_MIN, P_MAX)
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

    private fun fitBucket(usable: List<Sample>, minSamples: Int): Bucket {
        if (usable.size < minSamples) return Bucket(sampleCount = usable.size, ready = false)
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
        return Bucket(temperature = bestT, bins = reliabilityBins(usable), sampleCount = usable.size, ready = true)
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
