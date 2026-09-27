package com.dirk.kalshiodds.prediction

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.ln

/**
 * Tiny on-device logistic imported from `ml/train_edge.py`.
 * Weights + scaler + optional Platt — a few dozen floats, no TFLite.
 *
 * Two kinds:
 * - [KIND_LOGISTIC] (legacy): `sigmoid(bias + w·z)`, then blended with the
 *   market mid by [blendWeight].
 * - [KIND_OFFSET_LOGISTIC]: market-anchored,
 *   `sigmoid(logit(clip(mid)) + bias + w·z)`. `logit(mid)` is a fixed offset,
 *   not a standardized feature, so zero weights reproduce the mid. The output
 *   already *is* the fair; callers must not blend it with the market again.
 */
data class EdgeModel(
    val version: Int,
    val kind: String,
    val featureNames: List<String>,
    val weights: FloatArray,
    val bias: Float,
    val mean: FloatArray,
    val std: FloatArray,
    val plattA: Float = 1f,
    val plattB: Float = 0f,
    val blendWeight: Float = 0.35f,
    val feeMargin: Float = 0.07f,
    val confidenceMargin: Float = 0.03f,
    val metrics: Map<String, Double> = emptyMap(),
    /** [KIND_OFFSET_LOGISTIC] only: the mid is clipped to `[midClip, 1 − midClip]`. */
    val midClip: Float = DEFAULT_MID_CLIP
) {
    init {
        require(weights.size == featureNames.size) { "weights ${weights.size} != names ${featureNames.size}" }
        require(mean.size == weights.size && std.size == weights.size)
        require(kind == KIND_LOGISTIC || kind == KIND_OFFSET_LOGISTIC) { "unsupported model kind $kind" }
        require(midClip > 0f && midClip < 0.5f) { "mid_clip must be in (0, 0.5)" }
    }

    /** Output is anchored to the market mid and is the fair itself (no re-blend). */
    val isMarketAnchored: Boolean get() = kind == KIND_OFFSET_LOGISTIC

    /**
     * P(YES). [marketMid] is the Kalshi YES mid (0–1): the fixed offset of a
     * [KIND_OFFSET_LOGISTIC] model. When null, the `market_mid` feature in
     * [raw] is used. A legacy [KIND_LOGISTIC] model ignores it.
     */
    fun predictYes(raw: FloatArray, marketMid: Double? = null): Double {
        val linear = logit(raw).toDouble()
        val z = if (isMarketAnchored) {
            val mid = marketMid
                ?: featureNames.indexOf(MARKET_MID).takeIf { it in raw.indices }?.let { raw[it].toDouble() }
                ?: 0.5
            marketOffset(mid) + linear
        } else {
            linear
        }
        val p = sigmoid(z)
        val calibrated = if (plattA == 1f && plattB == 0f) {
            p
        } else {
            val lp = logitFromProb(p)
            sigmoid(plattA * lp + plattB)
        }
        return if (isMarketAnchored) {
            val c = midClip.toDouble()
            calibrated.coerceIn(c, 1.0 - c)
        } else {
            calibrated.coerceIn(0.02, 0.98)
        }
    }

    /** `logit(clip(mid, midClip, 1 − midClip))` — never standardized. */
    fun marketOffset(marketMid: Double): Double {
        val c = midClip.toDouble()
        val q = (if (marketMid.isFinite()) marketMid else 0.5).coerceIn(c, 1.0 - c)
        return ln(q / (1.0 - q))
    }

    fun logit(raw: FloatArray): Float {
        val n = weights.size
        var acc = bias
        val lim = minOf(n, raw.size)
        for (i in 0 until lim) {
            val s = if (std[i] < 1e-6f) 1f else std[i]
            acc += weights[i] * ((raw[i] - mean[i]) / s)
        }
        return acc
    }

    /**
     * Flag an edge only when |model − market| clears the official
     * order-level taker fee (amortized at the $5 ticket) + [confidenceMargin].
     */
    fun qualifiesEdge(modelYes: Double, marketMid: Double, feeRate: Double = feeMargin.toDouble()): Boolean {
        val m = marketMid.coerceIn(0.02, 0.98)
        val gap = kotlin.math.abs(modelYes - m)
        val fee = com.dirk.kalshiodds.signal.trade.KalshiFee.perContract(m, feeRate)
        return gap > fee + confidenceMargin
    }

    fun blendWithMarket(modelYes: Double, marketMid: Double): Double {
        // Already anchored to the market: blending again would double-count the mid.
        if (isMarketAnchored) return modelYes.coerceIn(0.02, 0.98)
        val w = blendWeight.toDouble().coerceIn(0.0, 1.0)
        return ((1.0 - w) * marketMid + w * modelYes).coerceIn(0.02, 0.98)
    }

    fun toJson(): String {
        val o = JSONObject()
        o.put("version", version)
        o.put("kind", kind)
        o.put("feature_names", JSONArray(featureNames))
        o.put("weights", jsonFloats(weights))
        o.put("bias", bias.toDouble())
        o.put("mean", jsonFloats(mean))
        o.put("std", jsonFloats(std))
        o.put("platt_a", plattA.toDouble())
        o.put("platt_b", plattB.toDouble())
        o.put("blend_weight", blendWeight.toDouble())
        o.put("fee_margin", feeMargin.toDouble())
        o.put("confidence_margin", confidenceMargin.toDouble())
        if (isMarketAnchored) o.put("mid_clip", midClip.toDouble())
        if (metrics.isNotEmpty()) {
            val m = JSONObject()
            metrics.forEach { (k, v) -> m.put(k, v) }
            o.put("metrics", m)
        }
        return o.toString()
    }

    companion object {
        const val KIND_LOGISTIC = "logistic"
        const val KIND_OFFSET_LOGISTIC = "offset_logistic"
        const val MARKET_MID = "market_mid"

        /** KalshiPrice 0.1¢ tick: zero weights reproduce every usable mid. */
        const val DEFAULT_MID_CLIP = 0.001f

        fun parse(raw: String): EdgeModel {
            val o = JSONObject(raw)
            val names = stringList(o.getJSONArray("feature_names"))
            val weights = floatArray(o.getJSONArray("weights"))
            val mean = floatArray(o.getJSONArray("mean"))
            val std = floatArray(o.getJSONArray("std"))
            require(names.size == weights.size) { "feature_names / weights length mismatch" }
            require(names.size == EdgeFeatures.SIZE) {
                "expected ${EdgeFeatures.SIZE} features, got ${names.size}"
            }
            val metrics = linkedMapOf<String, Double>()
            o.optJSONObject("metrics")?.let { m ->
                val keys = m.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    // Numbers only: a note string would be NaN and break toJson().
                    m.optDouble(k).takeIf { it.isFinite() }?.let { metrics[k] = it }
                }
            }
            return EdgeModel(
                version = o.optInt("version", 1),
                kind = o.optString("kind", KIND_LOGISTIC).ifBlank { KIND_LOGISTIC },
                featureNames = names,
                weights = weights,
                bias = o.optDouble("bias", 0.0).toFloat(),
                mean = mean,
                std = std,
                plattA = o.optDouble("platt_a", 1.0).toFloat(),
                plattB = o.optDouble("platt_b", 0.0).toFloat(),
                blendWeight = o.optDouble("blend_weight", 0.35).toFloat(),
                feeMargin = o.optDouble("fee_margin", 0.07).toFloat(),
                confidenceMargin = o.optDouble("confidence_margin", 0.03).toFloat(),
                metrics = metrics,
                midClip = o.optDouble("mid_clip", DEFAULT_MID_CLIP.toDouble()).toFloat()
            )
        }

        fun sigmoid(z: Double): Double {
            val x = z.coerceIn(-30.0, 30.0)
            return 1.0 / (1.0 + exp(-x))
        }

        fun logitFromProb(p: Double): Double {
            val q = p.coerceIn(1e-6, 1.0 - 1e-6)
            return ln(q / (1.0 - q))
        }

        private fun stringList(a: JSONArray): List<String> =
            (0 until a.length()).map { a.getString(it) }

        private fun floatArray(a: JSONArray): FloatArray =
            FloatArray(a.length()) { a.getDouble(it).toFloat() }

        private fun jsonFloats(xs: FloatArray): JSONArray {
            val a = JSONArray()
            for (x in xs) a.put(x.toDouble())
            return a
        }
    }
}
