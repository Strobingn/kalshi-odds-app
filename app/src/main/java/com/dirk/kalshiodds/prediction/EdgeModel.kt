package com.dirk.kalshiodds.prediction

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.ln

/** Flat binary tree exported from the offline gradient booster. */
data class EdgeTreeNode(
    val feature: Int = -1,
    val threshold: Double = 0.0,
    val left: Int = -1,
    val right: Int = -1,
    val value: Double? = null
)

data class EdgeTree(val nodes: List<EdgeTreeNode>) {
    fun predict(raw: FloatArray): Double {
        var index = 0
        repeat(nodes.size) {
            val node = nodes[index]
            node.value?.let { return it }
            index = if (raw[node.feature].toDouble() <= node.threshold) node.left else node.right
        }
        error("Invalid tree: traversal did not reach a leaf")
    }

    fun validate(featureCount: Int) {
        require(nodes.isNotEmpty() && nodes.size <= 64) { "invalid tree size" }
        nodes.forEachIndexed { i, node ->
            if (node.value != null) {
                require(node.value.isFinite()) { "non-finite tree leaf" }
            } else {
                require(node.feature in 0 until featureCount && node.threshold.isFinite()) { "invalid tree split" }
                require(node.left in (i + 1) until nodes.size && node.right in (i + 1) until nodes.size) {
                    "invalid tree child"
                }
            }
        }
    }
}

/**
 * On-device logistic or bounded tree ensemble imported from offline training.
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
    /** Train only a correction to the market log-odds instead of relearning the market from scratch. */
    val marketPrior: Boolean = false,
    /** Monotone PAV calibration knots, learned offline on historical settlements. */
    val isotonicX: DoubleArray = doubleArrayOf(),
    val isotonicY: DoubleArray = doubleArrayOf(),
    val metrics: Map<String, Double> = emptyMap(),
    val trees: List<EdgeTree> = emptyList(),
    val baseScore: Double = 0.0,
    val learningRate: Double = 0.05
) {
    init {
        require(kind == "logistic" || kind == "gbdt") { "unsupported model kind" }
        require(weights.size == featureNames.size) { "weights ${weights.size} != names ${featureNames.size}" }
        require(mean.size == weights.size && std.size == weights.size)
        require(isotonicX.size == isotonicY.size) { "isotonic calibration length mismatch" }
        require(isotonicX.zip(isotonicY).all { (x, y) -> x.isFinite() && y.isFinite() }) {
            "non-finite isotonic calibration"
        }
        require((1 until isotonicX.size).all { index -> isotonicX[index] >= isotonicX[index - 1] }) {
            "isotonic x values must be sorted"
        }
        require((1 until isotonicY.size).all { index -> isotonicY[index] >= isotonicY[index - 1] }) {
            "isotonic y values must be monotone"
        }
        if (kind == "gbdt") {
            require(featureNames == EdgeFeatures.NAMES) { "GBDT feature order differs from live app" }
            require(trees.isNotEmpty() && trees.size <= 512) { "invalid tree count" }
            require(baseScore.isFinite() && learningRate.isFinite() && learningRate > 0.0 &&
                plattA.isFinite() && plattB.isFinite()) { "invalid GBDT calibration" }
            trees.forEach { it.validate(featureNames.size) }
        }
    }

    fun predictYes(raw: FloatArray): Double {
        val z = logit(raw)
        val p = sigmoid(z)
        val calibrated = if (plattA == 1f && plattB == 0f) {
            p
        } else {
            val lp = logitFromProb(p)
            sigmoid(plattA * lp + plattB)
        }
        val withMarketPrior = if (marketPrior) {
            val market = raw.getOrNull(featureNames.indexOf("market_mid"))?.toDouble()
            calibratedLogit(calibrated, market)
        } else {
            calibrated
        }
        return applyIsotonic(withMarketPrior).coerceIn(MIN_DECISION_PROBABILITY, 1.0 - MIN_DECISION_PROBABILITY)
    }

    fun logit(raw: FloatArray): Double {
        if (kind == "gbdt") {
            require(raw.size == featureNames.size && raw.all { it.isFinite() }) { "invalid GBDT features" }
            var z = baseScore
            for (tree in trees) z += learningRate * tree.predict(raw)
            return z
        }
        val n = weights.size
        var acc = bias
        val lim = minOf(n, raw.size)
        for (i in 0 until lim) {
            val s = if (std[i] < 1e-6f) 1f else std[i]
            acc += weights[i] * ((raw[i] - mean[i]) / s)
        }
        return acc.toDouble()
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
        val w = blendWeight.toDouble().coerceIn(0.0, 1.0)
        return ((1.0 - w) * marketMid + w * modelYes)
            .coerceIn(MIN_DECISION_PROBABILITY, 1.0 - MIN_DECISION_PROBABILITY)
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
        o.put("market_prior", marketPrior)
        if (isotonicX.isNotEmpty()) {
            o.put("isotonic_x", JSONArray(isotonicX.toList()))
            o.put("isotonic_y", JSONArray(isotonicY.toList()))
        }
        if (kind == "gbdt") {
            o.put("base_score", baseScore)
            o.put("learning_rate", learningRate)
            o.put("trees", JSONArray().apply {
                trees.forEach { tree -> put(JSONObject().put("nodes", JSONArray().apply {
                    tree.nodes.forEach { node ->
                        put(if (node.value != null) JSONObject().put("value", node.value)
                        else JSONObject().put("feature", node.feature).put("threshold", node.threshold)
                            .put("left", node.left).put("right", node.right))
                    }
                })) }
            })
        }
        if (metrics.isNotEmpty()) {
            val m = JSONObject()
            metrics.forEach { (k, v) -> m.put(k, v) }
            o.put("metrics", m)
        }
        return o.toString()
    }

    companion object {
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
                    metrics[k] = m.optDouble(k)
                }
            }
            val kind = o.optString("kind", "logistic")
            val trees = if (kind == "gbdt") {
                val arr = o.getJSONArray("trees")
                require(arr.length() in 1..512)
                (0 until arr.length()).map { i ->
                    val nodes = arr.getJSONObject(i).getJSONArray("nodes")
                    EdgeTree((0 until nodes.length()).map { j ->
                        val node = nodes.getJSONObject(j)
                        if (node.has("value")) EdgeTreeNode(value = node.getDouble("value"))
                        else EdgeTreeNode(
                            feature = node.getInt("feature"),
                            threshold = node.getDouble("threshold"),
                            left = node.getInt("left"), right = node.getInt("right")
                        )
                    })
                }
            } else emptyList()
            return EdgeModel(
                version = o.optInt("version", 1),
                kind = kind,
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
                marketPrior = o.optBoolean("market_prior", false),
                isotonicX = doubleArray(o.optJSONArray("isotonic_x")),
                isotonicY = doubleArray(o.optJSONArray("isotonic_y")),
                metrics = metrics,
                trees = trees,
                baseScore = o.optDouble("base_score", 0.0),
                learningRate = o.optDouble("learning_rate", 0.05)
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

        private fun doubleArray(a: JSONArray?): DoubleArray =
            if (a == null) doubleArrayOf() else DoubleArray(a.length()) { a.getDouble(it) }

        private fun stringList(a: JSONArray): List<String> =
            (0 until a.length()).map { a.getString(it) }

        private fun floatArray(a: JSONArray): FloatArray =
            FloatArray(a.length()) { a.getDouble(it).toFloat() }

        private fun jsonFloats(xs: FloatArray): JSONArray {
            val a = JSONArray()
            for (x in xs) a.put(x.toDouble())
            return a
        }

        const val MIN_DECISION_PROBABILITY = 1e-4
    }

    private fun calibratedLogit(modelProbability: Double, marketProbability: Double?): Double {
        val market = marketProbability?.takeIf { it.isFinite() }
            ?.coerceIn(MIN_DECISION_PROBABILITY, 1.0 - MIN_DECISION_PROBABILITY)
            ?: return modelProbability
        return sigmoid(logitFromProb(modelProbability) + logitFromProb(market))
    }

    private fun applyIsotonic(probability: Double): Double {
        if (isotonicX.size < 2 || isotonicX.size != isotonicY.size) return probability
        if (probability <= isotonicX.first()) return isotonicY.first()
        if (probability >= isotonicX.last()) return isotonicY.last()
        val upper = isotonicX.indexOfFirst { probability <= it }
        if (upper <= 0) return probability
        val lower = upper - 1
        val width = isotonicX[upper] - isotonicX[lower]
        if (width <= 1e-12) return isotonicY[upper]
        val t = (probability - isotonicX[lower]) / width
        return isotonicY[lower] + (isotonicY[upper] - isotonicY[lower]) * t
    }
}
