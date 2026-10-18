package com.dirk.kalshiodds.data.api

import com.dirk.kalshiodds.data.dto.MarketsResponse

/**
 * 0.3.90 market discovery. Since ~Oct 18 2026 Kalshi's GET /markets?series_ticker=KX…15M&status=open returns an
 * EMPTY list even while a 15-minute window is trading (the market itself reports status "active"; status=active is
 * rejected as an invalid filter). GET /events?status=open still lists the live window, so when the status=open
 * market list is empty we resolve the open events and fetch their markets by event_ticker without a status filter,
 * keeping only active/open markets that have not closed yet.
 */
suspend fun KalshiApi.openMarkets(series: String, limit: Int? = null, cursor: String? = null, nowMs: Long = System.currentTimeMillis()): MarketsResponse {
    val direct = getMarkets(seriesTicker = series, status = "open", limit = limit, cursor = cursor)
    if (direct.markets.isNotEmpty() || cursor != null) return direct
    val events = runCatching { getEvents(series, "open", 10).events }.getOrDefault(emptyList())
    if (events.isEmpty()) return direct
    val markets = events.flatMap { ev ->
        runCatching { getMarkets(seriesTicker = series, status = null, limit = limit ?: 200, eventTicker = ev.eventTicker).markets }.getOrDefault(emptyList())
    }.filter { m -> OpenMarkets.isLive(m.status, m.closeTime, nowMs) }
    return MarketsResponse(cursor = null, markets = markets)
}

object OpenMarkets {
    private val LIVE = setOf("active", "open")
    fun isLive(status: String?, closeTimeIso: String?, nowMs: Long): Boolean {
        if (status != null && status.lowercase() !in LIVE) return false
        val close = closeTimeIso?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } ?: return true
        return close > nowMs
    }
}
