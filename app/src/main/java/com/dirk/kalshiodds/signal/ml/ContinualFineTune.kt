package com.dirk.kalshiodds.signal.ml

/**
 * Update the YES head (last layer) and the ensemble stack from the
 * settlement replay buffer. Adapter-style SGD; identity while cold.
 */
object ContinualFineTune {
    const val MIN_SAMPLES = 8
    const val LR = 0.05

    data class Result(
        val heads: HeadWeights,
        val stack: EnsembleStack.Weights,
        val applied: Int
    )

    fun update(
        heads: HeadWeights,
        stack: EnsembleStack.Weights,
        fresh: List<ReplaySample>,
        lr: Double = LR
    ): Result {
        if (fresh.isEmpty()) return Result(heads, stack, 0)
        val yesW = heads.yesW.copyOf()
        var yesB = heads.yesB
        var nextStack = stack
        var n = 0
        for (s in fresh) {
            val y = if (s.outcomeYes) 1.0 else 0.0
            if (s.backbone.size == yesW.size) {
                val h = s.backbone.toFloatArray()
                val z = MlMath.dot(h, yesW) + yesB
                val p = MlMath.sigmoid(z.toDouble()).coerceIn(1e-4, 1.0 - 1e-4)
                val err = (y - p).toFloat()
                for (i in yesW.indices) {
                    yesW[i] = (yesW[i] + (lr * err * h[i]).toFloat()).coerceIn(-3f, 3f)
                }
                yesB = (yesB + (lr * err).toFloat()).coerceIn(-2f, 2f)
            }
            val members = listOfNotNull(
                s.mlpYes?.let { EnsembleStack.Member("mlp", it) },
                s.cnnYes?.let { EnsembleStack.Member("cnn", it) },
                s.lstmYes?.let { EnsembleStack.Member("lstm", it) },
                s.gbmYes?.let { EnsembleStack.Member("gbm", it) }
            )
            if (members.isNotEmpty()) {
                nextStack = EnsembleStack.update(nextStack, members, s.outcomeYes, lr = 0.06)
            }
            n += 1
        }
        return Result(
            heads = heads.copy(yesW = yesW, yesB = yesB),
            stack = nextStack,
            applied = n
        )
    }
}
