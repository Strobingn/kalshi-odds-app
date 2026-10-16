package com.dirk.kalshiodds.release

import com.dirk.kalshiodds.decision.ScalpModels
import com.dirk.kalshiodds.ui.ScalpTabCopy
import com.dirk.kalshiodds.ui.ScalpTabFixtures
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseGate0351Test {
    private fun src(p: String) = listOf(File("src/main/java/com/dirk/kalshiodds/$p"), File("app/src/main/java/com/dirk/kalshiodds/$p")).first { it.exists() }.readText()

    @Test fun coinHeadlineIsPlain() {
        assertEquals("BTC — leader: Extreme-reversion, −$2.84 today", ScalpTabCopy.coinHeadline("BTC", ScalpTabFixtures.board(0)))
        assertEquals("ETH — no round trips today", ScalpTabCopy.coinHeadline("ETH", emptyList()))
    }

    @Test fun chipsShowCoinAndWindowEnd() {
        assertEquals("BTC 15m · 11:00 AM", ScalpModels.chipLabel("KXBTC15M-26OCT101100-00", null))
        assertEquals("SOL 15m · 11:00 AM", ScalpModels.chipLabel("KXSOL15M-26OCT101100-00", null))
    }

    @Test fun liveDot() {
        assertEquals(ScalpTabCopy.Live.GREEN, ScalpTabCopy.live(0))
        assertEquals(ScalpTabCopy.Live.AMBER, ScalpTabCopy.live(5_000))
        assertEquals(ScalpTabCopy.Live.RED, ScalpTabCopy.live(null))
        assertEquals("12:37 left", ScalpTabCopy.timeLeft(757))
    }

    @Test fun layoutRules() {
        val ui = src("ui/ScalpTabScreen.kt")
        assertTrue(ui.contains("if (st.heldContracts > 0)")) // sell-at buttons hidden when flat
        assertFalse(ui.contains("book age"))
        assertFalse(ui.contains("Tap = BUY"))
        assertFalse(ui.contains("tradeClient")) // paper only
        // raw per-model detail moved to Scalp Data, not deleted
        assertTrue(src("ui/ScalpDataScreen.kt").contains("ScalpTabCopy.detailLines"))
    }
}
