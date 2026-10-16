package com.dirk.kalshiodds.release

import com.dirk.kalshiodds.decision.AutopilotMinStake
import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.paper.AutopilotMode
import com.dirk.kalshiodds.signal.paper.AutopilotStep
import com.dirk.kalshiodds.signal.paper.PaperAutopilot
import com.dirk.kalshiodds.signal.paper.PaperBook
import com.dirk.kalshiodds.signal.paper.ShadowBook
import com.dirk.kalshiodds.ui.HomeFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 0.3.40 release gate: drives AutopilotStep.run — the exact per-market step OddsViewModel's
 * Autopilot loop calls — end to end with real PaperBook / ShadowBook / PaperAutopilot.
 * Nothing here re-implements the pipeline.
 */
class ReleaseGate0340SizingTest {
    private val now = HomeFixtures.NOW_MS
    private val settings = SignalSettings(
        paperTradingEnabled = true,
        aiPaperAutopilotEnabled = true,
        paperKellyFraction = 0.5
    )
    private val depth = 100_000
    private var ids = 0

    @Before fun reset() = PaperAutopilot.resetSession()

    private fun market() = HomeFixtures.market(
        ticker = "KXBTC15M-25SEP181700-50",
        seriesLabel = "Bitcoin",
        yesAsk = 0.20,
        aiYes = 80.0,
        predicted = "YES",
        closeMs = now + 372_000L,
        floorStrike = 67_000.0,
        spotUsd = 67_240.0,
        spotDelta = 240.0
    ).copy(regimeTag = "CHOP", sessionTag = "US", midVolPp = 0.80)

    private fun paperBook(startUsd: Double = 1_000.0) = PaperBook(idFactory = { "p${ids++}" }, nowMs = { now }).also {
        it.configure(kellyFraction = settings.paperKellyFraction, feeRate = settings.feeRate, startUsd = startUsd)
        it.reset(startUsd)
    }

    /** Two consecutive evaluations, as the real loop does (edge must hold twice). */
    private fun runTwice(
        mode: AutopilotMode,
        paper: PaperBook,
        shadow: ShadowBook,
        yesDepth: Int = depth
    ): AutopilotStep.Outcome {
        var last: AutopilotStep.Outcome? = null
        repeat(2) {
            last = AutopilotStep.run(
                paperBook = paper, shadowBook = shadow, market = market(), settings = settings,
                mode = mode, nowMs = now, yesAsk = 0.20, noAsk = 0.81, yesDepth = yesDepth, noDepth = depth,
                book = null, assessment = null, clientOrderId = "coid-${ids++}"
            )
        }
        return last!!
    }

    @Test
    fun paperKellyUnderFiveDollarsIsNoBet() {
        val paper = paperBook(startUsd = 15.0)
        val out = runTwice(AutopilotMode.PAPER, paper, ShadowBook())
        assertTrue("got $out / ${paper.snapshot().cashUsd} ${paper.snapshot().paperBankrollUsd}", out is AutopilotStep.Outcome.Skip)
        assertEquals(AutopilotMinStake.REASON, (out as AutopilotStep.Outcome.Skip).reason)
        assertTrue(paper.snapshot().fills.isEmpty())
    }

    @Test
    fun paperFillCappedUnderFiveDollarsByDepthIsNoBet() {
        // Big bankroll passes the pre-size check, but only 10 contracts @20¢ (~$2) are displayed.
        val paper = paperBook(startUsd = 1_000.0)
        val out = runTwice(AutopilotMode.PAPER, paper, ShadowBook(), yesDepth = 10)
        assertTrue("got $out", out is AutopilotStep.Outcome.Skip)
        assertEquals(AutopilotMinStake.REASON, (out as AutopilotStep.Outcome.Skip).reason)
        assertTrue(paper.snapshot().fills.isEmpty())
    }

    @Test
    fun paperKellyAtOrAboveFiveDollarsStillFills() {
        val paper = paperBook(startUsd = 1_000.0)
        val out = runTwice(AutopilotMode.PAPER, paper, ShadowBook())
        assertTrue(out is AutopilotStep.Outcome.Paper)
        val fill = paper.snapshot().fills.single()
        assertTrue(fill.stakeUsd >= AutopilotMinStake.USD)
    }

    @Test
    fun viewModelLoopDelegatesToAutopilotStep() {
        val src = listOf(
            java.io.File("app/src/main/java/com/dirk/kalshiodds/ui/OddsViewModel.kt"),
            java.io.File("src/main/java/com/dirk/kalshiodds/ui/OddsViewModel.kt")
        ).first { it.isFile }.readText()
        val body = src.substringAfter("private fun runPaperAutopilotOnce(").substringBefore("private fun assessForLedger(")
        assertTrue(body.contains("AutopilotStep.run("))
    }
}

class PaperBookMinStakeFloorTest {
    @Test
    fun autopilotFillCappedByCashUnderFiveDollarsIsSkipped() {
        val book = PaperBook(idFactory = { "x" }, nowMs = { HomeFixtures.NOW_MS })
        book.reset(4.0) // cash can only cover ~$4 — the cash cap pushes the clip under $5
        val fill = book.considerAutopilot(
            ticker = "KXBTC15M-25SEP181700-50", side = "YES", ask = 0.20, winProb = 0.9,
            depthContracts = 10_000, enabled = true, bankrollUsd = 1_000.0, maxStakeUsd = 50.0
        )
        org.junit.Assert.assertNull(fill)
        assertEquals(AutopilotMinStake.REASON, book.snapshot().lastMessage)
    }
}

class ReleaseGateFilesTrackedTest {
    private fun root(): java.io.File =
        listOf(java.io.File("."), java.io.File("..")).first { java.io.File(it, ".gitignore").isFile && java.io.File(it, "app").isDirectory }

    /** 0.3.38/0.3.39 release-gate tests were silently dropped by the ".gitignore" "release/" rule. */
    @Test
    fun releaseGateTestPackageIsNotGitIgnored() {
        val ig = java.io.File(root(), ".gitignore").readText()
        assertTrue(ig.contains("!app/src/test/java/com/dirk/kalshiodds/release/"))
    }

    @Test
    fun liveArmIsExcludedFromBackupAndDeviceTransfer() {
        val res = java.io.File(root(), "app/src/main/res/xml")
        assertTrue(java.io.File(res, "backup_rules.xml").readText().contains("kashi_live_autopilot_arm.xml"))
        val x = java.io.File(res, "data_extraction_rules.xml").readText()
        assertEquals(2, Regex("kashi_live_autopilot_arm\\.xml").findAll(x).count())
    }
}
