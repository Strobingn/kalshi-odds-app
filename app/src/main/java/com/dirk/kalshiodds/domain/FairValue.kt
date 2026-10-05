package com.dirk.kalshiodds.domain

/**
 * One YES percent (0–100) for the card edge, the ticket, and the prediction log.
 * A live score writes [MarketUiModel.fairValuePp]. With no score, the card's
 * digital fair is the fallback. A hidden AI percent is not used.
 */
object FairValue {
    fun yesPp(market: MarketUiModel): Double? {
        market.fairValuePp?.takeIf { it.isFinite() }?.let { return it }
        if (!market.showAiPercent) {
            return market.digitalFairPp?.takeIf { it.isFinite() }
        }
        return market.importedModelPp?.takeIf { it.isFinite() }
            ?: market.aiYesPercent?.takeIf { it.isFinite() }
            ?: market.digitalFairPp?.takeIf { it.isFinite() }
    }

    /** Model percent minus the same side's ask, in percentage points. */
    fun edgeVsAskPp(market: MarketUiModel, side: String): Double? {
        val yes = yesPp(market) ?: return null
        val ask = if (side.equals("NO", true)) market.noAsk else market.yesAsk
        val usable = ask?.takeIf { it.isFinite() } ?: return null
        val model = if (side.equals("NO", true)) 100.0 - yes else yes
        return model - usable * 100.0
    }
}
