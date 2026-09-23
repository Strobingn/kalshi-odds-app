package com.dirk.kalshiodds.signal.model

import com.dirk.kalshiodds.domain.CryptoMarkets
import com.dirk.kalshiodds.domain.MarketUiModel

enum class TickSource {
    WS_TICKER,
    WS_TRADE,
    REST
}

/**
 * Normalized crypto-market quote used by the scoring engine.
 * [receiveElapsedNanos] is [android.os.SystemClock.elapsedRealtimeNanos] on device
 * (or [System.nanoTime] in JVM tests) so we can measure tick→notify latency.
 */
data class MarketTick(
    val ticker: String,
    val series: String,
    val yesBid: Double?,
    val yesAsk: Double?,
    val lastPrice: Double?,
    val volume: Double?,
    val openInterest: Double?,
    val closeTimeEpochMs: Long?,
    val source: TickSource,
    val receiveElapsedNanos: Long,
    val exchangeTsMs: Long? = null,
    val tradeSize: Double? = null,
    val takerSide: String? = null
) {
    /** YES mid in 0–1 probability, or last if book is one-sided. */
    val mid01: Double?
        get() = when {
            yesBid != null && yesAsk != null -> (yesBid + yesAsk) / 2.0
            lastPrice != null -> lastPrice
            else -> null
        }

    val midPp: Double? get() = mid01?.times(100.0)

    companion object {
        fun inferSeries(ticker: String): String = CryptoMarkets.inferSeries(ticker)

        fun fromUi(model: MarketUiModel, receiveElapsedNanos: Long, source: TickSource = TickSource.REST): MarketTick =
            MarketTick(
                ticker = model.ticker,
                series = inferSeries(model.ticker),
                yesBid = model.yesBid,
                yesAsk = model.yesAsk,
                lastPrice = model.lastPrice ?: model.yesProbabilityPercent?.div(100.0),
                volume = model.volume,
                openInterest = model.openInterest,
                closeTimeEpochMs = model.closeTimeEpochMs,
                source = source,
                receiveElapsedNanos = receiveElapsedNanos
            )
    }
}
