package com.dirk.kalshiodds.data.supabase

import com.dirk.kalshiodds.data.local.results.InMemoryResultsStore
import com.dirk.kalshiodds.signal.paper.PaperFill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SupabaseSyncPaperTest {

    @Test
    fun packAndApplyRoundTripKellyFields() {
        val fill = PaperFill(
            id = "p-sync",
            ticker = "KXBTC15M-A",
            side = "YES",
            stakeUsd = 42.0,
            contracts = 80,
            limitPrice = 0.50,
            source = "AI hunter",
            createdAtMs = 11L,
            settled = true,
            outcome = "yes",
            won = true,
            pnlUsd = 38.0,
            note = "Kelly",
            kellyF = 0.31,
            kellyFraction = 0.5,
            bankrollAfterUsd = 1_038.0
        )
        val sync = SupabaseSync()
        val packed = sync.pack(SupabaseSync.LocalBundle(paperFills = listOf(fill)))
        val rec = packed.single { it.kind == "paper" }
        assertTrue(rec.payload.contains("\"kellyF\""))
        assertTrue(rec.payload.contains("\"kellyFraction\""))
        assertTrue(rec.payload.contains("\"bankrollAfterUsd\""))
        val captured = mutableListOf<PaperFill>()
        sync.apply(
            packed,
            InMemoryResultsStore(),
            onSettings = {},
            onPaper = { captured += it }
        )
        val out = captured.single()
        assertEquals(0.31, out.kellyF!!, 1e-9)
        assertEquals(0.5, out.kellyFraction!!, 1e-9)
        assertEquals(1_038.0, out.bankrollAfterUsd!!, 1e-9)
        assertEquals("p-sync", out.id)
        assertEquals("KXBTC15M-A", out.ticker)
    }

    @Test
    fun packIncludesAiSourceAndReupsertsWhenSettled() {
        val open = PaperFill(
            id = "p-ai",
            ticker = "KXBTC15M-A",
            side = "YES",
            stakeUsd = 4.0,
            contracts = 10,
            limitPrice = 0.40,
            source = "AI paper autopilot",
            createdAtMs = 10L,
            note = "open",
            aiPct = 64.0,
            updatedAtMs = 10L
        )
        val settled = open.copy(
            settled = true,
            outcome = "no",
            won = false,
            pnlUsd = -4.0,
            updatedAtMs = 90L
        )
        val sync = SupabaseSync()
        val first = sync.pack(SupabaseSync.LocalBundle(paperFills = listOf(open))).single { it.kind == "paper" }
        val second = sync.pack(SupabaseSync.LocalBundle(paperFills = listOf(settled))).single { it.kind == "paper" }
        assertTrue(first.payload.contains("\"aiPct\""))
        assertTrue(first.payload.contains("AI paper autopilot"))
        assertEquals(10L, first.updatedAtMs)
        assertEquals(90L, second.updatedAtMs)
        assertTrue(second.updatedAtMs > first.updatedAtMs)
        assertTrue(second.payload.contains("\"settled\":true"))
        val captured = mutableListOf<PaperFill>()
        sync.apply(listOf(second), InMemoryResultsStore(), onSettings = {}, onPaper = { captured += it })
        assertEquals(64.0, captured.single().aiPct!!, 1e-9)
        assertEquals("AI paper autopilot", captured.single().source)
        assertEquals(true, captured.single().settled)
        assertEquals(-4.0, captured.single().pnlUsd!!, 1e-9)
    }
}
