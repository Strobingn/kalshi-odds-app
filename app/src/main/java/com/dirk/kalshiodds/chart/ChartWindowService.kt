package com.dirk.kalshiodds.chart

import com.dirk.kalshiodds.data.backfill.LiveWindowBackfill
import com.dirk.kalshiodds.data.local.archive.ChartTickRow
import com.dirk.kalshiodds.data.local.archive.DataArchive
import com.dirk.kalshiodds.domain.CryptoMarkets
import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.signal.engine.TickBook

/**
 * Persist / restore / backfill the current 15-minute chart window.
 * Bounded: one window per ticker, [ChartSeriesMerge.MAX_WINDOW_POINTS] prints.
 */
class ChartWindowService(
    private val archive: DataArchive,
    private val book: TickBook,
    private val backfill: LiveWindowBackfill? = null,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    private val backfilled = HashSet<String>()

    fun persist(
        ticker: String,
        tMs: Long,
        yesBid: Double?,
        noBid: Double?,
        yesAsk: Double? = null,
        noAsk: Double? = null,
        spotUsd: Double? = null,
        source: String = ChartTickRow.SOURCE_LIVE
    ) {
        if (!CryptoMarkets.isCryptoTicker(ticker)) return
        if (yesBid == null && noBid == null && spotUsd == null) return
        archive.insertChartTicks(
            listOf(
                ChartTickRow(
                    ticker = ticker,
                    tMs = tMs,
                    yesBid = yesBid,
                    noBid = noBid,
                    yesAsk = yesAsk,
                    noAsk = noAsk,
                    spotUsd = spotUsd,
                    source = source
                )
            )
        )
    }

    fun persistPoints(ticker: String, points: List<BidPoint>, source: String = ChartTickRow.SOURCE_LIVE) {
        if (!CryptoMarkets.isCryptoTicker(ticker) || points.isEmpty()) return
        archive.insertChartTicks(points.map { ChartTickRow.fromBidPoint(ticker, it, source) })
    }

    fun restoreWindow(ticker: String, windowStartMs: Long, windowEndMs: Long): List<BidPoint> {
        if (!CryptoMarkets.isCryptoTicker(ticker)) return emptyList()
        val stored = archive.chartTicks(ticker, windowStartMs, windowEndMs, ChartSeriesMerge.MAX_WINDOW_POINTS)
            .map { it.toBidPoint() }
        book.seedBids(ticker, stored)
        val live = book.bidHistory(ticker)
        return ChartSeriesMerge.inWindow(
            ChartSeriesMerge.merge(stored, live),
            windowStartMs,
            windowEndMs
        )
    }

    fun seriesForCard(
        market: MarketUiModel,
        nowMs: Long = this.nowMs()
    ): List<BidPoint> {
        val end = market.closeTimeEpochMs ?: nowMs
        val start = end - WINDOW_MS
        val stored = archive.chartTicks(market.ticker, start, end, ChartSeriesMerge.MAX_WINDOW_POINTS)
            .map { it.toBidPoint() }
        val live = runCatching { book.bidHistory(market.ticker) }.getOrElse { emptyList() }
        val merged = ChartSeriesMerge.merge(stored, live)
        val headed = ChartSeriesMerge.withLiveHead(
            merged,
            nowMs.coerceAtMost(end),
            market.yesBid,
            market.noBid,
            market.spotUsd
        )
        return ChartDownsampler.downsample(
            ChartSeriesMerge.inWindow(headed, start, end),
            ChartDownsampler.CARD_POINTS
        )
    }

    fun needsBackfill(ticker: String, windowStartMs: Long, windowEndMs: Long): Boolean {
        if (ticker in backfilled) return false
        val pts = restoreWindow(ticker, windowStartMs, windowEndMs)
        return !ChartSeriesMerge.coversWindow(pts, windowStartMs, windowEndMs)
    }

    fun backfillWindow(
        ticker: String,
        series: String,
        windowStartMs: Long,
        windowEndMs: Long
    ): List<BidPoint> {
        val engine = backfill ?: return restoreWindow(ticker, windowStartMs, windowEndMs)
        val candles = runCatching {
            engine.candles(series, ticker, windowStartMs, windowEndMs)
        }.getOrElse { emptyList() }
        val spots = runCatching {
            engine.spotPath(series, windowStartMs, windowEndMs)
        }.getOrElse { emptyList() }
        if (candles.isNotEmpty()) {
            persistPoints(ticker, candles, ChartTickRow.SOURCE_BACKFILL)
        }
        if (spots.isNotEmpty()) {
            val aligned = alignSpots(candles.ifEmpty { restoreWindow(ticker, windowStartMs, windowEndMs) }, spots)
            persistPoints(ticker, aligned, ChartTickRow.SOURCE_BACKFILL)
            book.seedBids(ticker, aligned)
        } else {
            book.seedBids(ticker, candles)
        }
        backfilled.add(ticker)
        archive.trimChartTicks(setOf(ticker), windowStartMs - RETAIN_MS)
        return restoreWindow(ticker, windowStartMs, windowEndMs)
    }

    fun trimActive(tickers: Set<String>, windowStartMs: Long) {
        archive.trimChartTicks(tickers, windowStartMs - RETAIN_MS)
    }

    companion object {
        const val WINDOW_MS = 900_000L
        const val RETAIN_MS = 3_600_000L

        fun alignSpots(points: List<BidPoint>, spots: List<Pair<Long, Double>>): List<BidPoint> {
            if (points.isEmpty()) {
                return spots.map { (t, px) -> BidPoint(tMs = t, upBidCents = null, downBidCents = null, spotUsd = px) }
            }
            if (spots.isEmpty()) return points
            var si = 0
            return points.map { p ->
                while (si + 1 < spots.size && spots[si + 1].first <= p.tMs) si++
                val spot = if (spots[si].first <= p.tMs) spots[si].second else p.spotUsd
                if (spot == p.spotUsd) p else p.copy(spotUsd = spot)
            }
        }
    }
}
