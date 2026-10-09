package com.dirk.kalshiodds.signal.ml

/**
 * On-device tabular booster: a small forest of binary trees + sigmoid.
 * Pure Kotlin — no native GBM runtime. Trees are either the structured
 * default prior or a student export from [ml/train_heavy.py].
 */
data class GbmNode(
    val feature: Int = -1,
    val threshold: Float = 0f,
    val left: Int = -1,
    val right: Int = -1,
    val value: Float? = null
)

data class GbmTree(val nodes: List<GbmNode>) {
    fun predict(x: FloatArray): Float {
        if (nodes.isEmpty()) return 0f
        var i = 0
        var guard = 0
        while (guard++ < 64) {
            val n = nodes.getOrNull(i) ?: return 0f
            val leaf = n.value
            if (leaf != null) return leaf
            val feat = n.feature
            val v = if (feat in x.indices) x[feat] else 0f
            i = if (v <= n.threshold) n.left else n.right
            if (i < 0 || i >= nodes.size) return 0f
        }
        return 0f
    }
}

data class GbmModel(
    val trees: List<GbmTree>,
    val learningRate: Double = 0.35,
    val baseScore: Double = 0.0
)

object GbmBooster {
    const val FEATURES = 16

    val FEATURE_NAMES = listOf(
        "mid", "vol_norm", "tte", "volatility", "momentum", "mean_rev", "series_id", "oi_norm",
        "imbalance", "aggressor", "depth_q", "lead_lag", "spot", "cnn_p", "micro_z", "spread"
    )

    fun predictYes(x: FloatArray, model: GbmModel): Double {
        var z = model.baseScore
        for (tree in model.trees) {
            z += model.learningRate * tree.predict(x)
        }
        return MlMath.clip01(MlMath.sigmoid(z))
    }

    /**
     * Interpretable cold-start forest. Leaves are small so a single tree
     * cannot overpower the 0.2.x MLP when the stack is still identity.
     */
    fun defaultModel(): GbmModel = GbmModel(
        trees = listOf(
            // High mid → lean NO (mean revert)
            tree(
                GbmNode(feature = 0, threshold = 0.65f, left = 1, right = 2),
                GbmNode(value = 0.05f),
                GbmNode(value = -0.45f)
            ),
            // Low mid → lean YES
            tree(
                GbmNode(feature = 0, threshold = 0.35f, left = 1, right = 2),
                GbmNode(value = 0.45f),
                GbmNode(value = -0.05f)
            ),
            // Momentum
            tree(
                GbmNode(feature = 4, threshold = 0.015f, left = 1, right = 2),
                GbmNode(value = -0.12f),
                GbmNode(value = 0.28f)
            ),
            // Imbalance
            tree(
                GbmNode(feature = 8, threshold = 0.12f, left = 1, right = 2),
                GbmNode(value = -0.10f),
                GbmNode(value = 0.22f)
            ),
            // Aggressor
            tree(
                GbmNode(feature = 9, threshold = 0.15f, left = 1, right = 2),
                GbmNode(value = -0.08f),
                GbmNode(value = 0.18f)
            ),
            // Sequence net agrees YES
            tree(
                GbmNode(feature = 13, threshold = 0.58f, left = 1, right = 2),
                GbmNode(value = -0.06f),
                GbmNode(value = 0.30f)
            ),
            // Late TTE shrinks extreme mids (tte_frac < 0.22)
            tree(
                GbmNode(feature = 2, threshold = 0.22f, left = 1, right = 2),
                GbmNode(feature = 0, threshold = 0.50f, left = 3, right = 4),
                GbmNode(value = 0.00f),
                GbmNode(value = 0.10f),
                GbmNode(value = -0.10f)
            ),
            // Wide spread / thin conviction
            tree(
                GbmNode(feature = 15, threshold = 0.06f, left = 1, right = 2),
                GbmNode(value = 0.04f),
                GbmNode(value = -0.08f)
            )
        ),
        learningRate = 0.35,
        baseScore = 0.0
    )

    private fun tree(vararg nodes: GbmNode) = GbmTree(nodes.toList())

    fun tabular(
        mid: Double,
        volume: Double,
        tteFrac: Double,
        volatility: Double,
        momentum: Double,
        seriesId: Double,
        openInterest: Double,
        imbalance: Double,
        aggressor: Double,
        depthQuality: Double,
        leadLag: Double,
        spot: Double,
        cnnP: Double,
        microZ: Double,
        spread: Double
    ): FloatArray {
        val volNorm = kotlin.math.ln(1.0 + volume.coerceAtLeast(0.0)) / kotlin.math.ln(1.0 + 1_000_000.0)
        val oiNorm = kotlin.math.ln(1.0 + openInterest.coerceAtLeast(0.0)) / kotlin.math.ln(1.0 + 1_000_000.0)
        return floatArrayOf(
            mid.toFloat().coerceIn(0f, 1f),
            volNorm.toFloat(),
            tteFrac.toFloat().coerceIn(0f, 1f),
            volatility.toFloat(),
            momentum.toFloat(),
            (0.5 - mid).toFloat(),
            seriesId.toFloat(),
            oiNorm.toFloat(),
            imbalance.toFloat(),
            aggressor.toFloat(),
            depthQuality.toFloat(),
            leadLag.toFloat(),
            spot.toFloat(),
            cnnP.toFloat().coerceIn(0f, 1f),
            microZ.toFloat(),
            spread.toFloat().coerceIn(0f, 1f)
        )
    }
}
