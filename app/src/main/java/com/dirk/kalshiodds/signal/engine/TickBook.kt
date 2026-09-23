package com.dirk.kalshiodds.signal.engine

import com.dirk.kalshiodds.domain.CryptoMarkets
import com.dirk.kalshiodds.signal.model.MarketTick
import com.dirk.kalshiodds.signal.model.TickSource

/**
 * In-memory recent-tick + last-mid book used for momentum / volume-flow
 * and the related-crypto-series (BTC ↔ ETH ↔ SOL) fair-value term.
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
    private val lastMidBySeries = linkedMapOf<String, Double>()
    private val closeByTicker = linkedMapOf<String, Long>()
    private val oiByTicker = linkedMapOf<String, Double>()
    private val volumeByTicker = linkedMapOf<String, Double>()

    @Synchronized
    fun push(tick: MarketTick, nowMs: Long = System.currentTimeMillis()): Point? {
        if (!CryptoMarkets.isCryptoTicker(tick.ticker)) return last(tick.ticker)
        val mid = tick.mid01 ?: return last(tick.ticker)
        tick.closeTimeEpochMs?.let { closeByTicker[tick.ticker] = it }
        tick.openInterest?.let { oiByTicker[tick.ticker] = it }
        tick.volume?.let { volumeByTicker[tick.ticker] = it }
        lastMidBySeries[tick.series] = mid
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
}
