package com.dirk.kalshiodds.signal.scalp

import com.dirk.kalshiodds.signal.trade.LiveOrderSizer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AdaptivePaperScalpTest {
    private val ticker = "KXBTC15M-PAPER-TEST"
    private fun q(at: Long, bid: Double = .39, ask: Double = .40, depth: Double = 100.0,
                  ticker: String = this.ticker, close: Long = 900000, fair: Double? = .60) =
        PaperDepth(ticker, at, at, close, if (bid > 0) listOf(bid to depth) else emptyList(),
            if (ask < 1) listOf((1 - ask) to depth) else emptyList(), fair)
    private fun initial(cash: Double = 100.0) = PaperScalpState(accounts = PaperScalpCatalog.ALL.map { PaperScalpAccount(it.id, cash) })
    private fun account(lab: AdaptivePaperScalp) = lab.snapshot().accounts.first { it.id == "cheap-100" }
    private fun entered(cash: Double = 100.0): AdaptivePaperScalp {
        val lab = AdaptivePaperScalp(initial(cash))
        lab.onBook(q(1000)); lab.onBook(q(1300))
        assertTrue(account(lab).positions.isNotEmpty())
        return lab
    }

    @Test fun noLiveDollarCapAndNoSameSnapshotEntryFill() {
        val lab = AdaptivePaperScalp(initial(1000.0))
        lab.onBook(q(1000, depth = 2000.0))
        assertTrue(account(lab).positions.isEmpty())
        assertEquals(1, account(lab).pending.size)
        lab.onBook(q(1300, depth = 2000.0))
        val a = account(lab)
        assertTrue(a.positions.single().entryValue > 5.0)
        assertTrue(a.cash >= 0)
        assertEquals(1000.0, a.cash + a.positions.single().entryValue + a.positions.single().entryFee, 1e-8)
    }
    @Test fun buysStrictlyBelowFiftyAndRemainsEligibleNearOpenAndClose() {
        for (at in listOf(1L, 890000L)) {
            val lab = AdaptivePaperScalp(initial())
            lab.onBook(q(at, .48, .49))
            lab.onBook(q(at + 300, .48, .49))
            assertEquals(.49, account(lab).positions.single().entry, 1e-9)
        }
        val lab = AdaptivePaperScalp(initial())
        lab.onBook(q(1000, .49, .50))
        lab.onBook(q(1300, .49, .50))
        assertTrue(account(lab).positions.isEmpty())
        assertFalse(PaperScalpPolicy.below50(.50))
    }
    @Test fun initialStopCutsLoserAndWaitsForLaterExecutableBook() {
        val lab = entered()
        lab.onBook(q(1600, .339, .349))
        assertEquals("Initial protective stop", account(lab).positions.single().exitReason)
        assertEquals(0, account(lab).closed)
        lab.onBook(q(1900, .33, .34))
        val a = account(lab)
        assertEquals(1, a.closed)
        assertTrue(a.positions.isEmpty())
        assertTrue(a.results.single().netUsd < 0)
        assertTrue(a.results.single().feesUsd > a.results.single().count * 0.0)
        assertEquals(a.realized, a.cash - 100, 1e-8)
    }
    @Test fun highWaterAndTriggeredExitSurviveRestartAndNeverWiden() {
        var persisted = initial()
        var lab = AdaptivePaperScalp(initial(), persist = { persisted = it })
        lab.onBook(q(1000)); lab.onBook(q(1300)); lab.onBook(q(1600, .48, .49))
        assertEquals(.46, account(lab).positions.single().stop, 1e-9)
        lab = AdaptivePaperScalp(Json.decodeFromString<PaperScalpState>(Json.encodeToString(persisted)), persist = { persisted = it })
        lab.onBook(q(1900, .459, .469))
        assertEquals(.46, account(lab).positions.single().stop, 1e-9)
        assertNotNull(account(lab).positions.single().exitReason)
        lab = AdaptivePaperScalp(persisted)
        lab.onBook(q(2200, .47, .48))
        assertEquals(1, account(lab).closed) // exit remained latched despite a rebound
    }
    @Test fun partialExitDoesNotReuseStaticDepthAndWalksWorsePrices() {
        val lab = entered()
        val before = account(lab).positions.single().remaining
        lab.onBook(q(1600, .33, .34, 1.0))
        lab.onBook(q(1900, .33, .34, 1.0))
        assertEquals(before - 1, account(lab).positions.single().remaining)
        lab.onBook(q(2200, .33, .34, 1.0))
        assertEquals(before - 1, account(lab).positions.single().remaining)
        lab.onBook(q(2500, .33, .34, 2.0)) // one newly displayed contract
        assertEquals(before - 2, account(lab).positions.single().remaining)
        val fill = PaperBookWalk.sell(listOf(.35 to 3.0, .30 to 20.0), 10)
        assertEquals(10, fill.count)
        assertEquals(3 * .35 + 7 * .30, fill.value, 1e-9)
    }
    @Test fun missingBidOrWindowRolloverNeverCreatesAnExit() {
        val lab = entered()
        lab.onBook(q(1600, 0.0, .40))
        assertNotNull(account(lab).positions.single().exitReason)
        lab.onBook(q(1900, 0.0, .40))
        lab.onBook(q(901000, ticker = "KXBTC15M-NEXT", close = 1800000))
        assertEquals(0, account(lab).closed)
        assertTrue(account(lab).positions.any { it.ticker == ticker })
        lab.settle(ticker, false, 902000)
        assertEquals(1, account(lab).closed)
        assertTrue(account(lab).results.single().reason.contains("settled"))
    }
    @Test fun settlementIncludesPartialSaleAndFeesExactlyOnce() {
        val lab = entered()
        lab.onBook(q(1600, .33, .34, 1.0)); lab.onBook(q(1900, .33, .34, 1.0))
        val p = account(lab).positions.single()
        val expected = p.netProceeds + p.remaining - p.entryValue - p.entryFee
        lab.settle(ticker, true, 900000)
        assertEquals(expected, account(lab).realized, 1e-8)
        val once = account(lab).cash
        lab.settle(ticker, true, 901000)
        assertEquals(once, account(lab).cash, 0.0)
    }
    @Test fun pauseStopsNewEntriesButContinuesProtectingOpenPositions() {
        val lab = entered()
        lab.setPaused(true)
        lab.onBook(q(1600, .33, .34)); lab.onBook(q(1900, .32, .33))
        assertEquals(1, account(lab).closed)
        lab.onBook(q(2200, .35, .36))
        assertTrue(account(lab).pending.isEmpty())
    }
    @Test fun staleDuplicateAndInvalidBooksCannotFill() {
        val lab = AdaptivePaperScalp(initial())
        lab.onBook(q(1000))
        lab.onBook(q(1000))
        lab.onBook(q(1300), nowMs = 10000)
        lab.onBook(q(1600).copy(yes = listOf(Double.NaN to 100.0)))
        assertTrue(account(lab).positions.isEmpty())
        assertEquals(0, account(lab).closed)
    }
    @Test fun noNegativeCashNoFreeBankrollAndDepthLimitedEntry() {
        val broke = AdaptivePaperScalp(initial(.10))
        broke.onBook(q(1000)); broke.onBook(q(1300))
        assertTrue(account(broke).positions.isEmpty())
        val lab = AdaptivePaperScalp(initial(1000.0))
        lab.onBook(q(1000, depth = 2.0)); lab.onBook(q(1300, depth = 2.0))
        assertEquals(2, account(lab).positions.single().count)
        val fill = PaperBookWalk.buy(listOf(.40 to 100.0), .40, .41)
        assertEquals(0, fill.count) // entry plus fees must fit
    }
    @Test fun learningUsesOnlyCompletedTradesAndGroupsCorrelatedWindows() {
        val lab = entered()
        assertEquals(0, PaperScalpLearning.lesson(account(lab).results, "cheap", .40).n)
        lab.onBook(q(1600, .33, .34)); lab.onBook(q(1900, .32, .33))
        val learned = PaperScalpLearning.lesson(account(lab).results, "cheap", .40)
        assertEquals(1, learned.n)
        assertTrue(learned.meanNet < 0)
        assertTrue(learned.sizeScale < 1)
        val cloned = (1..10).map { account(lab).results.single().copy(id = "$it") }
        assertEquals(1, PaperScalpLearning.lesson(cloned, "cheap", .40).windows)
        assertEquals(0, PaperScalpLearning.lesson(cloned, "momentum", .40).n)
    }
    @Test fun noiseDoesNotStopOutAndTrailRequiresNetProfitAtItsStopLevel() {
        val lab = entered()
        val initial = account(lab).positions.single()
        assertEquals(.34, initial.stop, 1e-9)
        lab.onBook(q(1600, .379, .389)) // old entry-minus-2c rule would trigger
        assertNull(account(lab).positions.single().exitReason)
        lab.onBook(q(1900, .43, .44)) // gross gain, but fees make a 2c trail unprofitable
        assertFalse(account(lab).positions.single().trailArmed)
        assertEquals(.34, account(lab).positions.single().stop, 1e-9)
        lab.onBook(q(2200, .48, .49))
        val p = account(lab).positions.single()
        assertTrue(p.trailArmed)
        assertTrue(p.remaining * p.stop - LiveOrderSizer.feeUsd(p.remaining, p.stop) - p.entryValue - p.entryFee >= p.count * .01)
        val stop = p.stop
        lab.onBook(q(2500, .47, .48))
        assertTrue(account(lab).positions.single().stop >= stop)
    }
    @Test fun cheapPriceWithoutFeeAdjustedEdgeDoesNotBuyAndSignalsAreRechecked() {
        for (fair in listOf(null, .42, .44)) {
            val lab = AdaptivePaperScalp(initial())
            lab.onBook(q(1000, fair = fair)); lab.onBook(q(1300, fair = fair))
            assertTrue(account(lab).positions.isEmpty())
            assertTrue(account(lab).pending.isEmpty())
        }
        val lab = AdaptivePaperScalp(initial())
        lab.onBook(q(1000))
        lab.onBook(q(1300, fair = .41))
        assertTrue(account(lab).positions.isEmpty())
        assertEquals(1, account(lab).noFills)
    }
    @Test fun lossCooldownPreventsImmediateChurnButExpiresAndLearningCanBlockBadSetup() {
        val lab = entered()
        lab.onBook(q(1600, .33, .34)); lab.onBook(q(1900, .32, .33))
        lab.onBook(q(2200, depth = 200.0))
        assertTrue(account(lab).pending.isEmpty())
        lab.onBook(q(62000, depth = 200.0))
        assertEquals(1, account(lab).pending.size)
        val bad = account(lab).results.single()
        val losses = (1..12).map { bad.copy(id = "$it", ticker = "KXBTC15M-W${it % 3}", netUsd = -10.0, count = 100) }
        val old = initial().accounts.map { if (it.id == "cheap-100") it.copy(results = losses) else it }
        val blocked = AdaptivePaperScalp(initial().copy(accounts = old))
        blocked.onBook(q(1000))
        assertTrue(account(blocked).pending.isEmpty())
    }
    @Test fun legacyStateKeepsLossesAndExistingStopWhileNewCurvesPersistHonestBaseline() {
        val active = account(entered()).positions.single().copy(policyVersion = 1, stop = .38)
        val old = initial().copy(accounts = initial().accounts.map { if (it.id == "cheap-100")
            it.copy(cash = 50.0, realized = -12.0, fees = 4.0, positions = listOf(active), closed = 3) else it })
        val lab = AdaptivePaperScalp(Json.decodeFromString<PaperScalpState>(Json.encodeToString(old)))
        val equityBefore = account(lab).equity()
        lab.onBook(q(1600, .379, .389))
        val a = account(lab)
        assertEquals(-12.0, a.realized, 0.0)
        assertEquals(equityBefore, a.policyStartEquity, 0.0)
        assertEquals(3, a.policyStartClosed)
        assertEquals(.38, a.positions.single().stop, 1e-9)
        assertEquals("Legacy 2¢ trailing stop", a.positions.single().exitReason)
        val restored = AdaptivePaperScalp(Json.decodeFromString<PaperScalpState>(Json.encodeToString(lab.snapshot())))
        assertEquals(a.curve, account(restored).curve)
        assertEquals(a.maxDrawdown, account(restored).maxDrawdown, 0.0)
    }
    @Test fun noiseDistancesAreBoundedAndFrozenForOpenPositions() {
        val depth = q(1000)
        val (risk, trail) = PaperScalpPolicy.distances(depth, "YES", listOf(q(1, .10), q(2, .70), q(3, .20)))
        assertEquals(.10, risk, 1e-9)
        assertEquals(.04, trail, 1e-9)
        val lab = entered()
        lab.onBook(q(1600, .70, .71))
        val p = account(lab).positions.single()
        assertEquals(.06, p.riskDistance, 1e-9)
        assertEquals(.02, p.trailDistance, 1e-9)
    }
    @Test fun confirmedBookMomentumCanTradeWithoutExternalFairValue() {
        val lab = AdaptivePaperScalp(initial())
        lab.onBook(q(1000, .35, .36, fair = null))
        lab.onBook(q(1500, .37, .38, fair = null))
        lab.onBook(q(2000, .39, .40, fair = null))
        lab.onBook(q(2500, .45, .46, fair = null))
        val momentum = lab.snapshot().accounts.first { it.id == "momentum-35" }
        assertEquals(1, momentum.pending.size)
        lab.onBook(q(2800, .455, .46, fair = null))
        assertEquals(1, lab.snapshot().accounts.first { it.id == "momentum-35" }.positions.size)
    }
    @Test fun aiProfitExitRespectsBothLegFeesAndNetProfit() {
        val lab = entered()
        lab.onBook(q(1600, .48, .49, fair = .47))
        assertEquals("AI profit exit", account(lab).positions.single().exitReason)
        lab.onBook(q(1900, .48, .49, depth = 200.0))
        assertEquals(1, account(lab).closed)
        assertTrue(account(lab).results.single().netUsd > 0)
        val cost = LiveOrderSizer.allInUsd(1, .40)
        assertTrue(cost > .40)
    }
}
