package com.dirk.kalshiodds.release

import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.paper.PaperBook
import com.dirk.kalshiodds.signal.paper.PaperBookWalk
import com.dirk.kalshiodds.signal.paper.PaperDepthLevels
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseGate0346Test {
    private fun src(p: String) = listOf(File("src/main/java/com/dirk/kalshiodds/$p"), File("app/src/main/java/com/dirk/kalshiodds/$p")).first { it.exists() }.readText()

    @Test fun paperBankrollIsTwentyThousand() {
        assertEquals(20_000.0, SignalConstants.PAPER_START_USD, 0.0)
        assertTrue(src("signal/config/SignalPreferences.kt").contains("KEY_PAPER_RESET_0346"))
        assertTrue(src("KalshiOddsApp.kt").contains("applyPaperBankrollReset0346IfNeeded"))
        val old = PaperBook(initial = com.dirk.kalshiodds.signal.paper.PaperBookState(startingUsd = 10_000.0, cashUsd = 9_500.0))
        com.dirk.kalshiodds.signal.paper.PaperBankrollReset0346.apply(old)
        assertEquals(20_000.0, old.snapshot().cashUsd, 1e-9)
        assertEquals(1, old.snapshot().archived.size)
        assertTrue(src("ui/SettingsScreen.kt").contains("PaperResetCopy.BUTTON"))
        assertTrue(src("ui/ScalpDataScreen.kt").contains("resetPaperBankrollTo20k"))
        assertEquals("Reset paper bankroll to $20,000", com.dirk.kalshiodds.ui.PaperResetCopy.BUTTON)
    }

    @Test fun resetArchivesHistoryAndMarksPoint() {
        val b = PaperBook(nowMs = { 123L })
        b.reset(20_000.0, "0.3.46 one-time reset")
        val s = b.snapshot()
        assertEquals(20_000.0, s.cashUsd, 1e-9)
        assertEquals(123L, s.archived.last().archivedAtMs)
        assertEquals(20_000.0, s.archived.last().resetToUsd!!, 1e-9)
    }

    @Test fun walkConsumesMultipleLevelsAtVwap() {
        // asks: 40c x 5, 41c x 5, 43c x 10 → 15 contracts = 5*.40 + 5*.41 + 5*.43 = 6.20 → VWAP 41.33c
        val w = PaperBookWalk.walk(listOf(0.40 to 5.0, 0.41 to 5.0, 0.43 to 10.0), 15)
        assertEquals(15, w.filled); assertEquals(3, w.levelsUsed)
        assertEquals(6.20 / 15, w.vwap, 1e-9)
        // capped at total displayed depth
        val cap = PaperBookWalk.walk(listOf(0.40 to 5.0, 0.41 to 5.0), 50)
        assertEquals(10, cap.filled); assertEquals(0.405, cap.vwap, 1e-9)
    }

    @Test fun depthLevelsAreBestFirst() {
        val yes = listOf(0.55 to 3.0, 0.57 to 4.0)
        val no = listOf(0.40 to 6.0, 0.42 to 2.0)
        // YES buy walks NO bids inverted: 1-.42=.58 first, then .60
        val ya = PaperDepthLevels.levels("YES", true, yes, no)
        assertEquals(0.58, ya[0].first, 1e-9); assertEquals(0.60, ya[1].first, 1e-9)
        val yb = PaperDepthLevels.levels("YES", false, yes, no)
        assertEquals(0.57, yb[0].first, 1e-9)
    }

    @Test fun paperMarketBuyWalksTheBook() {
        val b = PaperBook()
        b.reset(20_000.0)
        b.depth = { _, _, buy -> if (buy) listOf(0.40 to 5.0, 0.41 to 5.0, 0.43 to 10.0) else null }
        val out = b.explicitFill("KXBTC15M-26OCT091200-00", "YES", 0.40, 15, "test", "walk")
        assertTrue(out.message, out.ok)
        val f = b.snapshot().fills.first { !it.settled }
        assertEquals(15, f.contracts)
        assertEquals(6.20 / 15, f.limitPrice, 1e-9)
        // more than displayed depth → capped at 20
        val b2 = PaperBook(); b2.reset(20_000.0)
        b2.depth = { _, _, _ -> listOf(0.40 to 5.0, 0.41 to 5.0, 0.43 to 10.0) }
        assertTrue(b2.explicitFill("KXBTC15M-26OCT091200-00", "YES", 0.40, 100, "test", "cap").ok)
        assertEquals(20, b2.snapshot().fills.first { !it.settled }.contracts)
        // no depth → no fill
        val b3 = PaperBook(); b3.reset(20_000.0); b3.depth = { _, _, _ -> emptyList() }
        assertFalse(b3.explicitFill("KXBTC15M-26OCT091200-00", "YES", 0.40, 5, "test", "none").ok)
    }
}
