package com.dirk.kalshiodds.signal.scalper.fleet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fleet's feed readers on responses saved from the real endpoints (2026-10-23). */
class FleetParsersTest {
    private fun res(name: String) = javaClass.classLoader!!.getResourceAsStream("fleet/$name")!!.bufferedReader().use { it.readText() }

    @Test
    fun kalshi() {
        val alt = FleetParsers.kalshiMarkets(res("kalshi_alt_markets.json")).single()
        assertTrue(alt.id.startsWith("KXETH15M-"))
        assertEquals(0.90, alt.yesAsk!!, 1e-9)
        assertEquals(0.11, alt.noAsk!!, 1e-9)
        assertEquals(2403.83, alt.strike!!, 1e-9)
        assertNotNull(alt.closeMs)
        val hourly = FleetParsers.kalshiMarkets(res("kalshi_hourly_markets.json"))
        assertEquals(6, hourly.size)
        // A $1.00 ask is nobody offering: no quote.
        assertNull(hourly.first().yesAsk)
        assertTrue(hourly.all { it.id.startsWith("KXBTCD-") && it.strike != null })
        assertNull(FleetParsers.kalshiResult("""{"market":{"result":""}}"""))
        assertEquals("yes", FleetParsers.kalshiResult("""{"market":{"result":"yes"}}"""))
    }

    @Test
    fun perp() {
        val p = FleetParsers.perpMarket(res("perp_market.json"), 1L)!!
        assertTrue(p.bid > 1.0 && p.ask >= p.bid)
        assertEquals(0.0001, p.btcPerContract, 1e-12)
        assertEquals(0.0012, p.takerFeeRate, 1e-12)
    }

    @Test
    fun polymarket() {
        val (up, down, rate) = FleetParsers.polyEvent(res("poly_event.json"))!!
        assertTrue(up.length > 20 && down.length > 20 && up != down)
        assertEquals(0.07, rate, 1e-12)
        assertNull(FleetParsers.polyResult(res("poly_event.json")))
        val closed = """[{"markets":[{"closed":true,"outcomes":"[\"Up\", \"Down\"]","outcomePrices":"[\"0\", \"1\"]"}]}]"""
        assertEquals("no", FleetParsers.polyResult(closed))
        assertEquals("yes", FleetParsers.polyResult(closed.replace("[\\\"0\\\", \\\"1\\\"]", "[\\\"1\\\", \\\"0\\\"]")))
        val (ask, size) = FleetParsers.polyBestAsk(res("poly_book.json"))!!
        assertTrue(ask > 0 && ask < 1 && size > 0)
    }

    @Test
    fun exchanges() {
        val cb = FleetParsers.coinbaseBook(res("coinbase_book.json"))
        val kr = FleetParsers.krakenBook(res("kraken_book.json"))
        val ge = FleetParsers.geminiBook(res("gemini_book.json"))
        for ((b, a) in listOf(cb, kr, ge)) {
            assertTrue(b.isNotEmpty() && a.isNotEmpty())
            assertTrue(b.first().first < a.first().first)
        }
        val nc = FleetMath.cfNowcast(listOf(cb, kr, ge))!!
        assertTrue(nc > cb.first.first().first * 0.99 && nc < cb.second.first().first * 1.01)
        val trades = FleetParsers.coinbaseTrades(res("coinbase_trades.json")) + FleetParsers.krakenTrades(res("kraken_trades.json"))
        assertEquals(40, trades.size)
        val newest = trades.maxOf { it.first } / 1000
        val vw = FleetParsers.vwapPerSecond(trades, newest, 120)
        assertTrue(vw.any { it.isFinite() })
        val candles = FleetParsers.coinbaseCandles(res("coinbase_candles.json"))
        assertEquals(40, candles.size)
        assertTrue(candles.zipWithNext().all { (a, b) -> b.first > a.first })
    }
}
