package com.dirk.kalshiodds.signal.ml

/**
 * Tiny temporal 1-D CNN over [T × 5] microstructure frames.
 *
 *   Conv1d(5→12, k=3) → ReLU → Conv1d(12→12, k=3) → ReLU → GAP
 *
 * Scratch tensors are reused across calls (HeavyMlRuntime.infer is
 * synchronized) so a live book flood does not allocate a new T×C grid
 * every tick.
 */
class TemporalCnn(
    private val conv1: Array<Array<FloatArray>>,
    private val bias1: FloatArray,
    private val conv2: Array<Array<FloatArray>>,
    private val bias2: FloatArray
) {
    val outChannels: Int get() = bias2.size
    private var scratch1: Array<FloatArray>? = null
    private var scratch2: Array<FloatArray>? = null

    fun encode(frames: Array<FloatArray>): FloatArray {
        val h1 = convInto(frames, conv1, bias1, slot = 1)
        val h2 = convInto(h1, conv2, bias2, slot = 2)
        return gap(h2)
    }

    fun encodeWithDropout(frames: Array<FloatArray>, drop: BooleanArray): FloatArray {
        val pooled = encode(frames)
        val out = pooled.copyOf()
        for (i in out.indices) if (i < drop.size && drop[i]) out[i] = 0f
        return out
    }

    private fun convInto(
        input: Array<FloatArray>,
        weight: Array<Array<FloatArray>>,
        bias: FloatArray,
        slot: Int
    ): Array<FloatArray> {
        val tLen = input.size
        val cout = weight.size
        val existing = if (slot == 1) scratch1 else scratch2
        val out = if (existing != null && existing.size == tLen && existing[0].size == cout) {
            existing
        } else {
            val created = Array(tLen) { FloatArray(cout) }
            if (slot == 1) scratch1 = created else scratch2 = created
            created
        }
        fillConv(input, weight, bias, out)
        return out
    }

    companion object {
        const val CONV_OUT = 12
        const val KERNEL = 3

        fun defaults(): TemporalCnn {
            val c1 = Array(CONV_OUT) { Array(SequenceFrame.CHANNELS) { FloatArray(KERNEL) } }
            val b1 = FloatArray(CONV_OUT)
            val c2 = Array(CONV_OUT) { Array(CONV_OUT) { FloatArray(KERNEL) } }
            val b2 = FloatArray(CONV_OUT)
            c1[0][0][0] = -0.80f; c1[0][0][2] = 0.80f
            c1[1][0][0] = 0.20f; c1[1][0][1] = 0.40f; c1[1][0][2] = 0.20f
            b1[1] = -0.20f
            c1[2][2][0] = 0.25f; c1[2][2][1] = 0.45f; c1[2][2][2] = 0.25f
            c1[3][3][0] = 0.20f; c1[3][3][1] = 0.40f; c1[3][3][2] = 0.20f
            c1[4][1][1] = 0.50f
            c1[5][4][0] = -0.40f; c1[5][4][2] = 0.40f
            for (oc in 6 until CONV_OUT) {
                val src = oc % 5
                c1[oc][src][1] = 0.12f
            }
            for (oc in 0 until CONV_OUT) {
                c2[oc][oc % CONV_OUT][1] = 0.35f
                c2[oc][0][1] += 0.15f
                c2[oc][2][1] += 0.10f
            }
            return TemporalCnn(c1, b1, c2, b2)
        }

        internal fun conv1d(
            input: Array<FloatArray>,
            weight: Array<Array<FloatArray>>,
            bias: FloatArray
        ): Array<FloatArray> {
            val out = Array(input.size) { FloatArray(weight.size) }
            fillConv(input, weight, bias, out)
            return out
        }

        internal fun fillConv(
            input: Array<FloatArray>,
            weight: Array<Array<FloatArray>>,
            bias: FloatArray,
            out: Array<FloatArray>
        ) {
            val tLen = input.size
            val cout = weight.size
            val k = weight[0][0].size
            val pad = k / 2
            for (t in 0 until tLen) {
                val dest = out[t]
                for (oc in 0 until cout) {
                    var acc = bias[oc]
                    val wOc = weight[oc]
                    for (ki in 0 until k) {
                        val src = t + ki - pad
                        if (src < 0 || src >= tLen) continue
                        val row = input[src]
                        val cin = minOf(row.size, wOc.size)
                        for (ic in 0 until cin) {
                            acc += wOc[ic][ki] * row[ic]
                        }
                    }
                    dest[oc] = MlMath.relu(acc)
                }
            }
        }

        internal fun gap(h: Array<FloatArray>): FloatArray {
            if (h.isEmpty()) return FloatArray(0)
            val c = h[0].size
            val out = FloatArray(c)
            for (t in h.indices) {
                for (i in 0 until c) out[i] += h[t][i]
            }
            val n = h.size.toFloat()
            for (i in 0 until c) out[i] /= n
            return out
        }
    }
}
