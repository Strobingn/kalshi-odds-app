package com.dirk.kalshiodds.signal.updown

import com.dirk.kalshiodds.signal.fair.DigitalOptionFairValue
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * The up/down model's inputs, exactly as `tools/research/updown/updown_features.py` builds them for training
 * (UpDownFeaturesTest holds this port to `updown_parity.json`, rows that file wrote from real recorded windows). Pure.
 *
 * Inputs, as the app holds them:
 *  - [spot]: Coinbase BTC-USD, one slot a second ending at the current second (NaN = no print that second), the
 *    Coinbase copy of the index book (`CfIndexBook.series`), at least 1,201 seconds;
 *  - [mid]: the YES mid ((bid + ask) / 2) at the end of each second of the window so far, NaN when unknown;
 *  - the strike, whole seconds to the close and into the window, and the YES top of book.
 */
object UpDownFeatures {
    const val STD_WINDOW_S = 900
    const val MIN_RETURNS = 120
    const val FRESH_S = 5
    const val SETTLE_W = 60.0
    /** Seconds of spot history [row] reads (the 900 s volatility and the 300 s move, with slack). */
    const val SPOT_SECONDS = 1201

    val NAMES = listOf(
        "tleft", "mid", "logit_mid", "spread", "imb", "fair", "logit_fair", "edge_logit", "z", "vol_bp",
        "r30", "r60", "r180", "r300", "dmid30", "dmid60", "dmid180"
    )
    private val IDX = NAMES.withIndex().associate { it.value to it.index }

    fun logit(p: Double, lo: Double): Double {
        val q = p.coerceIn(lo, 1.0 - lo)
        return ln(q / (1.0 - q))
    }

    /** Last value at or before [back] seconds ago ([hist]'s last slot is now), at most [FRESH_S] older; null if none. */
    fun valueAgo(hist: DoubleArray, back: Int): Double? {
        val j = hist.size - 1 - back
        var k = j
        while (k > j - FRESH_S && k >= 0) {
            val v = hist[k]
            if (!v.isNaN()) return v
            k--
        }
        return null
    }

    /** RMS of consecutive 1-second log returns over the last [STD_WINDOW_S] seconds, scaled to a minute (CfIndexBook.std1m). */
    fun std1m(hist: DoubleArray): Double? {
        val n = hist.size
        val lo = maxOf(0, n - 1 - STD_WINDOW_S)
        var ss = 0.0
        var cnt = 0
        for (k in lo + 1 until n) {
            val a = hist[k - 1]
            val b = hist[k]
            if (a.isNaN() || b.isNaN() || a <= 0.0 || b <= 0.0) continue
            val r = ln(b / a)
            ss += r * r
            cnt++
        }
        if (cnt < MIN_RETURNS) return null
        return sqrt(ss / cnt * 60.0)
    }

    /**
     * The NAMES row, or null when the app would not have it: no fresh spot, the volatility not warmed up, or no
     * two-sided book. [tteS] and [secIn] are whole seconds (epoch seconds of the close and of now).
     */
    fun row(
        spot: DoubleArray, strike: Double, tteS: Double, secIn: Int,
        yesBid: Double?, yesBidQty: Double?, yesAsk: Double?, yesAskQty: Double?,
        mid: DoubleArray
    ): DoubleArray? {
        val bid = yesBid?.takeIf { !it.isNaN() } ?: return null
        val ask = yesAsk?.takeIf { !it.isNaN() } ?: return null
        if (ask <= bid - 1e-9) return null
        val s = valueAgo(spot, 0) ?: return null
        val sd = std1m(spot)?.takeIf { it > 0.0 } ?: return null
        if (!strike.isFinite() || strike <= 0.0) return null
        if (tteS <= 0.0) return null
        val observed = if (tteS < SETTLE_W) {
            val from = maxOf(spot.size - 1 - Math.round(SETTLE_W - tteS).toInt(), 0)
            var sum = 0.0
            var n = 0
            for (k in from until spot.size) {
                val v = spot[k]
                if (!v.isNaN() && v > 0.0) { sum += ln(v); n++ }
            }
            if (n > 0) sum / n else null
        } else null
        val fair = DigitalOptionFairValue.pSettleAtLeast(
            spot = s, strike = strike, tteSeconds = tteS,
            sigmaAnnual = sd * sqrt(DigitalOptionFairValue.SECONDS_PER_YEAR / 60.0),
            observedMeanLog = observed, indexNoise = DigitalOptionFairValue.indexNoiseLog("BTC")
        ) ?: return null
        val m = (bid + ask) / 2.0
        val bq = yesBidQty?.takeIf { !it.isNaN() } ?: 0.0
        val aq = yesAskQty?.takeIf { !it.isNaN() } ?: 0.0
        val imb = if (bq + aq > 0.0) (bq - aq) / (bq + aq) else 0.0
        val z = ln(s / strike) / (sd * sqrt(maxOf(tteS, 1.0) / 60.0))
        val rets = intArrayOf(30, 60, 180, 300).map { h ->
            val then = valueAgo(spot, h)
            (if (then != null && then != 0.0) ln(s / then) / (sd * sqrt(h / 60.0)) else 0.0).coerceIn(-6.0, 6.0)
        }
        val dm = intArrayOf(30, 60, 180).map { h ->
            val then = if (mid.size > h) valueAgo(mid, h) else null
            if (then != null) m - then else 0.0
        }
        val lm = logit(m, 0.01)
        val lf = logit(fair, 0.005)
        return doubleArrayOf(
            tteS / 900.0, m, lm, ask - bid, imb, fair, lf, lf - lm, z.coerceIn(-8.0, 8.0), sd * 1e4,
            rets[0], rets[1], rets[2], rets[3], dm[0], dm[1], dm[2]
        )
    }

    /** The model's input row for [features] (the names in updown_model.json), or null when a name is unknown. */
    fun design(row: DoubleArray, features: List<String>): FloatArray? {
        fun f(n: String) = row[IDX.getValue(n)]
        val st = sqrt(f("tleft"))
        val out = FloatArray(features.size)
        for ((i, name) in features.withIndex()) {
            out[i] = when (name) {
                "logit_mid*sqrt_tleft" -> f("logit_mid") * st
                "logit_mid*tleft" -> f("logit_mid") * f("tleft")
                "edge_logit*sqrt_tleft" -> f("edge_logit") * st
                else -> IDX[name]?.let { row[it] } ?: return null
            }.toFloat()
        }
        return out
    }

    fun fair(row: DoubleArray): Double = row[IDX.getValue("fair")]
    fun mid(row: DoubleArray): Double = row[IDX.getValue("mid")]
}
