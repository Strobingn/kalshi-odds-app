package com.dirk.kalshiodds.signal.ml

import kotlin.math.abs
import kotlin.math.ceil

/**
 * Split-conformal prediction sets for a binary YES/NO.
 *
 * Nonconformity s = |p − y|. Coverage 1−α on the calibration bag.
 * Ambiguous {YES, NO} when the interval around p still contains 0.5.
 * Cold bag → identity (do not skip).
 */
object ConformalSets {
    const val MIN_SAMPLES = 12
    const val DEFAULT_ALPHA = 0.10

    data class State(
        val scores: List<Double> = emptyList(),
        val quantile: Double = 0.5,
        val alpha: Double = DEFAULT_ALPHA
    ) {
        val ready: Boolean get() = scores.size >= MIN_SAMPLES
    }

    data class Result(
        val set: Set<String>,
        val quantile: Double,
        val ambiguous: Boolean,
        val ready: Boolean,
        val note: String
    )

    fun fit(pairs: List<Pair<Double, Boolean>>, alpha: Double = DEFAULT_ALPHA): State {
        val s = pairs.map { (p, y) -> abs(MlMath.clip01(p) - if (y) 1.0 else 0.0) }.sorted()
        if (s.size < MIN_SAMPLES) return State(scores = s, alpha = alpha)
        return State(scores = s, quantile = quantile(s, alpha), alpha = alpha)
    }

    fun predict(pYes: Double, state: State): Result {
        val p = MlMath.clip01(pYes)
        if (!state.ready) {
            val side = if (p >= 0.5) "YES" else "NO"
            return Result(setOf(side), state.quantile, false, false, "conformal cold")
        }
        val q = state.quantile
        val lo = (p - q).coerceIn(0.0, 1.0)
        val hi = (p + q).coerceIn(0.0, 1.0)
        val set = buildSet {
            if (hi >= 0.5) add("YES")
            if (lo <= 0.5) add("NO")
        }.ifEmpty { setOf(if (p >= 0.5) "YES" else "NO") }
        val amb = set.size > 1
        return Result(
            set = set,
            quantile = q,
            ambiguous = amb,
            ready = true,
            note = if (amb) "conformal {YES,NO}" else "conformal {${set.first()}}"
        )
    }

    fun quantile(sorted: List<Double>, alpha: Double): Double {
        if (sorted.isEmpty()) return 0.5
        val n = sorted.size
        val idx = ceil((1.0 - alpha) * (n + 1)).toInt().coerceIn(1, n) - 1
        return sorted[idx].coerceIn(0.02, 0.50)
    }
}
