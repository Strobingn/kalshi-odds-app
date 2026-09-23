package com.dirk.kalshiodds.signal.ml

import kotlin.math.exp
import kotlin.math.tanh

/**
 * 8-unit LSTM over the same [T × 5] window. Cheap enough for a phone;
 * skipped when the sequence is shorter than [SequenceFeatures.MIN_FRAMES_FOR_SEQUENCE].
 */
class TinyLstm(
    private val wIh: Array<FloatArray>,
    private val wHh: Array<FloatArray>,
    private val bIh: FloatArray,
    private val bHh: FloatArray
) {
    val hidden: Int get() = bIh.size / 4

    fun encode(frames: Array<FloatArray>): FloatArray {
        val hDim = hidden
        var h = FloatArray(hDim)
        var c = FloatArray(hDim)
        for (t in frames.indices) {
            val step = lstmStep(frames[t], h, c, hDim)
            h = step.first
            c = step.second
        }
        return h
    }

    private fun lstmStep(x: FloatArray, hPrev: FloatArray, cPrev: FloatArray, hDim: Int): Pair<FloatArray, FloatArray> {
        val gates = FloatArray(hDim * 4)
        for (i in gates.indices) {
            var acc = bIh.getOrElse(i) { 0f } + bHh.getOrElse(i) { 0f }
            val wi = wIh.getOrElse(i) { floatArrayOf() }
            val wh = wHh.getOrElse(i) { floatArrayOf() }
            val xn = minOf(x.size, wi.size)
            for (j in 0 until xn) acc += wi[j] * x[j]
            val hn = minOf(hPrev.size, wh.size)
            for (j in 0 until hn) acc += wh[j] * hPrev[j]
            gates[i] = acc
        }
        val h = FloatArray(hDim)
        val c = FloatArray(hDim)
        for (i in 0 until hDim) {
            val ii = sig(gates[i])
            val ff = sig(gates[hDim + i])
            val gg = tanh(gates[2 * hDim + i].toDouble()).toFloat()
            val oo = sig(gates[3 * hDim + i])
            c[i] = ff * cPrev[i] + ii * gg
            h[i] = oo * tanh(c[i].toDouble()).toFloat()
        }
        return h to c
    }

    companion object {
        const val HIDDEN = 8

        fun defaults(): TinyLstm {
            val gates = HIDDEN * 4
            val wIh = Array(gates) { i ->
                val row = FloatArray(SequenceFrame.CHANNELS)
                val gate = i / HIDDEN
                val unit = i % HIDDEN
                val ch = unit % SequenceFrame.CHANNELS
                row[ch] = when (gate) {
                    0 -> 0.18f
                    1 -> 0.22f
                    2 -> if (ch == 0 || ch == 2) 0.28f else 0.08f
                    else -> 0.16f
                }
                row
            }
            val wHh = Array(gates) { i ->
                val row = FloatArray(HIDDEN)
                row[i % HIDDEN] = 0.12f
                row
            }
            val bIh = FloatArray(gates)
            val bHh = FloatArray(gates)
            // Forget-gate bias so the cell retains a short history.
            for (i in HIDDEN until 2 * HIDDEN) bIh[i] = 0.8f
            return TinyLstm(wIh, wHh, bIh, bHh)
        }

        private fun sig(z: Float): Float {
            val e = exp(-z.toDouble().coerceIn(-30.0, 30.0)).toFloat()
            return 1f / (1f + e)
        }
    }
}
