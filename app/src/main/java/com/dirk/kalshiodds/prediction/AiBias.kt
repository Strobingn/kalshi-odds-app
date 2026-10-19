package com.dirk.kalshiodds.prediction

import kotlin.math.exp
import kotlin.math.ln

/**
 * 1.0.11 de-bias: the small on-device models' raw output hugs ~50%, so on cheap contracts it nearly always
 * sat above the market price and produced lopsided UP calls. [shrinkToMarket] blends the model toward the
 * market-implied probability in logit space (model weight small), and [edgeSide] scores UP and DOWN with the
 * same threshold: UP edge = p − askYes, DOWN edge = (1 − p) − askNo, larger wins. Both are mirror-symmetric:
 * f(1 − model, 1 − market) = 1 − f(model, market).
 */
object AiBias {
    const val MODEL_WEIGHT = 0.25

    private fun logit(p: Double): Double { val q = p.coerceIn(1e-4, 1 - 1e-4); return ln(q / (1 - q)) }
    private fun sigmoid(z: Double): Double = 1.0 / (1.0 + exp(-z))

    fun shrinkToMarket(model: Double, market: Double, weight: Double = MODEL_WEIGHT): Double {
        if (!model.isFinite() || !market.isFinite()) return market
        val w = weight.coerceIn(0.0, 1.0)
        return sigmoid((1 - w) * logit(market) + w * logit(model)).coerceIn(0.02, 0.98)
    }

    /** "YES" / "NO" when that side's edge clears [threshold] (same for both sides), else null. */
    fun edgeSide(p: Double, askYes: Double, askNo: Double, threshold: Double): String? {
        val up = p - askYes
        val down = (1 - p) - askNo
        val best = maxOf(up, down)
        if (best <= threshold) return null
        return if (up > down) "YES" else if (down > up) "NO" else null
    }
}
