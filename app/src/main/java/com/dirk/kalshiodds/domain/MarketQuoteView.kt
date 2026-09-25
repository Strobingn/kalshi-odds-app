package com.dirk.kalshiodds.domain

/**
 * Single current quote used by the hero, bid/ask line, chart labels,
 * buy buttons, and payout multiple. Never mix AI mids into these fields.
 */
data class MarketQuoteView(
    val yesBid: Double?,
    val yesAsk: Double?,
    val noBid: Double?,
    val noAsk: Double?,
    val yesBidLabel: String,
    val yesAskLabel: String,
    val noBidLabel: String,
    val noAskLabel: String,
    val upButton: String,
    val downButton: String,
    val upHero: String,
    val downHero: String,
    val upMultiple: Double?,
    val downMultiple: Double?
) {
    val upHeader: String get() = "UP bid $yesBidLabel  ask $yesAskLabel"
    val downHeader: String get() = "DOWN bid $noBidLabel  ask $noAskLabel"
    val upChartLabel: String get() = "UP bid $yesBidLabel"
    val downChartLabel: String get() = "DOWN bid $noBidLabel"

    companion object {
        @JvmStatic
        fun of(market: MarketUiModel, feeRate: Double = 0.07): MarketQuoteView =
            of(market.yesBid, market.yesAsk, market.noBid, market.noAsk, feeRate)

        @JvmStatic
        fun of(
            yesBid: Double?,
            yesAsk: Double?,
            noBid: Double?,
            noAsk: Double?,
            feeRate: Double = 0.07
        ): MarketQuoteView {
            val yb = displayBid(yesBid)
            val ya = KalshiPrice.usable(yesAsk)
            val nb = displayBid(noBid)
            val na = KalshiPrice.usable(noAsk)
            return MarketQuoteView(
                yesBid = yb,
                yesAsk = ya,
                noBid = nb,
                noAsk = na,
                yesBidLabel = KalshiQuoteDisplay.formatBid(yb),
                yesAskLabel = KalshiQuoteDisplay.formatAsk(ya),
                noBidLabel = KalshiQuoteDisplay.formatBid(nb),
                noAskLabel = KalshiQuoteDisplay.formatAsk(na),
                upButton = KalshiQuoteDisplay.buttonLabel(true, ya, feeRate),
                downButton = KalshiQuoteDisplay.buttonLabel(false, na, feeRate),
                upHero = KalshiQuoteDisplay.formatAsk(ya),
                downHero = KalshiQuoteDisplay.formatAsk(na),
                upMultiple = KalshiQuoteDisplay.multiplier(ya, feeRate),
                downMultiple = KalshiQuoteDisplay.multiplier(na, feeRate)
            )
        }

        /** 100¢ is a real near-settlement bid; 0¢ is an empty book. */
        private fun displayBid(raw: Double?): Double? {
            if (raw == null || !raw.isFinite()) return null
            if (raw <= 0.0 + 1e-12) return null
            if (raw > 1.0 + 1e-12) return null
            return raw
        }
    }
}
