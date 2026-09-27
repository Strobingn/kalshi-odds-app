package com.dirk.kalshiodds.signal.external

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoinbaseTickerMessagesTest {

    private val ticker = """
        {"type":"ticker","sequence":37475248783,"product_id":"BTC-USD","price":"65012.34",
         "open_24h":"64000.00","volume_24h":"12345.6","low_24h":"63000","high_24h":"66000",
         "best_bid":"65012.33","best_ask":"65012.35","side":"buy",
         "time":"2026-09-27T12:00:00.123456Z","trade_id":370843401,"last_size":"0.0123"}
    """.trimIndent()

    @Test
    fun parsesTicker() {
        val p = CoinbaseTickerMessages.parse(ticker) as CoinbaseTickerMessages.Parsed.Tick
        assertEquals("BTC-USD", p.productId)
        assertEquals("BTC", p.asset)
        assertEquals(65_012.34, p.price, 1e-9)
        assertEquals("2026-09-27T12:00:00.123456Z", p.time)
        assertEquals(
            java.time.Instant.parse("2026-09-27T12:00:00.123Z").toEpochMilli(),
            CoinbaseTickerMessages.parseTimeMs(p.time)
        )
    }

    @Test
    fun parsesEthAndSolTickers() {
        val eth = CoinbaseTickerMessages.parse("""{"type":"ticker","product_id":"ETH-USD","price":"2500.5"}""")
        val sol = CoinbaseTickerMessages.parse("""{"type":"ticker","product_id":"SOL-USD","price":"150.25"}""")
        assertEquals("ETH", (eth as CoinbaseTickerMessages.Parsed.Tick).asset)
        assertEquals("SOL", (sol as CoinbaseTickerMessages.Parsed.Tick).asset)
        assertEquals(150.25, sol.price, 1e-12)
    }

    @Test
    fun heartbeatAndSubscriptions() {
        val hb = CoinbaseTickerMessages.parse(
            """{"type":"heartbeat","sequence":90,"last_trade_id":20,"product_id":"SOL-USD","time":"2026-09-27T12:00:00Z"}"""
        )
        assertEquals(CoinbaseTickerMessages.Parsed.Heartbeat("SOL-USD", "SOL"), hb)
        val sub = CoinbaseTickerMessages.parse(
            """{"type":"subscriptions","channels":[{"name":"ticker","product_ids":["BTC-USD"]},{"name":"heartbeat","product_ids":["BTC-USD"]}]}"""
        )
        assertEquals(CoinbaseTickerMessages.Parsed.Subscribed(listOf("ticker", "heartbeat")), sub)
    }

    @Test
    fun errorMessageKeepsReason() {
        val err = CoinbaseTickerMessages.parse("""{"type":"error","message":"Failed to subscribe","reason":"bad channel"}""")
        assertEquals(CoinbaseTickerMessages.Parsed.Error("Failed to subscribe: bad channel"), err)
    }

    @Test
    fun malformedTextIsNull() {
        assertNull(CoinbaseTickerMessages.parse(""))
        assertNull(CoinbaseTickerMessages.parse("not json"))
        assertNull(CoinbaseTickerMessages.parse("{\"type\":\"ticker\",\"price\":"))
        assertNull(CoinbaseTickerMessages.parse("[1,2,3]"))
        assertNull(CoinbaseTickerMessages.parse("\"ticker\""))
    }

    @Test
    fun otherTypesAndUnusableTickersAreIgnored() {
        fun isOther(text: String) =
            assertTrue(text, CoinbaseTickerMessages.parse(text) is CoinbaseTickerMessages.Parsed.Other)
        isOther("""{"type":"l2update","product_id":"BTC-USD","changes":[]}""")
        isOther("""{"type":"match","product_id":"BTC-USD","price":"65000"}""")
        isOther("""{"product_id":"BTC-USD","price":"65000"}""")
        isOther("""{"type":"ticker","product_id":"DOGE-USD","price":"0.1"}""")
        isOther("""{"type":"ticker","product_id":"BTC-USD","price":"abc"}""")
        isOther("""{"type":"ticker","product_id":"BTC-USD","price":"0"}""")
        isOther("""{"type":"ticker","product_id":"BTC-USD","price":"-1"}""")
        isOther("""{"type":"ticker","product_id":"BTC-USD","price":"NaN"}""")
        isOther("""{"type":"ticker","product_id":"BTC-USD"}""")
        isOther("""{"type":"ticker","price":"65000"}""")
        isOther("""{"type":"ticker","product_id":null,"price":"65000"}""")
        isOther("""{"type":"heartbeat","product_id":"XRP-USD"}""")
    }

    @Test
    fun badTimeParsesToNull() {
        assertNull(CoinbaseTickerMessages.parseTimeMs(null))
        assertNull(CoinbaseTickerMessages.parseTimeMs(""))
        assertNull(CoinbaseTickerMessages.parseTimeMs("yesterday"))
    }

    @Test
    fun subscribeMessageMatchesTheFeedProtocol() {
        val msg = JSONObject(CoinbaseTickerMessages.subscribe(CoinbaseTickerMessages.PRODUCTS))
        assertEquals("subscribe", msg.getString("type"))
        val products = msg.getJSONArray("product_ids")
        assertEquals(listOf("BTC-USD", "ETH-USD", "SOL-USD"), (0 until products.length()).map { products.getString(it) })
        val channels = msg.getJSONArray("channels")
        assertEquals(listOf("ticker", "heartbeat"), (0 until channels.length()).map { channels.getString(it) })
        assertEquals("wss://ws-feed.exchange.coinbase.com", CoinbaseSpotStream.WS_URL)
    }

    @Test
    fun productsFollowTheLiveSeries() {
        assertEquals(listOf("BTC-USD"), CoinbaseTickerMessages.productsFor(listOf("KXBTC15M")))
        assertEquals(
            listOf("BTC-USD", "ETH-USD", "SOL-USD"),
            CoinbaseTickerMessages.productsFor(listOf("KXBTC15M", "KXETH15M", "KXSOL15M", "KXBTCD"))
        )
        // Today's live universe is Bitcoin-only.
        assertEquals(
            listOf("BTC-USD"),
            CoinbaseTickerMessages.productsFor(com.dirk.kalshiodds.domain.CryptoMarkets.DEFAULT_SERIES)
        )
        assertEquals(CoinbaseTickerMessages.PRODUCTS, CoinbaseTickerMessages.productsFor(emptyList()))
    }

    @Test
    fun assetOfProductIds() {
        assertEquals("BTC", CoinbaseTickerMessages.assetOf("btc-usd"))
        assertEquals("ETH", CoinbaseTickerMessages.assetOf("ETH-USD"))
        assertNull(CoinbaseTickerMessages.assetOf("ADA-USD"))
        assertNull(CoinbaseTickerMessages.assetOf(null))
    }
}
