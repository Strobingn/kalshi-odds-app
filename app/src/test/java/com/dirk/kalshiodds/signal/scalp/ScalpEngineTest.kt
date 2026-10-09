package com.dirk.kalshiodds.signal.scalp

import com.dirk.kalshiodds.signal.engine.LocalOrderBook
import com.dirk.kalshiodds.signal.model.MarketTick
import com.dirk.kalshiodds.signal.model.TickSource
import com.dirk.kalshiodds.signal.trade.KalshiFee
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory [ScalpLedger] — the engine test never touches SQLite. */
private class FakeLedger : ScalpLedger {
    val positions = mutableListOf<ScalpPosition>()
    private var seq = 0L

    override fun hydrateFromDisk() {}

    override fun openPosition(): ScalpPosition? = positions.firstOrNull { it.status == ScalpPositionStatus.OPEN }

    override fun openPositions(): List<ScalpPosition> = positions.filter { it.status == ScalpPositionStatus.OPEN }

    override fun entriesSince(nowMs: Long, windowMs: Long): Int =
        positions.count { it.status == ScalpPositionStatus.OPEN || it.entryTimeMs >= nowMs - windowMs }

    override fun realizedPnlCentsToday(nowMs: Long): Long =
        positions.mapNotNull { it.pnlCents }.sum().toLong()

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
 * End-to-end smoke test: synthetic tick series through a real
 * [LocalOrderBook] and the real [PaperScalpExecutor] — dip → enter at the
 * ask → bounce → exit at the bid with taker fees netted out.
 */
class ScalpEngineTest {

    private class Harness(settings: ScalpSettings) {
        val ledger = FakeLedger()
        val book = LocalOrderBook()
        val events = mutableListOf<ScalpEvent>()
        var nowMs = 100_000L
        val settingsFlow = MutableStateFlow(settings)
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val engine: ScalpEngine

        init {
            val paper = PaperScalpExecutor(
                bookProvider = { book },
                idFactory = { "test-${++seq}" }
            )
            engine = ScalpEngine(
                settings = settingsFlow,
                bookProvider = { book },
                paperExecutor = paper,
                liveExecutor = paper, // never selected: liveMode = false
                store = ledger,
                scope = scope,
                now = { nowMs },
                onEvent = { events += it }
            )
        }

        fun tick(midCents: Int) {
            val bid = (midCents - 1) / 100.0
            val ask = (midCents + 1) / 100.0
            book.replaceSnapshot(
                yesLevels = listOf(bid to 100.0),
                noLevels = listOf((1.0 - ask) to 100.0)
            )
            engine.onTick(
                MarketTick(
                    ticker = "KXBTC15M-TEST",
                    series = "KXBTC15M",
                    yesBid = bid,
                    yesAsk = ask,
                    lastPrice = midCents / 100.0,
                    volume = null,
                    openInterest = null,
                    closeTimeEpochMs = null,
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

    private fun enabledSettings() = ScalpSettings(
        enabled = true,
        liveMode = false,
        maxStakeUsd = 5.0,
        // 5¢ target: after Kalshi taker fees on both legs (~3.5¢/ct at 45¢)
        // a 3¢ bounce is a guaranteed loser — the target must clear fees.
        takeProfitPp = 5.0,
        stopLossPp = 4.0,
        maxHoldMs = 600_000L,
        dipMinDropPp = 2.0,
        windowSeconds = 60
    )

    @Test
    fun dipEnterBounceExitRoundTripWithFees() = runBlocking {
        val h = Harness(enabledSettings())
        h.engine.start()

        // Falling leg: 50 → 44 over 12 ticks (accelerating drops).
        val fall = listOf(50, 49, 48, 47, 46, 45, 44, 44, 44, 44, 44, 44)
        fall.forEach { h.tick(it) }

        val entered = h.events.filterIsInstance<ScalpEvent.Entered>()
        assertEquals("expected one entry, events=${h.events}", 1, entered.size)
        val pos = entered.single().position
        assertEquals(45, pos.entryPriceCents)   // filled at the ask (44+1)
        assertEquals(ScalpMode.PAPER, pos.mode)
        assertEquals(11, pos.contracts)          // floor($5 / 0.45)

        // Bounce: bid must reach entry + 5 = 50 → mid 51.
        listOf(45, 46, 47, 48, 49, 50, 51).forEach { h.tick(it) }

        val exited = h.events.filterIsInstance<ScalpEvent.Exited>()
        assertEquals("expected one exit, events=${h.events}", 1, exited.size)
        val exit = exited.single()
        assertEquals(ExitReason.TARGET, exit.reason)
        assertEquals(50, exit.position.exitPriceCents)

        // P&L: 11 ct round trip 45→50 minus taker fees on both legs.
        val entryFee = kotlin.math.round(KalshiFee.total(11, 0.45) * 100.0).toInt()
        val exitFee = kotlin.math.round(KalshiFee.total(11, 0.50) * 100.0).toInt()
        val expectedPnl = (11 * 50 - exitFee) - (11 * 45 + entryFee)
        assertEquals(expectedPnl, exit.pnlCents)
        assertTrue("scalp pnl should be positive on a 3¢ bounce", exit.pnlCents > 0)

        // Ledger + state consistency.
        assertNull(h.ledger.openPosition())
        assertEquals("FLAT", h.engine.currentState().state)
        val closed = h.ledger.positions.single()
        assertEquals(ScalpPositionStatus.CLOSED, closed.status)
        assertEquals(expectedPnl, closed.pnlCents)
    }

    @Test
    fun stopLossCutsThePosition() = runBlocking {
        val h = Harness(enabledSettings())
        h.engine.start()

        listOf(50, 49, 48, 47, 46, 45, 44, 44, 44, 44, 44, 44).forEach { h.tick(it) }
        assertEquals(1, h.events.filterIsInstance<ScalpEvent.Entered>().size)

        // Slide further: bid ≤ 45 − 4 = 41 → mid 42.
        listOf(44, 43, 42).forEach { h.tick(it) }

        val exited = h.events.filterIsInstance<ScalpEvent.Exited>()
        assertEquals(1, exited.size)
        assertEquals(ExitReason.STOP, exited.single().reason)
        assertTrue(exited.single().pnlCents < 0)
        assertNull(h.ledger.openPosition())
    }

    @Test
    fun disabledEngineNeverTrades() = runBlocking {
        val h = Harness(enabledSettings().copy(enabled = false))
        h.engine.start()
        listOf(50, 49, 48, 47, 46, 45, 44, 44, 44, 44, 44, 44, 48, 49).forEach { h.tick(it) }
        assertTrue(h.events.isEmpty())
        assertEquals("FLAT", h.engine.currentState().state)
        assertTrue(h.ledger.positions.isEmpty())
    }

    @Test
    fun killSwitchBlocksEvenWhenEnabled() = runBlocking {
        val h = Harness(enabledSettings().copy(killSwitch = true))
        h.engine.start()
        listOf(50, 49, 48, 47, 46, 45, 44, 44, 44, 44, 44, 44).forEach { h.tick(it) }
        assertTrue(h.events.isEmpty())
        assertTrue(h.ledger.positions.isEmpty())
    }

    @Test
    fun restFallbackQuotesWorkWhenBookIsEmpty() = runBlocking {
        // Book provider always returns an empty book → engine must fall back
        // to the tick's yesBid/yesAsk for quotes (paper exit needs a bid, so
        // this only proves no crash + flat state; the entry uses tick ask).
        val ledger = FakeLedger()
        val book = LocalOrderBook() // stays empty
        val events = mutableListOf<ScalpEvent>()
        var nowMs = 100_000L
        val paper = PaperScalpExecutor(bookProvider = { book })
        val engine = ScalpEngine(
            settings = MutableStateFlow(enabledSettings()),
            bookProvider = { book },
            paperExecutor = paper,
            liveExecutor = paper,
            store = ledger,
            scope = CoroutineScope(Dispatchers.Unconfined),
            now = { nowMs },
            onEvent = { events += it }
        )
        runBlocking { engine.start() }
        fun tickOnly(midCents: Int) {
            engine.onTick(
                MarketTick(
                    ticker = "KXBTC15M-TEST",
                    series = "KXBTC15M",
                    yesBid = (midCents - 1) / 100.0,
                    yesAsk = (midCents + 1) / 100.0,
                    lastPrice = midCents / 100.0,
                    volume = null,
                    openInterest = null,
                    closeTimeEpochMs = null,
                    source = TickSource.REST,
                    receiveElapsedNanos = 0L
                )
            )
            nowMs += 1000L
        }
        // No crash on null-book quotes; paper entry still succeeds at the
        // tick ask (PaperScalpExecutor skips the book check when ask is null).
        listOf(50, 49, 48, 47, 46, 45, 44, 44, 44, 44, 44, 44).forEach { tickOnly(it) }
        assertEquals(1, events.filterIsInstance<ScalpEvent.Entered>().size)
        // No bid anywhere → exit cannot fill; position stays open.
        listOf(45, 46, 47, 48, 49).forEach { tickOnly(it) }
        assertEquals(0, events.filterIsInstance<ScalpEvent.Exited>().size)
        assertEquals("IN_POSITION", engine.currentState().state)
    }
}
