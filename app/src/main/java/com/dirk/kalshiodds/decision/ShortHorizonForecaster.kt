package com.dirk.kalshiodds.decision

/**
 * 0.3.53 short-horizon forecaster (deterministic, versioned, no LLM). Predicts the YES mid move over the next H seconds
 * from book-only features that exist both live (WS book) and in the box recorder: 60 s and 180 s mid momentum, top-3
 * level imbalance, distance from 50¢, and that distance × elapsed fraction. Ridge-OLS weights per coin and horizon,
 * fitted on recorder data (forecast53/evalfc.py, Sep 25 → Oct 10 2026). Missing history (window just opened) →
 * momentum = 0, so it is usable from the first book of a window (no warm-up), exactly as evaluated offline.
 * H = -1 means "to window close" (target = settlement − mid).
 */
object ShortHorizonForecaster {
    const val VERSION = "fc53-v1"
    val HORIZONS_S = listOf(60, 120, 180, 300, 480, 600, -1)

    /** Walk-forward-chosen horizon per coin (ride P&L after fees; no-trade ties → shortest). */
    val CHOSEN_HORIZON_S: Map<String, Int> = mapOf("BTC" to 480, "ETH" to 60, "SOL" to 60)

    private val W: Map<String, DoubleArray> = mapOf(
        "BTC|60" to doubleArrayOf(0.000455, -0.018458, 0.017611, 0.01004, 0.014958, -0.026189),
        "BTC|120" to doubleArrayOf(0.001198, -0.013455, 0.022398, 0.013066, 0.029212, -0.048802),
        "BTC|180" to doubleArrayOf(0.001657, 0.010855, 0.013842, 0.016474, 0.045578, -0.079294),
        "BTC|300" to doubleArrayOf(0.002607, 0.015452, -0.010269, 0.020521, 0.075079, -0.14991),
        "BTC|480" to doubleArrayOf(0.003212, -0.032035, -0.019302, 0.027892, 0.095235, -0.223717),
        "BTC|600" to doubleArrayOf(0.005803, 0.005606, -0.059999, 0.034231, 0.088715, -0.167925),
        "BTC|-1" to doubleArrayOf(-0.010614, 0.013546, 0.00044, 0.015795, 0.09018, -0.115126),
        "ETH|60" to doubleArrayOf(0.000553, 0.00278, 0.008224, 0.008638, -0.010787, 0.021442),
        "ETH|120" to doubleArrayOf(0.000941, 0.023646, 0.005796, 0.007984, -0.01004, 0.020105),
        "ETH|180" to doubleArrayOf(0.001281, 0.020434, -0.010099, 0.006863, 0.003638, 0.002869),
        "ETH|300" to doubleArrayOf(0.0013, -0.002735, -0.029219, 0.005766, 0.01764, -0.020879),
        "ETH|480" to doubleArrayOf(0.000479, -0.023231, 0.004668, 0.009595, 0.012159, -0.096734),
        "ETH|600" to doubleArrayOf(0.003595, -0.001344, 0.033865, 0.002756, -0.022993, -0.098006),
        "ETH|-1" to doubleArrayOf(-0.000443, 0.027466, -0.006371, 0.007025, 0.051304, -0.031344),
        "SOL|60" to doubleArrayOf(0.000505, -0.00238, -0.013014, 0.005149, -0.003998, 0.014036),
        "SOL|120" to doubleArrayOf(0.000975, -0.015794, -0.011946, 0.003986, -0.0029, 0.014231),
        "SOL|180" to doubleArrayOf(0.000962, -0.014979, -0.018058, 0.005699, -0.006338, 0.028413),
        "SOL|300" to doubleArrayOf(0.000482, -0.017416, -0.021998, 0.007938, -0.015827, 0.053546),
        "SOL|480" to doubleArrayOf(0.000264, -0.044233, 0.019365, 0.009017, -0.008401, -0.055744),
        "SOL|600" to doubleArrayOf(0.004588, 0.020904, 0.001652, 0.006692, -0.043662, -0.089411),
        "SOL|-1" to doubleArrayOf(-0.002273, -0.006604, -0.028399, 0.005384, 0.072326, -0.042938),
    )

    fun horizonFor(coin: String): Int = CHOSEN_HORIZON_S[coin] ?: 300

    fun horizonLabel(h: Int): String = if (h < 0) "to close" else if (h % 60 == 0) "${h / 60} min" else "$h s"

    fun weights(coin: String, horizonS: Int): DoubleArray? = W["$coin|$horizonS"]

    data class Sample(val atMs: Long, val mid: Double, val top3Yes: Double, val top3No: Double)

    /** [history] oldest first (may be empty at the open). */
    fun features(now: Sample, history: List<Sample>, closeMs: Long): DoubleArray {
        fun midAt(back: Long): Double {
            val target = now.atMs - back
            val s = history.firstOrNull { it.atMs >= target } ?: return now.mid
            return if (s.atMs < now.atMs) s.mid else now.mid
        }
        val m60 = now.mid - midAt(60_000L)
        val m180 = now.mid - midAt(180_000L)
        val imb = (now.top3Yes - now.top3No) / maxOf(now.top3Yes + now.top3No, 1e-9)
        val tau = ((closeMs - now.atMs) / 900_000.0).coerceIn(0.0, 1.0)
        return doubleArrayOf(1.0, m60, m180, imb, now.mid - 0.5, (now.mid - 0.5) * (1 - tau))
    }

    /** Predicted YES-mid move (dollars) over [horizonS]; null if no weights. */
    fun predict(coin: String, horizonS: Int, x: DoubleArray): Double? {
        val w = weights(coin, horizonS) ?: return null
        var s = 0.0
        for (i in x.indices) s += w[i] * x[i]
        return s.takeIf { it.isFinite() }
    }
}
