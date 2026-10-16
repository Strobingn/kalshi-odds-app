package com.dirk.kalshiodds.release

import com.dirk.kalshiodds.domain.withLiveQuote
import com.dirk.kalshiodds.signal.debug.PriceDebugLog
import com.dirk.kalshiodds.signal.engine.TickBook
import com.dirk.kalshiodds.signal.model.MarketTick
import com.dirk.kalshiodds.signal.model.TickSource
import com.dirk.kalshiodds.signal.ws.KalshiWsMessages
import com.dirk.kalshiodds.signal.ws.OrderbookSequencer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** 0.3.43: erratic contract prices — out-of-order rejection, per-ticker keying, seq-gap resync. */
class ReleaseGate0343PricesTest {
    private val btc = "KXBTC15M-26OCT091445-45"
    private val eth = "KXETH15M-26OCT091445-45"
    private val btcPrev = "KXBTC15M-26OCT091430-30"

    @Before fun reset() = PriceDebugLog.clear()

    private fun tick(
        t: String, bid: Double, ask: Double, src: TickSource = TickSource.WS_TICKER,
        ts: Long? = null, recv: Long = 0L, close: Long? = null, last: Double? = null
    ) = MarketTick(
        ticker = t, series = MarketTick.inferSeries(t), yesBid = bid, yesAsk = ask, lastPrice = last,
        volume = null, openInterest = null, closeTimeEpochMs = close, source = src,
        receiveElapsedNanos = recv, exchangeTsMs = ts, noBid = 1.0 - ask, noAsk = 1.0 - bid
    )

    // ---- (1) out of order ----

    @Test
    fun olderKalshiTsTickerUpdateIsDropped() {
        val b = TickBook()
        b.push(tick(btc, 0.48, 0.49, ts = 2_000_000L), nowMs = 10)
        b.push(tick(btc, 0.40, 0.41, ts = 1_999_000L), nowMs = 11) // arrives late, older ts
        assertEquals(0.48, b.lastTick(btc)!!.yesBid!!, 1e-9)
        assertTrue(PriceDebugLog.snapshot().any { it.verdict == PriceDebugLog.STALE && it.ticker == btc })
        b.push(tick(btc, 0.50, 0.51, ts = 2_001_000L), nowMs = 12)
        assertEquals(0.50, b.lastTick(btc)!!.yesBid!!, 1e-9)
    }

    @Test
    fun restPollCannotOverwriteFreshWsQuote() {
        val b = TickBook()
        b.push(tick(btc, 0.48, 0.49, ts = 1_000L, recv = 1_000_000_000L), nowMs = 1)
        // REST /markets lagged the live book by 2–3¢ on the box comparison (2026-10-09 14:32 ET).
        b.push(tick(btc, 0.46, 0.47, src = TickSource.REST, recv = 5_000_000_000L), nowMs = 2)
        assertEquals(0.48, b.lastTick(btc)!!.yesBid!!, 1e-9)
        assertTrue(PriceDebugLog.snapshot().any { it.verdict == PriceDebugLog.REST_SUPPRESSED })
        // Once the WS quote is older than the grace window, REST is used again (WS down / reconnecting).
        b.push(tick(btc, 0.46, 0.47, src = TickSource.REST, recv = 1_000_000_000L + TickBook.REST_WS_GRACE_NANOS + 1), nowMs = 3)
        assertEquals(0.46, b.lastTick(btc)!!.yesBid!!, 1e-9)
    }

    @Test
    fun tradePrintDoesNotMoveTheQuoteOrMidSeries() {
        val b = TickBook()
        b.push(tick(btc, 0.48, 0.50, ts = 1_000L), nowMs = 1)
        val trade = tick(btc, 0.30, 0.30, src = TickSource.WS_TRADE, ts = 1_001L, last = 0.30)
        b.push(trade, nowMs = 2)
        assertEquals(0.48, b.lastTick(btc)!!.yesBid!!, 1e-9)
        assertEquals(0.50, b.lastTick(btc)!!.yesAsk!!, 1e-9)
        assertTrue(b.series(btc).all { kotlin.math.abs(it.mid01 - 0.49) < 1e-9 })
        assertTrue(b.bidHistory(btc).none { it.upBidCents != null && it.upBidCents!! < 40f })
    }

    // ---- (2) per-ticker keying ----

    @Test
    fun updatesAreKeyedByFullTicker() {
        val b = TickBook()
        b.push(tick(btc, 0.48, 0.49), nowMs = 1)
        b.push(tick(eth, 0.20, 0.21), nowMs = 2)
        assertEquals(0.48, b.lastTick(btc)!!.yesBid!!, 1e-9)
        assertEquals(0.20, b.lastTick(eth)!!.yesBid!!, 1e-9)
        b.push(tick(btcPrev, 0.95, 0.96), nowMs = 3)
        assertEquals(0.48, b.lastTick(btc)!!.yesBid!!, 1e-9)
    }

    @Test
    fun previousWindowTickDoesNotMoveSeriesMid() {
        val b = TickBook()
        val now = 1_000_000L
        b.push(tick(btcPrev, 0.95, 0.97, close = now + 1_000), nowMs = now)
        b.push(tick(btc, 0.48, 0.50, close = now + 900_000), nowMs = now + 2_000) // prev window closed at now+1000
        b.push(tick(btcPrev, 0.99, 0.99, close = now + 1_000), nowMs = now + 3_000) // late tick for closed window
        assertEquals(0.49, b.lastMid("KXBTC15M")!!, 1e-9)
        assertFalse(b.isCurrentWindow(btcPrev, "KXBTC15M", now + 3_000))
        assertTrue(b.isCurrentWindow(btc, "KXBTC15M", now + 3_000))
    }

    @Test
    fun withLiveQuoteIgnoresAnotherTickersTick() {
        val m = com.dirk.kalshiodds.ui.HomeFixtures.market(ticker = btc, seriesLabel = "Bitcoin", yesAsk = 0.49, aiYes = 50.0, predicted = "YES", closeMs = 1L)
        val out = m.withLiveQuote(tick(eth, 0.10, 0.11))
        assertEquals(m.yesAsk, out.yesAsk)
    }

    // ---- (4) seq gaps ----

    @Test
    fun seqIsPerSubscriptionNotPerTicker() {
        val s = OrderbookSequencer()
        assertEquals(OrderbookSequencer.Verdict.APPLY, s.accept(2, 1)) // BTC snapshot
        assertEquals(OrderbookSequencer.Verdict.APPLY, s.accept(2, 2)) // ETH snapshot
        assertEquals(OrderbookSequencer.Verdict.APPLY, s.accept(2, 3)) // BTC delta — NOT a gap
        assertEquals(OrderbookSequencer.Verdict.APPLY, s.accept(2, 4)) // ETH delta
        assertEquals(OrderbookSequencer.Verdict.STALE, s.accept(2, 4)) // duplicate
        assertEquals(OrderbookSequencer.Verdict.STALE, s.accept(2, 3)) // out of order
        assertEquals(OrderbookSequencer.Verdict.GAP, s.accept(2, 7)) // missed 5, 6
        assertEquals(OrderbookSequencer.Verdict.APPLY, s.accept(2, 8))
        assertEquals(OrderbookSequencer.Verdict.APPLY, s.accept(3, 1)) // other sid independent
    }

    @Test
    fun parsedSnapshotAndDeltaCarrySidAndSeq() {
        val d = KalshiWsMessages.parse(
            """{"type":"orderbook_delta","sid":2,"seq":3,"msg":{"market_ticker":"$btc","market_id":"x","price_dollars":"0.4800","delta_fp":"-54.00","side":"yes","ts_ms":1669149841000}}""",
            0L
        ) as KalshiWsMessages.Parsed.OrderbookDelta
        assertEquals(2, d.sid); assertEquals(3, d.seq); assertEquals(1669149841000L, d.exchangeTsMs)
        val s = KalshiWsMessages.parse(
            """{"type":"orderbook_snapshot","sid":2,"seq":2,"msg":{"market_ticker":"$btc","market_id":"x","yes_dollars_fp":[["0.4800","10.00"]],"no_dollars_fp":[["0.5100","5.00"]]}}""",
            0L
        ) as KalshiWsMessages.Parsed.OrderbookSnapshot
        assertEquals(2, s.sid); assertEquals(2, s.seq)
    }

    @Test
    fun interleavedDeltasNoLongerWipeTheBook() {
        val b = TickBook()
        b.applySnapshot(btc, listOf(0.48 to 10.0), listOf(0.51 to 10.0), seq = 1)
        b.applySnapshot(eth, listOf(0.20 to 10.0), listOf(0.79 to 10.0), seq = 2)
        assertNotNull(b.applyDelta(btc, 0.47, 5.0, "yes", seq = 3))
        assertNotNull(b.applyDelta(eth, 0.19, 5.0, "yes", seq = 4))
        assertNotNull(b.applyDelta(btc, 0.46, 5.0, "yes", seq = 5)) // pre-0.3.43: "gap" 3→5 cleared the BTC book
        assertEquals(0.48, b.tickFromBook(btc, 0L)!!.yesBid!!, 1e-9)
        assertEquals(0.49, b.tickFromBook(btc, 0L)!!.yesAsk!!, 1e-9)
    }

    @Test
    fun seqGapInvalidatesBooksUntilResnapshot() {
        val b = TickBook()
        b.applySnapshot(btc, listOf(0.48 to 10.0), listOf(0.51 to 10.0), seq = 1)
        b.invalidateBooks(listOf(btc))
        assertFalse(b.hasSnapshot(btc))
        assertNull(b.applyDelta(btc, 0.10, 500.0, "yes", seq = 9)) // never a one-level book from deltas alone
        assertNull(b.tickFromBook(btc, 0L))
        assertTrue(PriceDebugLog.snapshot().any { it.verdict == PriceDebugLog.NO_SNAPSHOT })
        b.applySnapshot(btc, listOf(0.47 to 8.0), listOf(0.52 to 8.0), seq = 10)
        assertEquals(0.47, b.tickFromBook(btc, 0L)!!.yesBid!!, 1e-9)
    }

    @Test
    fun clientRequestsFreshSnapshotsOnGap() {
        val src = listOf(File("app/src/main/java/com/dirk/kalshiodds/signal/ws/KalshiWsClient.kt"), File("src/main/java/com/dirk/kalshiodds/signal/ws/KalshiWsClient.kt"))
            .first { it.exists() }.readText()
        assertTrue(src.contains("KalshiWsMessages.updateSubscription(id, sid, \"get_snapshot\", tickers)"))
        assertTrue(src.contains("runCatching { onBookGap(tickers) }"))
        assertTrue(src.contains("bookSeq.reset()"))
        val svc = listOf(File("app/src/main/java/com/dirk/kalshiodds/signal/service/LiveSignalsService.kt"), File("src/main/java/com/dirk/kalshiodds/signal/service/LiveSignalsService.kt"))
            .first { it.exists() }.readText()
        assertTrue(svc.contains("onBookGap = { tickers -> runCatching { hub.scoring.book.invalidateBooks(tickers) } }"))
    }

    // ---- debug log ----

    @Test
    fun priceLogCsvHasSourceTsSeqTickerField() {
        val b = TickBook()
        b.push(tick(btc, 0.48, 0.49, ts = 5_000L), nowMs = 1)
        val csv = PriceDebugLog.csv()
        assertTrue(csv.startsWith("wall_ms,source,ticker,field,value,kalshi_ts_ms,sid,seq,verdict"))
        assertTrue(csv.contains("WS_TICKER,$btc,yes_bid,0.4800,5000,,,applied"))
        assertFalse(csv.contains("PRIVATE KEY"))
    }
}
