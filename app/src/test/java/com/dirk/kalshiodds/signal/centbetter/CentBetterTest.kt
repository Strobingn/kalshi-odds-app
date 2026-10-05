package com.dirk.kalshiodds.signal.centbetter

import com.dirk.kalshiodds.signal.flowfade.MakerPaperBook
import com.dirk.kalshiodds.signal.latefav.LateFavoriteState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CentBetterTest {

    private val ticker = "KXBTC15M-26OCT041430-30"

    private fun inputs(
        fair: Double? = 0.60,
        tte: Long? = 600L,
        yesBid: Double? = 0.50,
        yesAsk: Double? = 0.53,
        noBid: Double? = 0.47,
        noAsk: Double? = 0.50,
        t: String = ticker
    ) = CentBetterRule.Inputs(t, 1_000L, tte, fair, yesBid, yesAsk, noBid, noAsk)

    @Test
    fun postsOneCentAboveTheBidOnTheFavoredSide() {
        val o = CentBetterRule.order(inputs(), alreadyEntered = false)!!
        assertEquals("YES", o.side)
        assertEquals(0.51, o.price, 1e-9)
        assertEquals(0.0, o.queueAhead, 0.0)
        assertEquals(0.09, o.imbalance, 1e-9) // edge = 0.60 - 0.51
        assertEquals(600L, o.tteSeconds)
    }

    @Test
    fun picksNoWhenTheFairLeansDown() {
        val o = CentBetterRule.order(inputs(fair = 0.40), alreadyEntered = false)!!
        assertEquals("NO", o.side)
        assertEquals(0.48, o.price, 1e-9)
        assertEquals(0.12, o.imbalance, 1e-9)
    }

    @Test
    fun needsMoreThanTheMargin() {
        // YES at 0.51 vs fair 0.53 → edge exactly 0.02: not enough.
        assertNull(CentBetterRule.order(inputs(fair = 0.53), alreadyEntered = false))
        assertNotNull(CentBetterRule.order(inputs(fair = 0.54), alreadyEntered = false))
    }

    @Test
    fun noRoomWhenTheSpreadIsOneCent() {
        // bid + 1¢ would equal the ask on both sides.
        assertNull(CentBetterRule.order(inputs(yesBid = 0.50, yesAsk = 0.51, noBid = 0.49, noAsk = 0.50), alreadyEntered = false))
    }

    @Test
    fun asksDerivedFromTheOtherSidesBid() {
        val o = CentBetterRule.order(inputs(yesAsk = null, noAsk = null), alreadyEntered = false)!!
        assertEquals("YES", o.side)
        assertEquals(0.51, o.price, 1e-9)
    }

    @Test
    fun timeBandEntryAndInputsGate() {
        assertNull(CentBetterRule.order(inputs(tte = 179L), alreadyEntered = false))
        assertNull(CentBetterRule.order(inputs(tte = 781L), alreadyEntered = false))
        assertNotNull(CentBetterRule.order(inputs(tte = 180L), alreadyEntered = false))
        assertNotNull(CentBetterRule.order(inputs(tte = 780L), alreadyEntered = false))
        assertNull(CentBetterRule.order(inputs(tte = null), alreadyEntered = false))
        assertNull(CentBetterRule.order(inputs(fair = null), alreadyEntered = false))
        assertNull(CentBetterRule.order(inputs(fair = Double.NaN), alreadyEntered = false))
        assertNull(CentBetterRule.order(inputs(), alreadyEntered = true))
        assertNull(CentBetterRule.order(inputs(t = "KXNOTCRYPTO-X"), alreadyEntered = false))
    }

    @Test
    fun queueZeroMeansTheFirstPrintAtOurPriceFills() {
        val o = CentBetterRule.order(inputs(), alreadyEntered = false)!!
        val book = MakerPaperBook(cancelMs = CentBetterRule.CANCEL_MS)
        assertTrue(book.post(o))
        // Taker buying YES does not sell into a YES bid.
        assertNull(book.onTrade(ticker, "yes", 0.51, 10.0, 2_000L))
        // Taker buying NO with YES printing at our 51¢ hits us.
        assertNotNull(book.onTrade(ticker, "no", 0.51, 1.0, 3_000L))
    }

    @Test
    fun cancelsAfterThirtySeconds() {
        val o = CentBetterRule.order(inputs(), alreadyEntered = false)!!
        val book = MakerPaperBook(cancelMs = CentBetterRule.CANCEL_MS)
        book.post(o)
        assertNull(book.onTrade(ticker, "no", 0.51, 5.0, 1_000L + 30_001L))
        assertNull(book.pending(ticker))
    }

    @Test
    fun summaryUsesItsOwnTitleTargetAndNote() {
        val s = CentBetterSummary.of(LateFavoriteState())
        assertEquals(CentBetterSummary.TITLE, s.title)
        assertTrue(s.recordLine, s.recordLine.contains("/ ${CentBetterSummary.TARGET_SETTLED} settled"))
        assertEquals(CentBetterSummary.NOTE, s.note)
        assertTrue(s.note.startsWith("Paper only."))
    }
}
