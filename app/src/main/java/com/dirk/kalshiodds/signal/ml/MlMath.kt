package com.dirk.kalshiodds.signal.ml

import kotlin.math.exp
import kotlin.math.ln

/** Shared numeric helpers for the 0.3.0 on-device stack. */
object MlMath {
    fun relu(x: Float): Float = if (x > 0f) x else 0f

    fun sigmoid(z: Double): Double {
        val e = exp(-z.coerceIn(-30.0, 30.0))
        return 1.0 / (1.0 + e)
    }

    fun logit(p: Double): Double {
        val x = p.coerceIn(1e-4, 1.0 - 1e-4)
        return ln(x / (1.0 - x))
    }

    fun softplus(z: Double): Double {
        val x = z.coerceIn(-30.0, 30.0)
        return if (x > 20.0) x else ln(1.0 + exp(x))
    }

    fun mean(xs: List<Double>): Double = if (xs.isEmpty()) 0.0 else xs.average()

    fun variance(xs: List<Double>): Double {
        if (xs.size < 2) return 0.0
        val m = xs.average()
        return xs.sumOf { (it - m) * (it - m) } / xs.size
    }

    fun stddev(xs: List<Double>): Double = kotlin.math.sqrt(variance(xs))

    fun dot(a: FloatArray, b: FloatArray): Float {
        val n = minOf(a.size, b.size)
        var s = 0f
        for (i in 0 until n) s += a[i] * b[i]
        return s
    }

    fun dense(x: FloatArray, w: Array<FloatArray>, b: FloatArray, activation: (Float) -> Float = { it }): FloatArray {
        val out = FloatArray(b.size)
        for (i in b.indices) {
            var acc = b[i]
            val row = w[i]
            val n = minOf(x.size, row.size)
            for (j in 0 until n) acc += row[j] * x[j]
            out[i] = activation(acc)
        }
        return out
    }

    fun clip01(p: Double): Double = p.coerceIn(0.02, 0.98)
}
