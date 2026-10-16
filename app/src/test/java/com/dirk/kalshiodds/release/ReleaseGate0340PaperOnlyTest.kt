package com.dirk.kalshiodds.release

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dirk.kalshiodds.decision.StrategyLadder
import com.dirk.kalshiodds.signal.paper.AutopilotMode
import com.dirk.kalshiodds.signal.paper.LiveOffMigration
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 0.3.40 owner decision: Autopilot and Scalp are PAPER-ONLY. No armed state, no live ladder stage,
 * no path from Autopilot/Scalp to an order. Manual tickets (Approve + typed REAL MONEY) are unchanged.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ReleaseGate0340PaperOnlyTest {
    private val main: File = listOf(File("app/src/main/java/com/dirk/kalshiodds"), File("src/main/java/com/dirk/kalshiodds"))
        .first { it.isDirectory }

    private fun src(rel: String) = File(main, rel).readText()

    /** Words that would mean a route to a real order. */
    private val orderWords = listOf("createLimit", "createOrder", "placeOrder", "tradeClient", "ticketSession", "TicketSession", "KalshiTradeClient")

    @Test
    fun autopilotAndScalpCodeCannotReachPlaceOrder() {
        listOf(
            "signal/paper/AutopilotStep.kt", "signal/paper/PaperAutopilot.kt", "signal/paper/PaperBook.kt",
            "signal/paper/ShadowBook.kt", "signal/paper/ShadowOrderPayload.kt", "signal/paper/AlwaysOn.kt",
            "decision/Scalp.kt", "decision/ScalpTuning.kt"
        ).forEach { f ->
            val text = src(f)
            orderWords.forEach { w -> assertFalse("$f mentions $w", text.contains(w)) }
        }
        // The ViewModel's Autopilot / Scalp / ladder loops never touch the order client or ticket session.
        val vm = src("ui/OddsViewModel.kt")
        listOf("private fun runPaperAutopilot(", "private fun runPaperAutopilotOnce(", "private fun runScalp(", "private fun runFav15Ladder(")
            .forEach { start ->
                assertTrue("missing $start", vm.contains(start))
                val body = vm.substringAfter(start).let { rest ->
                    val next = Regex("\n    (private |internal |override )?fun ").find(rest)?.range?.first ?: rest.length
                    rest.substring(0, next)
                }
                orderWords.forEach { w -> assertFalse("$start calls $w", body.contains(w)) }
            }
        // The ONLY production call to createLimit is TicketSession's placeOrder (manual Approve + REAL MONEY).
        val callers = main.walkTopDown().filter { it.isFile && it.extension == "kt" && !it.path.contains("/data/api/") }
            .filter { it.readText().contains("createLimit(") }.map { it.name }.toList()
        assertEquals(listOf("AppContainer.kt"), callers)
        val container = src("AppContainer.kt")
        assertTrue(container.substringBefore("createLimit(").substringAfterLast("TicketSession(").isNotEmpty())
        assertTrue(src("signal/trade/TicketSession.kt").contains("The only entry that may call [placeOrder]"))
    }

    @Test
    fun noLiveArmingOrLiveStageRemains() {
        assertEquals(listOf("PAPER", "SHADOW"), AutopilotMode.entries.map { it.name })
        assertEquals(AutopilotMode.PAPER, AutopilotMode.parse("LIVE"))
        assertEquals(listOf("PAPER", "SHADOW"), StrategyLadder.Stage.values().map { it.name })
        StrategyLadder.Id.values().forEach { assertTrue(it.maxStage.ordinal <= StrategyLadder.Stage.SHADOW.ordinal) }
        assertEquals(StrategyLadder.Stage.PAPER, StrategyLadder.Id.SCALP.maxStage)
        listOf("signal/paper/LiveAutopilotSession.kt", "signal/paper/AutopilotDispatch.kt", "signal/paper/LiveAutopilotGate.kt")
            .forEach { assertFalse(it, File(main, it).exists()) }
        val vm = src("ui/OddsViewModel.kt")
        assertFalse(vm.contains("liveAutopilotSession") || vm.contains("confirmLiveAutopilotRealMoney") || vm.contains("liveArm"))
    }

    @Test
    fun upgradeWithPersistedArmingDeletesItAndForcesPaper() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        // State left by 0.3.39: armed live Autopilot.
        ctx.getSharedPreferences(LiveOffMigration.LEGACY_ARM_PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("armed", true).putLong("armed_at_ms", 123L).commit()
        assertTrue(LiveOffMigration.deleteLegacyArming(ctx))
        assertTrue(ctx.getSharedPreferences(LiveOffMigration.LEGACY_ARM_PREFS, Context.MODE_PRIVATE).all.isEmpty())
        // Idempotent on every later start.
        assertFalse(LiveOffMigration.deleteLegacyArming(ctx))
        // Stored legacy mode "LIVE" is rewritten to PAPER; valid modes are left alone.
        assertEquals("PAPER", LiveOffMigration.rewriteMode("LIVE"))
        assertNull(LiveOffMigration.rewriteMode("PAPER"))
        assertNull(LiveOffMigration.rewriteMode("SHADOW"))
        val app = src("KalshiOddsApp.kt")
        assertTrue(app.indexOf("LiveOffMigration.deleteLegacyArming") in 1 until app.indexOf("container = AppContainer(this)"))
        assertTrue(app.contains("migrateAutopilotModePaperOnly"))
    }
}
