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
    val downMultiple: Double?,
    val upMultipleLabel: String,
    val downMultipleLabel: String
) {
    val upHeader: String get() = "UP bid $yesBidLabel  ask $yesAskLabel"
    val downHeader: String get() = "DOWN bid $noBidLabel  ask $noAskLabel"
    val upChartLabel: String get() = "UP bid $yesBidLabel"
    val downChartLabel: String get() = "DOWN bid $noBidLabel"

    companion object {
        @JvmStatic
        fun of(
            market: MarketUiModel,
            feeRate: Double = 0.07,
            stakeUsd: Double = 5.0
        ): MarketQuoteView =
            of(market.yesBid, market.yesAsk, market.noBid, market.noAsk, feeRate, stakeUsd)

        @JvmStatic
        fun of(
            yesBid: Double?,
            yesAsk: Double?,
            noBid: Double?,
            noAsk: Double?,
            feeRate: Double = 0.07,
            stakeUsd: Double = 5.0
        ): MarketQuoteView {
            val snap = ConsistentQuote.fromSameUpdate(yesBid, yesAsk, noBid, noAsk)
                ?: ConsistentQuote.Snap(yesBid, yesAsk, noBid, noAsk)
            val yb = displayBid(snap.yesBid)
            val ya = KalshiPrice.usable(snap.yesAsk)
            val nb = displayBid(snap.noBid)
            val na = KalshiPrice.usable(snap.noAsk)
            return MarketQuoteView(
                yesBid = yb,
                yesAsk = ya,
                noBid = nb,
                noAsk = na,
                yesBidLabel = KalshiQuoteDisplay.formatBid(yb),
                yesAskLabel = KalshiQuoteDisplay.formatAsk(ya),
                noBidLabel = KalshiQuoteDisplay.formatBid(nb),
                noAskLabel = KalshiQuoteDisplay.formatAsk(na),
                upButton = KalshiQuoteDisplay.buttonLabel(true, ya, feeRate, stakeUsd),
                downButton = KalshiQuoteDisplay.buttonLabel(false, na, feeRate, stakeUsd),
                upHero = KalshiQuoteDisplay.formatAsk(ya),
                downHero = KalshiQuoteDisplay.formatAsk(na),
                upMultiple = KalshiQuoteDisplay.multiplier(ya, feeRate, stakeUsd),
                downMultiple = KalshiQuoteDisplay.multiplier(na, feeRate, stakeUsd),
                upMultipleLabel = KalshiQuoteDisplay.multipleLabel(ya, feeRate, stakeUsd),
                downMultipleLabel = KalshiQuoteDisplay.multipleLabel(na, feeRate, stakeUsd)
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
