package com.dirk.kalshiodds.signal.scalp

import com.dirk.kalshiodds.signal.trade.LiveOrderSizer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AdaptivePaperScalpTest {
    private val ticker = "KXBTC15M-PAPER-TEST"
    private fun q(at: Long, bid: Double = .39, ask: Double = .40, depth: Double = 1000.0,
                  ticker: String = this.ticker, close: Long = 900000, fair: Double? = null) =
        PaperDepth(ticker, at, at, close, if (bid > 0) listOf(bid to depth) else emptyList(),
            if (ask < 1) listOf((1 - ask) to depth) else emptyList(), fair)
    private fun initial(cash: Double = 100.0) = PaperScalpState(accounts = PaperScalpCatalog.ALL.map { PaperScalpAccount(it.id, cash) })
    private fun account(lab: AdaptivePaperScalp) = lab.snapshot().accounts.first { it.id == "cheap-100" }
    private fun warm(lab: AdaptivePaperScalp, depth: Double = 1000.0) {
        (1000L..31000L step 1000).forEach { lab.onBook(q(it, .62, .63, depth)) }
        lab.onBook(q(32000, .35, .36, depth))
    }
    private fun entered(cash: Double = 100.0, depth: Double = 1000.0): AdaptivePaperScalp {
        val lab = AdaptivePaperScalp(initial(cash)); warm(lab, depth)
        lab.onBook(q(33000, depth = depth)); lab.onBook(q(33300, depth = depth))
        assertTrue(account(lab).positions.isNotEmpty())
        return lab
    }
    @Test fun noLiveDollarCapAndNoSameSnapshotFill() {
        val lab = AdaptivePaperScalp(initial(1000.0)); warm(lab)
        lab.onBook(q(33000))
        assertTrue(account(lab).positions.isEmpty()); assertEquals(1, account(lab).pending.size)
        lab.onBook(q(33300))
        val a = account(lab)
        assertTrue(a.positions.single().entryValue > 5.0); assertTrue(a.cash >= 0)
        assertEquals(1000.0, a.cash + a.positions.single().entryValue + a.positions.single().entryFee, 1e-8)
    }
    @Test fun cheapPriceAndHugeSettlementForecastAloneNeverBuy() {
        val lab = AdaptivePaperScalp(initial())
        (1000L..61000L step 1000).forEach { lab.onBook(q(it, fair = .99)) }
        assertTrue(account(lab).positions.isEmpty()); assertTrue(account(lab).pending.isEmpty())
        assertFalse(PaperScalpPolicy.below50(.50))
        assertTrue(PaperScalpPolicy.below50(.499))
    }
    @Test fun initialStopWaitsForLaterExecutableBook() {
        val lab = entered(); assertEquals(.36, account(lab).positions.single().stop, 1e-9)
        lab.onBook(q(33600, .359, .369))
        assertEquals("Initial protective stop", account(lab).positions.single().exitReason)
        assertEquals(0, account(lab).closed)
        lab.onBook(q(33900, .35, .36))
        val a = account(lab)
        assertEquals(1, a.closed); assertTrue(a.positions.isEmpty()); assertTrue(a.results.single().netUsd < 0)
        assertEquals(a.realized, a.cash - 100, 1e-8)
    }
    @Test fun noiseAndSmallGainCannotArmProfitTrail() {
        val lab = entered()
        lab.onBook(q(33600, .379, .389)) // the old 2¢ rule triggered here
        assertNull(account(lab).positions.single().exitReason)
        lab.onBook(q(33900, .47, .48))
        assertFalse(account(lab).positions.single().trailArmed)
        assertEquals(.36, account(lab).positions.single().stop, 1e-9)
        lab.onBook(q(34200, .54, .55))
        val p = account(lab).positions.single()
        assertTrue(p.trailArmed)
        assertTrue(p.remaining * p.stop - LiveOrderSizer.feeUsd(p.remaining, p.stop) - p.entryValue - p.entryFee >= p.plannedRiskUsd * .75)
    }
    @Test fun highWaterAndLatchedExitSurviveRestart() {
        var saved = initial()
        var lab = AdaptivePaperScalp(initial(), persist = { saved = it }); warm(lab)
        lab.onBook(q(33000)); lab.onBook(q(33300)); lab.onBook(q(33600, .54, .55))
        assertEquals(.52, account(lab).positions.single().stop, 1e-9)
        lab = AdaptivePaperScalp(Json.decodeFromString<PaperScalpState>(Json.encodeToString(saved)), persist = { saved = it })
        lab.onBook(q(33900, .519, .529))
        assertNotNull(account(lab).positions.single().exitReason)
        lab = AdaptivePaperScalp(saved); lab.onBook(q(34200, .53, .54))
        assertEquals(1, account(lab).closed)
    }
    @Test fun partialExitCannotReuseUnchangedDepth() {
        val lab = entered(); val before = account(lab).positions.single().remaining
        lab.onBook(q(33600, .35, .36, 1.0)); lab.onBook(q(33900, .35, .36, 1.0))
        assertEquals(before - 1, account(lab).positions.single().remaining)
        lab.onBook(q(34200, .35, .36, 1.0)); assertEquals(before - 1, account(lab).positions.single().remaining)
        lab.onBook(q(34500, .35, .36, 2.0)); assertEquals(before - 2, account(lab).positions.single().remaining)
        val fill = PaperBookWalk.sell(listOf(.35 to 3.0, .30 to 20.0), 10)
        assertEquals(10, fill.count); assertEquals(3 * .35 + 7 * .30, fill.value, 1e-9)
    }
    @Test fun missingBidAndRolloverCannotInventAnExit() {
        val lab = entered()
        lab.onBook(q(33600, 0.0)); lab.onBook(q(33900, 0.0))
        lab.onBook(q(901000, ticker = "KXBTC15M-NEXT", close = 1800000))
        assertEquals(0, account(lab).closed); assertTrue(account(lab).positions.any { it.ticker == ticker })
        lab.settle(ticker, false, 902000)
        assertEquals(1, account(lab).closed); assertTrue(account(lab).results.single().reason.contains("settled"))
    }
    @Test fun settlementIncludesPartialSalesAndFeesOnlyOnce() {
        val lab = entered(); lab.onBook(q(33600, .35, .36, 1.0)); lab.onBook(q(33900, .35, .36, 1.0))
        val p = account(lab).positions.single()
        val expected = p.netProceeds + p.remaining - p.entryValue - p.entryFee
        lab.settle(ticker, true, 900000)
        assertEquals(expected, account(lab).realized, 1e-8)
        val cash = account(lab).cash; lab.settle(ticker, true, 901000); assertEquals(cash, account(lab).cash, 0.0)
    }
    @Test fun pauseKeepsProtectionAndRejectsNewEntries() {
        val lab = entered(); lab.setPaused(true)
        lab.onBook(q(33600, .35, .36)); lab.onBook(q(33900, .34, .35))
        assertEquals(1, account(lab).closed); assertTrue(account(lab).pending.isEmpty())
    }
    @Test fun invalidStaleAndDuplicateBooksCannotFill() {
        val lab = AdaptivePaperScalp(initial()); warm(lab); lab.onBook(q(33000))
        lab.onBook(q(33000)); lab.onBook(q(33300), 40000)
        lab.onBook(q(33600).copy(yes = listOf(Double.NaN to 10.0)))
        assertTrue(account(lab).positions.isEmpty()); assertEquals(0, account(lab).closed)
    }
    @Test fun insufficientCashAndThinExitDepthRejectEntry() {
        val broke = AdaptivePaperScalp(initial(.10)); warm(broke); broke.onBook(q(33000)); broke.onBook(q(33300))
        assertTrue(account(broke).positions.isEmpty())
        val thin = AdaptivePaperScalp(initial(1000.0)); warm(thin, 2.0); thin.onBook(q(33000, depth = 2.0)); thin.onBook(q(33300, depth = 2.0))
        assertTrue(account(thin).positions.isEmpty())
        val liquid = entered(20000.0)
        assertTrue(account(liquid).positions.single().count <= 250)
        assertEquals(0, PaperBookWalk.buy(listOf(.40 to 100.0), .40, .41).count)
    }
    @Test fun pendingEntryIsCanceledIfSignalDisappears() {
        val lab = AdaptivePaperScalp(initial()); warm(lab); lab.onBook(q(33000))
        lab.onBook(q(33300, .34, .35))
        assertTrue(account(lab).positions.isEmpty()); assertEquals(1, account(lab).noFills)
    }
    @Test fun plannedTargetMustBeatActualRoundedFeeStopRisk() {
        val p = account(entered()).positions.single()
        val gain = p.count * p.targetBid - LiveOrderSizer.feeUsd(p.count, p.targetBid) - p.entryValue - p.entryFee
        val risk = p.entryValue + p.entryFee - p.count * p.stop + LiveOrderSizer.feeUsd(p.count, p.stop)
        assertEquals(risk, p.plannedRiskUsd, 1e-9)
        assertTrue(gain >= risk * 1.25)
    }
    @Test fun targetExitDoesNotUseSettlementProbability() {
        val lab = entered(); lab.onBook(q(33600, .47, .48, fair = .01))
        assertNull(account(lab).positions.single().exitReason)
        lab.onBook(q(33900, .62, .63))
        assertEquals("Planned target reached", account(lab).positions.single().exitReason)
        lab.onBook(q(34200, .62, .63)); assertTrue(account(lab).results.single().netUsd > 0)
    }
    @Test fun coldSizingIsSmallAndEvidenceGroupsContractWindows() {
        val lab = entered(); val cold = PaperScalpLearning.lesson(account(lab).results, "cheap", .4)
        assertEquals(.05, cold.sizeScale, 0.0)
        lab.onBook(q(33600, .35, .36)); lab.onBook(q(33900, .34, .35))
        val sample = account(lab).results.single()
        val clone = (1..12).map { sample.copy(id = "$it") }
        val lesson = PaperScalpLearning.lesson(clone, "cheap", .4)
        assertEquals(1, lesson.windows); assertEquals(.05, lesson.sizeScale, 0.0)
        assertEquals(0, PaperScalpLearning.lesson(clone.map { it.copy(policyVersion = 2) }, "cheap", .4).n)
    }
    @Test fun losingSetupAndCooldownPreventBlindReentry() {
        val lab = entered(); lab.onBook(q(33600, .35, .36)); lab.onBook(q(33900, .34, .35)); lab.onBook(q(34200))
        assertTrue(account(lab).pending.isEmpty())
        val sample = account(lab).results.single()
        val losses = (1..12).map { sample.copy(id = "$it", ticker = "KXBTC15M-${it % 3}", netUsd = -10.0, count = 100) }
        val blocked = AdaptivePaperScalp(initial().copy(accounts = initial().accounts.map { if (it.id == "cheap-100") it.copy(results = losses) else it }))
        warm(blocked); blocked.onBook(q(33000)); assertTrue(account(blocked).pending.isEmpty())
    }
    @Test fun upgradePreservesBothLegacyStopTypesAndHistoricalLosses() {
        val active = account(entered()).positions.single()
        for (version in listOf(1, 2)) {
            val prior = initial().copy(accounts = initial().accounts.map { if (it.id == "cheap-100") it.copy(cash = 50.0, realized = -12.0, closed = 3,
                positions = listOf(active.copy(policyVersion = version, stop = if (version == 1) .38 else .34)),
                policyStartedMs = 1000, policyVersion = 2, curve = listOf(PaperCurvePoint(1000, 98.0, -12.0, 4.0))) else it })
            val lab = AdaptivePaperScalp(Json.decodeFromString<PaperScalpState>(Json.encodeToString(prior)))
            val equity = account(lab).equity(); lab.onBook(q(33600, .379, .389))
            val a = account(lab)
            assertEquals(-12.0, a.realized, 0.0); assertEquals(equity, a.policyStartEquity, 0.0)
            assertEquals(3, a.policyStartClosed); assertEquals(2, a.curve.first().policyVersion)
            assertEquals(if (version == 1) .38 else .34, a.positions.single().stop, 1e-9)
            assertEquals(version == 1, a.positions.single().exitReason != null)
        }
    }
}
