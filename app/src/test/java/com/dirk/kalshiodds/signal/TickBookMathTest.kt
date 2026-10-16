package com.dirk.kalshiodds.signal

import com.dirk.kalshiodds.signal.engine.LocalOrderBook
import com.dirk.kalshiodds.signal.engine.TickBook
import com.dirk.kalshiodds.signal.model.MarketTick
import com.dirk.kalshiodds.signal.model.TickSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TickBookMathTest {

    @Test
    fun velocityIsDeltaMidOverDeltaTime() {
        // +0.10 mid in 1.0s → 0.10 / s
        val vel = LocalOrderBook.velocityPerSec(
            midsNewestLast = listOf(0.40, 0.50),
            timesMs = listOf(1_000L, 2_000L)
        )
        assertEquals(0.10, vel!!, 1e-9)
    }

    @Test
    fun velocityNullWhenTooFewOrZeroDt() {
        assertNull(LocalOrderBook.velocityPerSec(listOf(0.4), listOf(1L)))
        assertNull(LocalOrderBook.velocityPerSec(listOf(0.4, 0.5), listOf(10L, 10L)))
    }

    @Test
    fun accelerationIsRecentMinusOlderVelocity() {
        // older: 0.40 → 0.50 in 1s = +0.10/s
        // recent: 0.50 → 0.80 in 1s = +0.30/s
        // acc = 0.20/s
        val acc = LocalOrderBook.accelerationPerSec(
            midsNewestLast = listOf(0.40, 0.50, 0.50, 0.80),
            timesMs = listOf(0L, 1_000L, 1_000L, 2_000L)
        )
        assertEquals(0.20, acc!!, 1e-9)
    }

    @Test
    fun tickBookVelocityUsesLastNHistory() {
        val book = TickBook()
        val ticker = "KXBTC15M-VEL"
        for (i in 0 until 5) {
            book.push(
                tick(
                    ticker = ticker,
                    bid = 0.40 + i * 0.02,
                    ask = 0.42 + i * 0.02
                ),
                nowMs = 10_000L + i * 500L
            )
        }
        // mid_i = 0.41 + i*0.02 → 0.41 → 0.49 over 2000ms → 0.04 / s
        val vel = book.velocityPerSec(ticker, lookback = 16)
        assertEquals(0.04, vel!!, 1e-9)
    }

    @Test
    fun orderbookTicksDoNotEnterVelocitySeries() {
        val book = TickBook()
        val ticker = "KXETH15M-BOOK"
        book.push(tick(ticker, 0.40, 0.42), nowMs = 1_000L)
        book.push(
            tick(ticker, 0.70, 0.72).copy(source = TickSource.WS_ORDERBOOK),
            nowMs = 1_500L
        )
        book.push(tick(ticker, 0.50, 0.52), nowMs = 2_000L)
        // Only REST/ticker points: 0.41 → 0.51 in 1s
        assertEquals(0.10, book.velocityPerSec(ticker)!!, 1e-9)
        assertEquals(2, book.series(ticker).size)
    }

    @Test
    fun signedImbalanceFormula() {
        assertEquals(0.0, LocalOrderBook.signedImbalance(50.0, 50.0)!!, 1e-9)
        assertEquals(1.0, LocalOrderBook.signedImbalance(10.0, 0.0)!!, 1e-9)
        assertEquals(-1.0, LocalOrderBook.signedImbalance(0.0, 10.0)!!, 1e-9)
        // (150 - 80) / 230
        assertEquals(70.0 / 230.0, LocalOrderBook.signedImbalance(150.0, 80.0)!!, 1e-9)
        assertNull(LocalOrderBook.signedImbalance(0.0, 0.0))
    }

    @Test
    fun bookImbalanceNearMidBand() {
        val book = LocalOrderBook()
        book.replaceSnapshot(
            yesLevels = listOf(0.48 to 100.0, 0.47 to 50.0),
            noLevels = listOf(0.50 to 80.0),
            seq = 1
        )
        // Official orderbook: bids only; YES ask = 1 − best NO bid.
        // https://docs.kalshi.com/getting_started/orderbook_responses
        assertEquals(0.48, book.bestYesBid()!!, 1e-9)
        assertEquals(0.50, book.bestYesAsk()!!, 1e-9)
        // best YES bid 0.48, best YES ask 1-0.50=0.50, mid=0.49
        assertEquals(0.49, book.mid01()!!, 1e-9)
        // 3¢ band includes both YES levels and the NO level
        val imb = book.imbalance(bandCents = 3.0, topLevels = 3)
        assertEquals(70.0 / 230.0, imb!!, 1e-9)
    }

    @Test
    fun deltaUpdatesSizeAndCanClearLevel() {
        val book = LocalOrderBook()
        book.replaceSnapshot(listOf(0.40 to 10.0), listOf(0.55 to 10.0), seq = 1)
        assertTrue(book.applyDelta(0.40, 5.0, "yes", seq = 2))
        assertEquals(15.0, book.yesLevels().single { it.first == 0.40 }.second, 1e-9)
        assertTrue(book.applyDelta(0.40, -15.0, "yes", seq = 3))
        assertTrue(book.yesLevels().isEmpty())
    }

    @Test
    fun sequenceGapClearsUntilSnapshot() {
        val ticks = TickBook()
        val ticker = "KXSOL15M-GAP"
        ticks.applySnapshot(ticker, listOf(0.40 to 10.0), listOf(0.55 to 10.0), seq = 4)
        assertTrue(ticks.imbalance(ticker) != null)
        // 0.3.43: seq is per sid (OrderbookSequencer); on a real gap the client invalidates the books.
        ticks.invalidateBooks(listOf(ticker))
        assertNull(ticks.imbalance(ticker))
        assertNull(ticks.applyDelta(ticker, 0.40, 1.0, "yes", seq = 9))
        assertNull(ticks.imbalance(ticker))
        ticks.applySnapshot(ticker, listOf(0.41 to 8.0), listOf(0.56 to 8.0), seq = 10)
        assertTrue(ticks.imbalance(ticker) != null)
    }

    @Test
    fun wtiBookIsIgnored() {
        val ticks = TickBook()
        assertNull(
            ticks.applySnapshot("KXWTI15M-OIL", listOf(0.40 to 10.0), listOf(0.50 to 10.0), 1)
        )
        assertNull(ticks.applyDelta("KXWTI15M-OIL", 0.40, 1.0, "yes", 2))
        assertNull(ticks.imbalance("KXWTI15M-OIL"))
    }

    @Test
    fun topOfBookFallbackWhenBandEmpty() {
        val book = LocalOrderBook()
        // Mid ~ 0.50. Bids far below and asks far above a 1¢ band.
        book.replaceSnapshot(
            yesLevels = listOf(0.10 to 40.0, 0.05 to 5.0),
            noLevels = listOf(0.10 to 10.0, 0.05 to 1.0),
            seq = 1
        )
        // best bid 0.10, best ask 0.90, mid 0.50; 1¢ band is empty → top 1
        val imb = book.imbalance(bandCents = 1.0, topLevels = 1)
        assertEquals(LocalOrderBook.signedImbalance(40.0, 10.0), imb)
    }

    @Test
    fun snapshotBookIsIsolatedFromLaterDeltas() {
        val store = TickBook()
        store.applySnapshot(
            "KXBTC15M-SNAP",
            yesLevels = listOf(0.40 to 10.0),
            noLevels = listOf(0.55 to 12.0),
            seq = 1
        )
        val snap = store.snapshotBook("KXBTC15M-SNAP")
        assertTrue(snap != null && !snap.isEmpty())
        store.applyDelta("KXBTC15M-SNAP", price = 0.40, delta = 90.0, side = "yes", seq = 2)
        // Snapshot must not see the live TreeMap mutation.
        assertEquals(10.0, snap!!.yes.single { it.first == 0.40 }.second, 1e-9)
        val live = store.snapshotBook("KXBTC15M-SNAP")!!
        assertEquals(100.0, live.yes.single { it.first == 0.40 }.second, 1e-9)
    }

    @Test
    fun snapshotBookSurvivesConcurrentDeltas() {
        val store = TickBook()
        store.applySnapshot(
            "KXBTC15M-RACE",
            yesLevels = listOf(0.50 to 5.0),
            noLevels = listOf(0.49 to 5.0),
            seq = 1
        )
        val errors = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val writers = (0 until 4).map { i ->
            Thread {
                try {
                    repeat(80) { n ->
                        store.applyDelta(
                            "KXBTC15M-RACE",
                            price = 0.50,
                            delta = if (n % 2 == 0) 1.0 else -1.0,
                            side = "yes",
                            seq = 2 + n + i * 80
                        )
                    }
                } catch (t: Throwable) {
                    errors.add(t)
                }
            }
        }
        val readers = (0 until 4).map {
            Thread {
                try {
                    repeat(80) {
                        store.snapshotBook("KXBTC15M-RACE")
                    }
                } catch (t: Throwable) {
                    errors.add(t)
                }
            }
        }
        (writers + readers).forEach { it.start() }
        (writers + readers).forEach { it.join() }
        assertTrue(errors.joinToString { it.toString() }, errors.isEmpty())
    }

    @Test
    fun bookViewSurvivesConcurrentDeltas() {
        val store = TickBook()
        store.applySnapshot(
            "KXBTC15M-VIEW",
            yesLevels = listOf(0.50 to 5.0),
            noLevels = listOf(0.49 to 5.0),
            seq = 1
        )
        val errors = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val writers = (0 until 4).map { i ->
            Thread {
                try {
                    repeat(80) { n ->
                        store.applyDelta(
                            "KXBTC15M-VIEW",
                            price = 0.50,
                            delta = if (n % 2 == 0) 1.0 else -1.0,
                            side = "yes",
                            seq = 2 + n + i * 80
                        )
                    }
                } catch (t: Throwable) {
                    errors.add(t)
                }
            }
        }
        val readers = (0 until 4).map {
            Thread {
                try {
                    repeat(80) {
                        store.bookView("KXBTC15M-VIEW")
                    }
                } catch (t: Throwable) {
                    errors.add(t)
                }
            }
        }
        (writers + readers).forEach { it.start() }
        (writers + readers).forEach { it.join() }
        assertTrue(errors.joinToString { it.toString() }, errors.isEmpty())
    }

    @Test
    fun placeholderQuoteIsNotRecordedInBidHistory() {
        val book = TickBook()
        val ticker = "KXBTC15M-TAPE"
        book.push(tick(ticker, 0.63, 0.64), nowMs = 1_000L)
        book.push(tick(ticker, 0.0, 1.0), nowMs = 2_000L)
        book.noteSpot(ticker, 84_311.58, nowMs = 2_000L)
        val hist = book.bidHistory(ticker)
        assertTrue(hist.none { it.upBidCents != null && it.upBidCents!! < 1f })
        assertEquals(1, hist.count { it.upBidCents != null })
        assertTrue(hist.any { it.spotUsd != null && it.spotUsd!! > 80_000 })
    }

    @Test
    fun crossedBookDoesNotReplaceGoodBid() {
        val book = TickBook()
        val ticker = "KXBTC15M-X"
        book.push(tick(ticker, 0.63, 0.64), nowMs = 1_000L)
        book.push(tick(ticker, 0.80, 0.10), nowMs = 2_000L)
        val hist = book.bidHistory(ticker)
        assertEquals(1, hist.size)
        assertEquals(63f, hist.single().upBidCents!!, 0.01f)
    }

    @Test
    fun noteSpotAttachesToBidHistory() {
        val book = TickBook()
        val ticker = "KXETH15M-SPOT"
        book.noteSpot(ticker, 4_200.0, nowMs = 500L)
        book.push(tick(ticker, 0.50, 0.52), nowMs = 1_000L)
        val hist = book.bidHistory(ticker)
        assertEquals(1, hist.size)
        assertEquals(4_200.0, hist.single().spotUsd!!, 1e-6)
    }

    @Test
    fun lastTickKeepsSpreadWhenTradeAndMetaArrive() {
        val book = TickBook()
        val ticker = "KXBTC15M-BOOK"
        book.push(
            tick(ticker, 0.33, 0.34).copy(noBid = 0.66, noAsk = 0.67),
            nowMs = 1_000L
        )
        book.push(
            MarketTick(
                ticker = ticker,
                series = "KXBTC15M",
                yesBid = 0.34,
                yesAsk = 0.34,
                lastPrice = 0.34,
                volume = 2.0,
                openInterest = 2.0,
                closeTimeEpochMs = 1_800_000L,
                source = TickSource.WS_TRADE,
                receiveElapsedNanos = 2L,
                noBid = 0.66,
                noAsk = 0.66
            ),
            nowMs = 2_000L
        )
        book.updateMeta(ticker, 1_900_000L, 50.0, 10.0, floorStrike = 84_144.0)
        val last = book.lastTick(ticker)!!
        assertEquals(0.33, last.yesBid!!, 1e-12)
        assertEquals(0.34, last.yesAsk!!, 1e-12)
        assertEquals(0.66, last.noBid!!, 1e-12)
        assertEquals(0.67, last.noAsk!!, 1e-12)
        assertEquals(84_144.0, last.floorStrike!!, 1e-6)
    }

    @Test
    fun orderbookSnapshotWritesOfficialBidsAndAsks() {
        val book = TickBook()
        val ticker = "KXBTC15M-OB"
        book.applySnapshot(
            ticker,
            yesLevels = listOf(0.33 to 12.0, 0.32 to 8.0),
            noLevels = listOf(0.66 to 15.0, 0.65 to 4.0),
            seq = 1
        )
        assertEquals(0.33, book.orderBook(ticker)!!.bestYesBid()!!, 1e-12)
        assertEquals(0.34, book.orderBook(ticker)!!.bestYesAsk()!!, 1e-12)
        assertEquals(0.66, book.orderBook(ticker)!!.bestNoBid()!!, 1e-12)
        assertEquals(0.67, book.orderBook(ticker)!!.bestNoAsk()!!, 1e-12)
        val last = book.lastTick(ticker)!!
        assertEquals(0.33, last.yesBid!!, 1e-12)
        assertEquals(0.34, last.yesAsk!!, 1e-12)
        assertEquals(0.66, last.noBid!!, 1e-12)
        assertEquals(0.67, last.noAsk!!, 1e-12)
    }

    @Test
    fun sparklineSkipsZeroMids() {
        val book = TickBook()
        val ticker = "KXBTC15M-SPARK"
        book.push(tick(ticker, 0.33, 0.35), nowMs = 1_000L)
        book.push(tick(ticker, 0.0, 0.0).copy(lastPrice = 0.0), nowMs = 2_000L)
        book.push(tick(ticker, 0.34, 0.36), nowMs = 3_000L)
        val series = book.midHistoryPp(ticker)
        assertTrue(series.none { it == 0f })
        assertTrue(series.all { it in 1f..99f })
    }

    private fun tick(ticker: String, bid: Double, ask: Double) = MarketTick(
        ticker = ticker,
        series = MarketTick.inferSeries(ticker),
        yesBid = bid,
        yesAsk = ask,
        lastPrice = (bid + ask) / 2.0,
        volume = 1_000.0,
        openInterest = 100.0,
        closeTimeEpochMs = 1_800_000L,
        source = TickSource.REST,
        receiveElapsedNanos = 1L
    )
}
