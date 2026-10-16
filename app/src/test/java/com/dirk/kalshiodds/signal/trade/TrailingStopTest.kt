package com.dirk.kalshiodds.signal.trade

import com.dirk.kalshiodds.signal.paper.PaperBook
import com.dirk.kalshiodds.signal.paper.PaperBookState
import com.dirk.kalshiodds.signal.paper.PaperFill
import com.dirk.kalshiodds.signal.paper.PaperPriceObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrailingStopTest {
    private val ticker = "KXBTC15M-26SEP251500-00"

    private fun fill(
        side: String = "YES",
        entry: Double = 0.40,
        contracts: Int = 10,
        created: Long = 1_000L,
        peak: Double? = null,
        close: Long? = null,
        entryFee: Double = 0.0,
        history: List<PaperPriceObservation> = emptyList()
    ) = PaperFill(
        id = "fill-1",
        ticker = ticker,
        side = side,
        stakeUsd = entry * contracts,
        contracts = contracts,
        limitPrice = entry,
        source = "test",
        createdAtMs = created,
        note = "test",
        entryFeeUsd = entryFee,
        peakPrice = peak,
        closeTimeMs = close,
        quoteHistory = history
    )

    private fun manager(
        state: PaperBookState,
        trailing: Double = 2.0,
        activation: Double = 0.5,
        lossDelay: Long = 30_000L,
        maxHold: Long = 12 * 60_000L,
        settlementFallback: Long = 30_000L,
        maxAge: Long = 5_000L,
        enabled: Boolean = true
    ) = state.getTrailingStopManager(
        trailingStopPct = trailing,
        trailActivationPct = activation,
        stopLossDelayMs = lossDelay,
        maxHoldMs = maxHold,
        settlementFallbackMs = settlementFallback,
        maxQuoteAgeMs = maxAge,
        enabled = enabled
    )

    @Test
    fun `default trail is percentage of actual entry and is bounded`() {
        val config = PaperBookState().getTrailingStopManager().getConfig()
        assertEquals(2.0, config.trailingStopPct, 0.0001)
        assertEquals(3.0, config.stopLossPct, 0.0001)
        assertTrue(config.trailingStopPct in 1.0..4.0)
        assertTrue(config.enabled)
    }

    @Test
    fun `small dip before activation does not auto exit`() {
        val f = fill(entry = 0.40)
        val state = manager(PaperBookState(fills = listOf(f))).calculatePositionState(f, 0.399, 2_000L)
        assertFalse(state.trailActivated)
        assertEquals(null, state.trailingStopPrice)
        assertFalse(state.stopHit)
    }

    @Test
    fun `trailing distance uses filled buy in and retained peak`() {
        val f = fill(entry = 0.40, peak = 0.50)
        val state = manager(PaperBookState(fills = listOf(f))).calculatePositionState(f, 0.49, 2_000L)
        assertTrue(state.trailActivated)
        assertEquals(0.50, state.peakPrice!!, 0.0001)
        assertEquals(0.492, state.trailingStopPrice!!, 0.0001)
        assertTrue(state.stopHit)
        assertEquals("TRAILING STOP", state.stopType)
    }

    @Test
    fun `stop hit boolean includes trailing hit even before delayed stop loss`() {
        val f = fill(entry = 0.40, peak = 0.50, created = 1_000L)
        val state = manager(
            PaperBookState(fills = listOf(f)),
            lossDelay = 30_000L
        ).calculatePositionState(f, 0.49, 2_000L)
        assertEquals(null, state.stopLossPrice)
        assertTrue(state.stopHit)
        assertEquals("TRAILING STOP", state.stopType)
    }

    @Test
    fun `NO position uses the executable NO bid rather than inverted YES`() {
        val f = fill(side = "NO", entry = 0.40, peak = 0.50)
        val state = manager(PaperBookState(fills = listOf(f))).calculatePositionState(f, 0.49, 2_000L)
        assertEquals(0.492, state.trailingStopPrice!!, 0.0001)
        assertTrue(state.stopHit)
    }

    @Test
    fun `stop loss waits for delay and then uses entry price`() {
        val f = fill(entry = 0.40, created = 1_000L)
        val stop = manager(PaperBookState(fills = listOf(f)), lossDelay = 10_000L)
        assertFalse(stop.calculatePositionState(f, 0.38, 5_000L).stopHit)
        val delayed = stop.calculatePositionState(f, 0.38, 11_000L)
        assertEquals(0.388, delayed.stopLossPrice!!, 0.0001)
        assertFalse(delayed.stopHit)
        assertEquals(null, delayed.stopType)
    }

    @Test
    fun `below entry dip after tiny peak does not trigger profit trail`() {
        val f = fill(entry = 0.40, peak = 0.402, created = 1_000L)
        val stop = manager(PaperBookState(fills = listOf(f)), lossDelay = 10_000L)
        val state = stop.calculatePositionState(f, 0.394, 2_000L)
        assertTrue(state.trailActivated)
        assertEquals(0.394, state.trailingStopPrice!!, 0.0001)
        assertFalse(state.stopHit)
    }

    @Test
    fun `below entry exit is reserved for explicit time fallback`() {
        val f = fill(entry = 0.40, created = 1_000L)
        val stop = manager(
            PaperBookState(fills = listOf(f)),
            lossDelay = 10_000L,
            maxHold = 12_000L
        )
        val state = stop.calculatePositionState(f, 0.38, 13_000L)
        assertTrue(state.timeFallback)
        assertTrue(state.stopHit)
        assertEquals("TIME FALLBACK", state.stopType)
    }

    @Test
    fun `transient below-entry dip with rebound does not trigger early risk exit`() {
        val f = fill(
            entry = 0.40,
            close = 100_000L,
            history = listOf(
                PaperPriceObservation(0.390, 7_000L),
                PaperPriceObservation(0.395, 8_000L)
            )
        )
        val state = manager(
            PaperBookState(fills = listOf(f)),
            maxHold = Long.MAX_VALUE,
            settlementFallback = 30_000L
        ).calculatePositionState(f, 0.394, 9_000L)
        assertFalse(state.timeFallback)
        assertFalse(state.earlyLossHit)
        assertFalse(state.stopHit)
    }

    @Test
    fun `sustained decline near close permits bounded early risk exit`() {
        val f = fill(
            entry = 0.40,
            close = 100_000L,
            history = listOf(
                PaperPriceObservation(0.398, 7_000L),
                PaperPriceObservation(0.392, 8_000L)
            )
        )
        val state = manager(
            PaperBookState(fills = listOf(f)),
            maxHold = Long.MAX_VALUE,
            settlementFallback = 30_000L
        ).calculatePositionState(f, 0.386, 9_000L)
        assertFalse(state.timeFallback)
        assertTrue(state.earlyLossHit)
        assertTrue(state.stopHit)
        assertEquals("TREND RISK EXIT", state.stopType)
    }

    @Test
    fun `peak is persisted across quote checks`() {
        val f = fill(entry = 0.40, peak = 0.40)
        val book = PaperBook(PaperBookState(cashUsd = 96.0, fills = listOf(f)))
        val stops = StopOrderManager(
            book,
            StopOrderManager.StopConfig(
                trailingStopPct = 2.0,
                trailActivationPct = 0.5,
                stopLossDelayMs = Long.MAX_VALUE,
                maxHoldMs = Long.MAX_VALUE
            )
        )
        assertTrue(stops.checkAndExecute(mapOf(ticker to PaperExitQuote(0.50, 10, 2_000L, "book", "r1")), 2_000L).isEmpty())
        assertEquals(0.50, book.snapshot().fills.first().peakPrice!!, 0.0001)
        val execution = stops.checkAndExecute(mapOf(ticker to PaperExitQuote(0.49, 10, 3_000L, "book", "r2")), 3_000L)
        assertEquals(1, execution.size)
        assertEquals(10, execution.single().contracts)
    }

    @Test
    fun `stop manager respects executable depth and records fees`() {
        val f = fill(entry = 0.40, contracts = 10, peak = 0.50, entryFee = 0.01)
        val book = PaperBook(PaperBookState(cashUsd = 95.99, fills = listOf(f)))
        val manager = StopOrderManager(
            book,
            StopOrderManager.StopConfig(
                trailingStopPct = 2.0,
                trailActivationPct = 0.5,
                stopLossDelayMs = Long.MAX_VALUE,
                maxHoldMs = Long.MAX_VALUE
            )
        )
        val result = manager.checkAndExecute(
            mapOf(ticker to PaperExitQuote(0.49, availableContracts = 2, observedAtMs = 2_000L, sourceSnapshotId = "r1")),
            2_000L
        )
        assertEquals(1, result.size)
        assertEquals(2, result.single().contracts)
        assertTrue(result.single().exitFeeUsd > 0.0)
        assertEquals(8, book.snapshot().fills.count { !it.settled }.let { book.snapshot().fills.first { !it.settled }.contracts })
        assertTrue(book.snapshot().fills.any { it.settled && it.pnlUsd != null })
    }

    @Test
    fun `stale quote is ignored`() {
        val f = fill(entry = 0.40, peak = 0.50)
        val m = manager(PaperBookState(fills = listOf(f)), maxAge = 1_000L)
        val orders = m.checkPositions(mapOf(ticker to PaperExitQuote(0.49, 10, 1_000L)), 2_500L)
        assertTrue(orders.isEmpty())
    }

    @Test
    fun `close fallback exits rather than holding to settlement`() {
        val f = fill(entry = 0.40, created = 1_000L, close = 10_000L)
        val m = manager(PaperBookState(fills = listOf(f)), lossDelay = Long.MAX_VALUE, settlementFallback = 2_000L)
        val orders = m.checkPositions(mapOf(ticker to PaperExitQuote(0.39, 10, 8_500L)), 8_500L)
        assertEquals(1, orders.size)
        assertTrue(orders.single().isTimeFallback)
    }

    @Test
    fun `disabled manager does not execute`() {
        val f = fill(entry = 0.40, peak = 0.50)
        val m = manager(PaperBookState(fills = listOf(f)), enabled = false)
        assertTrue(m.checkPositions(mapOf(ticker to PaperExitQuote(0.49)), 2_000L).isEmpty())
        assertFalse(m.enabled())
    }
}
