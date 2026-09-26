package com.dirk.kalshiodds.chart

import com.dirk.kalshiodds.signal.engine.QuoteSanity

/**
 * Home-card sparkline and bid-history series. Missing, null, or zero
 * samples are dropped so a gap cannot draw as a 0-price dip (the
 * square-wave sawtooth on 0.3.14). The sparkline is YES mid only —
 * never a mix of bid and ask, or YES and NO, on one line.
 */
object ChartSeriesBuilder {

    /** YES mid in percent (exclusive 0–100). Oldest → newest. */
    fun sparklineMidsPp(raw: Iterable<Float?>): List<Float> {
        val out = ArrayList<Float>()
        for (v in raw) {
            val p = v?.takeIf { it.isFinite() } ?: continue
            if (p <= 0f || p >= 100f) continue
            out.add(p)
        }
        return out
    }

    /**
     * Keep one YES-mid series. [bidCents] and [askCents] are never
     * interleaved; mid is used when both exist, otherwise the one
     * usable side. Zeros / placeholders are skipped.
     */
    fun yesMidCents(bidCents: Float?, askCents: Float?): Float? {
        val bid = QuoteSanity.usableCents(bidCents)
        val ask = QuoteSanity.usableCents(askCents)
        return when {
            bid != null && ask != null -> ((bid + ask) / 2f)
            bid != null -> bid
            ask != null -> ask
            else -> null
        }
    }

    /** Drop 0/null bids so BidChart can break the line at gaps. */
    fun cleanBidPoints(points: List<BidPoint>): List<BidPoint> =
        points.map { p ->
            p.copy(
                upBidCents = QuoteSanity.usableCents(p.upBidCents),
                downBidCents = QuoteSanity.usableCents(p.downBidCents)
            )
        }.filter { it.hasQuote() || it.hasSpot() }
}
