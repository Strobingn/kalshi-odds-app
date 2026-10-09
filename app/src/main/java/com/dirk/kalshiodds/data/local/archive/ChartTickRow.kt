package com.dirk.kalshiodds.data.local.archive

import com.dirk.kalshiodds.chart.BidPoint
import com.dirk.kalshiodds.chart.dollarsToCents

/**
 * One persisted chart print for an open 15m market.
 * Dollars (0–1) for bids/asks; [tMs] is epoch millis.
 */
data class ChartTickRow(
    val ticker: String,
    val tMs: Long,
    val yesBid: Double? = null,
    val noBid: Double? = null,
    val yesAsk: Double? = null,
    val noAsk: Double? = null,
    val spotUsd: Double? = null,
    val source: String = SOURCE_LIVE
) {
    fun toBidPoint(): BidPoint = BidPoint(
        tMs = tMs,
        upBidCents = dollarsToCents(yesBid),
        downBidCents = dollarsToCents(noBid),
        spotUsd = spotUsd
    )

    companion object {
        const val SOURCE_LIVE = "live"
        const val SOURCE_REST = "rest"
        const val SOURCE_BACKFILL = "backfill"

        fun fromBidPoint(ticker: String, point: BidPoint, source: String = SOURCE_LIVE): ChartTickRow =
            ChartTickRow(
                ticker = ticker,
                tMs = point.tMs,
                yesBid = point.upBidCents?.div(100.0)?.toDouble(),
                noBid = point.downBidCents?.div(100.0)?.toDouble(),
                spotUsd = point.spotUsd,
                source = source
            )
    }
}
