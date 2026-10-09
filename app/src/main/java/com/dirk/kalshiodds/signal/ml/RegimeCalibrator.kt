package com.dirk.kalshiodds.signal.ml

import kotlinx.serialization.Serializable

/**
 * Per-(series, TTE) Platt + isotonic calibration.
 *
 * Cold buckets are identity. A bucket becomes ready after
 * [MIN_BUCKET_SAMPLES] settled outcomes.
 */
object RegimeCalibrator {

    const val MIN_BUCKET_SAMPLES = 8

    @Serializable
    data class Bucket(
        val series: String,
        val tte: String,
        val slope: Double = 1.0,
        val intercept: Double = 0.0,
        val knotsX: List<Double> = emptyList(),
        val knotsY: List<Double> = emptyList(),
        val sampleCount: Int = 0
    ) {
        val key: String get() = keyOf(series, tte)
        val ready: Boolean get() = sampleCount >= MIN_BUCKET_SAMPLES
    }

    @Serializable
    data class State(
        val buckets: Map<String, Bucket> = emptyMap()
    ) {
        fun bucket(series: String, tte: String): Bucket =
            buckets[keyOf(series, tte)] ?: Bucket(series = series, tte = tte)
    }

    data class Sample(
        val series: String,
        val tte: String,
        val pYes: Double,
        val outcomeYes: Boolean
    )

    fun identity(): State = State()

    fun keyOf(series: String, tte: String): String =
        "${series.uppercase()}|${tte.uppercase()}"

    fun apply(pYes: Double, series: String, tte: String, state: State): Double {
        val p = MlMath.clip01(pYes)
        val b = state.bucket(series, tte)
        if (!b.ready) return p
        val platt = MlMath.clip01(MlMath.sigmoid(b.slope * MlMath.logit(p) + b.intercept))
        val iso = if (b.knotsX.size >= 2) applyIsotonic(platt, b.knotsX, b.knotsY) else platt
        return MlMath.clip01(0.65 * platt + 0.35 * iso)
    }

    fun applyPp(pYesPp: Double, series: String, tte: String, state: State): Double =
        apply(pYesPp / 100.0, series, tte, state) * 100.0

    fun fit(samples: List<Sample>): State {
        val grouped = samples.groupBy { keyOf(it.series, it.tte) }
        val buckets = grouped.mapValues { (_, rows) ->
            val first = rows.first()
            fitBucket(first.series, first.tte, rows)
        }
        return State(buckets)
    }

    /** Full refit from the current settlement list. Cold buckets stay identity. */
    fun update(state: State, samples: List<Sample>): State {
        if (samples.isEmpty()) return state
        return fit(samples)
    }

    private fun fitBucket(series: String, tte: String, rows: List<Sample>, prior: Bucket? = null): Bucket {
        val usable = rows.map { MlMath.clip01(it.pYes) to if (it.outcomeYes) 1.0 else 0.0 }
        if (usable.size < MIN_BUCKET_SAMPLES) {
            return Bucket(series = series, tte = tte, sampleCount = usable.size)
        }
        var a = prior?.slope ?: 1.0
        var b = prior?.intercept ?: 0.0
        val lr = 0.08
        for ((p, y) in usable) {
            val pHat = MlMath.sigmoid(a * MlMath.logit(p) + b).coerceIn(1e-4, 1.0 - 1e-4)
            val err = y - pHat
            a = (a + lr * err * MlMath.logit(p)).coerceIn(0.40, 2.50)
            b = (b + lr * err).coerceIn(-1.50, 1.50)
        }
        val iso = isotonic(usable)
        return Bucket(
            series = series,
            tte = tte,
            slope = a,
            intercept = b,
            knotsX = iso.map { it.first },
            knotsY = iso.map { it.second },
            sampleCount = usable.size
        )
    }

    /**
     * Pool Adjacent Violators. Returns non-decreasing (x, ŷ) knots.
     */
    fun isotonic(pairs: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        if (pairs.isEmpty()) return emptyList()
        val sorted = pairs.sortedBy { it.first }
        data class Block(var w: Double, var y: Double, var x0: Double, var x1: Double)
        val blocks = ArrayList<Block>(sorted.size)
        for ((x, y) in sorted) {
            blocks.add(Block(1.0, y, x, x))
            while (blocks.size >= 2 && blocks[blocks.lastIndex - 1].y > blocks.last().y) {
                val b = blocks.removeAt(blocks.lastIndex)
                val a = blocks.removeAt(blocks.lastIndex)
                val w = a.w + b.w
                blocks.add(Block(w, (a.y * a.w + b.y * b.w) / w, a.x0, b.x1))
            }
        }
        return blocks.map { it.x0 to it.y.coerceIn(0.02, 0.98) }
    }

    fun applyIsotonic(p: Double, xs: List<Double>, ys: List<Double>): Double {
        if (xs.isEmpty() || ys.isEmpty()) return p
        if (p <= xs.first()) return ys.first()
        if (p >= xs.last()) return ys.last()
        for (i in 0 until xs.size - 1) {
            if (p >= xs[i] && p <= xs[i + 1]) {
                val span = (xs[i + 1] - xs[i]).coerceAtLeast(1e-9)
                val t = (p - xs[i]) / span
                return ys[i] * (1 - t) + ys[i + 1] * t
            }
        }
        return ys.last()
    }
}
