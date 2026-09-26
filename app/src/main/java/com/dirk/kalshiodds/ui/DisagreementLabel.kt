package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.domain.KalshiPrice
import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.signal.engine.QuoteSanity
import com.dirk.kalshiodds.signal.engine.TapeConflict
import java.util.Locale

/**
 * Home-card copy for a model-vs-market+spot conflict.
 *
 * The model's pick is the side with more than 50% model probability
 * (UP if P(YES) > 50, DOWN otherwise). [modelLeanSide] / [predictedSide]
 * are ignored when they contradict that probability — that was the
 * 0.3.14 "Model: UP 39%" false warning (model favored DOWN 61%).
 *
 * The market's pick is the mid-price side, using the same market+spot
 * rule as [TapeConflict.primaryFromMarket] when those fields are present.
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
        modelYesPercent = market.importedModelPp ?: market.aiYesPercent,
        marketYesPercent = market.yesProbabilityPercent,
        yesAsk = market.yesAsk,
        noAsk = market.noAsk,
        yesBid = market.yesBid,
        noBid = market.noBid,
        spotUsd = market.spotUsd,
        strikeUsd = market.floorStrike
    )

    @Suppress("UNUSED_PARAMETER")
    fun of(
        tapeConflict: Boolean,
        modelLeanSide: String? = null,
        primaryHeroSide: String? = null,
        predictedSide: String? = null,
        modelYesPercent: Double? = null,
        marketYesPercent: Double? = null,
        yesAsk: Double? = null,
        noAsk: Double? = null,
        yesBid: Double? = null,
        noBid: Double? = null,
        spotUsd: Double? = null,
        strikeUsd: Double? = null
    ): Copy? {
        val modelSide = modelFavoredSide(modelYesPercent)
            ?: upDown(modelLeanSide)
            ?: upDown(predictedSide)
        val marketSide = marketFavoredSide(
            primaryHeroSide = primaryHeroSide,
            marketYesPercent = marketYesPercent,
            yesAsk = yesAsk,
            noAsk = noAsk,
            yesBid = yesBid,
            noBid = noBid,
            spotUsd = spotUsd,
            strikeUsd = strikeUsd
        )
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

    /**
     * Side with more than 50% model probability. 50% is not a pick.
     * [SignalStance.leanLineSide] treats 50 as UP; the warning needs a
     * strict majority so a 39% UP print is DOWN 61%.
     */
    fun modelFavoredSide(yesPercent: Double?): String? {
        val p = yesPercent?.takeIf { it.isFinite() } ?: return null
        return when {
            p > 50.0 -> "UP"
            p < 50.0 -> "DOWN"
            else -> null
        }
    }

    fun marketFavoredSide(
        primaryHeroSide: String? = null,
        marketYesPercent: Double? = null,
        yesAsk: Double? = null,
        noAsk: Double? = null,
        yesBid: Double? = null,
        noBid: Double? = null,
        spotUsd: Double? = null,
        strikeUsd: Double? = null
    ): String? {
        val fromBook = midSide(yesBid, yesAsk, noBid, noAsk)
            ?: sideFromYesPercent(marketYesPercent)
        val fromPrimary = upDown(primaryHeroSide)
        val fromSpotMarket = if (spotUsd != null || strikeUsd != null || yesAsk != null || noAsk != null) {
            upDown(
                TapeConflict.primaryFromMarket(
                    yesAsk = yesAsk,
                    noAsk = noAsk,
                    spotUsd = spotUsd,
                    strikeUsd = strikeUsd,
                    fairYes = null,
                    previousPrimary = primaryHeroSide,
                    yesBid = yesBid,
                    noBid = noBid
                )
            )
        } else {
            null
        }
        return fromBook ?: fromSpotMarket ?: fromPrimary
    }

    private fun upDown(raw: String?): String? = when (raw?.trim()?.uppercase(Locale.US)) {
        "YES", "UP" -> "UP"
        "NO", "DOWN" -> "DOWN"
        else -> null
    }

    private fun sideFromYesPercent(yes: Double?): String? = modelFavoredSide(yes)

    private fun midSide(
        yesBid: Double?,
        yesAsk: Double?,
        noBid: Double?,
        noAsk: Double?
    ): String? {
        val mid = QuoteSanity.robustMid(yesBid, yesAsk)
            ?: noAsk?.let { KalshiPrice.usable(1.0 - it) }
            ?: noBid?.let { KalshiPrice.usable(1.0 - it) }
            ?: return null
        return modelFavoredSide(mid * 100.0)
    }

    private fun percentOnSide(yes: Double?, side: String): Double? {
        val p = yes?.takeIf { it.isFinite() } ?: return null
        return if (side == "UP") p else 100.0 - p
    }
}
