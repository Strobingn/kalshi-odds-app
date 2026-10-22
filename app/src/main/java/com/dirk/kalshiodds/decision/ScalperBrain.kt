package com.dirk.kalshiodds.decision

import org.json.JSONObject
import java.io.File
import kotlin.math.exp

/**
 * Kalshi Scalper self-learning (deterministic, no LLM). One tiny online logistic model per strategy:
 *   p(win) = sigmoid(w0 + w1·(ask−0.50) + w2·(fair−ask) + w3·(minute/15))
 * Updated by one bounded SGD step after every closed paper trade of that strategy (label = net > 0 after fees),
 * plus a learned entry threshold (minConf) that rises after losses and falls after wins, clamped to [0.40, 0.75].
 * Weights are clamped to ±4 so nothing can run away. Persisted to a JSON file on device. If the model has fewer
 * than [MIN_TRAINED] closes it falls back to the CF fair value (the simple fallback).
 */
object ScalperBrain {
    const val LR = 0.05
    const val L2 = 0.001
    const val W_MAX = 4.0
    const val CONF_MIN = 0.40
    const val CONF_MAX = 0.75
    const val MIN_TRAINED = 20
    const val HOLD_CONF = 0.95

    data class State(val w: DoubleArray, var minConf: Double, var n: Int = 0, var wins: Int = 0, var lastP: Double = Double.NaN)

    private val lock = Any()
    private val states = HashMap<String, State>()
    @Volatile private var file: File? = null

    fun init(dir: File) = synchronized(lock) {
        file = File(dir, "scalper_brain.json")
        runCatching {
            val o = JSONObject(file!!.readText())
            o.keys().forEach { k ->
                val s = o.getJSONObject(k); val wa = s.getJSONArray("w")
                states[k] = State(DoubleArray(4) { wa.optDouble(it, 0.0) }, s.optDouble("minConf", 0.5), s.optInt("n"), s.optInt("wins"))
            }
        }
        Unit
    }

    /** Distinct deterministic starting threshold per strategy (so no two start identical). */
    fun seedConf(code: String): Double = 0.46 + (Math.floorMod(code.hashCode(), 9)) * 0.01

    private fun st(code: String) = states.getOrPut(code) { State(doubleArrayOf(0.0, -1.0, 6.0, 0.0), seedConf(code)) }

    fun features(ask: Double, fair: Double?, minute: Double): DoubleArray =
        doubleArrayOf(1.0, ask - 0.5, ((fair ?: ask) - ask).coerceIn(-0.5, 0.5), (minute / 15.0).coerceIn(0.0, 1.0))

    private fun sig(z: Double) = 1.0 / (1.0 + exp(-z.coerceIn(-30.0, 30.0)))
    private fun dot(w: DoubleArray, x: DoubleArray) = (0 until 4).sumOf { w[it] * x[it] }

    fun p(code: String, x: DoubleArray): Double = synchronized(lock) { sig(dot(st(code).w, x)).also { st(code).lastP = it } }
    fun minConf(code: String): Double = synchronized(lock) { st(code).minConf }
    fun trained(code: String): Boolean = synchronized(lock) { st(code).n >= MIN_TRAINED }

    /** Confidence the held side settles in the money: on-device GBM (fair value if missing); once trained, the lower of that and this strategy's learner. */
    fun holdConfidence(code: String, bid: Double, fair: Double?, gbm: Double? = null): Double {
        val f = gbm?.takeIf { it.isFinite() } ?: fair?.takeIf { it.isFinite() } ?: return 0.0
        if (!trained(code)) return f
        val pm = synchronized(lock) { sig(dot(st(code).w, features(bid, f, 15.0))) }
        return minOf(f, pm)
    }

    /** Drop learned weights so the next close does not tune off trades from before a bankroll reset. */
    fun resetRecent() = synchronized(lock) {
        states.clear()
        persist()
    }

    fun learn(code: String, x: DoubleArray, won: Boolean) = synchronized(lock) {
        val s = st(code)
        val err = (if (won) 1.0 else 0.0) - sig(dot(s.w, x))
        for (i in 0 until 4) s.w[i] = (s.w[i] + LR * (err * x[i] - L2 * s.w[i])).coerceIn(-W_MAX, W_MAX)
        s.minConf = (s.minConf + if (won) -0.0025 else 0.005).coerceIn(CONF_MIN, CONF_MAX)
        s.n++; if (won) s.wins++
        persist()
    }

    private fun persist() {
        val f = file ?: return
        val o = JSONObject()
        states.forEach { (k, s) -> o.put(k, JSONObject().put("w", org.json.JSONArray(s.w.toList())).put("minConf", s.minConf).put("n", s.n).put("wins", s.wins)) }
        runCatching { val tmp = File(f.parentFile, f.name + ".tmp"); tmp.writeText(o.toString()); tmp.renameTo(f) }
    }

    fun summary(code: String): String = synchronized(lock) {
        val s = st(code)
        String.format(java.util.Locale.US, "AI learned %d · min conf %.0f%%%s · w=[%.2f %.2f %.2f %.2f]%s", s.n, s.minConf * 100,
            if (s.lastP.isFinite()) String.format(java.util.Locale.US, " · last conf %.0f%%", s.lastP * 100) else "",
            s.w[0], s.w[1], s.w[2], s.w[3], if (s.n < MIN_TRAINED) " · warming up (fair-value fallback)" else "")
    }

    private val BX = Regex("bx=([-0-9.]+),([-0-9.]+),([-0-9.]+),([-0-9.]+)")
    fun tag(x: DoubleArray) = String.format(java.util.Locale.US, "bx=%.4f,%.4f,%.4f,%.4f", x[0], x[1], x[2], x[3])
    fun parse(note: String): DoubleArray? = BX.find(note)?.groupValues?.drop(1)?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == 4 }?.toDoubleArray()
}
