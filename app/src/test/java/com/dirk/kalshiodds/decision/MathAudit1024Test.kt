package com.dirk.kalshiodds.decision

import com.dirk.kalshiodds.chart.BidPoint
import com.dirk.kalshiodds.signal.fair.LiveFair
import com.dirk.kalshiodds.signal.paper.PaperAutopilot
import com.dirk.kalshiodds.signal.paper.PaperBook
import com.dirk.kalshiodds.signal.paper.PaperBookState
import com.dirk.kalshiodds.signal.paper.ShadowBook
import com.dirk.kalshiodds.signal.paper.ShadowTicket
import com.dirk.kalshiodds.signal.trade.KalshiFee
import com.dirk.kalshiodds.ui.charts.ChartShapes
import com.dirk.kalshiodds.ui.charts.rich.RichTradeMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.0.24 math audit: fees, fair-value exits, bankroll, shared fills, live fair. */
class MathAudit1024Test {

    @Test fun takerFeeMatchesThePublishedCentTable() {
        assertEquals(1.75, KalshiFee.total(100, 0.50), 1e-9)
        assertEquals(0.02, KalshiFee.total(1, 0.40), 1e-9)
        assertEquals(0.02, KalshiFee.total(1, 0.20), 1e-9)
        assertEquals(1.68, KalshiFee.total(100, 0.40), 1e-9)
        assertEquals(0.0, KalshiFee.makerFee(10, 0.50, "KXBTC15M"), 0.0)
        assertEquals(KalshiFee.total(10, 0.50, KalshiFee.MAKER_COEFFICIENT), KalshiFee.sweepFee(listOf(10 to 0.50), KalshiFee.MAKER_COEFFICIENT), 1e-9)
    }

    @Test fun dustAboveAnExactCentDoesNotAddACent() {
        assertEquals(4.0, KalshiFee.ceilCent(4.00000005), 1e-9)
        assertEquals(4.01, KalshiFee.ceilCent(4.001), 1e-9)
        assertEquals(KalshiFee.total(100, 0.20), ScalpRule.orderFee(100, 0.20), 1e-9)
    }

    @Test fun splitExitRoundsTheFeeOncePerOrder() {
        val piece = ScalpRule.orderFee(5, 0.40)
        val whole = ScalpRule.orderFee(10, 0.40)
        val swept = KalshiFee.sweepFee(listOf(5 to 0.40, 5 to 0.40))
        assertEquals(whole, swept, 1e-9)
        assertTrue(swept < piece + piece - 1e-9)
        val open = ScalpTrade("t", "KXBTC15M-X", "YES", ScalpState.OPEN, 0, 0.40, 0.50, contracts = 10, entryPrice = 0.40, entryFeeUsd = 0.17)
        val first = ScalpFills.sell(open, 1_000, 5, 5 * 0.50, piece)
        val both = ScalpFills.sell(first, 2_000, 5, 5 * 0.50, piece)
        assertEquals(KalshiFee.sweepFee(listOf(5 to 0.50, 5 to 0.50)), both.exitFeeUsd, 1e-9)
    }

    @Test fun fairCrossAtALossIsNotATakeProfit() {
        val q = ScalpRule.Quote(
            "KXBTC15M-X", 1_000, 1_000 + 400_000, 1_000,
            0.09, 10.0, 0.10, 10.0, 99_000.0, 100_000.0, 3e-5
        )
        val reason = ScalpRule.exitSignal("YES", 0.14, q.copy(yesBid = 0.096, yesAsk = 0.10), ScalpParams.byId(KeptScalpers.FAIR_ID)!!)
        assertEquals(ScalpRule.ExitReason.GAP_CLOSED, reason)
        assertTrue(reason!!.label.startsWith("fair-value exit"))
        assertFalse(reason.label.contains("take-profit"))
        assertEquals(RichTradeMath.ExitBucket.FAIR, RichTradeMath.exitBucket(reason.label))
        assertEquals(RichTradeMath.ExitBucket.TP, RichTradeMath.exitBucket("take profit +12¢"))
    }

    @Test fun shadowClipsCannotSpendTheBankrollMoreThanOnce() {
        val start = 10_000.0
        val book = ShadowBook(com.dirk.kalshiodds.signal.paper.ShadowBookState(startingUsd = start, cashUsd = start))
        var left = start
        repeat(14) { i ->
            val stake = 2_200.0
            book.record(shadow(i, stake))
            val snap = book.snapshot()
            assertTrue(snap.cashUsd >= -1e-6)
            val open = snap.tickets.filter { it.booked && !it.settled }.sumOf { it.stakeUsd }
            assertTrue(open <= start + 1e-6)
            left = snap.cashUsd
        }
        assertTrue(left < start)
        assertTrue(book.snapshot().tickets.count { it.booked } * 2_200.0 <= start + 1e-6)
    }

    @Test fun freeBankrollNeverGoesNegative() {
        val paper = PaperBookState(startingUsd = 1_000.0, cashUsd = 50.0, lifetimeRealizedPnlUsd = 0.0)
        assertEquals(50.0, PaperAutopilot.freeBankroll(paper), 1e-9)
        assertEquals(0.0, PaperAutopilot.freeBankroll(paper, extraOpenUsd = 2_000.0), 0.0)
        assertEquals(0.0, PaperAutopilot.freeBankroll(paper.copy(cashUsd = -5.0)), 0.0)
    }

    @Test fun sharedShockFillsCountOnceInThePortfolioTotal() {
        fun t(id: String, net: Double) = ScalpTrade(id, "KXBTC15M-X", "YES", ScalpState.CLOSED, 0, 0.40, 0.5,
            contracts = 10, entryPrice = 0.40, entryAtMs = 10_000, closedAtMs = 20_000, netUsd = net, ruleVersion = "v|$id|P")
        val rows = listOf(t("sf04", -1.0), t("sf07", -1.0), t("sf08", -1.0), t("sf42", 2.0))
        assertEquals(1.0, ScalpPortfolio.dedupedNet(rows), 1e-9)
        assertTrue(ScalpPortfolio.countsShared(listOf("sf04", "sf07", "sf41")))
        assertFalse(ScalpPortfolio.countsShared(listOf("sf42", "sf41")))
    }

    @Test fun autoBenchDefaultsOffAndMigrationClearsASavedBench() {
        assertFalse(ScalpControls().autoBench)
        val saved = ScalpControls(autoBench = true, aiBench = setOf("sf42"), benchOverrides = mapOf("sf41" to "BENCHED"))
        val next = ScalpAutoBenchMigration.apply(saved)
        assertFalse(next.autoBench)
        assertTrue(next.aiBench.isEmpty())
        assertEquals("BENCHED", next.benchOverrides["sf41"])
        assertNull(ScalpControls().pausedReason("sf42"))
        assertEquals("Bench · forced benched", ScalpControls(benchOverrides = mapOf("sf42" to "BENCHED")).pausedReason("sf42"))
        assertEquals("Entries paused", ScalpControls(killSwitch = true).pausedReason("sf42"))
        assertEquals("V2 hourly entry limit", ScalpControls(maxTradesPerHour = 2).pausedReason("sf42", entriesLastHour = 2))
        assertNull(ScalpControls(maxTradesPerHour = 0).pausedReason("sf42", entriesLastHour = 99))
    }

    @Test fun liveFairMovesWithSpotOnTheSameTimestamp() {
        val calm = LiveFair.yesPercent(100_000.0, 100_000.0, 600.0, 0.50)!!
        val up = LiveFair.yesPercent(100_400.0, 100_000.0, 600.0, 0.50)!!
        assertTrue(up > calm + 1.0)
        val points = listOf(
            BidPoint(1_000, 40f, 60f, 100_000.0, calm.toFloat()),
            BidPoint(2_000, 55f, 45f, 100_400.0, up.toFloat())
        )
        val ai = ChartShapes.aiSamples(points)
        assertEquals(listOf(1_000L, 2_000L), ai.map { it.tMs })
        assertEquals(0L, ChartShapes.aiLagMs(points))
        assertTrue(ai[1].value > ai[0].value)
    }

    @Test fun explicitBuyDebitsTheFeeOnceAndSettlementMatchesCash() {
        val book = PaperBook(PaperBookState(startingUsd = 1_000.0, cashUsd = 1_000.0), idFactory = { "m" }, nowMs = { 1L })
        val bought = book.explicitFill("KXBTC15M-X", "YES", 0.40, 10, "manual", "n")
        assertTrue(bought.ok)
        val fee = KalshiFee.total(10, 0.40)
        assertEquals(4.0 + fee, bought.stakeUsd, 1e-6)
        assertEquals(1_000.0 - bought.stakeUsd, book.snapshot().cashUsd, 1e-6)
        val settled = book.settle("KXBTC15M-X", "yes").single()
        assertEquals(10.0 - bought.stakeUsd, settled.pnlUsd!!, 1e-6)
        assertEquals(1_000.0 + settled.pnlUsd!!, book.snapshot().cashUsd, 1e-6)
    }
}

private fun shadow(i: Int, stake: Double) = ShadowTicket(
    id = "s$i", ticker = "KXBTC15M-X", side = "YES", bookSide = "yes", count = 100,
    limitPrice = 0.40, yesLimitPrice = 0.40, clientOrderId = "c$i", createdAtMs = i.toLong(),
    reason = "test", payloadJson = "{}", depthFill = true, booked = true, stakeUsd = stake
)
