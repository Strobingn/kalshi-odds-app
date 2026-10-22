package com.dirk.kalshiodds.decision

import com.dirk.kalshiodds.data.local.paper.ScalpSchema
import com.dirk.kalshiodds.data.local.results.SqliteResultsStore
import com.dirk.kalshiodds.data.supabase.SupabaseSync
import com.dirk.kalshiodds.data.supabase.SyncMerge
import com.dirk.kalshiodds.decision.insights.ScalpInsights
import com.dirk.kalshiodds.domain.EtTime
import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.paper.PaperBook
import com.dirk.kalshiodds.ui.PaperResetCopy
import com.dirk.kalshiodds.ui.ScalpBankrollResetCopy
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScalpBankrollResetTest {
    @After fun clearNote() { ScalpBankrollReset.clearDisplayedNote() }

    private fun src(p: String) = listOf(
        File("src/main/java/com/dirk/kalshiodds/$p"),
        File("app/src/main/java/com/dirk/kalshiodds/$p")
    ).first { it.exists() }.readText()

    private fun closed(id: String, net: Double) = ScalpTrade(
        id = id, ticker = "KXBTC15M-26OCT091015-15", side = "YES", state = ScalpState.CLOSED,
        signalAtMs = 1L, signalAsk = 0.20, fairAtSignal = 0.40, contracts = 10, entryPrice = 0.20,
        entryFeeUsd = 0.11, entryAtMs = 2L, soldContracts = 10, proceedsUsd = 2.0, exitFeeUsd = 0.0,
        closedAtMs = 9L, netUsd = net, ruleVersion = "v|v2-dip-g05-t08-s06-d10-w180|P"
    )

    @Test fun pendingClipBecomesNoFillAndOpenClipFlattensAtEntry() {
        val pending = closed("p", 0.0).copy(state = ScalpState.PENDING_ENTRY, entryPrice = null, entryFeeUsd = 0.0, soldContracts = 0, proceedsUsd = 0.0, closedAtMs = null, netUsd = null)
        val noFill = ScalpBankrollReset.flatten(pending, 50L)
        assertEquals(ScalpState.NO_FILL, noFill.state)
        assertNull(noFill.netUsd)
        assertTrue(noFill.note.contains("no Kalshi order"))

        val open = closed("o", 0.0).copy(state = ScalpState.OPEN, soldContracts = 0, proceedsUsd = 0.0, exitFeeUsd = 0.0, closedAtMs = null, netUsd = null)
        val flat = ScalpBankrollReset.flatten(open, 50L)
        assertEquals(ScalpState.CLOSED, flat.state)
        assertEquals(10, flat.soldContracts)
        assertEquals(0.0, flat.exitFeeUsd, 0.0)
        assertEquals(-open.entryFeeUsd, flat.netUsd!!, 1e-9)
        assertEquals("scalping bankroll reset", flat.exitReason)
        assertTrue(flat.note.contains("no Kalshi order"))
    }

    @Test fun resetArchivesHistoryAndStartsDisplayedStatsAtZero() {
        val at = 1_761_158_460_000L
        val store = InMemoryScalpPersistence()
        val open = closed("open", 0.0).copy(state = ScalpState.OPEN, soldContracts = 0, proceedsUsd = 0.0, exitFeeUsd = 0.0, closedAtMs = null, netUsd = null)
        store.upsertScalp(open)
        store.upsertScalpAndStats(closed("old", -4.25), null)
        val book = ScalpBook(store)
        val code = "v2-dip-g05-t08-s06-d10-w180"
        val controls = MemoryScalpControls(ScalpControls(
            manual = true, stopCents = 8,
            strategySettings = mapOf(code to ScalpStrategySettings(
                contracts = 4, minEntryCents = 15, maxEntryCents = 55, stopCents = 6,
                aiLearns = true, aiBaseCents = 40, aiN = 12, aiNet = -3.5, aiNote = "tuned on the old run"
            )),
            algo = mapOf(code to mapOf("maxEntryCents" to 48.0))
        ))
        controls.recordOutcome(code, closed("old", -4.25))
        val insights = ScalpInsights(book, controls, store) {}
        insights.start()
        assertEquals(listOf(-4.25), insights.tapes(book.stats.value).first { it.code == code }.recentReal)
        assertEquals(1, controls.feedback(code).trades)
        val probe = "reset-probe"
        ScalperBrain.learn(probe, doubleArrayOf(1.0, 0.0, 0.0, 0.0), false)
        assertTrue(ScalperBrain.summary(probe).contains("AI learned 1 ·"))
        val paper = PaperBook(nowMs = { at })
        var cashReset = false
        val before = controls.state.value
        val msg = ScalpBankrollReset.apply(book, controls, insights, at) {
            cashReset = true
            paper.reset(SignalConstants.PAPER_START_USD, "scalping bankroll reset")
        }
        assertTrue(cashReset)
        assertEquals(20_000.0, paper.snapshot().cashUsd, 0.0)
        assertEquals(1, paper.snapshot().archived.size)
        assertTrue(book.snapshot().isEmpty())
        assertEquals(0, book.flushWrites())
        assertTrue(book.stats.value.all { it.n == 0 })
        assertTrue(store.pageTrades(code, null, null, 20).isEmpty())
        assertTrue(store.loadActiveScalps().isEmpty())
        assertEquals(2, store.tradeCount())
        assertTrue(store.unsyncedClosed(10).map { it.id }.containsAll(listOf("old", "open")))
        assertEquals(msg, store.unsyncedScalpResets().single().note)
        assertEquals(20_000.0, store.unsyncedScalpResets().single().cashUsd, 0.0)
        val after = controls.state.value
        val kept = before.settings(code)
        val fresh = after.settings(code)
        assertEquals(0, fresh.aiN)
        assertEquals(0.0, fresh.aiNet, 0.0)
        assertEquals(kept.minEntryCents, fresh.minEntryCents)
        assertEquals(kept.maxEntryCents, fresh.maxEntryCents)
        assertEquals(kept.contracts, fresh.contracts)
        assertEquals(kept.stopCents, fresh.stopCents)
        assertEquals(kept.aiBaseCents, fresh.aiBaseCents)
        assertTrue(fresh.aiLearns)
        assertTrue(after.manual)
        assertEquals(8, after.stopCents)
        assertEquals(48.0, after.algo[code]?.get("maxEntryCents")!!, 0.0)
        assertEquals(0, controls.feedback(code).trades)
        assertTrue(insights.tapes(book.stats.value).first { it.code == code }.recentReal.isEmpty())
        assertTrue(ScalperBrain.summary(probe).contains("AI learned 0 ·"))
        assertTrue(msg.contains(EtTime.fullDateClock(at)))
        assertTrue(msg.contains("$20,000"))
        assertTrue(msg.contains("ET"))
        assertTrue(msg.contains("No Kalshi orders"))
        store.upsertScalpAndStats(closed("fresh", 1.25).copy(closedAtMs = at + 5_000), null)
        book.reloadStats()
        assertEquals(listOf("fresh"), store.pageTrades(code, null, null, 10).map { it.id })
        assertEquals(1, book.stats.value.filter { it.scalperId == code }.sumOf { it.n })
        assertEquals(3, store.tradeCount())
    }

    @Test fun confirmationAndSyncMarkerDoNotDeleteHistory() {
        val at = 1_761_158_460_000L
        val msg = ScalpBankrollReset.confirmation(at)
        assertTrue(msg.contains("$20,000"))
        assertTrue(msg.contains(EtTime.fullDateClock(at)))
        assertTrue(msg.contains("ET"))
        val marker = ScalpResetMarker(at, msg, 12, SignalConstants.PAPER_START_USD)
        val packed = SupabaseSync().pack(SupabaseSync.LocalBundle(
            scalps = listOf(closed("kept", -1.0)),
            scalpResets = listOf(marker)
        ))
        assertEquals(1, packed.count { it.kind == "scalp" })
        val row = packed.single { it.kind == "scalp_reset" }
        assertFalse(row.deleted)
        assertTrue(row.key.contains("reset"))
        assertTrue(row.payload.contains("20000"))
        assertTrue(row.payload.contains("archivedN"))
        assertFalse(row.payload.contains("DELETE"))
        assertEquals(listOf(row.kind), SyncMerge.outgoing(listOf(row.copy(updatedAtMs = 5L)), 9_000L).map { it.kind })
        assertFalse(SupabaseSync.PULL_KINDS.contains("scalp_reset"))
        val sql = ScalpSchema.upgradeSql(ScalpSchema.FROM_VERSION).joinToString("\n")
        assertTrue(sql.contains("archived"))
        assertTrue(sql.contains("scalp_resets"))
        assertFalse(sql.contains("DROP"))
        assertEquals(11, ScalpSchema.VERSION)
        assertEquals(13, SqliteResultsStore.DB_VERSION)
    }

    @Test fun scalpTabAndScalpDataShareTheResetLabel() {
        val arena = src("ui/KashiArenaScreen.kt")
        assertTrue(arena.contains("ScalpBankrollResetCopy.BUTTON"))
        assertTrue(arena.contains("ScalpBankrollResetCopy.CONFIRM"))
        assertTrue(arena.contains("RESET_SCALP_BANKROLL"))
        val data = src("ui/ScalpDataScreen.kt")
        assertTrue(data.contains("ScalpBankrollResetCopy.BUTTON"))
        assertTrue(data.contains("resetPaperBankrollTo20k"))
        assertEquals("Reset scalping bankroll", ScalpBankrollResetCopy.BUTTON)
        assertEquals("Tap again to reset — history is archived", ScalpBankrollResetCopy.CONFIRM)
        assertEquals("Reset paper bankroll to $20,000", PaperResetCopy.BUTTON)
        assertTrue(src("ui/SettingsScreen.kt").contains("PaperResetCopy.BUTTON"))
    }
}
