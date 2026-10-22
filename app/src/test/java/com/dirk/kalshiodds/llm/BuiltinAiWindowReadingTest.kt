package com.dirk.kalshiodds.llm

import com.dirk.kalshiodds.decision.ScalpRule
import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.engine.BookScoreGate
import com.dirk.kalshiodds.signal.engine.CoinEvalConflator
import com.dirk.kalshiodds.signal.engine.MarketEventDebouncer
import com.dirk.kalshiodds.signal.engine.ScoringEngine
import com.dirk.kalshiodds.signal.engine.TickBook
import com.dirk.kalshiodds.signal.model.MarketTick
import com.dirk.kalshiodds.signal.model.TickSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BuiltinAiWindowReadingTest {

    @Test
    fun conflatorAndDebouncerHaveLowLatencyDefaultsAndImmediateQuietFire() {
        assertEquals(60L, CoinEvalConflator.MIN_GAP_MS)
        assertEquals(75L, MarketEventDebouncer.DEBOUNCE_MS)
        assertEquals(500L, MarketEventDebouncer.EXIT_TIMER_MS)
        assertEquals(150L, BookScoreGate.MIN_INTERVAL_MS)
        assertEquals(150L, ScoringEngine.BOOK_SCORE_MIN_INTERVAL_MS)
        assertEquals(2_000L, AiWindowWatcher.POLL_MS)

        val d = StandardTestDispatcher()
        val scope = TestScope(d)
        val calls = mutableListOf<Pair<Long, Set<String>>>()
        val conflator = CoinEvalConflator(
            scope = scope,
            dispatcher = d,
            minGapMs = 60L,
            nowNanos = { scope.testScheduler.currentTime * 1_000_000L }
        ) { _, tickers ->
            calls += scope.testScheduler.currentTime to tickers
        }

        // First burst coalesces over minGapMs (60ms)
        conflator.fire("KXBTC15M-26OCT221200-00")
        conflator.fire("KXBTC15M-26OCT221200-01")
        scope.advanceTimeBy(61L)
        scope.runCurrent()
        assertEquals(1, calls.size)
        assertEquals(setOf("KXBTC15M-26OCT221200-00", "KXBTC15M-26OCT221200-01"), calls.first().second)

        // After the lane has been quiet for >60ms, the next single tick fires without waiting another full 60ms
        scope.advanceTimeBy(200L)
        scope.runCurrent()
        val quietTickAt = scope.testScheduler.currentTime
        conflator.fire("KXBTC15M-26OCT221200-00")
        scope.runCurrent()
        assertEquals(2, calls.size)
        assertEquals(quietTickAt, calls.last().first)
    }

    @Test
    fun tickBookSpotReturnFeedsScoringEngineWhenExternalSpotCacheHasNoReturns() {
        val book = TickBook()
        val t0 = 1_700_000_000_000L
        book.noteSpot("KXBTC15M-A", 80_000.0, t0 - 300_000L)
        book.noteSpot("KXBTC15M-A", 80_400.0, t0 - 60_000L)
        book.noteSpot("KXBTC15M-A", 80_804.0, t0)

        val ret1m = book.spotReturn("KXBTC15M-A", t0, 60_000L)
        val ret5m = book.spotReturn("KXBTC15M-A", t0, 300_000L)
        assertNotNull(ret1m)
        assertNotNull(ret5m)
        assertEquals((80_804.0 - 80_400.0) / 80_400.0, ret1m!!, 1e-6)
        assertEquals((80_804.0 - 80_000.0) / 80_000.0, ret5m!!, 1e-6)

        val engine = ScoringEngine(book = book, idFactory = { "id" })
        val tick = MarketTick(
            ticker = "KXBTC15M-A",
            series = "KXBTC15M",
            yesBid = 0.52,
            yesAsk = 0.54,
            lastPrice = 0.53,
            volume = 1_500.0,
            openInterest = 400.0,
            closeTimeEpochMs = t0 + 420_000L,
            source = TickSource.WS_TICKER,
            receiveElapsedNanos = 1L
        )
        val scored = engine.score(tick, SignalSettings(), nowMs = t0)
        assertNotNull(scored)
        assertNotNull(scored!!.spotPp)
        assertNotNull(scored.spotLabel)
        assertEquals(80_804.0, scored.spotUsd!!, 1e-6)
    }

    @Test
    fun liveWindowIncludesRichAtmTelemetryAndStrikeCrossEvents() {
        val now = 1_700_000_300_000L
        val close = now + 300_000L
        val open = close - 900_000L
        val atmQuote = ScalpRule.Quote(
            ticker = "KXBTC15M-26OCT221200-50",
            nowMs = now,
            closeMs = close,
            bookAtMs = now - 120L,
            yesBid = 0.48,
            yesBidSize = 300.0,
            yesAsk = 0.50,
            yesAskSize = 100.0,
            spot = 81_100.0,
            strike = 81_000.0,
            sigmaPerSec = 2e-4,
            cfSpot = 81_110.0,
            cfAgeMs = 85L
        )
        val telemetry = ChatPrompt.atmTelemetry(atmQuote)
        assertTrue(telemetry.contains("ATM telemetry:"))
        assertTrue(telemetry.contains("spot fair YES"))
        assertTrue(telemetry.contains("BRTI fair YES"))
        assertTrue(telemetry.contains("net edge"))
        assertTrue(telemetry.contains("spread 2.0¢"))
        assertTrue(telemetry.contains("top depth 300×100 (imb +50%)"))
        assertTrue(telemetry.contains("book age 120ms"))
        assertTrue(telemetry.contains("CF $81,110.00 (85ms)"))
        assertFalse(telemetry.contains("snapshot", ignoreCase = true))

        val liveText = ChatPrompt.liveWindow(
            ChatPrompt.LiveWindow(
                nowMs = now,
                quotes = listOf(atmQuote),
                btcAtOpen = 80_950.0,
                modelUp = ChatPrompt.modelUp(atmQuote, 0.0)
            )
        )
        assertTrue(liveText.contains(telemetry))

        val snap = AiLiveSnapshot(
            atMs = open + 25_000L,
            windowKey = "26OCT221200",
            ticker = atmQuote.ticker,
            closeMs = close,
            strike = 81_000.0,
            btc = 81_020.0,
            yesBid = 0.55,
            yesAsk = 0.57,
            noBid = 0.43,
            noAsk = 0.45,
            modelUp = 0.58,
            points = listOf(
                AiLivePoint(open + 1_000L, 80_990.0, 81_000.0, 0.46, 0.52, 0.48, 0.54, 0.45),
                AiLivePoint(open + 4_000L, 81_015.0, 81_000.0, 0.55, 0.43, 0.57, 0.45, 0.58)
            )
        )
        val history = ChatPrompt.tickHistory(snap)
        assertTrue(history.contains("strike cross"))
    }
}
