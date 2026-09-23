package com.dirk.kalshiodds.signal.ml

/**
 * Tiny temporal 1-D CNN over [T × 5] microstructure frames.
 *
 *   Conv1d(5→12, k=3) → ReLU → Conv1d(12→12, k=3) → ReLU → GAP
 *
 * Default kernels are structured priors (momentum / imbalance / size), not
 * random noise, so cold-start still reacts to a rising or falling book.
 * Optional student weights replace these via [HeavyMlWeights].
 */
class TemporalCnn(
    private val conv1: Array<Array<FloatArray>>,
    private val bias1: FloatArray,
    private val conv2: Array<Array<FloatArray>>,
    private val bias2: FloatArray
) {
    val outChannels: Int get() = bias2.size

    fun encode(frames: Array<FloatArray>): FloatArray {
        val h1 = conv1d(frames, conv1, bias1)
        val h2 = conv1d(h1, conv2, bias2)
        return gap(h2)
    }

    fun encodeWithDropout(frames: Array<FloatArray>, drop: BooleanArray): FloatArray {
        val pooled = encode(frames)
        val out = pooled.copyOf()
        for (i in out.indices) if (i < drop.size && drop[i]) out[i] = 0f
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
            // Filter 0: mid momentum  [-0.8, 0, +0.8]
            c1[0][0][0] = -0.80f; c1[0][0][2] = 0.80f
            // Filter 1: mid level
            c1[1][0][0] = 0.20f; c1[1][0][1] = 0.40f; c1[1][0][2] = 0.20f
            b1[1] = -0.20f
            // Filter 2: imbalance
            c1[2][2][0] = 0.25f; c1[2][2][1] = 0.45f; c1[2][2][2] = 0.25f
            // Filter 3: aggressor
            c1[3][3][0] = 0.20f; c1[3][3][1] = 0.40f; c1[3][3][2] = 0.20f
            // Filter 4: size
            c1[4][1][1] = 0.50f
            // Filter 5: spot
            c1[5][4][0] = -0.40f; c1[5][4][2] = 0.40f
            // Remaining filters: small residual copies so the layer is full-rank-ish
            for (oc in 6 until CONV_OUT) {
                val src = oc % 5
                c1[oc][src][1] = 0.12f
            }
            // conv2: mix nearby channels, emphasize 0/2/3 (mom, imb, agg)
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
            val tLen = input.size
            val cout = weight.size
            val k = weight[0][0].size
            val pad = k / 2
            val out = Array(tLen) { FloatArray(cout) }
            for (t in 0 until tLen) {
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
                    out[t][oc] = MlMath.relu(acc)
                }
            }
            return out
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
