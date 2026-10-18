package com.dirk.kalshiodds.data.api

import com.dirk.kalshiodds.data.dto.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

/** 1.0.5: Kalshi returns [] for markets?status=open on 15-min series; discovery falls back to open events. */
class OpenMarketsTest {
    private fun api(direct: List<MarketDto>, byEvent: List<MarketDto>, events: List<String>, seen: MutableList<String>): KalshiApi =
        Proxy.newProxyInstance(KalshiApi::class.java.classLoader, arrayOf(KalshiApi::class.java)) { _, m, a ->
            when (m.name) {
                "getMarkets" -> { val status = a[1] as String?; val ev = a[5] as String?; seen += "markets status=$status event=$ev"
                    if (ev != null) MarketsResponse(markets = byEvent) else MarketsResponse(markets = if (status == "open") direct else emptyList()) }
                "getEvents" -> { seen += "events"; EventsResponse(events = events.map { EventDto(it, "KXBTC15M") }) }
                else -> error(m.name)
            }
        } as KalshiApi

    private fun mk(t: String, status: String, close: String) = MarketDto(ticker = t, status = status, closeTime = close)

    @Test fun fallsBackToOpenEventsWhenStatusOpenIsEmpty() = runBlocking {
        val seen = mutableListOf<String>()
        val now = java.time.Instant.parse("2026-10-18T17:50:00Z").toEpochMilli()
        val r = api(emptyList(), listOf(mk("KXBTC15M-26OCT181400-00", "active", "2026-10-18T18:00:00Z"), mk("OLD", "closed", "2026-10-18T17:45:00Z")),
            listOf("KXBTC15M-26OCT181400"), seen).openMarkets("KXBTC15M", 20, nowMs = now)
        assertEquals(listOf("KXBTC15M-26OCT181400-00"), r.markets.map { it.ticker })
        assertTrue(seen.contains("markets status=null event=KXBTC15M-26OCT181400"))
    }

    @Test fun usesDirectListWhenKalshiReturnsIt() = runBlocking {
        val seen = mutableListOf<String>()
        val r = api(listOf(mk("A", "active", "2099-01-01T00:00:00Z")), emptyList(), listOf("E"), seen).openMarkets("KXBTC15M")
        assertEquals(listOf("A"), r.markets.map { it.ticker }); assertFalse(seen.contains("events"))
    }
}
