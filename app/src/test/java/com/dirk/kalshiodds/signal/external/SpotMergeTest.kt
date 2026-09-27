package com.dirk.kalshiodds.signal.external

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotMergeTest {

    private val now = 1_700_000_000_000L

    private val rest = AssetSpotFeatures(
        asset = "BTC",
        spotReturn1m = 0.0011,
        spotReturn5m = 0.0022,
        realizedVol15m = 0.0009,
        fundingRate = 0.0001,
        lastPrice = 64_900.0,
        source = "binance",
        fetchedAtMs = now - 20_000L
    )

    /** 6 minutes of 1 s prints with small alternating moves, ending at [end]. */
    private fun warmBook(end: Long = now): SpotStreamBook {
        val book = SpotStreamBook()
        var p = 65_000.0
        for (s in 360 downTo 0) {
            p *= if (s % 2 == 0) 1.00005 else 0.99996
            assertTrue(book.onPrint("BTC", p, end - s * 1_000L))
        }
        return book
    }

    @Test
    fun freshStreamWinsOverRest() {
        val book = warmBook()
        val live = book.latest("BTC", now + 500L)!!
        val merged = SpotMerge.merge(rest, live)!!
        assertEquals(live.lastPrice, merged.lastPrice!!, 0.0)
        assertEquals(live.return1m!!, merged.spotReturn1m!!, 0.0)
        assertEquals(live.return5m!!, merged.spotReturn5m!!, 0.0)
        assertEquals(live.barStd1m!!, merged.realizedVol15m!!, 0.0)
        assertEquals("funding stays REST", 0.0001, merged.fundingRate!!, 0.0)
        assertEquals(SpotMerge.SOURCE, merged.source)
        assertEquals("coinbase-ws", merged.source)
        assertEquals(now, merged.fetchedAtMs)
    }

    @Test
    fun staleStreamFallsBackToRestUnchanged() {
        val book = warmBook()
        val snap = ExternalSnapshot(btc = rest, fetchedAtMs = now)
        assertNull(book.latest("BTC", now + 5_001L))
        assertSame(rest, SpotMerge.forSeries(snap, book, "KXBTC15M", now + 5_001L))
        assertSame(rest, SpotMerge.forSeries(snap, null, "KXBTC15M", now))
        assertSame(rest, SpotMerge.merge(rest, null))
    }

    @Test
    fun freshWithinFiveSeconds() {
        val book = warmBook()
        assertNotNull(book.latest("BTC", now + 4_999L))
        assertNotNull(book.latest("btc", now))
        assertNull("no ETH prints", book.latest("ETH", now))
    }

    @Test
    fun heartbeatKeepsAQuietProductFresh() {
        val book = SpotStreamBook()
        book.onPrint("SOL", 150.0, now)
        book.touch("SOL", now + 8_000L)
        val live = book.latest("SOL", now + 12_000L)
        assertNotNull(live)
        assertEquals(150.0, live!!.lastPrice, 0.0)
        // Heartbeats alone never keep a price past a minute.
        book.touch("SOL", now + 64_000L)
        assertNull(book.latest("SOL", now + 65_000L))
    }

    @Test
    fun heartbeatWithoutAnyPrintIsNotAPrice() {
        val book = SpotStreamBook()
        book.touch("ETH", now)
        assertNull(book.latest("ETH", now))
    }

    @Test
    fun warmingStreamFillsGapsFromRestFieldByField() {
        val book = SpotStreamBook()
        // 90 s of prints: 1m return exists, 5m and σ do not yet.
        for (s in 90 downTo 0) book.onPrint("BTC", 65_000.0 + s, now - s * 1_000L)
        val live = book.latest("BTC", now)!!
        assertNotNull(live.return1m)
        assertNull(live.return5m)
        assertNull(live.barStd1m)
        val merged = SpotMerge.forSeries(ExternalSnapshot(btc = rest), book, "KXBTC15M", now)!!
        assertEquals(65_000.0, merged.lastPrice!!, 0.0)
        assertEquals(live.return1m!!, merged.spotReturn1m!!, 0.0)
        assertEquals(rest.spotReturn5m!!, merged.spotReturn5m!!, 0.0)
        assertEquals(rest.realizedVol15m!!, merged.realizedVol15m!!, 0.0)
        assertEquals("coinbase-ws+binance", merged.source)
    }

    @Test
    fun streamWithoutRestStillScores() {
        val book = warmBook()
        val merged = SpotMerge.forSeries(ExternalSnapshot(), book, "KXBTC15M", now)!!
        assertEquals("coinbase-ws", merged.source)
        assertNull(merged.fundingRate)
        assertNotNull(merged.realizedVol15m)
        assertNull(SpotMerge.merge(null, null))
    }

    @Test
    fun seriesRoutesToItsOwnAsset() {
        val book = warmBook()
        val eth = rest.copy(asset = "ETH", lastPrice = 2_500.0, source = "coinbase")
        val snap = ExternalSnapshot(btc = rest, eth = eth)
        // ETH has no stream prints → REST ETH, untouched by BTC's stream.
        assertSame(eth, SpotMerge.forSeries(snap, book, "KXETH15M", now))
        assertEquals("coinbase-ws", SpotMerge.forSeries(snap, book, "KXBTC15M", now)!!.source)
        assertNull(SpotMerge.forSeries(snap, book, "KXINX", now))
    }

    @Test
    fun assetOfSeriesAndTickers() {
        assertEquals("BTC", ExternalSnapshot.assetOf("KXBTC15M"))
        assertEquals("BTC", ExternalSnapshot.assetOf("KXBTC15M-26SEP271215-15"))
        assertEquals("ETH", ExternalSnapshot.assetOf("kxeth15m"))
        assertEquals("SOL", ExternalSnapshot.assetOf("KXSOL15M-26SEP271215-15"))
        assertNull(ExternalSnapshot.assetOf("KXWTI"))
        val snap = ExternalSnapshot(btc = rest)
        assertSame(rest, snap.forSeries("KXBTC15M"))
        assertNull(snap.forSeries("KXETH15M"))
    }

    @Test
    fun reconnectBreakKeepsSigmaButDropsTheGapReturn() {
        val book = warmBook()
        val before = book.latest("BTC", now)!!.barStd1m!!
        book.markBreak()
        // Reconnected 3 s later, 0.8% higher.
        book.onPrint("BTC", 65_000.0 * 1.008, now + 3_000L)
        book.onPrint("BTC", 65_000.0 * 1.008, now + 13_000L)
        book.onPrint("BTC", 65_000.0 * 1.008, now + 23_000L)
        val after = book.latest("BTC", now + 23_000L)!!.barStd1m!!
        assertTrue("σ must not absorb the reconnect jump: $before → $after", after <= before * 1.01)
    }
}
