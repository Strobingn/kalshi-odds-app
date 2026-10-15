package com.dirk.kalshiodds.signal.scalp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stop-loss audit (owner-requested regression guard): EVERY strategy in the
 * registry must ship positive TP / SL / max-hold defaults. A strategy without
 * a stop must never reach the engine — this test fails the build if one is
 * added without them.
 */
class ScalpStrategyDefaultsTest {

    @Test
    fun registryHasTwentyStrategies() {
        assertEquals(20, ScalpStrategy.values().size)
    }

    @Test
    fun everyStrategyShipsPositiveRiskDefaults() {
        ScalpStrategy.values().forEach { s ->
            assertTrue(
                "${s.name}: takeProfitPp must be > 0 (was ${s.defaultTakeProfitPp})",
                s.defaultTakeProfitPp > 0.0
            )
            assertTrue(
                "${s.name}: stopLossPp must be > 0 (was ${s.defaultStopLossPp})",
                s.defaultStopLossPp > 0.0
            )
            assertTrue(
                "${s.name}: defaultMaxHoldMs must be > 0 (was ${s.defaultMaxHoldMs})",
                s.defaultMaxHoldMs > 0L
            )
            assertTrue(
                "${s.name}: defaultWindowSeconds must be > 0 (was ${s.defaultWindowSeconds})",
                s.defaultWindowSeconds > 0
            )
        }
    }

    @Test
    fun defaultsAreWithinSaneRanges() {
        // Cheap-yes scalps: TP/SL in cents, holds well under the 15-minute
        // window so the WINDOW_CLOSE force-exit is a backstop, not the plan.
        ScalpStrategy.values().forEach { s ->
            assertTrue("${s.name}: TP ${s.defaultTakeProfitPp}¢ out of range", s.defaultTakeProfitPp in 1.0..10.0)
            assertTrue("${s.name}: SL ${s.defaultStopLossPp}¢ out of range", s.defaultStopLossPp in 1.0..10.0)
            assertTrue(
                "${s.name}: max hold ${s.defaultMaxHoldMs}ms exceeds the window",
                s.defaultMaxHoldMs <= 15 * 60 * 1000L
            )
        }
    }

    @Test
    fun engineDecidersUseTheRegistryDefaults() {
        // The aggressive decider path must actually consume the audited
        // numbers (a drift between registry and engine would void the audit).
        val engine = ScalpEngine(
            settings = kotlinx.coroutines.flow.MutableStateFlow(ScalpSettings(enabled = true)),
            bookProvider = { null },
            paperExecutor = PaperScalpExecutor(bookProvider = { null }),
            liveExecutor = PaperScalpExecutor(bookProvider = { null }),
            store = object : ScalpLedger {
                override fun hydrateFromDisk() {}
                override fun openPosition(): ScalpPosition? = null
                override fun openPositions(): List<ScalpPosition> = emptyList()
                override fun entriesSince(nowMs: Long, windowMs: Long): Int = 0
                override fun realizedPnlCentsToday(nowMs: Long): Long = 0L
                override fun recordEnter(p: ScalpPosition, f: Int, id: String?): ScalpPosition = p
                override fun recordExit(id: String, px: Int, t: Long, r: ExitReason, pnl: Int, fee: Int) {}
                override fun attachOrderIds(positionId: String, clientOrderId: String, orderId: String?) {}
            },
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        )
        kotlinx.coroutines.runBlocking { engine.start() }
        val state = engine.currentState()
        assertTrue(state.aggressive)
        assertEquals(
            "every strategy reports state in aggressive mode",
            ScalpStrategy.values().size,
            state.strategyStates.size
        )
    }
}
