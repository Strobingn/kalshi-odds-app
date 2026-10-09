package com.dirk.kalshiodds.signal.scalper

import org.json.JSONArray
import org.json.JSONObject

/**
 * The scalper's gradient-boosted trees, exported by
 * `tools/research/scalp/train_compact.py` to `assets/scalper_model.json`.
 *
 * [predict] returns the expected result, in cents per contract, of one
 * resting scalp posted now **from the front of the queue**: join the best
 * bid for 20 s, offer 1¢ higher when filled, stop 4¢ lower, give up after
 * 120 s. [queuePenaltyCents] is what a queue of that many contracts ahead
 * took off that result on the test days.
 *
 * Trained 2026-09-18 → 09-28, threshold picked on 09-29 → 10-02, scored once
 * on 10-03 → 10-09: the orders it picks made +0.35¢ each at the front of the
 * queue (99% interval [+0.24, +0.47], 7 of 7 days) against +0.18¢ for every
 * order. Pure math; never places an order.
 */
class ScalperModel(
    val features: List<String>,
    /** Act when the prediction is at least this many cents (the profit-maximising cut on the validation days). */
    val thetaCents: Double,
    private val trees: List<Tree>,
    /** (contracts ahead, cents lost), ascending by contracts. */
    val queuePenalty: List<Pair<Double, Double>>,
    val testFrontOfQueueCents: Double? = null
) {
    class Tree(
        val feature: IntArray,
        val threshold: DoubleArray,
        val left: IntArray,
        val right: IntArray,
        val value: DoubleArray
    )

    val treeCount: Int get() = trees.size

    /** Expected cents per contract for a front-of-queue scalp with these [x] features. */
    fun predict(x: FloatArray): Double {
        require(x.size == features.size) { "expected ${features.size} features, got ${x.size}" }
        var sum = 0.0
        for (t in trees) {
            var i = 0
            while (t.feature[i] >= 0) {
                // The training rows were float32: compare the float value, as LightGBM did.
                i = if (x[t.feature[i]].toDouble() <= t.threshold[i]) t.left[i] else t.right[i]
            }
            sum += t.value[i]
        }
        return sum
    }

    /** Cents a queue of [contractsAhead] takes off the front-of-queue result (piecewise linear, flat past the last point). */
    fun queuePenaltyCents(contractsAhead: Double): Double {
        if (queuePenalty.isEmpty() || !contractsAhead.isFinite() || contractsAhead <= 0.0) return 0.0
        var prev = 0.0 to 0.0
        for (p in queuePenalty) {
            if (contractsAhead <= p.first) {
                val span = p.first - prev.first
                if (span <= 0.0) return p.second
                return prev.second + (p.second - prev.second) * (contractsAhead - prev.first) / span
            }
            prev = p
        }
        return queuePenalty.last().second
    }

    /** The prediction after the queue: what the order is expected to make where it actually sits. */
    fun expectedCents(prediction: Double, contractsAhead: Double): Double =
        prediction - queuePenaltyCents(contractsAhead)

    companion object {
        const val ASSET_NAME = "scalper_model.json"

        fun parse(raw: String): ScalperModel {
            val o = JSONObject(raw)
            val names = strings(o.getJSONArray("features"))
            require(names == PrintGrid.FEATURES) { "scalper model features do not match PrintGrid.FEATURES" }
            val ts = o.getJSONArray("trees")
            val trees = (0 until ts.length()).map { i ->
                val t = ts.getJSONObject(i)
                Tree(
                    feature = ints(t.getJSONArray("f")),
                    threshold = doubles(t.getJSONArray("t")),
                    left = ints(t.getJSONArray("l")),
                    right = ints(t.getJSONArray("r")),
                    value = doubles(t.getJSONArray("v"))
                )
            }
            require(trees.isNotEmpty()) { "scalper model has no trees" }
            val pen = o.optJSONArray("queue_penalty")
            val penalty = if (pen == null) {
                emptyList()
            } else {
                (0 until pen.length()).map { i ->
                    val row = pen.getJSONArray(i)
                    row.getDouble(0) to row.getDouble(1)
                }.sortedBy { it.first }
            }
            return ScalperModel(
                features = names,
                thetaCents = o.getDouble("theta_cents"),
                trees = trees,
                queuePenalty = penalty,
                testFrontOfQueueCents = o.optDouble("test_front_of_queue_cents").takeIf { it.isFinite() }
            )
        }

        private fun strings(a: JSONArray): List<String> = (0 until a.length()).map { a.getString(it) }
        private fun ints(a: JSONArray): IntArray = IntArray(a.length()) { a.getInt(it) }
        private fun doubles(a: JSONArray): DoubleArray = DoubleArray(a.length()) { a.getDouble(it) }
    }
}
