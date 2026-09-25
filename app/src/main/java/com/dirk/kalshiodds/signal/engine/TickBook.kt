package com.dirk.kalshiodds.signal.engine

import com.dirk.kalshiodds.data.api.KalshiApi
import com.dirk.kalshiodds.domain.CryptoMarkets
import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.model.MarketTick
import com.dirk.kalshiodds.signal.model.TickSource
import kotlin.math.ln
import kotlin.math.tanh

/**
 * In-memory recent-tick + last-mid + local order-book store used for
 * volume-flow, tick velocity, related-crypto-series (BTC ↔ ETH ↔ SOL),
 * cross-asset lead–lag, aggressor flow, and bid/ask imbalance. Crypto only.
 */
class TickBook(private val maxPoints: Int = 80) {
    data class Point(
        val mid01: Double,
        val volume: Double?,
        val tradeSize: Double?,
        val takerSide: String?,
        val source: TickSource,
        val nowMs: Long
    )

    private val byTicker = linkedMapOf<String, ArrayDeque<Point>>()
    private val lastTickByTicker = linkedMapOf<String, MarketTick>()
    private val lastMidBySeries = linkedMapOf<String, Double>()
    private val closeByTicker = linkedMapOf<String, Long>()
    private val oiByTicker = linkedMapOf<String, Double>()
    private val volumeByTicker = linkedMapOf<String, Double>()
    private val books = linkedMapOf<String, LocalOrderBook>()
    private val strikeByTicker = linkedMapOf<String, Double>()
    private val seriesHistory = linkedMapOf<String, ArrayDeque<Pair<Long, Double>>>()
    private val maxSeriesPoints = 48
    private val bidsByTicker = linkedMapOf<String, ArrayDeque<BidSample>>()
    private val spotsByTicker = linkedMapOf<String, ArrayDeque<SpotSample>>()
    private val lastSpotByTicker = linkedMapOf<String, Double>()
    private val maxBidPoints = 240
    private val maxSpotPoints = 180

    data class BidSample(
        val tMs: Long,
        val yesBid: Double?,
        val noBid: Double?,
        val spotUsd: Double? = null
    )

    data class SpotSample(
        val tMs: Long,
        val spotUsd: Double
    )

    /**
     * Immutable book metrics copied under the TickBook lock. Scoring / Extended
     * AI must never iterate a live [LocalOrderBook] TreeMap — WS deltas mutate
     * it on the ingest thread and that was a ConcurrentModificationException.
     */
    data class BookView(
        val imbalance: Double? = null,
        val depthNear: Double? = null,
        val depthFar: Double? = null,
        val depthDecay: Double? = null,
        val pulse: LocalOrderBook.Pulse? = null
    )

    @Synchronized
    fun push(tick: MarketTick, nowMs: Long = System.currentTimeMillis()): Point? {
        if (!CryptoMarkets.isCryptoTicker(tick.ticker)) return last(tick.ticker)
        lastTickByTicker[tick.ticker] = tick
        tick.closeTimeEpochMs?.let { closeByTicker[tick.ticker] = it }
        tick.openInterest?.let { oiByTicker[tick.ticker] = it }
        tick.volume?.let { volumeByTicker[tick.ticker] = it }
        val mid = tick.mid01
        if (mid != null) {
            lastMidBySeries[tick.series] = mid
            pushSeriesMid(tick.series, nowMs, mid)
        }
        // Order-book-derived ticks refresh last mid / meta but do not pollute
        // the velocity / volume-flow series (those stay ticker/trade/REST).
        recordBid(tick, nowMs)
        if (tick.source == TickSource.WS_ORDERBOOK) return last(tick.ticker)
        if (mid == null) return last(tick.ticker)
        val q = byTicker.getOrPut(tick.ticker) { ArrayDeque() }
        val point = Point(
            mid01 = mid,
            volume = tick.volume,
            tradeSize = tick.tradeSize,
            takerSide = tick.takerSide,
            source = tick.source,
            nowMs = nowMs
        )
        q.addLast(point)
        while (q.size > maxPoints) q.removeFirst()
        return point
    }

    @Synchronized
    fun series(ticker: String): List<Point> = byTicker[ticker]?.toList().orEmpty()

    /** YES mid in percent, copied under the lock for sparklines. */
    @Synchronized
    fun midHistoryPp(ticker: String): List<Float> =
        byTicker[ticker]?.map { (it.mid01 * 100.0).toFloat() }.orEmpty()

    @Synchronized
    fun bidHistory(ticker: String): List<com.dirk.kalshiodds.chart.BidPoint> {
        val bids = bidsByTicker[ticker]?.toList().orEmpty()
        val spots = spotsByTicker[ticker]?.toList().orEmpty()
        if (bids.isEmpty() && spots.isEmpty()) return emptyList()
        if (bids.isEmpty()) {
            return spots.map {
                com.dirk.kalshiodds.chart.BidPoint(
                    tMs = it.tMs,
                    upBidCents = null,
                    downBidCents = null,
                    spotUsd = it.spotUsd
                )
            }
        }
        var si = 0
        return bids.map { b ->
            while (si + 1 < spots.size && spots[si + 1].tMs <= b.tMs) si++
            val spot = when {
                spots.isNotEmpty() && spots[si].tMs <= b.tMs -> spots[si].spotUsd
                else -> b.spotUsd ?: lastSpotByTicker[ticker]
            }
            com.dirk.kalshiodds.chart.BidPoint(
                tMs = b.tMs,
                upBidCents = b.yesBid?.times(100.0)?.toFloat(),
                downBidCents = b.noBid?.times(100.0)?.toFloat(),
                spotUsd = spot
            )
        }
    }

    /**
     * Last Coinbase/Binance print for [ticker]. Used so the hero spot
     * panel has a dashed TARGET line in real USD space.
     */
    @Synchronized
    fun noteSpot(ticker: String, spotUsd: Double?, nowMs: Long = System.currentTimeMillis()) {
        if (!CryptoMarkets.isCryptoTicker(ticker)) return
        val px = spotUsd?.takeIf { it.isFinite() && it > 0.0 } ?: return
        lastSpotByTicker[ticker] = px
        val q = spotsByTicker.getOrPut(ticker) { ArrayDeque() }
        val last = q.lastOrNull()
        if (last != null && nowMs - last.tMs < 400L && kotlin.math.abs(last.spotUsd - px) < 0.01) {
            return
        }
        q.addLast(SpotSample(nowMs, px))
        while (q.size > maxSpotPoints) q.removeFirst()
    }

    @Synchronized
    fun lastSpot(ticker: String): Double? = lastSpotByTicker[ticker]

    private fun recordBid(tick: MarketTick, nowMs: Long) {
        val (yesBid, yesAsk) = QuoteSanity.usablePair(tick.yesBid, tick.yesAsk)
        val noFromAsk = yesAsk?.let { (1.0 - it).coerceIn(0.0, 1.0) }
        val rawNo = tick.noBid
        val no = if (QuoteSanity.isPlaceholder(rawNo)) noFromAsk else rawNo
        val chartYes = yesBid ?: chartablePrice(tick.yesBid)
        val chartNo = no ?: chartablePrice(tick.noBid) ?: noFromAsk
        if (isEmptyPlaceholderBook(tick)) return
        if (chartYes == null && chartNo == null) return
        val q = bidsByTicker.getOrPut(tick.ticker) { ArrayDeque() }
        val last = q.lastOrNull()
        val spot = lastSpotByTicker[tick.ticker]
        if (last != null && nowMs - last.tMs < 400L &&
            last.yesBid == chartYes && last.noBid == chartNo
        ) {
            if (spot != null && last.spotUsd == null) {
                q.removeLast()
                q.addLast(last.copy(spotUsd = spot))
            }
            return
        }
        q.addLast(BidSample(nowMs, chartYes, chartNo, spot))
        while (q.size > maxBidPoints) q.removeFirst()
    }

    /** 0/1 complementary empty book — do not replace a good last print. */
    private fun isEmptyPlaceholderBook(tick: MarketTick): Boolean {
        val noRealYesBid = chartablePrice(tick.yesBid).let { it == null || it <= 0.0 }
        val noRealNoBid = chartablePrice(tick.noBid).let { it == null || it <= 0.0 }
        val asksArePlaceholders = QuoteSanity.isPlaceholder(tick.yesAsk) &&
            (tick.noAsk == null || QuoteSanity.isPlaceholder(tick.noAsk))
        return noRealYesBid && noRealNoBid && asksArePlaceholders
    }

    /** Inclusive 0–1 so a real 100¢ near-settlement bid can be charted. */
    private fun chartablePrice(price: Double?): Double? {
        if (price == null || !price.isFinite()) return null
        if (price < 0.0 || price > 1.0) return null
        return price
    }

    /**
     * Restore bid + spot prints after process death. Merges by timestamp
     * so live ticks already in the ring are not overwritten.
     */
    @Synchronized
    fun seedBids(ticker: String, points: List<com.dirk.kalshiodds.chart.BidPoint>) {
        if (!CryptoMarkets.isCryptoTicker(ticker) || points.isEmpty()) return
        val q = bidsByTicker.getOrPut(ticker) { ArrayDeque() }
        val existing = q.associateBy { it.tMs }.toMutableMap()
        for (p in points.sortedBy { it.tMs }) {
            val yes = p.upBidCents?.div(100.0)?.toDouble()
            val no = p.downBidCents?.div(100.0)?.toDouble()
            val spot = p.spotUsd?.takeIf { it.isFinite() && it > 0.0 }
            if (yes == null && no == null && spot == null) continue
            val prev = existing[p.tMs]
            val merged = BidSample(
                tMs = p.tMs,
                yesBid = yes ?: prev?.yesBid,
                noBid = no ?: prev?.noBid,
                spotUsd = spot ?: prev?.spotUsd
            )
            existing[p.tMs] = merged
            if (spot != null) lastSpotByTicker[ticker] = spot
        }
        q.clear()
        existing.values.sortedBy { it.tMs }.forEach { q.addLast(it) }
        while (q.size > maxBidPoints) q.removeFirst()
        val spots = spotsByTicker.getOrPut(ticker) { ArrayDeque() }
        val spotSeen = spots.map { it.tMs }.toHashSet()
        for (p in points) {
            val spot = p.spotUsd?.takeIf { it.isFinite() && it > 0.0 } ?: continue
            if (p.tMs in spotSeen) continue
            spots.addLast(SpotSample(p.tMs, spot))
            spotSeen.add(p.tMs)
        }
        while (spots.size > maxSpotPoints) spots.removeFirst()
    }

    /**
     * Seed sparkline history after process death. No-op once live ticks
     * already filled the ring so we never block or overwrite the scan path.
     */
    @Synchronized
    fun seedSeries(ticker: String, mids: List<Pair<Long, Double>>) {
        if (!CryptoMarkets.isCryptoTicker(ticker) || mids.isEmpty()) return
        val q = byTicker.getOrPut(ticker) { ArrayDeque() }
        if (q.size >= 8) return
        val existing = q.map { it.nowMs }.toHashSet()
        for ((ts, mid) in mids.sortedBy { it.first }) {
            if (!mid.isFinite() || mid <= 0.0 || mid >= 1.0) continue
            if (ts in existing) continue
            q.addLast(
                Point(
                    mid01 = mid,
                    volume = null,
                    tradeSize = null,
                    takerSide = null,
                    source = TickSource.REST,
                    nowMs = ts
                )
            )
            existing.add(ts)
            while (q.size > maxPoints) q.removeFirst()
        }
    }

    @Synchronized
    fun last(ticker: String): Point? = byTicker[ticker]?.lastOrNull()

    @Synchronized
    fun lastTick(ticker: String): MarketTick? = lastTickByTicker[ticker]

    @Synchronized
    fun lastMid(series: String): Double? = lastMidBySeries[series]

    /** Mean mid of other watched crypto series (never non-crypto). */
    @Synchronized
    fun relatedCryptoMid(series: String, watchedSeries: Set<String>): Double? {
        val others = lastMidBySeries.filter { (key, _) ->
            key != series && key in watchedSeries && CryptoMarkets.isCryptoTicker(key)
        }
        if (others.isEmpty()) return null
        return others.values.average()
    }

    @Synchronized
    fun closeTime(ticker: String): Long? = closeByTicker[ticker]

    @Synchronized
    fun openInterest(ticker: String): Double? = oiByTicker[ticker]

    @Synchronized
    fun volume(ticker: String): Double? = volumeByTicker[ticker]

    @Synchronized
    fun orderBook(ticker: String): LocalOrderBook? = books[ticker]

    @Synchronized
    fun rememberStrike(ticker: String, strike: Double?) {
        val px = strike?.takeIf { it.isFinite() && it > 0.0 } ?: return
        strikeByTicker[ticker] = px
    }

    @Synchronized
    fun strike(ticker: String): Double? = strikeByTicker[ticker]

    /** Snapshot imbalance / depth / pulse in one lock hold. */
    @Synchronized
    fun bookView(ticker: String): BookView {
        val book = books[ticker] ?: return BookView()
        return BookView(
            imbalance = book.imbalance(),
            depthNear = book.depthNearMid().takeIf { it > 0.0 },
            depthFar = book.depthNearMid(SignalConstants.DEPTH_FAR_CENTS),
            depthDecay = book.depthDecay(),
            pulse = book.pulse()
        )
    }

    /**
     * Immutable copy of the local book. UI / TicketBuilder must not iterate
     * the live TreeMap — WS deltas mutate it on the tick thread.
     */
    @Synchronized
    fun snapshotBook(ticker: String): BookLevelSnapshot? {
        val book = books[ticker] ?: return null
        val snap = book.snapshot()
        return if (snap.isEmpty()) null else snap
    }

    @Synchronized
    fun applySnapshot(
        ticker: String,
        yesLevels: List<Pair<Double, Double>>,
        noLevels: List<Pair<Double, Double>>,
        seq: Int? = null
    ): LocalOrderBook? {
        if (!CryptoMarkets.isCryptoTicker(ticker)) return null
        val book = books.getOrPut(ticker) { LocalOrderBook() }
        book.replaceSnapshot(yesLevels, noLevels, seq)
        book.mid01()?.let { mid ->
            val series = CryptoMarkets.inferSeries(ticker)
            lastMidBySeries[series] = mid
            pushSeriesMid(series, System.currentTimeMillis(), mid)
        }
        return book
    }

    /**
     * Incremental depth update. Returns null when the ticker is non-crypto or
     * a sequence gap was detected (book is cleared until the next snapshot).
     */
    @Synchronized
    fun applyDelta(
        ticker: String,
        price: Double,
        delta: Double,
        side: String,
        seq: Int? = null
    ): LocalOrderBook? {
        if (!CryptoMarkets.isCryptoTicker(ticker)) return null
        val book = books.getOrPut(ticker) { LocalOrderBook() }
        if (!book.applyDelta(price, delta, side, seq)) {
            book.clear()
            return null
        }
        book.mid01()?.let { mid ->
            val series = CryptoMarkets.inferSeries(ticker)
            lastMidBySeries[series] = mid
            pushSeriesMid(series, System.currentTimeMillis(), mid)
        }
        return book
    }

    @Synchronized
    fun imbalance(
        ticker: String,
        bandCents: Double = LocalOrderBook.DEFAULT_BAND_CENTS,
        topLevels: Int = LocalOrderBook.DEFAULT_TOP_LEVELS
    ): Double? = books[ticker]?.imbalance(bandCents, topLevels)

    @Synchronized
    fun depthNearMid(ticker: String, bandCents: Double = SignalConstants.DEPTH_NEAR_CENTS): Double? =
        books[ticker]?.depthNearMid(bandCents)?.takeIf { it > 0.0 }

    @Synchronized
    fun depthDecay(ticker: String): Double? = books[ticker]?.depthDecay()

    @Synchronized
    fun bookPulse(ticker: String): LocalOrderBook.Pulse? = books[ticker]?.pulse()

    /**
     * Signed aggressor imbalance from trade `taker_side` when the WS trade
     * feed provides it. 0 when no aggressor tags are present.
     */
    @Synchronized
    fun aggressorScore(ticker: String): Double {
        val pts = byTicker[ticker]?.toList().orEmpty().takeLast(16)
        var signed = 0.0
        var weight = 0.0
        for (p in pts) {
            val side = when (p.takerSide?.lowercase()) {
                "yes", "yes_taker", "buy_yes" -> 1.0
                "no", "no_taker", "buy_no" -> -1.0
                else -> continue
            }
            val w = 1.0 + ln(1.0 + (p.tradeSize ?: 1.0).coerceAtLeast(0.0))
            signed += side * w
            weight += w
        }
        if (weight < 1e-9) return 0.0
        return (signed / weight).coerceIn(-1.0, 1.0)
    }

    /**
     * Short-lag leading of BTC → ETH/SOL (and reverse).
     * Leader move over `[now − 3s, now − 0.4s]` minus follower move over
     * the last 0.4s, tanh-scaled to `[-1, 1]`. Null when histories are cold.
     */
    @Synchronized
    fun leadLagScore(targetSeries: String, nowMs: Long): Double? {
        val targetHist = seriesHistory[targetSeries]?.toList().orEmpty()
        if (targetHist.size < 2) return null
        val leaders = leadersFor(targetSeries)
        var weighted = 0.0
        var wsum = 0.0
        for (leader in leaders) {
            val hist = seriesHistory[leader]?.toList().orEmpty()
            if (hist.size < 2) continue
            val leadMove = deltaBetween(
                hist,
                nowMs - SignalConstants.LEAD_WINDOW_MS,
                nowMs - SignalConstants.LAG_OFFSET_MS
            ) ?: continue
            val followMove = deltaBetween(
                targetHist,
                nowMs - SignalConstants.LAG_OFFSET_MS,
                nowMs
            ) ?: 0.0
            val residual = leadMove - followMove
            val w = if (leader == KalshiApi.SERIES_BTC) 1.0 else 0.65
            weighted += residual * w
            wsum += w
        }
        if (wsum < 1e-9) return null
        return tanh((weighted / wsum) * 25.0).coerceIn(-1.0, 1.0)
    }

    @Synchronized
    fun flowScore(ticker: String): Double {
        val pts = byTicker[ticker]?.toList().orEmpty()
        if (pts.size < 2) return 0.0
        var signed = 0.0
        var weight = 0.0
        for (i in 1 until pts.size) {
            val prev = pts[i - 1]
            val cur = pts[i]
            val dMid = cur.mid01 - prev.mid01
            val size = cur.tradeSize ?: ((cur.volume ?: 0.0) - (prev.volume ?: 0.0)).coerceAtLeast(0.0)
            val side = when (cur.takerSide?.lowercase()) {
                "yes" -> 1.0
                "no" -> -1.0
                else -> if (dMid > 1e-6) 1.0 else if (dMid < -1e-6) -1.0 else 0.0
            }
            val w = 1.0 + kotlin.math.ln(1.0 + size.coerceAtLeast(0.0))
            signed += side * w
            weight += w
        }
        if (weight < 1e-9) return 0.0
        return (signed / weight).coerceIn(-1.0, 1.0)
    }

    @Synchronized
    fun momentumPp(ticker: String): Double {
        val pts = byTicker[ticker]?.toList().orEmpty()
        if (pts.size < 2) return 0.0
        return ((pts.last().mid01 - pts.first().mid01) * 100.0).coerceIn(-40.0, 40.0)
    }

    /**
     * Tick velocity: `Δmid / Δt` over the last [lookback] ticker/trade/REST
     * points (probability units per second). Null when the window is too short.
     */
    @Synchronized
    fun velocityPerSec(ticker: String, lookback: Int = VELOCITY_LOOKBACK): Double? {
        val window = velocityWindow(ticker, lookback) ?: return null
        return LocalOrderBook.velocityPerSec(window.first, window.second)
    }

    /**
     * Short acceleration: recent-half velocity minus older-half velocity.
     */
    @Synchronized
    fun accelerationPerSec(ticker: String, lookback: Int = VELOCITY_LOOKBACK): Double? {
        val window = velocityWindow(ticker, lookback) ?: return null
        return LocalOrderBook.accelerationPerSec(window.first, window.second)
    }

    private fun velocityWindow(ticker: String, lookback: Int): Pair<List<Double>, List<Long>>? {
        val pts = byTicker[ticker]?.toList().orEmpty()
        if (pts.size < 2) return null
        val take = lookback.coerceIn(2, pts.size)
        val slice = pts.takeLast(take)
        return slice.map { it.mid01 } to slice.map { it.nowMs }
    }

    @Synchronized
    fun pruneTo(keep: Set<String>) {
        if (keep.isEmpty()) return
        fun MutableMap<String, *>.drop() {
            keys.filter { it !in keep }.forEach { remove(it) }
        }
        byTicker.drop()
        lastTickByTicker.drop()
        closeByTicker.drop()
        oiByTicker.drop()
        volumeByTicker.drop()
        books.drop()
        strikeByTicker.drop()
        bidsByTicker.drop()
        spotsByTicker.drop()
        lastSpotByTicker.drop()
    }

    @Synchronized
    fun tickFromBook(
        ticker: String,
        receiveElapsedNanos: Long,
        nowMs: Long = System.currentTimeMillis()
    ): MarketTick? {
        if (!CryptoMarkets.isCryptoTicker(ticker)) return null
        val book = books[ticker] ?: return null
        val bid = book.bestYesBid()
        val ask = book.bestYesAsk()
        val mid = book.mid01() ?: return null
        val last = lastTickByTicker[ticker]
        return MarketTick(
            ticker = ticker,
            series = last?.series ?: CryptoMarkets.inferSeries(ticker),
            yesBid = bid,
            yesAsk = ask,
            lastPrice = last?.lastPrice ?: mid,
            volume = last?.volume ?: volumeByTicker[ticker],
            openInterest = last?.openInterest ?: oiByTicker[ticker],
            closeTimeEpochMs = last?.closeTimeEpochMs ?: closeByTicker[ticker],
            source = TickSource.WS_ORDERBOOK,
            receiveElapsedNanos = receiveElapsedNanos,
            exchangeTsMs = nowMs,
            noBid = last?.noBid ?: ask?.let { (1.0 - it).coerceIn(0.0, 1.0) },
            noAsk = last?.noAsk ?: bid?.let { (1.0 - it).coerceIn(0.0, 1.0) }
        )
    }

    private fun pushSeriesMid(series: String, nowMs: Long, mid: Double) {
        val q = seriesHistory.getOrPut(series) { ArrayDeque() }
        val last = q.lastOrNull()
        if (last != null && nowMs - last.first < 80 && kotlin.math.abs(last.second - mid) < 1e-6) return
        q.addLast(nowMs to mid)
        while (q.size > maxSeriesPoints) q.removeFirst()
    }

    companion object {
        /** Last N ticks used for velocity / acceleration (user: 10–20). */
        const val VELOCITY_LOOKBACK = 16

        fun leadersFor(targetSeries: String): List<String> {
            return when (targetSeries) {
                KalshiApi.SERIES_BTC -> listOf(KalshiApi.SERIES_ETH, KalshiApi.SERIES_SOL)
                else -> listOf(KalshiApi.SERIES_BTC)
            }
        }

        /**
         * Mid change between the last point at-or-before [fromMs] and the
         * last point at-or-before [toMs]. Null when the window is empty.
         */
        fun deltaBetween(hist: List<Pair<Long, Double>>, fromMs: Long, toMs: Long): Double? {
            if (hist.size < 2 || toMs <= fromMs) return null
            val a = hist.lastOrNull { it.first <= fromMs } ?: hist.firstOrNull { it.first >= fromMs } ?: return null
            val b = hist.lastOrNull { it.first <= toMs } ?: return null
            if (b.first <= a.first) return null
            return b.second - a.second
        }
    }
}
