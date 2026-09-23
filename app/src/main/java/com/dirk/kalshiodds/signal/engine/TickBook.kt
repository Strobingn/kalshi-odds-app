package com.dirk.kalshiodds.signal.engine

import com.dirk.kalshiodds.domain.CryptoMarkets
import com.dirk.kalshiodds.signal.model.MarketTick
import com.dirk.kalshiodds.signal.model.TickSource

/**
 * In-memory recent-tick + last-mid + local order-book store used for
 * volume-flow, tick velocity, related-crypto-series (BTC ↔ ETH ↔ SOL),
 * and bid/ask imbalance. Crypto only.
 */
class TickBook(private val maxPoints: Int = 32) {
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

    @Synchronized
    fun push(tick: MarketTick, nowMs: Long = System.currentTimeMillis()): Point? {
        if (!CryptoMarkets.isCryptoTicker(tick.ticker)) return last(tick.ticker)
        lastTickByTicker[tick.ticker] = tick
        tick.closeTimeEpochMs?.let { closeByTicker[tick.ticker] = it }
        tick.openInterest?.let { oiByTicker[tick.ticker] = it }
        tick.volume?.let { volumeByTicker[tick.ticker] = it }
        val mid = tick.mid01
        if (mid != null) lastMidBySeries[tick.series] = mid
        // Order-book-derived ticks refresh last mid / meta but do not pollute
        // the velocity / volume-flow series (those stay ticker/trade/REST).
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
            lastMidBySeries[CryptoMarkets.inferSeries(ticker)] = mid
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
            lastMidBySeries[CryptoMarkets.inferSeries(ticker)] = mid
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

    fun tickFromBook(
        ticker: String,
        receiveElapsedNanos: Long,
        nowMs: Long = System.currentTimeMillis()
    ): MarketTick? {
        if (!CryptoMarkets.isCryptoTicker(ticker)) return null
        val book = synchronized(this) { books[ticker] } ?: return null
        val bid = book.bestYesBid()
        val ask = book.bestYesAsk()
        val mid = book.mid01() ?: return null
        val last = synchronized(this) { lastTickByTicker[ticker] }
        return MarketTick(
            ticker = ticker,
            series = last?.series ?: CryptoMarkets.inferSeries(ticker),
            yesBid = bid,
            yesAsk = ask,
            lastPrice = last?.lastPrice ?: mid,
            volume = last?.volume ?: synchronized(this) { volumeByTicker[ticker] },
            openInterest = last?.openInterest ?: synchronized(this) { oiByTicker[ticker] },
            closeTimeEpochMs = last?.closeTimeEpochMs ?: synchronized(this) { closeByTicker[ticker] },
            source = TickSource.WS_ORDERBOOK,
            receiveElapsedNanos = receiveElapsedNanos,
            exchangeTsMs = nowMs
        )
    }

    companion object {
        /** Last N ticks used for velocity / acceleration (user: 10–20). */
        const val VELOCITY_LOOKBACK = 16
    }
}
