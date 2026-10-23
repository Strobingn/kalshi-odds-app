package com.dirk.kalshiodds.signal.scalper

import org.json.JSONArray
import org.json.JSONObject

/**
 * The scalper's gradient-boosted trees, exported by `tools/research/scalp/train_calibrated.py` (or the older
 * `train_compact.py`) to `assets/scalper_model.json`.
 *
 * Two kinds of file:
 *  - **classifier** (current): depth-capped trees whose sum is the log-odds that one resting scalp posted now, from
 *    the front of the queue (join the best bid 20 s, offer 1¢ higher when filled, stop 4¢ lower, give up after
 *    120 s), makes money. [probability] is that chance after isotonic calibration on the validation days;
 *    [predict] turns it into expected cents a contract through the cents-by-chance curve measured on the same days,
 *    so the threshold and the queue penalty stay in cents.
 *  - **regression** (older): the trees' sum is the expected cents directly; [probability] is null.
 *
 * [queuePenaltyCents] is what a queue of that many contracts ahead took off the result on the test days.
 * [contributions] splits one prediction into what each feature added (the path through every tree), for the
 * Explain button. Pure math; never places an order.
 */
class ScalperModel(
    val features: List<String>,
    /** Act when the prediction is at least this many cents (the profit-maximising cut on the validation days). */
    val thetaCents: Double,
    private val trees: List<Tree>,
    /** (contracts ahead, cents lost), ascending by contracts. */
    val queuePenalty: List<Pair<Double, Double>>,
    val testFrontOfQueueCents: Double? = null,
    val kind: Kind = Kind.REGRESSION,
    /** Classifier only: raw chance (sigmoid of the trees' sum) → calibrated chance, as interpolation knots. */
    val calibration: Curve? = null,
    /** Classifier only: calibrated chance → expected cents a contract, as interpolation knots. */
    val centsByChance: Curve? = null,
    /** What the training run measured (test days, calibration bins, feature gains), for the screens; may be empty. */
    val report: JSONObject? = null
) {
    enum class Kind { REGRESSION, CLASSIFIER }

    class Tree(
        val feature: IntArray,
        val threshold: DoubleArray,
        val left: IntArray,
        val right: IntArray,
        val value: DoubleArray,
        /** Every node's mean output (leaves: the leaf value), or null when the file has none: then no [contributions]. */
        val nodeValue: DoubleArray? = null
    )

    /** Piecewise-linear curve through ascending knots, flat outside them (numpy's `interp`). */
    class Curve(val x: DoubleArray, val y: DoubleArray) {
        init {
            require(x.size == y.size && x.isNotEmpty()) { "curve needs matching, non-empty knots" }
            for (i in 1 until x.size) require(x[i] > x[i - 1]) { "curve knots must ascend" }
        }

        fun at(v: Double): Double {
            if (v.isNaN()) return y[0]
            if (v <= x[0]) return y[0]
            val n = x.size
            if (v >= x[n - 1]) return y[n - 1]
            var lo = 0
            var hi = n - 1
            while (hi - lo > 1) {
                val mid = (lo + hi) ushr 1
                if (x[mid] <= v) lo = mid else hi = mid
            }
            val t = (v - x[lo]) / (x[hi] - x[lo])
            return y[lo] + (y[hi] - y[lo]) * t
        }
    }

    val isClassifier: Boolean get() = kind == Kind.CLASSIFIER

    val treeCount: Int get() = trees.size

    /** The ONNX Runtime copy of these trees ([OnnxTrees]), or null to walk them in Kotlin. */
    @Volatile
    private var onnx: ((FloatArray) -> Double)? = null

    /** Which engine [predict] runs on now, for the screens: "ONNX Runtime (CPU)" or "Kotlin trees (…why)". */
    @Volatile
    var engine: String = "Kotlin trees"
        private set

    /**
     * Run [predict] on [run] (the ONNX model of these same trees) from now on, once it gives the Kotlin trees' answer
     * on [probes] rows built from every split ([probeRows]). Returns null when attached, else why not (the Kotlin trees
     * stay). If [run] ever throws later, [predict] goes back to the Kotlin trees for good and says so in [engine].
     */
    fun attachOnnx(run: (FloatArray) -> Double, label: String = "ONNX Runtime (CPU)", probes: List<FloatArray> = probeRows()): String? {
        var worst = 0.0
        for (x in probes) {
            val got = runCatching { run(x) }.getOrElse { e ->
                return fallBack("ONNX did not run: ${e.message ?: e.javaClass.simpleName}")
            }
            if (!got.isFinite()) return fallBack("ONNX gave a non-number")
            worst = maxOf(worst, kotlin.math.abs(got - marginTrees(x)))
        }
        if (worst > PARITY_TOLERANCE_CENTS) return fallBack("ONNX differs from the trees by ${"%.6f".format(java.util.Locale.US, worst)}¢")
        onnx = run
        engine = label
        return null
    }

    /** Stay on the Kotlin trees and say why in [engine] (the ONNX file could not be opened). */
    fun markKotlin(why: String) {
        fallBack(why)
    }

    private fun fallBack(why: String): String {
        onnx = null
        engine = "Kotlin trees ($why)"
        return why
    }

    /** Expected cents per contract for a front-of-queue scalp with these [x] features. */
    fun predict(x: FloatArray): Double = centsOfMargin(margin(x))

    /** Classifier: the calibrated chance (0..1) that the scalp makes money; null for a regression file. */
    fun probability(x: FloatArray): Double? = if (isClassifier) chanceOfMargin(margin(x)) else null

    /** The trees' sum on the engine in use (ONNX when attached): log-odds for a classifier, cents for a regression. */
    fun margin(x: FloatArray): Double {
        require(x.size == features.size) { "expected ${features.size} features, got ${x.size}" }
        val o = onnx
        if (o != null) {
            val v = runCatching { o(x) }.getOrNull()
            if (v != null && v.isFinite()) return v
            fallBack("ONNX stopped answering")
        }
        return marginTrees(x)
    }

    /** Calibrated chance for a trees' sum [m] (classifier). */
    fun chanceOfMargin(m: Double): Double {
        val raw = 1.0 / (1.0 + kotlin.math.exp(-m))
        return (calibration?.at(raw) ?: raw).coerceIn(0.0, 1.0)
    }

    /** Expected cents for a trees' sum [m]: through the chance for a classifier, [m] itself for a regression. */
    fun centsOfMargin(m: Double): Double {
        if (!isClassifier) return m
        val p = chanceOfMargin(m)
        return centsByChance?.at(p) ?: error("classifier file has no cents_by_chance curve")
    }

    /** [predict] on the Kotlin trees alone. */
    fun predictTrees(x: FloatArray): Double = centsOfMargin(marginTrees(x))

    /** True when [contributions] can split a prediction (the file has every node's value). */
    val explainable: Boolean get() = trees.all { it.nodeValue != null }

    /** The trees' sum before any feature: the sum of every root's value. */
    val bias: Double get() = trees.sumOf { it.nodeValue?.get(0) ?: 0.0 }

    /**
     * What each feature added to [marginTrees] for [x], by following the path through every tree and giving each
     * split's change in node value to its feature. [bias] plus every contribution equals [marginTrees] exactly.
     * Null when the file has no node values. Same units as the margin (log-odds for a classifier).
     */
    fun contributions(x: FloatArray): DoubleArray? {
        require(x.size == features.size) { "expected ${features.size} features, got ${x.size}" }
        if (!explainable) return null
        val out = DoubleArray(features.size)
        for (t in trees) {
            val nv = t.nodeValue!!
            var i = 0
            while (t.feature[i] >= 0) {
                val f = t.feature[i]
                val next = if (x[f].toDouble() <= t.threshold[i]) t.left[i] else t.right[i]
                out[f] += nv[next] - nv[i]
                i = next
            }
        }
        return out
    }

    /** The trees' sum on the Kotlin trees alone (what an ONNX copy must match). */
    fun marginTrees(x: FloatArray): Double {
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

    /**
     * Rows that sit on, just under and just over the trees' splits (and one with every feature missing), so a check
     * of another engine reaches every branch kind. Deterministic.
     */
    fun probeRows(count: Int = 64): List<FloatArray> {
        val n = features.size
        val byFeature = Array(n) { ArrayList<Double>() }
        for (t in trees) for (i in t.feature.indices) if (t.feature[i] >= 0) byFeature[t.feature[i]] += t.threshold[i]
        val rows = ArrayList<FloatArray>(count + 1)
        for (j in 0 until count) {
            rows += FloatArray(n) { k ->
                val ths = byFeature[k]
                if (ths.isEmpty()) return@FloatArray (j % 7 - 3).toFloat()
                val f = ths[(j * 31 + k * 17) % ths.size].toFloat()
                when ((j + k) % 3) {
                    0 -> f
                    1 -> Math.nextUp(f)
                    else -> Math.nextDown(f)
                }
            }
        }
        rows += FloatArray(n) { Float.NaN }
        return rows
    }

    companion object {
        const val ASSET_NAME = "scalper_model.json"
        const val ONNX_ASSET_NAME = "scalper_model.onnx"
        /** Largest difference, in cents, allowed between the ONNX model and the Kotlin trees (float32 leaf sums). */
        const val PARITY_TOLERANCE_CENTS = 1e-3

        fun parse(raw: String): ScalperModel {
            val o = JSONObject(raw)
            val names = strings(o.getJSONArray("features"))
            val unknown = names.filterNot { it in PrintGrid.CANDIDATES }
            require(unknown.isEmpty()) { "scalper model uses features the app cannot rebuild: $unknown" }
            require(names.toSet().size == names.size) { "scalper model repeats a feature" }
            val ts = o.getJSONArray("trees")
            val trees = (0 until ts.length()).map { i ->
                val t = ts.getJSONObject(i)
                Tree(
                    feature = ints(t.getJSONArray("f")),
                    threshold = doubles(t.getJSONArray("t")),
                    left = ints(t.getJSONArray("l")),
                    right = ints(t.getJSONArray("r")),
                    value = doubles(t.getJSONArray("v")),
                    nodeValue = t.optJSONArray("iv")?.let { doubles(it) }
                ).also { tr ->
                    val n = tr.feature.size
                    require(tr.threshold.size == n && tr.left.size == n && tr.right.size == n && tr.value.size == n) { "tree $i: arrays differ in length" }
                    require(tr.nodeValue == null || tr.nodeValue.size == n) { "tree $i: node values differ in length" }
                    for (k in 0 until n) if (tr.feature[k] >= 0) {
                        require(tr.feature[k] < names.size) { "tree $i: feature out of range" }
                        require(tr.left[k] in 0 until n && tr.right[k] in 0 until n) { "tree $i: child out of range" }
                    }
                }
            }
            require(trees.isNotEmpty()) { "scalper model has no trees" }
            val kind = if (o.optString("kind") == "classifier") Kind.CLASSIFIER else Kind.REGRESSION
            fun curve(key: String): Curve? = o.optJSONObject(key)?.let { c -> Curve(doubles(c.getJSONArray("x")), doubles(c.getJSONArray("y"))) }
            val calibration = curve("calibration")
            val cents = curve("cents_by_chance")
            if (kind == Kind.CLASSIFIER) require(cents != null) { "classifier file has no cents_by_chance curve" }
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
                testFrontOfQueueCents = o.optDouble("test_front_of_queue_cents").takeIf { it.isFinite() },
                kind = kind,
                calibration = calibration,
                centsByChance = cents,
                report = o.optJSONObject("report")
            )
        }

        private fun strings(a: JSONArray): List<String> = (0 until a.length()).map { a.getString(it) }
        private fun ints(a: JSONArray): IntArray = IntArray(a.length()) { a.getInt(it) }
        private fun doubles(a: JSONArray): DoubleArray = DoubleArray(a.length()) { a.getDouble(it) }
    }
}
