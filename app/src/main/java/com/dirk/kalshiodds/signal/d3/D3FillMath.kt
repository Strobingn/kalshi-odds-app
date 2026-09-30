package com.dirk.kalshiodds.signal.d3

/**
 * Honest paper-queue fill. A resting bid fills only when a later trade
 * prints **through** our price (strictly better for the taker) **or**
 * cumulative volume at our exact price exceeds the size that was ahead
 * of us when we placed.
 *
 * Conservative: a print *at* our bid does not fill us until the queue
 * ahead is consumed. Never invents a fill from the quote alone.
 */
object D3FillMath {

    fun yesPriceForSide(side: String, sidePrice: Double): Double =
        if (side.equals("NO", true)) 1.0 - sidePrice else sidePrice

    fun tradeTouchesSide(side: String, yesPrice: Double, bidPrice: Double): Boolean {
        val ourYes = yesPriceForSide(side, bidPrice)
        return if (side.equals("NO", true)) {
            // Buying NO = selling YES. A YES print above (1 − bid) is through.
            yesPrice + 1e-12 > ourYes
        } else {
            yesPrice - 1e-12 < ourYes
        }
    }

    fun tradeAtOurPrice(side: String, yesPrice: Double, bidPrice: Double): Boolean {
        val ourYes = yesPriceForSide(side, bidPrice)
        return kotlin.math.abs(yesPrice - ourYes) < 1e-9
    }

    fun filled(
        side: String,
        bidPrice: Double,
        sizeAhead: Double,
        trades: List<D3TradePrint>,
        placedAtMs: Long
    ): Boolean {
        var atPrice = 0.0
        for (t in trades) {
            if (t.createdAtMs < placedAtMs) continue
            if (t.count <= 0.0 || !t.yesPrice.isFinite()) continue
            if (tradeTouchesSide(side, t.yesPrice, bidPrice) && !tradeAtOurPrice(side, t.yesPrice, bidPrice)) {
                return true
            }
            if (tradeAtOurPrice(side, t.yesPrice, bidPrice)) {
                atPrice += t.count
                if (atPrice > sizeAhead + 1e-9) return true
            }
        }
        return false
    }

    fun volumeAtPrice(
        side: String,
        bidPrice: Double,
        trades: List<D3TradePrint>,
        placedAtMs: Long
    ): Double {
        var atPrice = 0.0
        for (t in trades) {
            if (t.createdAtMs < placedAtMs) continue
            if (tradeAtOurPrice(side, t.yesPrice, bidPrice)) atPrice += t.count
        }
        return atPrice
    }
}
