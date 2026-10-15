package com.dirk.kalshiodds.signal.scalp

import com.dirk.kalshiodds.signal.engine.LocalOrderBook
import com.dirk.kalshiodds.signal.model.MarketTick
import com.dirk.kalshiodds.signal.model.TickSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Engine-level enforcement of the two hard rules the owner asked to verify:
 *
 * 1. UNIVERSAL ENTRY GATE — no strategy may enter while mid > 50¢ (the
 *    [ScalpSettings.maxEntryCents] gate), in aggressive AND single-strategy
 *    mode. Exits are unaffected.
 * 2. STOP-LOSS AUDIT — for a representative sample of strategies (7, incl.
 *    3 of the 9 new ones), a post-entry drop of stopLossPp+1¢ produces an
 *    [ExitReason.STOP] exit.
 */
class ScalpEngineGateTest {

    private class Harness(
        settings: ScalpSettings,
        spot: ScalpSpot? = null,
        var bidSize: Double = 100.0,
        var askSize: Double = 100.0,
        var askOffsetCents: Int = 1
    ) {
        val ledger = object : ScalpLedger {
            val positions = mutableListOf<ScalpPosition>()
            override fun hydrateFromDisk() {}
            override fun openPosition(): ScalpPosition? =
                positions.firstOrNull { it.status == ScalpPositionStatus.OPEN }
            override fun openPosition(strategy: ScalpStrategy): ScalpPosition? =
                openPositions().firstOrNull { it.strategy == strategy }
            override fun openPositions(): List<ScalpPosition> =
                positions.filter { it.status == ScalpPositionStatus.OPEN }
            override fun entriesSince(nowMs: Long, windowMs: Long): Int =
                positions.count { it.status == ScalpPositionStatus.OPEN || it.entryTimeMs >= nowMs - windowMs }
            override fun realizedPnlCentsToday(nowMs: Long): Long =
                positions.mapNotNull { it.pnlCents }.sum().toLong()
            override fun realizedPnlCentsTotal(): Long = realizedPnlCentsToday(nowMs)
            override fun recordEnter(p: ScalpPosition, f: Int, id: String?): ScalpPosition {
                val stored = p.copy(entryFeeCents = f, clientOrderId = id)
                positions += stored
                return stored
            }
            override fun recordExit(id: String, px: Int, t: Long, r: ExitReason, pnl: Int, fee: Int) {
                val i = positions.indexOfFirst { it.id == id }
                if (i < 0) return
                positions[i] = positions[i].copy(
                    status = ScalpPositionStatus.CLOSED,
                    exitPriceCents = px,
                    exitTimeMs = t,
                    exitReason = r,
                    pnlCents = pnl,
                    exitFeeCents = fee
                )
            }
            override fun attachOrderIds(positionId: String, clientOrderId: String, orderId: String?) {}
        }
        val book = LocalOrderBook()
        val events = mutableListOf<ScalpEvent>()
        var nowMs = 100_000L
        val engine: ScalpEngine

        init {
            val paper = PaperScalpExecutor(bookProvider = { book }, idFactory = { "gate-${++seq}" })
            engine = ScalpEngine(
                settings = MutableStateFlow(settings),
                bookProvider = { book },
                paperExecutor = paper,
                liveExecutor = paper,
                store = ledger,
                scope = CoroutineScope(Dispatchers.Unconfined),
                now = { nowMs },
                onEvent = { events += it },
                spotProvider = { spot }
            )
        }

        fun tick(mid: Double) {
            val bid = (mid - 1) / 100.0
            val ask = (mid + askOffsetCents) / 100.0
            book.replaceSnapshot(
                yesLevels = listOf(bid to bidSize),
                noLevels = listOf((1.0 - ask) to askSize)
            )
            engine.onTick(
                MarketTick(
                    ticker = "KXBTC15M-GATE",
                    series = "KXBTC15M",
                    yesBid = bid,
                    yesAsk = ask,
                    lastPrice = mid / 100.0,
                    volume = null,
                    openInterest = null,
                    closeTimeEpochMs = 900_000L, // far away: no close interference
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

    // ---- Universal 50c entry gate -------------------------------------------

    @Test
    fun noStrategyEntersAboveTheGate() = runBlocking {
        // Bid-heavy book + hot spot: every fuel line is open — only the gate
        // can keep the roster out.
        val h = Harness(ScalpSettings(enabled = true), spot = ScalpSpot(return15s = 0.001))
        h.bidSize = 300.0
        h.askSize = 50.0
        h.engine.start()

        val series = mutableListOf<Double>()
        series += (51..58).map { it.toDouble() }                 // slow rise, entirely above the gate
        series += listOf(60.0, 64.0, 68.0, 72.0)                 // momentum-style drive
        series += listOf(67.0, 62.0, 57.0, 52.0)                 // crash back toward (but not through) 50
        series += List(12) { 52.0 }                              // textbook DIP pattern… at 52¢
        series.forEach { h.tick(it) }

        assertTrue(
            "gate must block every entry above 50c, events=${h.events}",
            h.events.filterIsInstance<ScalpEvent.Entered>().isEmpty()
        )
        assertEquals(
            "full roster keeps computing under the gate",
            ScalpStrategy.values().size,
            h.engine.currentState().strategyStates.size
        )
    }

    @Test
    fun entriesResumeAtOrBelowTheGate() = runBlocking {
        val h = Harness(ScalpSettings(enabled = true))
        h.engine.start()
        // The same fall that was gated at 52¢ enters the moment mid hits 50¢.
        listOf(56.0, 54.5, 53.0, 51.5, 50.0, 50.0, 50.0, 50.0, 50.0, 50.0, 50.0)
            .forEach { h.tick(it) }
        val entered = h.events.filterIsInstance<ScalpEvent.Entered>()
        assertTrue("dip at/below the gate must enter, events=${h.events}", entered.isNotEmpty())
        assertEquals(ScalpStrategy.DIP_HUNT, entered.first().position.strategy)
        assertEquals(51, entered.first().position.entryPriceCents) // filled at the ask (50+1)
    }

    @Test
    fun gateAppliesInSingleStrategyModeToo() = runBlocking {
        // aggressive = false: the conservative DIP_HUNT profile is gated by
        // the same shared check, not just the swarm roster. DIP threshold 2¢
        // (the conservative declared default is 5¢ — deeper than either
        // synthetic dip below).
        val above = Harness(
            ScalpSettings(enabled = true, aggressive = false, dipMinDropPp = 2.0)
        )
        above.engine.start()
        listOf(56.0, 54.5, 53.0, 52.0, 52.0, 52.0, 52.0, 52.0, 52.0, 52.0, 52.0)
            .forEach { above.tick(it) }
        assertTrue(
            "conservative mode must also respect the gate, events=${above.events}",
            above.events.filterIsInstance<ScalpEvent.Entered>().isEmpty()
        )

        val below = Harness(
            ScalpSettings(enabled = true, aggressive = false, dipMinDropPp = 2.0)
        )
        below.engine.start()
        listOf(56.0, 54.5, 53.0, 51.5, 50.0, 50.0, 50.0, 50.0, 50.0, 50.0, 50.0)
            .forEach { below.tick(it) }
        assertEquals(
            "dip at 50c enters in single-strategy mode too",
            1,
            below.events.filterIsInstance<ScalpEvent.Entered>().size
        )
    }

    // ---- Stop-loss audit: synthetic SL+1c drop → ExitReason.STOP ------------

    private class StopCase(
        val strategy: ScalpStrategy,
        val spot: ScalpSpot? = null,
        val askOffset: Int = 1,
        val setup: List<Double>,
        val crashTo: Double
    )

    private fun stopCases(): List<StopCase> = listOf(
        // The proven DIP pattern: 56 → 50 then flat.
        StopCase(
            strategy = ScalpStrategy.DIP_HUNT,
            setup = listOf(56.0, 54.5, 53.0, 51.5, 50.0, 50.0, 50.0, 50.0, 50.0, 50.0, 50.0),
            crashTo = 42.0
        ),
        // Stretched below the rolling VWAP: long plateau, then a stepped drop
        // slow enough to pass the tape guard on the last leg.
        StopCase(
            strategy = ScalpStrategy.VWAP_REVERT,
            setup = List(20) { 52.0 } + listOf(51.0, 50.0, 49.0) + List(5) { 48.0 },
            crashTo = 42.0
        ),
        // NEW — capitulation: crash, sit at the low, first uptick, then stop.
        StopCase(
            strategy = ScalpStrategy.CAPITULATION_FADE,
            setup = listOf(56.0, 54.5, 53.0, 51.5, 50.0, 50.0, 50.0) +
                listOf(49.0, 48.0, 47.0) + List(6) { 47.0 } + listOf(48.0),
            crashTo = 40.0
        ),
        // NEW — Kalshi panic vs flat spot (keep the spot impulse hot so the
        // MOMENTUM_FADE exit can't preempt the stop).
        StopCase(
            strategy = ScalpStrategy.SPOT_DIVERGENCE,
            spot = ScalpSpot(return15s = 0.0006, return1m = 0.0006),
            setup = (44..54).map { it.toDouble() } + listOf(53.0, 52.0, 51.0, 50.0) + List(8) { 50.0 },
            crashTo = 44.0
        ),
        // NEW — 100s of −0.1¢/s bleed, then the collapse.
        StopCase(
            strategy = ScalpStrategy.SLOW_BLEED_FADE,
            setup = (0 until 100).map { 50.0 - 0.1 * it },
            crashTo = 33.0
        ),
        // NEW — two lows (40 then 41): single-print crashes so the second-low
        // PRINT is the higher low (a granular descent would leave a trail of
        // prints between the two bottoms and secondLowPp would read 40.5).
        // The long flat at 41 clears DOUBLE_DIP's 30-sample minimum.
        StopCase(
            strategy = ScalpStrategy.DOUBLE_DIP,
            setup = listOf(48.0, 40.0) +                       // dip 1: straight to 40
                listOf(41.0, 42.0, 43.0, 44.0, 45.0) +         // rise
                listOf(44.0, 41.0) + List(22) { 41.0 },        // dip 2 → 41, hold
            crashTo = 34.0
        ),
        // NEW — spread widens to 3¢ at the low, first lift, then collapse.
        StopCase(
            strategy = ScalpStrategy.VOLUME_DRYUP,
            askOffset = 2, // spread = ask − bid = 3¢
            setup = listOf(56.0, 54.0, 52.0, 50.0, 48.0, 47.0, 46.0) + List(8) { 46.0 } + listOf(47.0),
            crashTo = 39.0
        )
    )

    @Test
    fun stopLossCutsEverySampledStrategy() = runBlocking {
        // 7 strategies ≥ 6 required, incl. 5 of the new nine. Each gets a
        // fresh engine, enters on its synthetic pattern, then the tape drops
        // stopLossPp+1¢ — the exit reason must be STOP.
        for (case in stopCases()) {
            val h = Harness(ScalpSettings(enabled = true), spot = case.spot)
            h.askOffsetCents = case.askOffset
            h.engine.start()

            case.setup.forEach { h.tick(it) }
            val entered = h.events.filterIsInstance<ScalpEvent.Entered>()
                .filter { it.position.strategy == case.strategy }
            assertTrue("${case.strategy} must enter on its pattern, events=${h.events}", entered.isNotEmpty())
            val entry = entered.first().position
            val slPp = case.strategy.defaultStopLossPp
            assertTrue(
                "${case.strategy}: entry ${entry.entryPriceCents}c must clear the ${slPp}c stop room",
                entry.entryPriceCents - slPp >= 1
            )

            repeat(5) { h.tick(case.crashTo) }
            val stop = h.events.filterIsInstance<ScalpEvent.Exited>()
                .filter { it.position.strategy == case.strategy }
                .firstOrNull { it.reason == ExitReason.STOP }
            assertNotNull(
                "${case.strategy}: a drop of ${slPp.toInt()}+1c must exit STOP, " +
                    "exits=${h.events.filterIsInstance<ScalpEvent.Exited>()}",
                stop
            )
            assertTrue(stop!!.pnlCents < 0)
        }
    }
}
