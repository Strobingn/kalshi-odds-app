package com.dirk.kalshiodds.signal.scalp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScalpLabTest {

    @Test
    fun twentyDifferentScalps() {
        assertEquals(20, ScalpCatalog.ALL.size)
        assertEquals(20, ScalpCatalog.ALL.map { it.id }.distinct().size)
        val lab = ScalpLab()
        assertEquals(20, lab.snapshot().accounts.size)
        assertEquals(ScalpCatalog.START_CASH, lab.snapshot().accounts.sumOf { it.cashUsd } / 20.0, 1e-9)
        assertEquals(20_000.0, ScalpCatalog.START_CASH, 1e-9)
    }

    @Test
    fun atLeastTenAlgosBetTheSameFifteenMinuteQuote() {
        val lab = ScalpLab()
        lab.onQuote(busyTape())
        val open = lab.snapshot().accounts.count { it.position != null }
        assertTrue("open=$open", open >= 10)
        val cheap = lab.snapshot().accounts.first { it.id == "all_in_cheap" }.position!!
        assertEquals("YES", cheap.side)
        assertTrue(cheap.contracts > 4_000)
        assertTrue(cheap.stakeUsd > 4_000.0)
        assertTrue(cheap.stakeUsd < 5_500.0)
    }

    @Test
    fun noCapDoesNotBuyAboveFifty() {
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
        val trail = lab.snapshot().accounts.first { it.id == "all_in_under_50" }
        val first = lab.snapshot().accounts.first { it.id == "first_5" }
        assertNull(noCap.position)
        assertNull(trail.position)
        assertNull(first.position)
    }

    @Test
    fun dipBuysAndSellsAtTheTarget() {
        val lab = ScalpLab()
        lab.onQuote(busyTape())
        val bought = lab.snapshot().accounts.first { it.id == "all_in_under_50" }
        assertTrue(bought.position != null)
        assertEquals("YES", bought.position!!.side)
        assertTrue(bought.position!!.stakeUsd > 1_500.0)
        lab.onQuote(busyTape(yesBid = 0.70, yesAsk = 0.71, yesBids = listOf(0.79, 0.76, 0.73, 0.70)))
        val sold = lab.snapshot().accounts.first { it.id == "all_in_under_50" }
        assertNull(sold.position)
        assertEquals(1, sold.trades)
        assertEquals(0, sold.wins)
        assertTrue(sold.realizedPnlUsd < 0.0)
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
        assertTrue(after.cashUsd > ScalpCatalog.START_CASH)
    }

    @Test
    fun brokeBookStillBetsFullSizeAndGoesNegative() {
        val broke = ScalpCatalog.ALL.map { algo ->
            ScalpAccount(id = algo.id, name = algo.name, rule = algo.rule, cashUsd = 0.50)
        }
        val lab = ScalpLab(ScalpLabState(accounts = broke))
        lab.onQuote(busyTape())
        val cheap = lab.snapshot().accounts.first { it.id == "all_in_cheap" }
        assertTrue(cheap.position != null)
        assertTrue(cheap.position!!.stakeUsd > 1_500.0)
        assertTrue(cheap.cashUsd < 0.0)
    }

    @Test
    fun bookAheadOfTheStartBetsAFractionOfThatCash() {
        val rich = ScalpCatalog.merge(emptyList()).map { account ->
            if (account.id == "all_in_cheap") account.copy(cashUsd = 40_000.0) else account
        }
        val lab = ScalpLab(ScalpLabState(accounts = rich))
        lab.onQuote(busyTape())
        val cheap = lab.snapshot().accounts.first { it.id == "all_in_cheap" }
        assertTrue(cheap.position!!.stakeUsd > 3_500.0)
        assertTrue(cheap.cashUsd < 40_000.0)
    }

    @Test
    fun dipHoldsUntilTheTarget() {
        val lab = ScalpLab()
        lab.onQuote(busyTape())
        val bought = lab.snapshot().accounts.first { it.id == "first_5" }
        assertTrue(bought.position != null)
        lab.onQuote(busyTape(yesBid = 0.77, yesAsk = 0.78))
        val still = lab.snapshot().accounts.first { it.id == "first_5" }
        assertTrue(still.position != null)
    }

    @Test
    fun lateFavoriteSkipsUntilTheLastTwoMinutes() {
        val lab = ScalpLab()
        lab.onQuote(
            tape(
                yesAsk = 0.80,
                yesBid = 0.79,
                noAsk = 0.12,
                noBid = 0.10,
                elapsedMs = 12L * 60L * 1000L,
                tteMs = 3L * 60L * 1000L
            )
        )
        assertNull(lab.snapshot().accounts.first { it.id == "late_squeeze" }.position)
        lab.onQuote(
            tape(
                yesAsk = 0.80,
                yesBid = 0.79,
                noAsk = 0.12,
                noBid = 0.10,
                elapsedMs = 14L * 60L * 1000L,
                tteMs = 60_000L
            )
        )
        val pos = lab.snapshot().accounts.first { it.id == "late_squeeze" }.position
        assertEquals("YES", pos?.side)
    }

    @Test
    fun zeroStopHoldsACrashToTheClose() {
        val lab = ScalpLab()
        lab.onQuote(busyTape())
        val before = ScalpSettings.stopLoss
        ScalpSettings.stopLoss = 0.0
        try {
            lab.onQuote(busyTape(yesBid = 0.40, yesAsk = 0.41, yesBids = listOf(0.89, 0.70, 0.55, 0.40)))
            assertTrue(lab.snapshot().accounts.first { it.id == "all_in_cheap" }.position != null)
        } finally {
            ScalpSettings.stopLoss = before
        }
    }

    @Test
    fun aStuckBookClosesTheOldWindowAndTradesTheNextOne() {
        val lab = ScalpLab()
        lab.onQuote(busyTape())
        assertTrue(lab.snapshot().accounts.first { it.id == "all_in_cheap" }.position != null)
        lab.onQuote(busyTape().copy(ticker = "KXBTC15M-NEXT"))
        val next = lab.snapshot().accounts.first { it.id == "all_in_cheap" }
        assertTrue(next.trades >= 1)
        assertEquals("KXBTC15M-NEXT", next.position?.ticker)
        assertTrue(next.lastNote.contains("BUY") || next.lesson.contains("new one"))
    }

    @Test
    fun settlementLossShrinksTheNextBet() {
        val lab = ScalpLab()
        lab.onQuote(busyTape())
        val first = lab.snapshot().accounts.first { it.id == "slow_trail" }.position!!
        assertEquals("YES", first.side)
        lab.settle("KXBTC15M-TEST", "no")
        val learned = lab.snapshot().accounts.first { it.id == "slow_trail" }
        assertTrue(learned.settleLosses >= 1)
        assertTrue(learned.sizeScale < 1.0)
        assertTrue(learned.lesson.contains("settlement"))
        lab.onQuote(busyTape().copy(ticker = "KXBTC15M-NEXT"))
        val again = lab.snapshot().accounts.first { it.id == "slow_trail" }.position!!
        assertTrue(again.stakeUsd < first.stakeUsd)
    }

    @Test
    fun anIdleBookStillTakesTheEarlyCheapTrade() {
        val lab = ScalpLab()
        lab.onQuote(busyTape())
        val late = lab.snapshot().accounts.first { it.id == "late_squeeze" }
        assertTrue(late.position != null)
        assertTrue(late.position!!.stakeUsd > 4_000.0)
        assertTrue(late.position!!.stakeUsd < 6_000.0)
    }

    private fun busyTape(
        yesBid: Double = 0.79,
        yesAsk: Double = 0.80,
        yesBids: List<Double> = listOf(0.70, 0.74, 0.77, 0.79)
    ) = tape(
        yesAsk = yesAsk,
        yesBid = yesBid,
        noAsk = 0.12,
        noBid = 0.10,
        elapsedMs = 13L * 60L * 1000L,
        tteMs = 100_000L,
        yesBids = yesBids,
        yesHigh = yesAsk
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
