package com.dirk.kalshiodds.signal.lastminute

import com.dirk.kalshiodds.ui.HomeFixtures
import com.dirk.kalshiodds.ui.ScorecardCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LastMinuteCopyTest {

    @Test
    fun boxAndConfirmDoNotClaimALiveEdge() {
        assertEquals("Last-minute play", LastMinuteCopy.TITLE)
        assertEquals(
            "Unproven on live order books - tracking on paper",
            LastMinuteCopy.UNPROVEN_SUBTITLE
        )
        assertEquals("Model EV (unproven)", LastMinuteCopy.MODEL_EV_LABEL)
        assertEquals(
            "This strategy has not yet made money on live order books.",
            LastMinuteCopy.CONFIRM_UNPROVEN
        )
        assertEquals(LastMinuteCopy.UNPROVEN_SUBTITLE, ScorecardCopy.LAST_MINUTE_SUBTITLE)
        assertFalse(LastMinuteCopy.UNPROVEN_SUBTITLE.contains("edge", ignoreCase = true))
        assertFalse(LastMinuteCopy.UNPROVEN_SUBTITLE.contains("profit", ignoreCase = true))
        assertFalse(LastMinuteCopy.MODEL_EV_LABEL.contains("expected", ignoreCase = true))
    }

    @Test
    fun notificationIncludesPaperAndSideAtCents() {
        val fired = HomeFixtures.lastMinuteFiredBtc().lastMinute!!.fired!!
        val title = LastMinuteCopy.notificationTitle(fired)
        assertTrue(title.startsWith("Last-minute play (paper): BUY "))
        assertTrue(title.contains(" at "))
        assertTrue(title.contains("¢"))
        assertEquals(title, LastMinuteCopy.notificationBody(fired))
        assertTrue(title.contains("(paper)"))
        assertFalse(title.contains("EV"))
        assertFalse(title.contains("wins +"))
    }

    @Test
    fun modelEvAndRecordShowTrackingMoney() {
        assertEquals("Model EV (unproven) 1.25", LastMinuteCopy.modelEvLine(1.25))
        assertEquals("Won $12.50", LastMinuteCopy.wonUsdLine(12.50))
        assertEquals("Lost $8.00", LastMinuteCopy.lostUsdLine(8.00))
        assertEquals("Net P&L -4.00", LastMinuteCopy.netPnlLine(-4.00))
        val record = LastMinuteCopy.recordLine(3, 76, 3.0 / 79.0, 30.0, 694.0, -664.0)
        assertTrue(record.startsWith("3-76"))
        assertTrue(record.contains("won $30.00"))
        assertTrue(record.contains("lost $694.00"))
        assertTrue(record.contains("net -664.00"))
    }

    @Test
    fun scorecardPlacesLastMinuteNearTheTop() {
        val pick = HomeFixtures.lastMinuteScorecardUi().view.lastMinute
        val view = HomeFixtures.lastMinuteScorecardUi().view
        val lines = view.allLines()
        val lm = lines.indexOf(ScorecardCopy.LAST_MINUTE_TITLE)
        val combined = lines.indexOf(ScorecardCopy.COMBINED_TITLE)
        assertTrue(lm >= 0)
        assertTrue(combined < 0 || lm < combined)
        assertTrue(lines.contains(ScorecardCopy.LAST_MINUTE_SUBTITLE))
        assertTrue(lines.contains(LastMinuteCopy.wonUsdLine(pick.wonUsd)))
        assertTrue(lines.contains(LastMinuteCopy.lostUsdLine(pick.lostUsd)))
        assertTrue(lines.contains(LastMinuteCopy.netPnlLine(pick.pnlUsd)))
        assertTrue(pick.record.contains("won $"))
        assertTrue(pick.record.contains("lost $"))
        assertTrue(pick.record.contains("net "))
    }

    @Test
    fun screensShowUnprovenCopyOnBoxConfirmAndScorecard() {
        val card = read("ui/components/MarketCard.kt")
        assertTrue(card.contains("LastMinuteCopy.UNPROVEN_SUBTITLE"))
        assertTrue(card.contains("LastMinuteCopy.modelEvLine"))
        val confirm = read("ui/components/TradeTicketCard.kt")
        assertTrue(confirm.contains("LastMinuteCopy.CONFIRM_UNPROVEN"))
        assertTrue(confirm.contains("TicketKind.LAST_MINUTE"))
        val scorecard = read("ui/ScorecardScreen.kt")
        val lm = scorecard.indexOf("LastMinuteScorecardCard")
        val combined = scorecard.indexOf("ScorecardCopy.COMBINED_TITLE")
        assertTrue(lm in 0 until combined)
        assertTrue(scorecard.contains("LAST_MINUTE_SUBTITLE"))
        assertTrue(scorecard.contains("wonUsdLine"))
        assertTrue(scorecard.contains("lostUsdLine"))
        assertTrue(scorecard.contains("netPnlLine"))
        val notifier = read("signal/lastminute/LastMinuteNotifier.kt")
        assertTrue(notifier.contains("notificationTitle"))
        assertFalse(read("signal/lastminute/LastMinuteCopy.kt").contains("EV/$"))
    }

    private fun read(rel: String): String {
        val files = listOf(
            File("app/src/main/java/com/dirk/kalshiodds/$rel"),
            File("src/main/java/com/dirk/kalshiodds/$rel")
        )
        return files.first { it.isFile }.readText()
    }
}
