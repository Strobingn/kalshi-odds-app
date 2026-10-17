package com.dirk.kalshiodds.decision

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * 0.3.54 forecaster fc54 (deterministic, versioned, no LLM): predicts the YES mid move over H seconds from
 * lead signals — CF Benchmarks index (Coinbase spot fallback) move over the last 5/15/30/60 s scaled into probability
 * units (φ(d)·Δln S / σ√τ), implied fair prob vs Kalshi mid gap, vol-scaled distance to strike d, time left —
 * plus 60 s mid momentum, top-3 book imbalance and distance from 50¢. Ridge-OLS per coin and horizon on recorder
 * books + Binance 1 s spot (basis-adjusted to the strike) Sep 25 → Oct 10 2026 (docs/fc54). Horizon and entry
 * margin over the full round trip are walk-forward chosen per coin. Lead history is per coin, so it carries across
 * windows and is ready the moment a window opens.
 */
object LeadForecaster {
    const val VERSION = "fc54-v1"
    val HORIZONS_S = listOf(30, 60, 120, 180, 300, 600, -1)
    data class Config(val horizonS: Int, val marginUsd: Double)

    /** Last walk-forward pick per coin (chosen only on prior days' out-of-sample P&L). */
    val CHOSEN: Map<String, Config> = mapOf("BTC" to Config(600, 0.02), "ETH" to Config(180, 0.0), "SOL" to Config(120, 0.0))
    fun configFor(coin: String): Config = CHOSEN[coin] ?: Config(300, 0.02)

    private val W: Map<String, DoubleArray> = mapOf(
        "BTC|30" to doubleArrayOf(-0.003048, -0.011251, 0.008816, 0.001843, 0.009416, 0.001255, 0.00623, 0.045327, 0.019325, 0.005817, 0.013186),
        "BTC|60" to doubleArrayOf(-0.005539, -0.045542, 0.010032, 0.012054, 0.020702, -0.004707, 0.011125, 0.04396, 0.029393, 0.013835, 0.022714),
        "BTC|120" to doubleArrayOf(-0.009314, -0.039498, 0.013005, 0.022195, 0.02262, -0.009791, 0.018366, 0.032127, 0.008204, -0.000504, 0.039154),
        "BTC|180" to doubleArrayOf(-0.014161, -0.029253, 0.016166, 0.029337, 0.02493, -0.015245, 0.026102, 0.033645, 0.013621, 0.011909, 0.040016),
        "BTC|300" to doubleArrayOf(-0.021349, -0.026267, 0.019999, 0.022184, 0.033998, -0.011815, 0.035664, 0.032001, 0.011252, 0.014873, 0.028044),
        "BTC|600" to doubleArrayOf(-0.141787, -0.08058, 0.034031, -0.078185, 0.003501, 0.114401, 0.175235, 0.023896, 0.014426, -0.00128, 0.06228),
        "BTC|-1" to doubleArrayOf(-0.017298, -0.059513, 0.018171, 0.033889, -0.021904, -0.007383, 0.013355, 0.045474, 0.026059, 0.017262, 0.069037),
        "ETH|30" to doubleArrayOf(-0.001022, -0.016397, 0.008335, 0.002781, 0.012327, 0.002003, 0.002223, 0.028667, 0.005747, -0.001622, 0.021024),
        "ETH|60" to doubleArrayOf(-0.001718, -0.027722, 0.008232, 0.008931, 0.028861, -0.001799, 0.004116, 0.027407, 0.013923, 0.000248, 0.031226),
        "ETH|120" to doubleArrayOf(-0.003435, -0.030291, 0.007606, 0.005135, 0.043141, 0.00131, 0.007542, 0.024296, -0.00184, 0.003198, 0.05689),
        "ETH|180" to doubleArrayOf(-0.005459, -0.048641, 0.006258, 0.00694, 0.067694, -0.001746, 0.010984, 0.027284, 0.012631, 0.020381, 0.047319),
        "ETH|300" to doubleArrayOf(-0.00961, -0.050446, 0.004583, 0.008854, 0.12505, -0.012308, 0.015911, 0.025249, 0.006041, 0.011891, 0.014457),
        "ETH|600" to doubleArrayOf(-0.049931, -0.020692, 0.00127, -0.042319, 0.14517, 0.023797, 0.06316, 0.031552, -0.007497, -0.017049, 0.02946),
        "ETH|-1" to doubleArrayOf(-0.00547, -0.0141, 0.005929, 0.069824, 0.018575, -0.033826, 0.010106, 0.029302, 0.00432, 0.009407, 0.028923),
        "SOL|30" to doubleArrayOf(-0.001862, -0.034173, 0.006306, -0.000848, 0.020694, 0.006904, 0.004282, 0.053464, 0.013592, 0.00495, 0.025447),
        "SOL|60" to doubleArrayOf(-0.002972, -0.046126, 0.00542, 0.000956, 0.029926, 0.006057, 0.00658, 0.051337, 0.020874, 0.010055, 0.031623),
        "SOL|120" to doubleArrayOf(-0.00339, -0.062173, 0.004191, 0.005243, 0.043569, 0.003611, 0.007818, 0.050132, 0.01589, 0.009615, 0.035694),
        "SOL|180" to doubleArrayOf(-0.004041, -0.052146, 0.00573, 0.003517, 0.050562, 0.00699, 0.008454, 0.046921, 0.016387, 0.010514, 0.017512),
        "SOL|300" to doubleArrayOf(-0.008446, -0.0299, 0.008112, -0.023673, 0.044918, 0.037997, 0.013419, 0.05301, 0.014531, -0.003197, -0.009502),
        "SOL|600" to doubleArrayOf(-0.029948, -0.045445, 0.006266, -0.059012, 0.057787, 0.009661, 0.041379, 0.05215, -0.008398, -0.006615, 0.091974),
        "SOL|-1" to doubleArrayOf(-0.004161, -0.040689, 0.004397, 0.077243, 0.060251, -0.036206, 0.003963, 0.056445, 0.007316, 0.000695, 0.010889),
    )
    fun weights(coin: String, h: Int): DoubleArray? = W["$coin|$h"]

    private fun phiCdf(x: Double): Double {
        // Abramowitz-Stegun 7.1.26 erf
        val z = x / sqrt(2.0); val s = if (z < 0) -1.0 else 1.0; val a = kotlin.math.abs(z)
        val t = 1.0 / (1.0 + 0.3275911 * a)
        val y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * exp(-a * a)
        return 0.5 * (1.0 + s * y)
    }

    /** leadAt(k) = lead price k seconds ago (null if unknown → that lag term is 0). */
    fun features(mid: Double, mid60Ago: Double?, imb: Double, lead: Double, strike: Double, sigmaPerSec: Double,
                 tauS: Double, leadAt: (Int) -> Double?): DoubleArray? {
        if (!(lead > 0 && strike > 0 && sigmaPerSec > 0)) return null
        val tau = tauS.coerceAtLeast(1.0)
        val z = sigmaPerSec * sqrt(tau)
        val d = (ln(lead / strike) / z).coerceIn(-4.0, 4.0)
        val fair = phiCdf(d)
        val phi = exp(-d * d / 2) / sqrt(2 * Math.PI)
        val x = DoubleArray(11)
        x[0] = 1.0; x[1] = mid60Ago?.let { mid - it } ?: 0.0; x[2] = imb; x[3] = mid - 0.5; x[4] = fair - mid
        x[5] = d / 4; x[6] = tau / 900.0
        listOf(5, 15, 30, 60).forEachIndexed { i, k ->
            x[7 + i] = leadAt(k)?.takeIf { it > 0 }?.let { (phi * ln(lead / it) / z).coerceIn(-0.5, 0.5) } ?: 0.0
        }
        return x
    }

    fun predict(coin: String, h: Int, x: DoubleArray): Double? {
        val w = weights(coin, h) ?: return null
        fun raw(v: DoubleArray): Double { var s = 0.0; for (i in v.indices) s += w[i] * v[i]; return s }
        // 0.3.87 side-neutral: antisymmetrise over the UP/DOWN mirror so the intercept (and any asymmetric weight)
        // cannot add a built-in UP or DOWN bias. A mirrored market gets exactly the negated forecast.
        val s = (raw(x) - raw(mirror(x))) / 2.0
        return s.takeIf { it.isFinite() }
    }

    /** UP/DOWN mirror of a feature vector: every directional feature negated; intercept and time left unchanged. */
    fun mirror(x: DoubleArray): DoubleArray = DoubleArray(x.size) { i -> if (i == 0 || i == 6) x[i] else -x[i] }

    fun note(): String = ScalpParams.COINS.joinToString(" · ") { c ->
        val k = configFor(c); "$c " + ShortHorizonForecaster.horizonLabel(k.horizonS) + " +" + Math.round(k.marginUsd * 100) + "¢"
    } + " ($VERSION)"
}
