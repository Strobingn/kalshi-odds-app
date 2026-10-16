package com.dirk.kalshiodds.release

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dirk.kalshiodds.decision.ScalpParams
import com.dirk.kalshiodds.decision.ScalpRule
import com.dirk.kalshiodds.decision.ScalpState
import com.dirk.kalshiodds.decision.ScalpTrade
import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.config.SignalPreferences
import com.dirk.kalshiodds.signal.paper.PaperBook
import com.dirk.kalshiodds.signal.paper.PaperBankrollReset0340
import com.dirk.kalshiodds.signal.paper.PaperBookState
import com.dirk.kalshiodds.signal.paper.PaperFill
import com.dirk.kalshiodds.ui.AppRoutes
import com.dirk.kalshiodds.ui.DipNav
import com.dirk.kalshiodds.ui.ScalpData
import com.dirk.kalshiodds.ui.ScorecardCopy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ReleaseGate0340BankrollAndScalpDataTest {
    private fun fill(id: String, pnl: Double?) = PaperFill(
        id = id, ticker = "KXBTC15M-A", side = "YES", stakeUsd = 10.0, contracts = 20, limitPrice = 0.5,
        source = "test", createdAtMs = 9L, note = "",
    ).let { if (pnl != null) it.copy(settled = true, pnlUsd = pnl) else it }

    @Test
    fun defaultPaperBankrollIsTenThousand() {
        // 0.3.46 moved the default to $20,000; the 0.3.40 migration keeps its historical $10,000 target.
        assertEquals(10_000.0, PaperBankrollReset0340.TARGET, 0.0)
        assertEquals(20_000.0, SignalConstants.PAPER_START_USD, 0.0)
        assertEquals(20_000.0, PaperBookState().startingUsd, 0.0)
        assertEquals(20_000.0, PaperBookState().cashUsd, 0.0)
    }

    @Test
    fun upgradeResetsBankrollToTenThousandKeepsHistoryAndMarksResetPoint() {
        // An existing 0.3.39 book: $1,000 start, a few settled fills.
        val old = PaperBookState(startingUsd = 1_000.0, cashUsd = 1_012.0,
            fills = listOf(fill("a", 5.0), fill("b", 7.0)), lifetimeRealizedPnlUsd = 12.0)
        val book = PaperBook(initial = old, nowMs = { 1_760_000_000_000L })
        assertEquals(10_000.0, PaperBankrollReset0340.apply(book), 0.0)
        val s = book.snapshot()
        assertEquals(10_000.0, s.startingUsd, 1e-9)
        assertEquals(10_000.0, s.cashUsd, 1e-9)
        assertEquals(10_000.0, s.paperBankrollUsd, 1e-9)
        assertEquals(10_000.0, book.startUsd, 1e-9)
        // History kept (archived, not deleted).
        assertEquals(setOf("a", "b"), s.archivedFills().map { it.id }.toSet())
        assertEquals(10_000.0, s.archived.last().resetToUsd!!, 1e-9)
        // Reset point is marked on the scorecard.
        val marker = ScorecardCopy.resetMarkerLine(s)
        assertNotNull(marker)
        assertTrue(marker!!, marker.contains("10,000") && marker.contains("2 earlier fills archived"))
        assertTrue(ScorecardCopy.of(emptyList(), s).allLines().any { it.startsWith("── Reset point") })
    }

    @Test
    fun freshInstallStartsAtTenThousandWithoutAnEmptyArchive() {
        val book = PaperBook()
        com.dirk.kalshiodds.signal.paper.PaperBankrollReset0346.apply(book)
        assertTrue(book.snapshot().archived.isEmpty())
        assertNull(ScorecardCopy.resetMarkerLine(book.snapshot()))
        assertEquals(20_000.0, book.snapshot().cashUsd, 1e-9)
    }

    @Test
    fun migrationIsOneTimeAndSetsTheSettingsStart() = runBlocking {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val prefs = SignalPreferences(ctx)
        val book = PaperBook(initial = PaperBookState(startingUsd = 1_000.0, cashUsd = 990.0, fills = listOf(fill("c", -10.0)), lifetimeRealizedPnlUsd = -10.0))
        prefs.applyPaperBankrollReset0340IfNeeded(book) // may be first run or already flagged by the app's own startup
        assertTrue(prefs.settings.first().paperBankrollStartUsd in setOf(10_000.0, 20_000.0)) // 0.3.46 startup may already have bumped it
        val n = book.snapshot().archived.size
        prefs.applyPaperBankrollReset0340IfNeeded(book) // flagged now: never again
        assertEquals(n, book.snapshot().archived.size)
        val app = listOf(java.io.File("app/src/main/java/com/dirk/kalshiodds/KalshiOddsApp.kt"), java.io.File("src/main/java/com/dirk/kalshiodds/KalshiOddsApp.kt"))
            .first { it.isFile }.readText()
        assertTrue(app.indexOf("applyPaperBankrollReset0340IfNeeded") in 1 until app.indexOf("applyPaperBankrollReset0328IfNeeded"))
    }

    @Test
    fun manualSetAndResetControlExists() {
        val src = listOf(java.io.File("app/src/main/java/com/dirk/kalshiodds/ui"), java.io.File("src/main/java/com/dirk/kalshiodds/ui"))
            .first { it.isDirectory }
        assertTrue(java.io.File(src, "SettingsViewModel.kt").readText().contains("fun resetPaperBookTo("))
        assertTrue(java.io.File(src, "SettingsScreen.kt").readText().contains("resetPaperBankrollTo20k()"))
    }

    // ---- Scalp Data ----
    private fun trip(id: String, net: Double, closed: Long, fees: Double = 0.3) = ScalpTrade(
        id = id, ticker = "KXBTC15M-26OCT091015-15", side = "YES", state = ScalpState.CLOSED, signalAtMs = closed - 60_000,
        signalAsk = 0.4, fairAtSignal = 0.5, contracts = 10, entryPrice = 0.4, entryFeeUsd = fees / 2, entryAtMs = closed - 57_000,
        soldContracts = 10, proceedsUsd = 4.0 + net + fees, exitFeeUsd = fees / 2, closedAtMs = closed, netUsd = net,
        exitReason = "profit target hit", ruleVersion = "${ScalpRule.VERSION}|${ScalpParams.CLASSIC.id}|P"
    )

    @Test
    fun scalpDataSummaryEquityCurveAndEveryRoundTrip() {
        val trades = listOf(trip("w1", 2.0, 1_000L), trip("l1", -1.0, 2_000L), trip("w2", 0.5, 3_000L), trip("b", 0.0, 4_000L),
            ScalpTrade("o", "KXBTC15M-26OCT091015-15", "NO", ScalpState.OPEN, 5_000L, 0.3, 0.4))
        val s = ScalpData.summary(trades)
        assertEquals(1.5, s.netUsd, 1e-9)
        assertEquals(2.5, s.grossWinsUsd, 1e-9)
        assertEquals(-1.0, s.grossLossesUsd, 1e-9)
        assertEquals(2, s.wins); assertEquals(1, s.losses); assertEquals(1, s.breakeven)
        assertEquals(1.25, s.avgWinUsd!!, 1e-9); assertEquals(-1.0, s.avgLossUsd!!, 1e-9)
        assertEquals(2.0, s.biggestWinUsd!!, 1e-9); assertEquals(-1.0, s.biggestLossUsd!!, 1e-9)
        assertEquals(-0.5, s.netExTopWinUsd, 1e-9)
        assertEquals(1.2, s.feesUsd, 1e-9)
        assertEquals(1, s.open)
        assertEquals(listOf(0.0, 2.0, 1.0, 1.5, 1.5), ScalpData.equityCurve(trades))
        val rows = ScalpData.tripLines(trades)
        assertEquals(4, rows.size)
        assertEquals("b", rows.first().key) // newest first
        assertTrue(rows.all { it.text.contains("in 40¢") && it.text.contains("fees $0.30") && it.text.contains(" ET") })
        assertTrue(ScalpData.summaryLines(s).any { it.startsWith("Net excluding the top win") })
    }

    @Test
    fun scalpDataIsEasyToReach() {
        assertTrue(AppRoutes.SCALP_DATA in AppRoutes.ALL)
        assertTrue(DipNav.moreDestinations.any { it.route == AppRoutes.SCALP_DATA })
        assertEquals("More → Scalp Data", DipNav.howToReach(AppRoutes.SCALP_DATA))
        val main = listOf(java.io.File("app/src/main/java/com/dirk/kalshiodds/MainActivity.kt"), java.io.File("src/main/java/com/dirk/kalshiodds/MainActivity.kt"))
            .first { it.isFile }.readText()
        // Home Scalp card tap opens Scalp Data.
        assertTrue(main.contains("onOpenScalp = { navigator.open(AppRoutes.SCALP_DATA) }"))
        assertTrue(main.contains("ScalpDataScreen("))
    }
}
