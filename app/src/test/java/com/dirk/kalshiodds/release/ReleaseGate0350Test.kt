package com.dirk.kalshiodds.release

import com.dirk.kalshiodds.decision.PriceLadder
import com.dirk.kalshiodds.decision.ScalpBook
import com.dirk.kalshiodds.decision.ScalpMemory
import com.dirk.kalshiodds.decision.ScalpModels
import com.dirk.kalshiodds.decision.ScalpRule
import com.dirk.kalshiodds.decision.ScalpState
import com.dirk.kalshiodds.decision.ScalpTrade
import com.dirk.kalshiodds.signal.engine.CoinEvalConflator
import com.dirk.kalshiodds.ui.AppRoutes
import com.dirk.kalshiodds.ui.DipNav
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class ReleaseGate0350Test {
    private fun src(p: String) = listOf(File("src/main/java/com/dirk/kalshiodds/$p"), File("app/src/main/java/com/dirk/kalshiodds/$p")).first { it.exists() }.readText()

    @Test fun conflatesPerCoinNewestWinsOneJobPerCoin() {
        val d = StandardTestDispatcher()
        val scope = TestScope(d)
        val calls = mutableListOf<Pair<String, Set<String>>>()
        val c = CoinEvalConflator(scope, d, minGapMs = 150, nowNanos = { scope.testScheduler.currentTime * 1_000_000 }) { coin, t -> calls += coin to t }
        repeat(500) { c.fire("KXBTC15M-26OCT101000-00"); c.fire("KXETH15M-26OCT101000-00") }
        assertEquals(2, c.activeJobs()) // one per coin, not one per tick
        scope.advanceTimeBy(151); scope.runCurrent()
        assertEquals(2, calls.size)
        assertTrue(c.conflated >= 998)
        assertEquals(1, c.maxConcurrentPerCoin)
        // quiet → lanes stop; new event restarts
        scope.advanceTimeBy(500); scope.runCurrent()
        assertEquals(0, c.activeJobs())
        c.fire("KXSOL15M-26OCT101000-00")
        scope.advanceTimeBy(151); scope.runCurrent()
        assertEquals("SOL", calls.last().first)
    }

    @Test fun evalErrorsDoNotKillTheLane() {
        val d = StandardTestDispatcher()
        val scope = TestScope(d)
        var n = 0
        val c = CoinEvalConflator(scope, d, minGapMs = 10) { _, _ -> n++; error("boom") }
        c.fire("KXBTC15M-X"); scope.advanceTimeBy(11); scope.runCurrent()
        c.fire("KXBTC15M-X"); scope.advanceTimeBy(11); scope.runCurrent()
        assertEquals(2, n)
        assertEquals(2L, c.errors)
    }

    @Test fun dirtySetIsBounded() {
        val d = StandardTestDispatcher()
        val scope = TestScope(d)
        val c = CoinEvalConflator(scope, d, minGapMs = 1_000) { _, _ -> }
        repeat(10_000) { c.fire("KXBTC15M-T$it") }
        assertTrue(c.pendingFor("BTC") <= CoinEvalConflator.MAX_DIRTY_PER_COIN)
    }

    private val ET = ZoneId.of("America/New_York")
    private val FMT = DateTimeFormatter.ofPattern("yyMMMddHHmm", Locale.US)
    private fun ticker(coin: String, closeMs: Long) =
        "KX${coin}15M-" + ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(closeMs), ET).format(FMT).uppercase() + "-00"

    /**
     * SOAK: 30 virtual minutes of BTC/ETH/SOL ticks at 20 Hz each through the conflator into a
     * real ScalpBook. Asserts: one job per coin at a time, conflation drops stale ticks, ledger memory bounded.
     */
    @Test fun soakThirtyMinutesOfTicksStaysBoundedAndFast() {
        val d = StandardTestDispatcher()
        val scope = TestScope(d)
        val book = ScalpBook(bankrollUsd = { 20_000.0 })
        val rnd = Random(7)
        val t0 = 1_791_000_000_000L - (1_791_000_000_000L % 900_000L)
        val px = HashMap<String, Double>()
        var evalCount = 0
        val conflator = CoinEvalConflator(scope, d, minGapMs = 150, nowNanos = { scope.testScheduler.currentTime * 1_000_000 }) { _, tickers ->
            val now = t0 + scope.testScheduler.currentTime
            tickers.forEach { t ->
                val closeMs = now - (now % 900_000L) + 900_000L
                val mid = px.getOrPut(t) { 0.5 }
                book.onQuote(
                    ScalpRule.Quote(t, now, closeMs, now - 50, mid - 0.01, 50.0, mid + 0.01, 50.0, 100.0 + rnd.nextDouble(-1.0, 1.0), 100.0, 0.0005),
                    enabled = true
                )
            }
            evalCount++
        }
        val minutes = 30
        val wall0 = System.nanoTime()
        val stepMs = 50L // 20 Hz per coin
        var tick = 0L
        while (tick * stepMs < minutes * 60_000L) {
            val now = t0 + tick * stepMs
            val closeMs = now - (now % 900_000L) + 900_000L
            for (coin in listOf("BTC", "ETH", "SOL")) {
                val t = ticker(coin, closeMs)
                px[t] = ((px[t] ?: 0.5) + rnd.nextDouble(-0.02, 0.02)).coerceIn(0.05, 0.95)
                conflator.fire(t)
            }
            scope.advanceTimeBy(stepMs); scope.runCurrent()
            tick++
        }
        val wallS = (System.nanoTime() - wall0) / 1e9
        // 0.3.49 cost ~13 ms per ScalpBook eval on a desktop JVM (String.format ids, ruleVersion splits, ledger scans);
        // 0.3.50 must keep 30 min × 3 coins of evals far under real time.
        assertTrue("soak took ${wallS}s", wallS < 90.0)
        assertEquals(1, conflator.maxConcurrentPerCoin)
        assertTrue("conflated ${conflator.conflated}", conflator.conflated > conflator.events / 2)
        assertTrue(evalCount < conflator.events / 2)
        assertTrue(book.allTrades().size <= (ScalpMemory.MAX_CLOSED + ScalpMemory.MAX_NO_FILL) * 105 / 100 + 500)
        assertTrue(book.marks.value.size <= ScalpMemory.MAX_TICKERS + 3)
    }

    @Test fun memoryBoundKeepsActiveRows() {
        fun tr(i: Int, s: ScalpState) = ScalpTrade("id$i", "KXBTC15M-26OCT101000-00", "YES", s, i.toLong(), 0.5, 0.5)
        val rows = (0 until 9_000).map { tr(it, ScalpState.CLOSED) } + (0 until 3_000).map { tr(100_000 + it, ScalpState.NO_FILL) } + tr(-1, ScalpState.OPEN)
        val b = ScalpMemory.bound(rows)
        assertEquals(ScalpMemory.MAX_CLOSED + ScalpMemory.MAX_NO_FILL + 1, b.size)
        assertTrue(b.any { it.state == ScalpState.OPEN })
    }

    @Test fun pipelineIsOffMainAndConflated() {
        val vm = src("ui/OddsViewModel.kt")
        assertTrue(vm.contains("Dispatchers.Default.limitedParallelism(1)"))
        assertTrue(vm.contains("CoinEvalConflator(viewModelScope, paperLane)"))
        assertFalse(vm.contains("MarketEventDebouncer(viewModelScope)"))
        assertTrue(vm.contains("viewModelScope.launch(paperLane)"))
        // FGS start deadline: startForeground stays the first thing in onStartCommand / onCreate path (unchanged file)
        assertTrue(src("signal/service/LiveSignalsService.kt").contains("startForeground"))
    }

    @Test fun sixModelsWithSlices() {
        assertEquals(6, ScalpModels.ALL.size)
        assertEquals(ScalpModels.Model.MAKER_DIP, ScalpModels.modelOf(ScalpModels.MAKER_DIP_PARAMS))
        assertTrue(ScalpModels.MAKER_DIP_PARAMS.maker)
        assertEquals(20_000.0 / 6, ScalpModels.sliceUsd(20_000.0), 1e-9)
        assertTrue(src("decision/Scalp.kt").contains("ScalpModels.MAKER_DIP_PARAMS"))
    }

    @Test fun leaderboardAndPerWindow() {
        val fair = ScalpModels.Model.FAIR_GAP
        fun closed(id: String, w: String, net: Double, variant: String) = ScalpTrade(
            id, "KXBTC15M-$w-00", "YES", ScalpState.CLOSED, 0L, 0.4, 0.5, contracts = 10, entryPrice = 0.4, entryFeeUsd = 0.1,
            netUsd = net, closedAtMs = 1L, ruleVersion = "${ScalpRule.VERSION}|$variant|P"
        )
        val mk = ScalpModels.MAKER_DIP_PARAMS.id
        val fg = com.dirk.kalshiodds.decision.ScalpParams.seedFor("BTC").id
        val trades = listOf(closed("a", "26OCT101000", 1.0, fg), closed("b", "26OCT101000", -0.5, mk), closed("c", "26OCT101015", 2.0, mk),
            closed("s", "26OCT101015", 99.0, mk).copy(ruleVersion = "${ScalpRule.VERSION}|$mk|S"))
        val lb = ScalpModels.leaderboard(trades)
        assertEquals(6, lb.size)
        assertEquals(ScalpModels.Model.MAKER_DIP, lb.first().model)
        assertEquals(1.5, lb.first().netUsd, 1e-9) // shadow row excluded
        assertEquals(0.5, lb.first().winRate!!, 1e-9)
        assertEquals(ScalpModels.Model.MAKER_DIP, ScalpModels.best(lb))
        val w = ScalpModels.perWindow(trades)
        assertEquals(fair, w.first { it.windowKey == "26OCT101000" }.best)
        assertEquals(ScalpModels.Model.MAKER_DIP, w.first { it.windowKey == "26OCT101015" }.best)
    }

    @Test fun ladderRowsOrdersAndExits() {
        val rows = PriceLadder.rows("YES", listOf(0.40 to 10.0, 0.41 to 5.0), listOf(0.55 to 7.0), listOf(PriceLadder.Order("o1", true, 0.38, 3)))
        assertEquals(99, rows.size)
        assertEquals(99, rows.first().cents)
        assertEquals(5.0, rows.first { it.cents == 41 }.bidSize, 1e-9)
        assertEquals(7.0, rows.first { it.cents == 45 }.askSize, 1e-9)
        assertEquals(3, rows.first { it.cents == 38 }.myBuyQty)
        assertEquals(listOf("o1"), rows.first { it.cents == 38 }.myOrderIds)
        assertEquals(0.45, PriceLadder.exitPrice(0.42, 3)!!, 1e-9)
        assertEquals(0.99, PriceLadder.exitPrice(0.98, 10)!!, 1e-9)
        assertNull(PriceLadder.exitPrice(null, 3))
        assertEquals(0.40, PriceLadder.repriced(0.42, 2), 1e-9)
        assertNotNull(rows[PriceLadder.focusIndex(rows)])
    }

    @Test fun scalpTabIsABottomTabAndScalpDataStillReachable() {
        assertTrue(AppRoutes.SCALP_TAB in AppRoutes.TABS)
        assertEquals("Scalp", DipNav.tabLabel[AppRoutes.SCALP_TAB])
        assertTrue(AppRoutes.SCALP_DATA in AppRoutes.ALL)
        assertTrue(DipNav.moreDestinations.any { it.route == AppRoutes.SCALP_DATA })
        val tab = src("ui/ScalpTabScreen.kt")
        assertTrue(tab.contains("onOpenScalpData"))
        // paper only: no trade client / real-order path from the Scalp tab
        assertFalse(tab.contains("tradeClient"))
        assertFalse(src("decision/ScalpModels.kt").contains("tradeClient"))
    }
}
