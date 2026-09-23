package com.dirk.kalshiodds.signal

import com.dirk.kalshiodds.signal.ws.KalshiWsMessages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KalshiWsMessagesTest {

    @Test
    fun parseTickerExample() {
        val raw = """
            {
              "type": "ticker",
              "sid": 11,
              "msg": {
                "market_id": "9b0f6b43-5b68-4f9f-9f02-9a2d1b8ac1a1",
                "market_ticker": "KXBTC15M-26SEP231600-00",
                "price_dollars": "0.4800",
                "yes_bid_dollars": "0.4500",
                "yes_ask_dollars": "0.5300",
                "volume_fp": "33896.00",
                "open_interest_fp": "20422.00",
                "ts_ms": 1669149841000
              }
            }
        """.trimIndent()
        val parsed = KalshiWsMessages.parse(raw, receiveElapsedNanos = 99L)
        val tick = (parsed as KalshiWsMessages.Parsed.Ticker).tick
        assertEquals("KXBTC15M-26SEP231600-00", tick.ticker)
        assertEquals("KXBTC15M", tick.series)
        assertEquals(0.45, tick.yesBid!!, 1e-6)
        assertEquals(0.53, tick.yesAsk!!, 1e-6)
        assertEquals(49.0, tick.midPp!!, 0.01)
        assertEquals(99L, tick.receiveElapsedNanos)
    }

    @Test
    fun parseTradeExample() {
        val raw = """
            {
              "type": "trade",
              "sid": 11,
              "seq": 2,
              "msg": {
                "trade_id": "d91bc706-ee49-470d-82d8-11418bda6fed",
                "market_ticker": "KXETH15M-26SEP231645-45",
                "yes_price_dollars": "0.3600",
                "no_price_dollars": "0.6400",
                "count_fp": "136.00",
                "taker_side": "no",
                "ts_ms": 1669149841000
              }
            }
        """.trimIndent()
        val parsed = KalshiWsMessages.parse(raw, 7L)
        val tick = (parsed as KalshiWsMessages.Parsed.Trade).tick
        assertEquals("KXETH15M-26SEP231645-45", tick.ticker)
        assertEquals(0.36, tick.lastPrice!!, 1e-6)
        assertEquals("no", tick.takerSide)
        assertEquals(136.0, tick.tradeSize!!, 1e-6)
    }

    @Test
    fun subscribeIncludesCryptoTickersOnlyWhenProvided() {
        val body = KalshiWsMessages.subscribe(
            3,
            listOf("ticker", "trade"),
            listOf("KXBTC15M-A", "KXETH15M-B")
        )
        assertTrue(body.contains("\"cmd\":\"subscribe\""))
        assertTrue(body.contains("ticker"))
        assertTrue(body.contains("KXBTC15M-A"))
        assertTrue(body.contains("KXETH15M-B"))
    }
}
