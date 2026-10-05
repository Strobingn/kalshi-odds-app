package com.dirk.kalshiodds.signal.model

/**
 * The on-device MLP and an imported edge model are shown only when they
 * actually move the blend. Weight 0, or a model that was not trained on BTC,
 * must not paint an "AI says" banner or an AI percent.
 */
object AiDisplay {
    fun visible(channelWeight: Double, importedWeight: Double?, trainedOnBtc: Boolean): Boolean {
        if (channelWeight.isFinite() && channelWeight > 1e-9) return true
        val imported = importedWeight?.takeIf { it.isFinite() } ?: return false
        return imported > 1e-9 && trainedOnBtc
    }
}
