package com.dirk.kalshiodds.signal.scalp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScalpLabTest {

    @Test
    fun fifteenDifferentScalps() {
        assertEquals(15, ScalpCatalog.ALL.size)
        assertEquals(15, ScalpCatalog.ALL.map { it.id }.distinct().size)
        val lab = ScalpLab()
        assertEquals(15, lab.snapshot().accounts.size)
        assertEquals(100.0, lab.snapshot().accounts.sumOf { it.cashUsd } / 15.0, 1e-9)
    }

    @Test
    fun atLeastTenAlgosBetTheSameFifteenMinuteQuote() {
        val lab = ScalpLab()
        lab.onQuote(busyTape())
        val open = lab.snapshot().accounts.count { it.position != null }
        assertTrue("open=$open", open >= 10)
        val cheap = lab.snapshot().accounts.first { it.id == "all_in_cheap" }.position!!
        assertEquals("YES", cheap.side)
        assertTrue(cheap.contracts > 100)
        assertTrue(cheap.stakeUsd > 50.0)
    }

    @Test
    fun noCapBuysAboveFiftyAndTheUnderFiftyRuleDoesNot() {
        val lab = ScalpLab()
        lab.onQuote(
            tape(
                yesAsk = 0.60,
                yesBid = 0.59,
                noAsk = 0.55,
                noBid = 0.54,
                elapsedMs = 8L * 60L * 1000L
            )
        )
        val noCap = lab.snapshot().accounts.first { it.id == "no_cap" }
        val under = lab.snapshot().accounts.first { it.id == "all_in_under_50" }
        val first = lab.snapshot().accounts.first { it.id == "first_5" }
        assertEquals("NO", noCap.position?.side)
        assertTrue(noCap.position!!.entry >= 0.50)
        assertNull(under.position)
        assertNull(first.position)
    }

    @Test
    fun allInUnderFiftySellsTheRiseBeforeSettlement() {
        val lab = ScalpLab()
        lab.onQuote(busyTape())
        val bought = lab.snapshot().accounts.first { it.id == "all_in_under_50" }
        assertTrue(bought.position != null)
        lab.onQuote(busyTape(yesBid = 0.30, yesAsk = 0.31, yesBids = listOf(0.19, 0.22, 0.26, 0.30)))
        val sold = lab.snapshot().accounts.first { it.id == "all_in_under_50" }
        assertNull(sold.position)
        assertEquals(1, sold.trades)
        assertEquals(1, sold.wins)
        assertTrue(sold.realizedPnlUsd > 0.0)
        assertTrue(sold.lastNote.contains("SELL"))
    }

    @Test
    fun settlementPaysAWinnerTheScalpDidNotExit() {
        val lab = ScalpLab()
        lab.onQuote(busyTape())
        val pos = lab.snapshot().accounts.first { it.id == "slow_trail" }.position!!
        assertTrue(lab.settle("KXBTC15M-TEST", "yes") >= 1)
        val after = lab.snapshot().accounts.first { it.id == "slow_trail" }
        assertNull(after.position)
        val pnl = pos.contracts * 1.0 - pos.stakeUsd - pos.feeUsd
        assertEquals(pnl, after.realizedPnlUsd, 1e-6)
        assertTrue(after.cashUsd > 100.0)
    }

    private fun busyTape(
        yesBid: Double = 0.19,
        yesAsk: Double = 0.20,
        yesBids: List<Double> = listOf(0.10, 0.13, 0.16, 0.19)
    ) = tape(
        yesAsk = yesAsk,
        yesBid = yesBid,
        noAsk = 0.82,
        noBid = 0.80,
        elapsedMs = 6L * 60L * 1000L,
        tteMs = 9L * 60L * 1000L,
        yesBids = yesBids,
        yesHigh = 0.32
    )

    private fun tape(
        yesAsk: Double,
        yesBid: Double,
        noAsk: Double,
        noBid: Double,
        elapsedMs: Long = 60_000L,
        tteMs: Long = 14L * 60L * 1000L,
        yesBids: List<Double> = listOf(yesBid),
        noBids: List<Double> = listOf(noBid),
        yesHigh: Double = yesBid,
        noHigh: Double = noBid
    ) = ScalpTape(
        ticker = "KXBTC15M-TEST",
        yesBid = yesBid,
        yesAsk = yesAsk,
        noBid = noBid,
        noAsk = noAsk,
        elapsedMs = elapsedMs,
        tteMs = tteMs,
        yesBids = yesBids,
        noBids = noBids,
        yesHigh = yesHigh,
        yesLow = yesBid,
        noHigh = noHigh,
        noLow = noBid
    )
}
