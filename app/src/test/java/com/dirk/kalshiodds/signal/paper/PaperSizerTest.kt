package com.dirk.kalshiodds.signal.paper

import com.dirk.kalshiodds.signal.model.SignalAlert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaperSizerTest {

    private fun alert(side: String, fairYesPp: Double, ticker: String = "KXBTC15M-26OCT051030-30") = SignalAlert(
        id = "a", ticker = ticker, series = "KXBTC15M",
        deltaPp = 10.0, fairValuePp = fairYesPp, marketMidPp = 50.0, reason = "edge",
        createdAtMs = 1L, receiveElapsedNanos = 1L, predictedSide = side
    )

    @Test
    fun noEdgeMeansNoExtraStake() {
        assertEquals(0.0, PaperSizer.fraction(0.50, 0.50), 0.0)
        assertEquals(0.0, PaperSizer.fraction(0.50, 0.51), 0.0) // 1¢ of edge is eaten by the fee
        assertEquals(0.0, PaperSizer.fraction(0.50, null), 0.0)
        assertEquals(0, PaperSizer.contracts(100.0, 0.50, 0.40))
    }

    @Test
    fun biggerEdgeMeansBiggerShareOfCash() {
        val small = PaperSizer.fraction(0.50, 0.58)
        val big = PaperSizer.fraction(0.50, 0.75)
        assertTrue(small > 0.0)
        assertTrue(big > small)
    }

    @Test
    fun kellyFractionMatchesTheFormula() {
        val c = 0.5 + 0.07 * 0.25
        // Below the 10% cap the fraction is raw Kelly.
        val f = (0.70 - c) / (1.0 - c)
        if (f <= PaperSizer.MAX_FRACTION) {
            assertEquals(f, PaperSizer.fraction(0.50, 0.70), 1e-12)
        } else {
            assertEquals(PaperSizer.MAX_FRACTION, PaperSizer.fraction(0.50, 0.70), 1e-12)
        }
    }

    @Test
    fun aCertainWinIsCappedAtTenPercent() {
        // A model that equals the market must not make paper results luck:
        // even a "certain" win stakes at most 10% of paper cash.
        assertEquals(PaperSizer.MAX_FRACTION, PaperSizer.fraction(0.50, 1.0), 1e-12)
        assertEquals(0.10, PaperSizer.MAX_FRACTION, 1e-12)
        val n = PaperSizer.contracts(100.0, 0.50, 1.0)
        assertTrue("n=$n", n in 18..20)
    }

    @Test
    fun neverMoreThanPaperCashCovers() {
        for (cash in listOf(3.0, 17.0, 100.0, 1_000.0)) {
            for (p in listOf(0.05, 0.20, 0.50, 0.80, 0.95)) {
                val n = PaperSizer.contracts(cash, p, 0.99)
                assertTrue("cash=$cash p=$p n=$n", PaperBuy.costUsd(n, p) <= cash + 1e-9)
            }
        }
    }

    @Test
    fun autoAlertFillIsSizedByEdgeInsteadOfFiveDollars() {
        val book = PaperBook(idFactory = { "p1" }, nowMs = { 10L })
        // NO side: fair YES 62% means NO wins 38%; NO ask 20¢ is a large edge.
        val fill = book.considerAlert(alert("NO", 62.0), ask = 0.20, enabled = true)!!
        assertTrue("stake ${fill.stakeUsd}", fill.stakeUsd > 5.5)
        assertTrue(fill.note.contains("sized by edge"))
        assertTrue(book.snapshot().cashUsd >= 0.0)
        assertTrue(book.snapshot().cashUsd < 94.5)
    }

    @Test
    fun alertWithoutEdgeKeepsTheFiveDollarClip() {
        val book = PaperBook(idFactory = { "p1" }, nowMs = { 10L })
        val fill = book.considerAlert(alert("YES", 52.0), ask = 0.51, enabled = true)!!
        assertEquals(9, fill.contracts) // floor(5 / 0.51)
        assertTrue(!fill.note.contains("sized by edge"))
    }

    @Test
    fun theSecondBetUsesWhatIsLeftNotTheStartingBalance() {
        var n = 0
        val book = PaperBook(idFactory = { "p${n++}" }, nowMs = { 10L })
        val first = book.considerAlert(alert("YES", 90.0), ask = 0.50, enabled = true)!!
        val second = book.considerAlert(alert("YES", 90.0, ticker = "KXBTC15M-26OCT051045-45"), ask = 0.50, enabled = true)
        assertTrue(first.stakeUsd > 50.0)
        assertTrue(second == null || second.stakeUsd < first.stakeUsd)
        assertTrue(book.snapshot().cashUsd >= 0.0)
    }
}
