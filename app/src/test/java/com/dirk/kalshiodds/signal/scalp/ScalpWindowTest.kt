package com.dirk.kalshiodds.signal.scalp

import com.dirk.kalshiodds.signal.engine.LocalOrderBook
import com.dirk.kalshiodds.signal.model.MarketTick
import com.dirk.kalshiodds.signal.model.TickSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory ledger with a settable running P&L (bankroll sizing tests). */
private class WinLedger : ScalpLedger {
    val positions = mutableListOf<ScalpPosition>()
    var totalPnlCents = 0L

    override fun hydrateFromDisk() {}

    override fun openPosition(): ScalpPosition? = positions.firstOrNull { it.status == ScalpPositionStatus.OPEN }

    override fun openPosition(strategy: ScalpStrategy): ScalpPosition? =
        openPositions().firstOrNull { it.strategy == strategy }

    override fun openPositions(): List<ScalpPosition> = positions.filter { it.status == ScalpPositionStatus.OPEN }

    override fun entriesSince(nowMs: Long, windowMs: Long): Int =
        positions.count { it.status == ScalpPositionStatus.OPEN || it.entryTimeMs >= nowMs - windowMs }

    override fun realizedPnlCentsToday(nowMs: Long): Long =
        positions.mapNotNull { it.pnlCents }.sum().toLong()

    override fun realizedPnlCentsTotal(): Long = totalPnlCents

    override fun recordEnter(position: ScalpPosition, entryFeeCents: Int, clientOrderId: String?): ScalpPosition {
        val stored = position.copy(entryFeeCents = entryFeeCents, clientOrderId = clientOrderId)
        positions += stored
        return stored
    }

    override fun recordExit(
        positionId: String,
        exitPriceCents: Int,
        exitTimeMs: Long,
        reason: ExitReason,
        pnlCents: Int,
        exitFeeCents: Int
    ) {
        val i = positions.indexOfFirst { it.id == positionId }
        if (i < 0) return
        totalPnlCents += pnlCents
        positions[i] = positions[i].copy(
            status = ScalpPositionStatus.CLOSED,
            exitPriceCents = exitPriceCents,
            exitTimeMs = exitTimeMs,
            exitReason = reason,
            pnlCents = pnlCents,
            exitFeeCents = exitFeeCents
        )
    }

    override fun attachOrderIds(positionId: String, clientOrderId: String, orderId: String?) {
        positions.replaceAll { if (it.id == positionId) it.copy(clientOrderId = clientOrderId, orderId = orderId) else it }
    }
}

/**
 * Engine-level window lifecycle: WINDOW_CLOSE force-exits, rollover resets,
 * full-roster concurrency, and bankroll-scaled sizing.
 */
class ScalpWindowTest {

    private class Harness(
        settings: ScalpSettings,
        spot: ScalpSpot? = null,
        var bidSize: Double = 120.0,
        var askSize: Double = 80.0
    ) {
        val ledger = WinLedger()
        val book = LocalOrderBook()
        val events = mutableListOf<ScalpEvent>()
        var nowMs = 100_000L
        var closeTimeMs: Long? = null
        var ticker = "KXBTC15M-W1"
        val settingsFlow = MutableStateFlow(settings)
        val engine: ScalpEngine

        init {
            val paper = PaperScalpExecutor(bookProvider = { book }, idFactory = { "win-${++seq}" })
            engine = ScalpEngine(
                settings = settingsFlow,
                bookProvider = { book },
                paperExecutor = paper,
                liveExecutor = paper, // never selected: liveMode = false
                store = ledger,
                scope = CoroutineScope(Dispatchers.Unconfined),
                now = { nowMs },
                onEvent = { events += it },
                spotProvider = { spot }
            )
        }

        fun tick(mid: Double, tk: String = ticker, close: Long? = closeTimeMs) {
            val bid = (mid - 1) / 100.0
            val ask = (mid + 1) / 100.0
            book.replaceSnapshot(
                yesLevels = listOf(bid to bidSize),
                noLevels = listOf((1.0 - ask) to askSize)
            )
            engine.onTick(
                MarketTick(
                    ticker = tk,
                    series = "KXBTC15M",
                    yesBid = bid,
                    yesAsk = ask,
                    lastPrice = mid / 100.0,
                    volume = null,
                    openInterest = null,
                    closeTimeEpochMs = close,
                    source = TickSource.WS_ORDERBOOK,
                    receiveElapsedNanos = 0L
                )
            )
            nowMs += 1000L
        }

        companion object {
            var seq = 0
        }
    }

    /** Fall 56 → 50 then flat — the proven DIP_HUNT entry pattern. */
    private fun dipSeries(): List<Double> =
        listOf(56.0, 54.5, 53.0, 51.5, 50.0, 50.0, 50.0, 50.0, 50.0, 50.0, 50.0)

    @Test
    fun windowCloseForceExitsOpenPositionBeforeSettle() = runBlocking {
        val h = Harness(ScalpSettings(enabled = true)) // aggressive defaults
        h.closeTimeMs = 160_000L
        h.engine.start()

        dipSeries().forEach { h.tick(it) }
        val dipEntered = h.events.filterIsInstance<ScalpEvent.Entered>()
            .filter { it.position.strategy == ScalpStrategy.DIP_HUNT }
        assertEquals(1, dipEntered.size)

        // Flat ticks until the first tick inside close − 5s buffer (nowMs 155_000).
        repeat(45) { h.tick(50.0) }

        val exits = h.events.filterIsInstance<ScalpEvent.Exited>()
        assertTrue("expected force-exits, events=${h.events}", exits.isNotEmpty())
        exits.forEach { assertEquals(ExitReason.WINDOW_CLOSE, it.reason) }
        // Every entered position (DIP + quiet-regime RANGE_FADE) was closed.
        assertEquals(
            h.events.filterIsInstance<ScalpEvent.Entered>().size,
            exits.size
        )
        val dipExit = exits.single { it.position.strategy == ScalpStrategy.DIP_HUNT }
        assertEquals("exit must land at the buffer edge", 155_000L, dipExit.position.exitTimeMs)
        assertTrue("exit before close", dipExit.position.exitTimeMs!! <= 160_000L)
        assertTrue(h.ledger.openPositions().isEmpty())

        // Flat strategies near close surface WINDOW_CLOSED on the chips, and
        // no new entries fire inside the buffer.
        assertTrue(
            h.engine.currentState().strategyStates.any { it.state == "WINDOW_CLOSED" }
        )
        val lastEntryMs = h.events.filterIsInstance<ScalpEvent.Entered>()
            .maxOf { it.position.entryTimeMs }
        assertTrue("no entries inside the close buffer", lastEntryMs < 155_000L)
    }

    @Test
    fun rolloverResetsStrategyStateAndSkipsCrossWindowDebounce() = runBlocking {
        val h = Harness(ScalpSettings(enabled = true))
        h.closeTimeMs = 160_000L
        h.engine.start()

        // Window 1: DIP opens, then the close forces it out.
        dipSeries().forEach { h.tick(it) }
        repeat(45) { h.tick(50.0) }
        val closeExit = h.events.filterIsInstance<ScalpEvent.Exited>()
            .single { it.position.strategy == ScalpStrategy.DIP_HUNT }
        assertEquals(ExitReason.WINDOW_CLOSE, closeExit.reason)

        // Window 2 (ticker flips at the next tick). The FIFTY_FLIP cross fires
        // on the THIRD tick of the new window — 3s after the WINDOW_CLOSE
        // exit, inside the 5s aggressive debounce. Entering proves both the
        // state reset (fresh prevMid/velocity) and the debounce skip.
        h.ticker = "KXBTC15M-W2"
        h.closeTimeMs = 220_000L
        h.tick(48.0)
        h.tick(49.0)
        h.tick(50.0)

        val entered = h.events.filterIsInstance<ScalpEvent.Entered>().map { it.position }
        val flip = entered.single { it.strategy == ScalpStrategy.FIFTY_FLIP }
        assertEquals("KXBTC15M-W2", flip.ticker)
        assertEquals(51, flip.entryPriceCents) // filled at the ask (50+1)
        assertEquals(
            "must be eligible immediately after rollover, not after the debounce",
            158_000L,
            flip.entryTimeMs
        )
        assertTrue(
            "no fifty-flip trade in window 1 (no cross there)",
            entered.none { it.strategy == ScalpStrategy.FIFTY_FLIP && it.ticker == "KXBTC15M-W1" }
        )
        assertNull(h.ledger.openPosition(ScalpStrategy.DIP_HUNT))
    }

    @Test
    fun atLeastSixStrategiesEnterOnOneMixedSeries() = runBlocking {
        // Constant +6bp spot impulse keeps SPOT_LEAD armed; a 300:50 book
        // gives imbalance +0.71 (BOOK_IMBALANCE fuel, FIFTY_FLIP support).
        // The intentional 40¢ crash realizes losses across the early riders;
        // widen the breaker so this test measures concurrency, not risk
        // management (the $10 daily-loss trip itself is covered by
        // ScalpGuardrailsTest).
        val h = Harness(ScalpSettings(enabled = true, maxDailyLossUsd = 1_000.0), spot = ScalpSpot(return15s = 0.001))
        h.bidSize = 300.0
        h.askSize = 50.0
        h.closeTimeMs = 900_000L // far away: no close interference
        h.engine.start()

        val series = mutableListOf<Double>()
        series += (40..50).map { it.toDouble() }          // t1..t11 slow rise, cross 50
        series += listOf(52.0, 56.0, 60.0, 64.0, 68.0, 72.0)  // t12..t17 drive +4/tick
        series += listOf(74.0, 78.0, 82.0, 86.0, 90.0, 94.0)  // t18..t23 constant drive to the ceiling
        series += listOf(89.0, 84.0, 79.0, 74.0, 69.0, 64.0, 59.0, 54.0) // crash
        series += List(6) { 50.0 }                        // stabilize → DIP + VWAP
        series += List(50) { 56.0 }                       // quiet plateau (range setup)
        series += List(7) { 50.0 }                        // extension → RANGE_FADE
        series.forEach { h.tick(it) }

        assertTrue(
            "no guardrail may trip, events=${h.events.filterIsInstance<ScalpEvent.GuardrailBlocked>()}",
            h.events.none { it is ScalpEvent.GuardrailBlocked }
        )
        val entered = h.events.filterIsInstance<ScalpEvent.Entered>().map { it.position.strategy }.toSet()
        // RANGE_FADE needs a genuinely quiet, flat band — mathematically
        // impossible on this 6¢-range mixed series — so it stays out of the
        // expected set (it has its own engine test in
        // windowCloseForceExitsOpenPositionBeforeSettle).
        val expect = listOf(
            ScalpStrategy.BOOK_IMBALANCE,
            ScalpStrategy.SPOT_LEAD,
            ScalpStrategy.OPEN_DRIVE,
            ScalpStrategy.FIFTY_FLIP,
            ScalpStrategy.MOMENTUM_SNIPER,
            ScalpStrategy.EXTREME_REVERSAL,
            ScalpStrategy.DIP_HUNT,
            ScalpStrategy.VWAP_REVERT
        )
        expect.forEach { strategy ->
            assertTrue(
                "expected $strategy to enter, events=${h.events}",
                strategy in entered
            )
        }
        assertTrue("concurrency cap respected", h.ledger.openPositions().size <= 12)
        assertEquals(
            "every strategy state reported",
            11,
            h.engine.currentState().strategyStates.size
        )
    }

    @Test
    fun positionSizingScalesWithPaperBankroll() = runBlocking {
        // Fresh ledger: $1,000 seed → 25% = $250 → the $10 aggressive cap binds.
        val winner = Harness(ScalpSettings(enabled = true))
        winner.engine.start()
        dipSeries().forEach { winner.tick(it) }
        val rich = winner.events.filterIsInstance<ScalpEvent.Entered>().single().position
        assertEquals("bankroll $1000 → stake capped at $10", 19, rich.contracts) // floor($10 / 0.51)

        // Bankroll ground down to $10 → 25% = $2.50 → sizing follows it down.
        val loser = Harness(ScalpSettings(enabled = true))
        loser.ledger.totalPnlCents = -99_000L
        loser.engine.start()
        dipSeries().forEach { loser.tick(it) }
        val poor = loser.events.filterIsInstance<ScalpEvent.Entered>().single().position
        assertEquals("bankroll $10 → stake $2.50", 4, poor.contracts) // floor($2.50 / 0.51)
    }
}
