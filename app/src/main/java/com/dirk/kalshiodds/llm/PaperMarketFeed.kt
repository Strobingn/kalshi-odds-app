package com.dirk.kalshiodds.llm

import java.util.Locale

/** Bounded device-local samples, never a claim that the model itself receives every tick. */
class PaperMarketFeed {
    data class Sample(
        val ticker: String, val observedAtMs: Long, val closeAtMs: Long,
        val yesBid: Double?, val yesAsk: Double?, val noBid: Double?, val noAsk: Double?, val spot: Double?, val target: Double?,
        val fairPercent: Double?, val quoteAgeMs: Long?, val openPaper: Int, val realizedPaper: Double
    )

    private val samples = ArrayDeque<Sample>()

    @Synchronized fun record(sample: Sample) {
        if (samples.lastOrNull()?.ticker != sample.ticker) samples.clear()
        if (samples.lastOrNull()?.observedAtMs?.let { sample.observedAtMs <= it } == true) return
        samples.addLast(sample)
        while (samples.size > 900 || samples.first().observedAtMs < sample.observedAtMs - 15 * 60_000L) samples.removeFirst()
    }

    @Synchronized fun clear() = samples.clear()

    @Synchronized fun chartSamples(): List<Sample> = samples.toList()
        .groupBy { it.observedAtMs / 15_000L }.values.map { it.last() }

    @Synchronized fun render(): String {
        if (samples.isEmpty()) return "Market feed unavailable: waiting for an open BTC 15-minute window."
        val rows = samples.toList()
        val last = rows.last()
        fun n(value: Double?): String = value?.takeIf { it.isFinite() }?.let { String.format(Locale.US, "%.3f", it) } ?: "?"
        return buildString {
            appendLine("DEVICE MARKET FEED: ${last.ticker}; ${rows.size} one-second observations; observed ${rows.first().observedAtMs}..${last.observedAtMs} UTC ms; close=${last.closeAtMs}.")
            appendLine("Each row is the last observation in a 15-second bucket, not necessarily a fresh exchange tick. age is milliseconds since last Kalshi tick, ? if unavailable. Spot freshness is unknown.")
            appendLine("time_ms,yes_bid,yes_ask,no_bid,no_ask,spot_usd,target_usd,model_yes_percent,quote_age_ms,paper_open,paper_realized_usd,yes_bid_min,yes_bid_max,spot_min,spot_max")
            rows.groupBy { it.observedAtMs / 15_000L }.values.forEach { bucket ->
                val s = bucket.last()
                val bids = bucket.mapNotNull { it.yesBid?.takeIf(Double::isFinite) }
                val spots = bucket.mapNotNull { it.spot?.takeIf(Double::isFinite) }
                appendLine("${s.observedAtMs},${n(s.yesBid)},${n(s.yesAsk)},${n(s.noBid)},${n(s.noAsk)},${n(s.spot)},${n(s.target)},${n(s.fairPercent)},${s.quoteAgeMs ?: "?"},${s.openPaper},${n(s.realizedPaper)},${n(bids.minOrNull())},${n(bids.maxOrNull())},${n(spots.minOrNull())},${n(spots.maxOrNull())}")
            }
        }
    }
}
