package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.domain.MarketUiModel
import java.util.Locale

/**
 * Home-card copy for a model-vs-market+spot conflict.
 *
 * Detection stays in [com.dirk.kalshiodds.signal.engine.TapeConflict] —
 * this only maps existing [MarketUiModel] fields to a short warning.
 * Returns null on agreement or when the sides cannot be named.
 */
object DisagreementLabel {
    const val TITLE = "Model disagrees with market"

    data class Copy(val title: String, val detail: String)

    fun of(market: MarketUiModel): Copy? = of(
        tapeConflict = market.tapeConflict,
        modelLeanSide = market.modelLeanSide,
        primaryHeroSide = market.primaryHeroSide,
        predictedSide = market.predictedSide,
        modelYesPercent = market.importedModelPp ?: market.aiYesPercent
    )

    fun of(
        tapeConflict: Boolean,
        modelLeanSide: String? = null,
        primaryHeroSide: String? = null,
        predictedSide: String? = null,
        modelYesPercent: Double? = null
    ): Copy? {
        if (!tapeConflict) return null
        val modelSide = upDown(modelLeanSide) ?: upDown(predictedSide) ?: sideFromYesPercent(modelYesPercent)
        val marketSide = upDown(primaryHeroSide)
        if (modelSide == null || marketSide == null) return null
        if (modelSide == marketSide) return null
        val pct = percentOnSide(modelYesPercent, modelSide)
        val modelPart = if (pct != null) {
            String.format(Locale.US, "Model: %s %.0f%%", modelSide, pct)
        } else {
            "Model: $modelSide"
        }
        return Copy(
            title = TITLE,
            detail = "$modelPart  ·  Market + spot: $marketSide"
        )
    }

    private fun upDown(raw: String?): String? = when (raw?.trim()?.uppercase(Locale.US)) {
        "YES", "UP" -> "UP"
        "NO", "DOWN" -> "DOWN"
        else -> null
    }

    private fun sideFromYesPercent(yes: Double?): String? {
        val p = yes?.takeIf { it.isFinite() } ?: return null
        return if (p >= 50.0) "UP" else "DOWN"
    }

    private fun percentOnSide(yes: Double?, side: String): Double? {
        val p = yes?.takeIf { it.isFinite() } ?: return null
        return if (side == "UP") p else 100.0 - p
    }
}
