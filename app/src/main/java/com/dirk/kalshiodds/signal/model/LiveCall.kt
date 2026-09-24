package com.dirk.kalshiodds.signal.model

import com.dirk.kalshiodds.signal.engine.ScoringEngine
import kotlin.math.abs

/**
 * Front-and-center UP / DOWN read, streamed from the live price feed.
 * YES on Kalshi 15m crypto contracts maps to UP; NO maps to DOWN.
 */
data class LiveCall(
    val ticker: String,
    val series: String,
    val upPct: Double,
    val downPct: Double,
    val direction: String,
    val marketYesPct: Double,
    val edgePp: Double,
    val confidence: Double? = null,
    val source: TickSource = TickSource.REST,
    val updatedAtMs: Long = System.currentTimeMillis(),
    val predictedSide: String = if (direction == UP) "YES" else "NO"
) {
    val convictionPp: Double get() = abs(upPct - 50.0)

    companion object {
        const val UP = "UP"
        const val DOWN = "DOWN"

        fun directionFromUp(upPct: Double): String = if (upPct >= 50.0) UP else DOWN

        fun fromScore(
            ticker: String,
            series: String,
            score: ScoringEngine.Score,
            source: TickSource,
            nowMs: Long = System.currentTimeMillis()
        ): LiveCall {
            val up = score.fairValuePp.coerceIn(1.0, 99.0)
            val down = (100.0 - up).coerceIn(1.0, 99.0)
            return LiveCall(
                ticker = ticker,
                series = series,
                upPct = up,
                downPct = down,
                direction = directionFromUp(up),
                marketYesPct = score.marketMidPp,
                edgePp = score.deltaPp,
                confidence = score.confidence,
                source = source,
                updatedAtMs = nowMs,
                predictedSide = score.predictedSide
            )
        }
    }
}

/** Last WS / REST quote so market % can move the instant the book ticks. */
data class LiveQuote(
    val ticker: String,
    val marketYesPct: Double,
    val yesBid: Double? = null,
    val yesAsk: Double? = null,
    val source: TickSource = TickSource.REST,
    val updatedAtMs: Long = System.currentTimeMillis()
) {
    companion object {
        fun fromTick(tick: MarketTick, nowMs: Long = System.currentTimeMillis()): LiveQuote? {
            val mid = tick.midPp ?: return null
            return LiveQuote(
                ticker = tick.ticker,
                marketYesPct = mid,
                yesBid = tick.yesBid,
                yesAsk = tick.yesAsk,
                source = tick.source,
                updatedAtMs = nowMs
            )
        }
    }
}
