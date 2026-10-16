package com.dirk.kalshiodds.signal.trade

import com.dirk.kalshiodds.domain.MarketUiModel

/** A Bitcoin 15-minute market with only the fields a test cares about; the rest are neutral. */
internal fun testMarket(
    ticker: String,
    @Suppress("UNUSED_PARAMETER") series: String = "KXBTC15M",
    yesAsk: Double? = null,
    noAsk: Double? = null,
    yesBid: Double? = null,
    noBid: Double? = null,
    closeTimeEpochMs: Long? = null
) = MarketUiModel(
    ticker = ticker,
    title = "BTC",
    subtitle = null,
    floorStrike = null,
    yesBid = yesBid,
    yesAsk = yesAsk,
    noBid = noBid,
    noAsk = noAsk,
    lastPrice = yesAsk,
    yesProbabilityPercent = yesAsk?.times(100.0),
    noProbabilityPercent = noAsk?.times(100.0),
    volume = null,
    volume24h = null,
    closeTimeLocal = null,
    closeTimeEpochMs = closeTimeEpochMs,
    status = "active",
    seriesLabel = "Bitcoin"
)
