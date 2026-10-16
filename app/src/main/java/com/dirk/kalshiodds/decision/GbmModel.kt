package com.dirk.kalshiodds.decision

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Kalshi Scalper on-device gradient-boosted model (LightGBM, trained off-device on recorded BTC 15-minute books + spot),
 * exported as plain tree JSON and evaluated here. Output: isotonic-calibrated P(YES settles). Deterministic, no LLM.
 * Features: z (spot vs strike in σ units), fair value, minutes left, mid, spread, top-of-book imbalance, 60 s mid change.
 * If the asset is missing [pYes] returns null and callers fall back to fair value.
 */
object GbmModel {
    private class Node(val f: Int, val t: Double, val l: Node?, val r: Node?, val defLeft: Boolean, val v: Double)
    @Volatile private var trees: List<Node> = emptyList()
    @Volatile private var isoX: DoubleArray = DoubleArray(0)
    @Volatile private var isoY: DoubleArray = DoubleArray(0)
    @Volatile var info: String = "not loaded"; private set
    val loaded: Boolean get() = trees.isNotEmpty()

    fun load(json: String) {
        val o = JSONObject(json)
        val ts = o.getJSONArray("trees")
        trees = (0 until ts.length()).map { node(ts.getJSONObject(it)) }
        isoX = arr(o.getJSONArray("iso_x")); isoY = arr(o.getJSONArray("iso_y"))
        val m = o.optJSONObject("metrics")
        info = "GBM ${trees.size} trees · ${o.optString("trained_on")}" + (m?.let { String.format(java.util.Locale.US, " · Brier %.3f vs market %.3f", it.optDouble("model"), it.optDouble("market_mid")) } ?: "")
    }
    private fun arr(a: JSONArray) = DoubleArray(a.length()) { a.getDouble(it) }
    private fun node(o: JSONObject): Node = if (o.has("v")) Node(-1, 0.0, null, null, true, o.getDouble("v"))
        else Node(o.getInt("f"), o.getDouble("t"), node(o.getJSONObject("l")), node(o.getJSONObject("r")), o.optBoolean("d", true), 0.0)

    /** Raw features in training order: z, pf, tau_m, mid_p, spr_p, imb, dm60_p. */
    fun predict(x: DoubleArray): Double? {
        val ts = trees; if (ts.isEmpty()) return null
        var s = 0.0
        for (t in ts) { var n = t; while (n.f >= 0) { val v = x[n.f]; n = (if (v.isNaN()) (if (n.defLeft) n.l else n.r) else if (v <= n.t) n.l else n.r)!! }; s += n.v }
        val raw = 1.0 / (1.0 + exp(-s))
        return iso(raw)
    }
    private fun iso(p: Double): Double {
        val xs = isoX; val ys = isoY; if (xs.isEmpty()) return p
        if (p <= xs[0]) return ys[0]; if (p >= xs[xs.size - 1]) return ys[ys.size - 1]
        var i = java.util.Arrays.binarySearch(xs, p); if (i >= 0) return ys[i]; i = -i - 1
        val w = (p - xs[i - 1]) / (xs[i] - xs[i - 1]); return ys[i - 1] + w * (ys[i] - ys[i - 1])
    }

    /** P(side settles in the money) from a live quote, or null (no model / missing inputs). */
    fun pSide(q: ScalpRule.Quote, side: String, dm60: Double = 0.0): Double? {
        val s = q.spot ?: return null; val k = q.strike ?: return null; val sig = q.sigmaPerSec
        val yb = q.yesBid ?: return null; val ya = q.yesAsk ?: return null
        if (s <= 0 || k <= 0 || sig == null || sig <= 0 || q.tauS <= 0) return null
        val z = (ln(s / k) / (sig * sqrt(ScalpRule.effectiveTauSeconds(q.tauS)))).coerceIn(-8.0, 8.0)
        val pf = ScalpRule.fairYes(s, k, sig, q.tauS) ?: return null
        val bq = q.yesBidSize ?: 0.0; val aq = q.yesAskSize ?: 0.0
        val imb = (bq - aq) / maxOf(bq + aq, 1.0)
        val py = predict(doubleArrayOf(z, pf, q.tauS / 60.0, (yb + ya) / 2, ya - yb, imb, dm60)) ?: return null
        return if (side == "YES") py else 1.0 - py
    }
}
