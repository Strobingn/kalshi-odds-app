package com.dirk.kalshiodds.decision

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.robolectric.annotation.Config

class ScalpControlsTest {
    private val tk = "KXBTC15M-26OCT122015-15"
    private val close = ScalpTicker.closeMs(tk)!!
    private val at = close - 300_000
    private fun q(now: Long, bid: Double = .39, ask: Double = .40) = ScalpRule.Quote(tk, now, close, now, bid, 100.0, ask, 100.0, 100_000.0, 100_000.0, 3e-5)
    private fun source(id: String = "source") = ScalpTrade(id, tk, "YES", ScalpState.PENDING_ENTRY, at, .4, .5, ruleVersion = "${ScalpRule.VERSION}|sf41|P")
    private fun controls() = MemoryScalpControls(ScalpControls(stopLossPercent = 20, peakTrailEnabled = false, manual = true, contracts = 3, stopCents = 6,
        armCents = 8, maxStakeUsd = 1.0, disabledStrategies = ScalpModels.V2_MODELS.map { it.code }.filter { it != "v2-sf41" }.toSet()))
    @Test fun unlimitedMeansNoHourlyOrWindowQuota() {
        val store = MemoryScalpControls()
        repeat(20000) { store.recordEntry(at) }
        assertEquals(20000, store.entries(at))
        assertNull(store.state.value.entryBlock("v2-sf41", 1000, store.entries(at), -100000.0))
        assertEquals(0, store.entries(at + 3_601_000))
    }
    @Test fun manualValuesReachFillAndStopsKeepWorkingWhilePaused() {
        val control = controls()
        val book = ScalpBook(liveExtras = true, variants = emptyList(), controls = control)
        book.importRestored(listOf(source()))
        book.onQuote(q(at + 2_000), false)
        val t = book.snapshot().first { it.variantId == "v2-sf41" }
        assertEquals(2, t.contracts) // fee-inclusive $1 cap, manual requested clip = 3
        assertEquals(.06, ScalpV2.savedStop(t), 1e-9)
        assertEquals(.08, ScalpV2.savedNumber(t.note, "v2arm", 0.0), 1e-9)
        control.save(control.state.value.copy(enabled = false, stopCents = 30))
        book.onQuote(q(at + 3_000, .33), false)
        assertEquals(ScalpState.PENDING_EXIT, book.snapshot().first { it.id == t.id }.state)
        book.onQuote(q(at + 4_000, .33), false)
        book.flushWrites()
        assertEquals(1, control.feedback("v2-sf41").trades)
        assertTrue(control.feedback("v2-sf41").recentNetPerContract < 0)
        book.flushWrites()
        assertEquals(1, control.feedback("v2-sf41").trades)
    }
    @Test fun userTakeProfitAndMaxHoldActuallyExit() {
        for (target in listOf(true, false)) {
            val control = controls().also { it.save(it.state.value.copy(takeProfitCents = if (target) 1 else 0, maxHoldSeconds = if (target) 0 else 5)) }
            val book = ScalpBook(liveExtras = true, variants = emptyList(), controls = control)
            book.importRestored(listOf(source()))
            book.onQuote(q(at + 2_000), false)
            book.onQuote(q(at + 8_000, if (target) .45 else .39), false)
            val held = book.snapshot().first { it.variantId == "v2-sf41" }
            assertEquals(ScalpState.PENDING_EXIT, held.state)
            assertTrue(held.exitReason!!.contains(if (target) "take-profit" else "max hold"))
        }
    }
    @Test fun userArmingCanExceedStopAndTrailCanExceedTenCents() {
        assertNull(ScalpV2.exit(.40, .06, .50, .47, false, 2, .02, armThreshold = .08))
        assertNull(ScalpV2.exit(.40, .06, .70, .58, true, 2, .15, armThreshold = .04))
        assertNotNull(ScalpV2.exit(.40, .06, .70, .54, true, 2, .15, armThreshold = .04))
    }
    @Test fun gatesAndDipFilterHaveDefinedBoundaries() {
        val cfg = ScalpControls(maxOpen = 2, maxTradesPerHour = 3, maxDailyLossUsd = 5.0, dipCents = 2.0)
        assertNotNull(cfg.entryBlock("x", 2, 0, 0.0))
        assertNotNull(cfg.entryBlock("x", 0, 3, 0.0))
        assertNotNull(cfg.entryBlock("x", 0, 0, -5.0))
        assertNull(cfg.entryBlock("x", 1, 2, -4.99))
        assertFalse(cfg.dipOk(emptyList(), .4))
        assertTrue(cfg.dipOk(listOf(.5, .5, .5), .45))
        assertFalse(cfg.dipOk(listOf(.4, .4, .4), .45))
    }
    @Test fun profitTargetsUseActualEntryFees() {
        val t = source().copy(state = ScalpState.OPEN, contracts = 3, entryPrice = .10, entryFeeUsd = .03)
        assertEquals((.45 - .03 - .30 - .03) / 3, ScalpV2.netPerContract(t, .15), 1e-9)
        assertNull(ScalpV2.exit(.10, .06, .20, .15, false, 3, .02, .04, netGain = .03))
    }
    @Test fun depthBudgetIncludesRoundedFeesAtEachLevel() {
        val (n, usd, fees) = ScalpControls(maxStakeUsd = .32).walkBuy(listOf(.09 to 1.0, .095 to 1.0, .1 to 10.0), 10, .1)
        assertEquals(3, n)
        assertTrue(usd + fees <= .32 + 1e-9)
        assertEquals(.03, fees, 1e-9)
        assertEquals(0.0, ScalpControls(maxStakeUsd = Double.NaN).normalized().maxStakeUsd, 0.0)
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SavedScalpControlsTest {
    @Test fun heavyTradeTrafficCoalescesDiskWritesAndKeepsEveryOutcome() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val prefs = ctx.getSharedPreferences("kashi_v2_controls", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val a = SavedScalpControls(ctx, flushDelayMs = 60_000)
        val trade = ScalpTrade("t", "KXBTC15M-X", "YES", ScalpState.CLOSED, 0, .4, .5,
            contracts = 2, netUsd = .10, closedAtMs = 10_000)
        val workers = (0..3).map { worker -> Thread {
            repeat(5000) { i ->
                a.recordEntry(10_000)
                if (i < 100) a.recordOutcome("v2-test-$worker", trade.copy(id = "$worker-$i"))
            }
        }.apply { start() } }
        workers.forEach { it.join(10_000); assertFalse(it.isAlive) }
        assertEquals(20_000, a.entries(10_000))
        assertEquals(0, a.entries(3_611_000))
        assertEquals(20_000, a.entries(10_000))
        (0..3).forEach { assertEquals(100, a.feedback("v2-test-$it").trades) }
        // The hot path updates memory, not the SharedPreferences map or lifecycle apply queue.
        assertNull(prefs.getString("entry-buckets", null))
        assertNull(prefs.getString("feedback:v2-test-0", null))
        assertTrue(a.flushPending())
        assertNotNull(prefs.getString("feedback:v2-test-0", null))
        a.close()
        val b = SavedScalpControls(ctx, flushDelayMs = 60_000)
        assertEquals(20_000, b.entries(10_000))
        (0..3).forEach { worker ->
            b.recordOutcome("v2-test-$worker", trade.copy(id = "$worker-99"))
            assertEquals(100, b.feedback("v2-test-$worker").trades)
        }
        repeat(4000) { b.recordEntry(20_000L + it * 1000L) }
        assertEquals(3601, b.entries(4_019_000))
        b.close()
    }

    @Test fun controlsCountsAndEachOutcomeSurviveReopenWithoutDuplicateLearning() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("kashi_v2_controls", Context.MODE_PRIVATE).edit().clear().commit()
        val a = SavedScalpControls(ctx)
        a.save(ScalpControls(manual = true, stopCents = 7, peakTrailCents = 4, stopLossPercent = 25, maxTradesPerHour = 0, disabledStrategies = setOf("v2-sf41")))
        a.recordEntry(10000)
        val trade = ScalpTrade("closed", "KXBTC15M-X", "YES", ScalpState.CLOSED, 0, .4, .5, contracts = 2, netUsd = .10, closedAtMs = 10000)
        a.recordOutcome("v2-sf42", trade)
        a.close()
        val b = SavedScalpControls(ctx)
        assertEquals(7, b.state.value.stopCents)
        assertTrue(b.state.value.peakTrailEnabled)
        assertEquals(4, b.state.value.peakTrailCents)
        assertEquals(25, b.state.value.stopLossPercent)
        assertFalse(b.state.value.permits("v2-sf41"))
        assertEquals(1, b.entries(10000))
        b.recordOutcome("v2-sf42", trade)
        assertEquals(1, b.feedback("v2-sf42").trades)
        assertEquals(.05, b.feedback("v2-sf42").meanNetPerContract, 1e-9)
        b.recordOutcome("v2-sf42", trade.copy(id = "second", netUsd = -.20))
        assertEquals(2, b.feedback("v2-sf42").trades)
        b.close()
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SavedOnePercentMigrationTest {
    @Test fun existingStoredTwentyPercentIsUpgradedAndLaterUserChoiceSurvivesRestart() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val prefs = ctx.getSharedPreferences("kashi_v2_controls", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("settings", """{"stopLossPercent":20,"contracts":7,"strategySettings":{"v2-sf41":{"stopLossPercent":30,"trailCents":4}}}""").commit()
        val first = SavedScalpControls(ctx)
        assertEquals(1, first.state.value.stopLossPercent)
        assertEquals(7, first.state.value.contracts)
        assertNull(first.state.value.settings("v2-sf41").stopLossPercent)
        assertEquals(4, first.state.value.settings("v2-sf41").trailCents)
        first.save(first.state.value.copy(stopLossPercent = 5))
        first.close()
        val restarted = SavedScalpControls(ctx)
        assertEquals(5, restarted.state.value.stopLossPercent)
        restarted.close()
    }
}
