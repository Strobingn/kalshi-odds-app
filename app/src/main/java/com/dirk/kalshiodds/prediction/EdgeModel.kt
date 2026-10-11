package com.dirk.kalshiodds.prediction

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.ln

/**
 * Tiny on-device **market-offset** logistic from `ml/train_edge.py` (schema 2):
 *
 *     logit P(YES) = logit(mid) + bias + Σ w_i · (x_i − mean_i) / std_i
 *
 * With every weight and the bias at 0 it returns the Kalshi mid, so it can
 * only move away from the market by what the trainer fitted on held-out data.
 * Features: [EdgeFeatures] (Python twin: `tools/backtest/pipeline.edge_features`).
 *
 * Schema-1 models (absolute P(YES), trained with a UTC/local time-of-day mix-up
 * and, before 0.3.8, on the settlement price) do not parse. Synthetic
 * `"fixture": true` exports never parse either.
 */
data class EdgeModel(
    val schema: Int,
    val kind: String,
    val featureNames: List<String>,
    val weights: DoubleArray,
    val bias: Double,
    val mean: DoubleArray,
    val std: DoubleArray,
    /** Minimum net EV per contract (dollars) at the ask before a side counts as an edge. */
    val evMargin: Double = DEFAULT_EV_MARGIN,
    val metrics: Map<String, Double> = emptyMap()
) {
    init {
        require(weights.size == featureNames.size) { "weights ${weights.size} != names ${featureNames.size}" }
        require(mean.size == weights.size && std.size == weights.size)
    }

    /** Kept for existing labels (“Model market_offset v2”). */
    val version: Int get() = schema

    fun predictYes(raw: DoubleArray, marketMid: Double): Double {
        val z = logitFromProb(marketMid) + offsetLogit(raw)
        return sigmoid(z).coerceIn(P_MIN, P_MAX)
    }

    /** bias + Σ w·z — the model's log-odds shift away from the market. */
    fun offsetLogit(raw: DoubleArray): Double {
        var acc = bias
        val lim = minOf(weights.size, raw.size)
        for (i in 0 until lim) {
            val s = if (std[i] < 1e-9) 1.0 else std[i]
            acc += weights[i] * ((raw[i] - mean[i]) / s)
        }
        return acc
    }

    fun toJson(): String {
        val o = JSONObject()
        o.put("version", schema)
        o.put("schema", schema)
        o.put("kind", kind)
        o.put("fixture", false)
        o.put("feature_names", JSONArray(featureNames))
        o.put("weights", jsonDoubles(weights))
        o.put("bias", bias)
        o.put("mean", jsonDoubles(mean))
        o.put("std", jsonDoubles(std))
        o.put("ev_margin", evMargin)
        if (metrics.isNotEmpty()) {
            val m = JSONObject()
            metrics.forEach { (k, v) -> if (v.isFinite()) m.put(k, v) }
            o.put("metrics", m)
        }
        return o.toString()
    }

    override fun equals(other: Any?): Boolean =
        other is EdgeModel && other.toJson() == toJson()

    override fun hashCode(): Int = toJson().hashCode()

    companion object {
        const val SCHEMA = 2
        const val KIND = "market_offset"
        const val P_MIN = 0.005
        const val P_MAX = 0.995
        const val DEFAULT_EV_MARGIN = 0.02

        fun parse(raw: String): EdgeModel {
            val o = JSONObject(raw)
            val schema = o.optInt("schema", o.optInt("version", 1))
            require(schema == SCHEMA) {
                "model schema $schema is retired — retrain with ml/train_edge.py (schema $SCHEMA)"
            }
            val kind = o.optString("kind")
            require(kind == KIND) { "model kind '$kind' is not '$KIND'" }
            require(!o.optBoolean("fixture", false)) { "synthetic fixture model — not for live use" }
            val names = stringList(o.getJSONArray("feature_names"))
            require(names == EdgeFeatures.NAMES) {
                "feature_names $names do not match this app (${EdgeFeatures.NAMES})"
            }
            val weights = doubles(o.getJSONArray("weights"))
            val mean = doubles(o.getJSONArray("mean"))
            val std = doubles(o.getJSONArray("std"))
            require(names.size == weights.size) { "feature_names / weights length mismatch" }
            val bias = o.optDouble("bias", 0.0)
            require((weights + mean + std + bias).all { it.isFinite() }) { "non-finite weights" }
            val metrics = linkedMapOf<String, Double>()
            o.optJSONObject("metrics")?.let { m ->
                val keys = m.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = m.optDouble(k, Double.NaN)
                    if (v.isFinite()) metrics[k] = v
                }
            }
            return EdgeModel(
                schema = schema,
                kind = kind,
                featureNames = names,
                weights = weights,
                bias = bias,
                mean = mean,
                std = std,
                evMargin = o.optDouble("ev_margin", DEFAULT_EV_MARGIN).takeIf { it.isFinite() && it >= 0.0 }
                    ?: DEFAULT_EV_MARGIN,
                metrics = metrics
            )
        }

        fun sigmoid(z: Double): Double {
            val x = z.coerceIn(-30.0, 30.0)
            return 1.0 / (1.0 + exp(-x))
        }

        fun logitFromProb(p: Double): Double {
            val q = p.coerceIn(P_MIN, P_MAX)
            return ln(q / (1.0 - q))
        }

        private fun stringList(a: JSONArray): List<String> =
            (0 until a.length()).map { a.getString(it) }

        private fun doubles(a: JSONArray): DoubleArray =
            DoubleArray(a.length()) { a.getDouble(it) }

        private fun jsonDoubles(xs: DoubleArray): JSONArray {
            val a = JSONArray()
            for (x in xs) a.put(x)
            return a
        }
    }
}
