package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.prediction.PredictionLogEntry
import com.dirk.kalshiodds.signal.feedback.ScorecardMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ScorecardCopyTest {

    @Test
    fun emptyStateAbsentWhenSettledPicksPresentEvenIfPerSeriesEmpty() {
        val entries = HomeFixtures.sampleSettledEntries()
        val view = ScorecardCopy.of(entries, 12.40)
        val window = ScorecardMetrics.window(ScorecardCopy.settledPicks(entries))
        val contradiction = ScorecardMetrics.Snapshot(
            daily = window,
            rolling = window,
            allTime = window,
            perSeries = emptyList(),
            sampleCount = window.total,
            openCount = 2,
            voidCount = 0,
            calibrationReady = false,
            temperature = null,
            calibrationSamples = 0,
            honest = ScorecardMetrics.honest(ScorecardCopy.settledPicks(entries))
        )
        assertTrue("0.3.13 fixture: perSeries empty while settled picks exist", contradiction.perSeries.isEmpty())
        assertEquals(18, contradiction.sampleCount)
        assertEquals(18, contradiction.honest.n)
        assertEquals(18, view.settledCount)
        assertEquals(12, view.summary.wins)
        assertEquals(6, view.summary.losses)
        assertFalse(view.showsEmptyState)
        assertNull(view.emptyState())
        assertFalse(view.allLines().any { it.contains("No settled samples") })
        assertFalse(view.allLines().any { it.contains(ScorecardCopy.NO_SETTLED) })
        assertEquals(ScorecardCopy.NO_SETTLED, ScorecardCopy.emptyState(0))
        assertNull(ScorecardCopy.emptyState(18))
        assertFalse(ScorecardCopy.showsEmptyState(18))
        assertTrue(ScorecardCopy.showsEmptyState(0))
    }

    @Test
    fun emptyStatePresentWhenZeroPicks() {
        val view = ScorecardCopy.of(emptyList(), 99.0)
        assertEquals(0, view.settledCount)
        assertTrue(view.showsEmptyState)
        assertEquals(ScorecardCopy.NO_SETTLED, view.emptyState())
        assertTrue(view.allLines().contains(ScorecardCopy.NO_SETTLED))
        assertTrue(view.timeOfDay.all { it.settledCount == 0 && it.line.endsWith(ScorecardCopy.EM_DASH) })
        assertTrue(view.recent.isEmpty())
        assertFalse(view.allLines().any { it.contains("0%") })
        assertFalse(view.allLines().any { it.contains("0-0") })
        assertFalse(view.allLines().any { it.contains("By coin") })
    }

    @Test
    fun filtersStoredEthSolAndDropsCoinSection() {
        val entries = HomeFixtures.sampleSettledEntries()
        assertTrue(entries.any { it.ticker.startsWith("KXETH15M") })
        assertTrue(entries.any { it.ticker.startsWith("KXSOL15M") })
        val settled = ScorecardCopy.settledPicks(entries)
        assertEquals(18, settled.size)
        assertTrue(settled.all { it.ticker.startsWith("KXBTC15M") })
        assertTrue(settled.none { it.ticker.startsWith("KXETH") || it.ticker.startsWith("KXSOL") })
        val view = ScorecardCopy.of(entries, 12.40)
        assertEquals(18, view.settledCount)
        assertEquals("12-6", ScorecardCopy.recordLine(view.summary))
        assertEquals("67%", ScorecardCopy.winRateLine(view.summary))
        assertEquals("paper +$12.40", ScorecardCopy.paperPnlLine(view.summary))
        assertEquals("18 settled", ScorecardCopy.settledCountLine(view.summary))
        assertFalse(view.allLines().any { it.contains("By coin") })
        assertFalse(view.allLines().any { it.contains("SOL ") })
        assertFalse(view.allLines().any { it.contains("ETH ") })
        assertTrue(view.timeOfDay.any { it.settledCount > 0 })
        assertEquals("SOL  —", ScorecardCopy.bucketLine("SOL", 0, 0, 0, null))
        assertEquals("—", ScorecardCopy.percentOrDash(null))
    }

    @Test
    fun recentPicksComeFromTheSameSettledList() {
        val entries = HomeFixtures.sampleSettledEntries() + PredictionLogEntry(
            ticker = "KXSOL15M-NOBET",
            series = "KXSOL15M",
            predictedYes = 0.55,
            predictedNo = 0.45,
            marketMid = 0.50,
            timestampMs = 9_000L,
            closeTimeMs = 9_000L,
            outcome = "yes",
            predictedSide = "NO_BET"
        )
        val view = ScorecardCopy.of(entries, 12.40)
        assertEquals(18, view.recent.size)
        assertTrue(view.recent.all { it.ticker.startsWith("KXBTC15M") })
        assertTrue(view.recent.none { it.ticker.contains("NOBET") })
        assertTrue(view.recent.none { it.ticker.startsWith("KXETH") || it.ticker.startsWith("KXSOL") })
        val first = view.recent.first()
        assertTrue(first.line.contains(first.coin))
        assertTrue(first.line.contains(first.side))
        assertTrue(first.line.contains(if (first.won) ScorecardCopy.WON else ScorecardCopy.LOST))
        assertTrue(first.settledAtMs >= view.recent.last().settledAtMs)
    }

    @Test
    fun screenUsesScorecardCopyAndNeutralPnlColors() {
        val src = listOf(
            File("app/src/main/java/com/dirk/kalshiodds/ui/ScorecardScreen.kt"),
            File("src/main/java/com/dirk/kalshiodds/ui/ScorecardScreen.kt")
        ).first { it.isFile }.readText()
        assertTrue(src.contains("ScorecardCopy.NO_SETTLED"))
        assertTrue(src.contains("view.showsEmptyState"))
        assertFalse(src.contains("perSeries"))
        assertFalse(src.contains("No settled samples yet"))
        assertFalse(src.contains("Color.Green"))
        assertFalse(src.contains("Color.Red"))
        assertFalse(src.contains("accentGreen"))
        assertFalse(src.contains("accentRed"))
        assertFalse(src.contains("COINS_TITLE"))
        assertFalse(src.contains("By coin"))
        assertTrue(src.contains("NeutralStat(\"Paper P&L\""))
        assertTrue(src.contains("NeutralStat(\"Win rate\""))
        assertTrue(src.contains("SideColor.of"))
        assertTrue(src.contains("rememberSaveable"))
        assertTrue(src.contains("mutableStateOf(false)"))
    }
}
