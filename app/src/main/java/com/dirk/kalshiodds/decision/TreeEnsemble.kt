package com.dirk.kalshiodds.decision

import kotlin.math.exp

/**
 * Compact gradient-boosted forest in ONNX TreeEnsembleClassifier / TreeEnsembleRegressor shape.
 * The challenger is 100–300 trees, depth at most 5. [predict] only reads arrays.
 */
class TreeEnsemble private constructor(
    val inputShape: IntArray,
    val treeCount: Int,
    val maxDepth: Int,
    val logistic: Boolean,
    val base: Double,
    private val feature: IntArray,
    private val threshold: DoubleArray,
    private val left: IntArray,
    private val right: IntArray,
    private val leaf: DoubleArray,
    private val branch: ByteArray,
    private val root: IntArray,
    val specs: List<Spec>
) {
    val trainedBand: Boolean get() = treeCount in MIN_TREES..MAX_TREES && maxDepth <= MAX_DEPTH
    val features: Int get() = if (inputShape.size == 2) inputShape[1] else 0

    /** Raw leaf sum. Empty forest is 0. This is the net-payoff prediction when [logistic] is false. */
    fun predict(x: DoubleArray): Double = if (treeCount == 0) 0.0 else rawSum(x)

    /** Classifier probability. Empty forest is the neutral score 0.5. */
    fun probability(x: DoubleArray): Double {
        if (treeCount == 0) return 0.5
        val sum = rawSum(x)
        return if (logistic) 1.0 / (1.0 + exp(-sum.coerceIn(-20.0, 20.0))) else sum.coerceIn(0.0, 1.0)
    }

    private fun rawSum(x: DoubleArray): Double {
        var sum = base
        var t = 0
        while (t < treeCount) {
            var i = root[t]
            var guard = 0
            while (i >= 0 && left[i] >= 0 && guard++ <= MAX_DEPTH) {
                val f = feature[i]
                val v = if (f >= 0 && f < x.size) x[f] else 0.0
                val thr = threshold[i]
                val goLeft = when (branch[i]) {
                    LT -> v < thr
                    GTE -> v >= thr
                    GT -> v > thr
                    EQ -> v == thr
                    else -> v <= thr
                }
                i = if (goLeft) left[i] else right[i]
            }
            if (i >= 0 && i < leaf.size) sum += leaf[i]
            t++
        }
        return sum
    }

    data class Spec(
        val treeId: Int,
        val nodeId: Int,
        val feature: Int,
        val threshold: Double,
        val mode: String,
        val trueId: Int,
        val falseId: Int,
        val leaf: Double
    )

    companion object {
        const val MIN_TREES = 100
        const val MAX_TREES = 300
        const val MAX_DEPTH = 5
        const val TRAIN_TREES = 120
        const val TRAIN_DEPTH = 4
        private const val LT: Byte = 1
        private const val GTE: Byte = 2
        private const val GT: Byte = 3
        private const val EQ: Byte = 4

        val EMPTY = TreeEnsemble(
            inputShape = intArrayOf(1, ModelFilter.FEATURES),
            treeCount = 0,
            maxDepth = 0,
            logistic = true,
            base = 0.0,
            feature = IntArray(0),
            threshold = DoubleArray(0),
            left = IntArray(0),
            right = IntArray(0),
            leaf = DoubleArray(0),
            branch = ByteArray(0),
            root = IntArray(0),
            specs = emptyList()
        )

        fun build(shape: IntArray, specs: List<Spec>, logistic: Boolean, base: Double): TreeEnsemble {
            require(shape.size == 2 && shape[0] == 1 && shape[1] > 0) { "fixed shape [1, N] required" }
            require(specs.isNotEmpty()) { "no nodes" }
            val nFeat = shape[1]
            val byTree = specs.groupBy { it.treeId }
            require(byTree.size in 1..MAX_TREES) { "tree count ${byTree.size} outside 1..$MAX_TREES" }
            val feature = IntArray(specs.size)
            val threshold = DoubleArray(specs.size)
            val left = IntArray(specs.size) { -1 }
            val right = IntArray(specs.size) { -1 }
            val leaf = DoubleArray(specs.size)
            val branch = ByteArray(specs.size)
            val index = HashMap<Long, Int>(specs.size * 2)
            specs.forEachIndexed { i, s ->
                index[key(s.treeId, s.nodeId)] = i
                feature[i] = s.feature
                threshold[i] = s.threshold
                leaf[i] = s.leaf
                branch[i] = modeByte(s.mode)
            }
            val roots = IntArray(byTree.size)
            var maxDepth = 0
            var ti = 0
            for ((treeId, nodes) in byTree.toSortedMap()) {
                val children = HashSet<Int>()
                for (n in nodes) {
                    if (!n.mode.startsWith("BRANCH")) continue
                    children += n.trueId
                    children += n.falseId
                    require(n.feature in 0 until nFeat) { "feature ${n.feature} outside [1, $nFeat]" }
                }
                val rootNodes = nodes.filter { it.nodeId !in children }
                require(rootNodes.size == 1) { "tree $treeId needs one root" }
                val rootId = rootNodes[0].nodeId
                val depth = depthOf(nodes, rootId)
                require(depth <= MAX_DEPTH) { "depth $depth exceeds $MAX_DEPTH" }
                if (depth > maxDepth) maxDepth = depth
                roots[ti++] = index[key(treeId, rootId)] ?: error("missing root")
                for (n in nodes) {
                    val i = index[key(treeId, n.nodeId)] ?: continue
                    if (!n.mode.startsWith("BRANCH")) continue
                    left[i] = index[key(treeId, n.trueId)] ?: error("missing true node")
                    right[i] = index[key(treeId, n.falseId)] ?: error("missing false node")
                }
            }
            return TreeEnsemble(shape.copyOf(), byTree.size, maxDepth, logistic, base, feature, threshold, left, right, leaf, branch, roots, specs)
        }

        /**
         * On-device forest. [TRAIN_TREES] shallow trees, depth [TRAIN_DEPTH].
         * Rows are walked in order (no shuffle) so a retrain is repeatable.
         */
        fun fit(xs: List<DoubleArray>, ys: DoubleArray, trees: Int = TRAIN_TREES, depth: Int = TRAIN_DEPTH, lr: Double = 0.15, regression: Boolean = false): TreeEnsemble {
            require(trees in MIN_TREES..MAX_TREES) { "tree count $trees outside $MIN_TREES..$MAX_TREES" }
            require(depth in 1..MAX_DEPTH) { "depth $depth exceeds $MAX_DEPTH" }
            if (xs.size < 8 || ys.size != xs.size) return EMPTY
            val nFeat = xs[0].size
            val step = maxOf(1, xs.size / 96)
            val rows = ArrayList<Int>(96)
            var i = 0
            while (i < xs.size && rows.size < 96) {
                rows += i
                i += step
            }
            val n = rows.size
            val pred = DoubleArray(n)
            val all = IntArray(n) { it }
            val specs = ArrayList<Spec>(trees * 8)
            repeat(trees) { treeId ->
                val grad = DoubleArray(n)
                val hess = DoubleArray(n)
                for (r in 0 until n) {
                    if (regression) {
                        grad[r] = ys[rows[r]] - pred[r]
                        hess[r] = 1.0
                    } else {
                        val p = 1.0 / (1.0 + exp(-pred[r].coerceIn(-20.0, 20.0)))
                        grad[r] = ys[rows[r]] - p
                        hess[r] = p * (1.0 - p)
                    }
                }
                val treeSpecs = ArrayList<Spec>(8)
                val root = grow(treeId, all, 0, depth, xs, rows, grad, hess, lr, nFeat, treeSpecs)
                val by = HashMap<Int, Spec>(treeSpecs.size * 2)
                for (s in treeSpecs) by[s.nodeId] = s
                for (r in 0 until n) pred[r] += contrib(by, root, xs[rows[r]])
                specs += treeSpecs
            }
            return build(intArrayOf(1, nFeat), specs, logistic = !regression, base = 0.0)
        }

        private fun grow(
            treeId: Int,
            idxs: IntArray,
            depth: Int,
            maxDepth: Int,
            xs: List<DoubleArray>,
            rows: List<Int>,
            grad: DoubleArray,
            hess: DoubleArray,
            lr: Double,
            nFeat: Int,
            out: MutableList<Spec>
        ): Int {
            val id = out.size
            val leaf = leafValue(idxs, grad, hess, lr)
            val split = if (depth >= maxDepth || idxs.size < 8) null else bestSplit(idxs, xs, rows, grad, nFeat)
            if (split == null) {
                out += Spec(treeId, id, 0, 0.0, "LEAF", 0, 0, leaf)
                return id
            }
            val leftList = ArrayList<Int>()
            val rightList = ArrayList<Int>()
            for (ix in idxs) {
                val v = xs[rows[ix]][split.feature]
                if (v <= split.threshold) leftList += ix else rightList += ix
            }
            if (leftList.isEmpty() || rightList.isEmpty()) {
                out += Spec(treeId, id, 0, 0.0, "LEAF", 0, 0, leaf)
                return id
            }
            out += Spec(treeId, id, split.feature, split.threshold, "BRANCH_LEQ", -1, -1, 0.0)
            val trueId = grow(treeId, leftList.toIntArray(), depth + 1, maxDepth, xs, rows, grad, hess, lr, nFeat, out)
            val falseId = grow(treeId, rightList.toIntArray(), depth + 1, maxDepth, xs, rows, grad, hess, lr, nFeat, out)
            out[id] = Spec(treeId, id, split.feature, split.threshold, "BRANCH_LEQ", trueId, falseId, 0.0)
            return id
        }

        private class Split(val feature: Int, val threshold: Double)

        private fun bestSplit(idxs: IntArray, xs: List<DoubleArray>, rows: List<Int>, grad: DoubleArray, nFeat: Int): Split? {
            var bestGain = 1e-8
            var bestF = -1
            var bestT = 0.0
            val parent = sse(idxs, grad)
            val col = DoubleArray(idxs.size)
            for (f in 0 until nFeat) {
                for (i in idxs.indices) col[i] = xs[rows[idxs[i]]][f]
                java.util.Arrays.sort(col)
                val cuts = doubleArrayOf(col[idxs.size / 4], col[idxs.size / 2], col[(idxs.size * 3) / 4])
                var last = Double.NaN
                for (cut in cuts) {
                    if (cut == last) continue
                    last = cut
                    var sl = 0.0
                    var sl2 = 0.0
                    var nl = 0
                    var sr = 0.0
                    var sr2 = 0.0
                    var nr = 0
                    for (ix in idxs) {
                        val g = grad[ix]
                        if (xs[rows[ix]][f] <= cut) {
                            sl += g
                            sl2 += g * g
                            nl++
                        } else {
                            sr += g
                            sr2 += g * g
                            nr++
                        }
                    }
                    if (nl == 0 || nr == 0) continue
                    val gain = parent - sseParts(sl, sl2, nl) - sseParts(sr, sr2, nr)
                    if (gain > bestGain) {
                        bestGain = gain
                        bestF = f
                        bestT = cut
                    }
                }
            }
            return if (bestF < 0) null else Split(bestF, bestT)
        }

        private fun sse(idxs: IntArray, grad: DoubleArray): Double {
            var s = 0.0
            var s2 = 0.0
            for (ix in idxs) {
                val g = grad[ix]
                s += g
                s2 += g * g
            }
            return sseParts(s, s2, idxs.size)
        }

        private fun sseParts(s: Double, s2: Double, n: Int): Double {
            if (n <= 0) return 0.0
            return (s2 - (s * s) / n).coerceAtLeast(0.0)
        }

        private fun leafValue(idxs: IntArray, grad: DoubleArray, hess: DoubleArray, lr: Double): Double {
            var g = 0.0
            var h = 0.0
            for (ix in idxs) {
                g += grad[ix]
                h += hess[ix]
            }
            return g / (h + 1.0) * lr
        }

        private fun contrib(by: Map<Int, Spec>, root: Int, x: DoubleArray): Double {
            var n = by[root] ?: return 0.0
            var guard = 0
            while (n.mode.startsWith("BRANCH") && guard++ <= MAX_DEPTH) {
                val v = if (n.feature in x.indices) x[n.feature] else 0.0
                val next = if (v <= n.threshold) n.trueId else n.falseId
                n = by[next] ?: return 0.0
            }
            return n.leaf
        }

        private fun depthOf(nodes: List<Spec>, root: Int): Int {
            val by = nodes.associateBy { it.nodeId }
            fun walk(id: Int, depth: Int, seen: MutableSet<Int>): Int {
                if (depth > MAX_DEPTH) throw IllegalArgumentException("depth $depth exceeds $MAX_DEPTH")
                if (!seen.add(id)) throw IllegalArgumentException("cycle in tree")
                val n = by[id] ?: throw IllegalArgumentException("missing node $id")
                if (!n.mode.startsWith("BRANCH")) return depth
                val nextSeenL = HashSet(seen)
                val nextSeenR = HashSet(seen)
                return maxOf(walk(n.trueId, depth + 1, nextSeenL), walk(n.falseId, depth + 1, nextSeenR))
            }
            return walk(root, 0, HashSet())
        }

        private fun modeByte(mode: String): Byte = when (mode) {
            "BRANCH_LT" -> LT
            "BRANCH_GTE" -> GTE
            "BRANCH_GT" -> GT
            "BRANCH_EQ" -> EQ
            else -> 0
        }

        private fun key(tree: Int, node: Int) = (tree.toLong() shl 32) or (node.toLong() and 0xffffffffL)
    }
}
