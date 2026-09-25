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
            listOf("ticker", "trade", "orderbook_delta"),
            listOf("KXBTC15M-A", "KXETH15M-B")
        )
        assertTrue(body.contains("\"cmd\":\"subscribe\""))
        assertTrue(body.contains("ticker"))
        assertTrue(body.contains("orderbook_delta"))
        assertTrue(body.contains("KXBTC15M-A"))
        assertTrue(body.contains("KXETH15M-B"))
    }

    @Test
    fun parseOrderbookSnapshotDollars() {
        val raw = """
            {
              "type": "orderbook_snapshot",
              "sid": 2,
              "seq": 2,
              "msg": {
                "market_ticker": "KXBTC15M-26SEP231600-00",
                "market_id": "9b0f6b43-5b68-4f9f-9f02-9a2d1b8ac1a1",
                "yes_dollars_fp": [["0.4800", "100.00"], ["0.4700", "50.00"]],
                "no_dollars_fp": [["0.5000", "80.00"]]
              }
            }
        """.trimIndent()
        val parsed = KalshiWsMessages.parse(raw, 5L) as KalshiWsMessages.Parsed.OrderbookSnapshot
        assertEquals("KXBTC15M-26SEP231600-00", parsed.ticker)
        assertEquals(2, parsed.seq)
        assertEquals(5L, parsed.receiveElapsedNanos)
        assertEquals(0.48, parsed.yesLevels[0].first, 1e-6)
        assertEquals(100.0, parsed.yesLevels[0].second, 1e-6)
        assertEquals(0.50, parsed.noLevels[0].first, 1e-6)
        assertEquals(80.0, parsed.noLevels[0].second, 1e-6)
    }

    @Test
    fun parseOrderbookDeltaDollars() {
        val raw = """
            {
              "type": "orderbook_delta",
              "sid": 2,
              "seq": 3,
              "msg": {
                "market_ticker": "KXETH15M-26SEP231645-45",
                "market_id": "9b0f6b43-5b68-4f9f-9f02-9a2d1b8ac1a1",
                "price_dollars": "0.9600",
                "delta_fp": "-54.00",
                "side": "yes",
                "ts_ms": 1669149841000
              }
            }
        """.trimIndent()
        val parsed = KalshiWsMessages.parse(raw, 8L) as KalshiWsMessages.Parsed.OrderbookDelta
        assertEquals("KXETH15M-26SEP231645-45", parsed.ticker)
        assertEquals(0.96, parsed.price, 1e-6)
        assertEquals(-54.0, parsed.delta, 1e-6)
        assertEquals("yes", parsed.side)
        assertEquals(3, parsed.seq)
    }

    @Test
    fun parseTickerSubPennyAndWholeCent() {
        val raw = """
            {
              "type": "ticker",
              "sid": 11,
              "msg": {
                "market_ticker": "KXETH15M-26SEP251615-15",
                "yes_bid_dollars": "0.0140",
                "yes_ask_dollars": "0.0150",
                "price_dollars": "0.2500",
                "ts_ms": 1669149841000
              }
            }
        """.trimIndent()
        val tick = (KalshiWsMessages.parse(raw, 1L) as KalshiWsMessages.Parsed.Ticker).tick
        assertEquals(0.014, tick.yesBid!!, 1e-12)
        assertEquals(0.015, tick.yesAsk!!, 1e-12)
        assertEquals(0.25, tick.lastPrice!!, 1e-12)
    }

    @Test
    fun parseOrderbookSubPennyLevel() {
        val raw = """
            {
              "type": "orderbook_snapshot",
              "sid": 2,
              "seq": 2,
              "msg": {
                "market_ticker": "KXSOL15M-26SEP251600-00",
                "yes_dollars_fp": [["0.0010", "40.00"], ["0.0990", "12.00"]],
                "no_dollars_fp": [["0.2500", "8.00"]]
              }
            }
        """.trimIndent()
        val parsed = KalshiWsMessages.parse(raw, 5L) as KalshiWsMessages.Parsed.OrderbookSnapshot
        assertEquals(0.001, parsed.yesLevels[0].first, 1e-12)
        assertEquals(0.099, parsed.yesLevels[1].first, 1e-12)
        assertEquals(0.25, parsed.noLevels[0].first, 1e-12)
    }

    @Test
    fun parseTickerLegacyIntegerOneIsOneCent() {
        val raw = """
            {
              "type": "ticker",
              "sid": 11,
              "msg": {
                "market_ticker": "KXBTC15M-26SEP251600-00",
                "yes_ask": "1",
                "yes_bid": "45",
                "ts_ms": 1669149841000
              }
            }
        """.trimIndent()
        val tick = (KalshiWsMessages.parse(raw, 1L) as KalshiWsMessages.Parsed.Ticker).tick
        assertEquals(0.01, tick.yesAsk!!, 1e-12)
        assertEquals(0.45, tick.yesBid!!, 1e-12)
    }

    @Test
    fun parseDocAccurateTickerHasOnlyDollarsFields() {
        // https://docs.kalshi.com/websockets/market-ticker — required fields
        // are price_dollars / yes_bid_dollars / yes_ask_dollars. No yes_ask.
        val raw = """
            {
              "type": "ticker",
              "sid": 11,
              "msg": {
                "market_id": "9b0f6b43-5b68-4f9f-9f02-9a2d1b8ac1a1",
                "market_ticker": "KXBTC15M-26SEP251200-00",
                "price_dollars": "0.4300",
                "yes_bid_dollars": "0.0140",
                "yes_ask_dollars": "0.0150",
                "volume_fp": "10.00",
                "open_interest_fp": "4.00",
                "dollar_volume": 1,
                "dollar_open_interest": 1,
                "yes_bid_size_fp": "20.00",
                "yes_ask_size_fp": "12.00",
                "last_trade_size_fp": "1.00",
                "ts": 1669149841,
                "ts_ms": 1669149841000,
                "time": "2022-11-22T20:44:01Z"
              }
            }
        """.trimIndent()
        val tick = (KalshiWsMessages.parse(raw, 1L) as KalshiWsMessages.Parsed.Ticker).tick
        println("WS ticker sub-penny parsed yesAsk=${tick.yesAsk} yesBid=${tick.yesBid} last=${tick.lastPrice}")
        assertEquals(0.015, tick.yesAsk!!, 1e-12)
        assertEquals(0.014, tick.yesBid!!, 1e-12)
        assertEquals(0.43, tick.lastPrice!!, 1e-12)
        val q = com.dirk.kalshiodds.domain.MarketQuoteView.of(
            yesBid = tick.yesBid,
            yesAsk = tick.yesAsk,
            noBid = null,
            noAsk = 0.25
        )
        assertEquals("1.5¢", q.upHero)
        assertEquals(333.0 / 5.34, q.upMultiple!!, 1e-9)
        assertTrue(q.upMultiple!! <= 1.0 / 0.015 + 1e-9)
    }

    @Test
    fun parseDocAccurateOrderbookSubPennyAndWholeCent() {
        // https://docs.kalshi.com/websockets/orderbook-updates — yes_dollars_fp
        // only; legacy integer yes/no arrays are absent.
        val raw = """
            {
              "type": "orderbook_snapshot",
              "sid": 2,
              "seq": 2,
              "msg": {
                "market_ticker": "KXSOL15M-26SEP251200-00",
                "market_id": "9b0f6b43-5b68-4f9f-9f02-9a2d1b8ac1a1",
                "yes_dollars_fp": [["0.0010", "40.00"], ["0.0990", "12.00"], ["0.2500", "8.00"]],
                "no_dollars_fp": [["0.5600", "20.00"]]
              }
            }
        """.trimIndent()
        val parsed = KalshiWsMessages.parse(raw, 5L) as KalshiWsMessages.Parsed.OrderbookSnapshot
        println("WS book levels yes=${parsed.yesLevels} no=${parsed.noLevels}")
        assertEquals(0.001, parsed.yesLevels[0].first, 1e-12)
        assertEquals(0.099, parsed.yesLevels[1].first, 1e-12)
        assertEquals(0.25, parsed.yesLevels[2].first, 1e-12)
        assertEquals(0.56, parsed.noLevels[0].first, 1e-12)
    }

    @Test
    fun parseTickerPrefersDollarsOverLegacyInteger() {
        val raw = """
            {
              "type": "ticker",
              "sid": 11,
              "msg": {
                "market_ticker": "KXETH15M-26SEP251200-00",
                "yes_ask_dollars": "0.0150",
                "yes_ask": "1",
                "yes_bid_dollars": "0.0140",
                "yes_bid": "1",
                "price_dollars": "0.2500"
              }
            }
        """.trimIndent()
        val tick = (KalshiWsMessages.parse(raw, 1L) as KalshiWsMessages.Parsed.Ticker).tick
        assertEquals(0.015, tick.yesAsk!!, 1e-12)
        assertEquals(0.014, tick.yesBid!!, 1e-12)
    }
}
