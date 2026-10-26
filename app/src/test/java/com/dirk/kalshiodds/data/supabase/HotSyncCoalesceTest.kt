package com.dirk.kalshiodds.data.supabase

import com.dirk.kalshiodds.decision.ModelFilter
import com.dirk.kalshiodds.decision.ScalpGateLog
import com.dirk.kalshiodds.decision.ScalpState
import com.dirk.kalshiodds.decision.ScalpTrade
import com.dirk.kalshiodds.signal.paper.PaperFill
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class HotSyncCoalesceTest {
    private val window = 5 * HotSyncCoalesce.WINDOW_MS

    @Test
    fun modelsKeepTheLatestRowPerTwinSideAndQuarterHour() {
        val older = model("sf41-logistic", window + 1_000L, "YES", 0.40, false, "expected net -0.02 ≤ 0")
        val newer = model("sf41-logistic", window + 800_000L, "YES", 0.91, true, "enter · logistic")
        val tieLoser = model("sf41-logistic", window + 800_000L, "YES", 0.10, false, "stale")
        val tieWinner = model("sf41-logistic", window + 800_000L, "YES", 0.77, true, "enter · last")
        val otherSide = model("sf41-logistic", window + 2_000L, "NO", 0.20, false, "quiet-volume only")
        val nextWindow = model("sf41-logistic", window + HotSyncCoalesce.WINDOW_MS, "YES", 0.33, false, "next")
        val otherTwin = model("sf41-lgbm", window + 3_000L, "YES", 0.50, false, "lgbm net -0.01 ≤ 0")
        val noise = List(40) { i ->
            model("sf41-logistic", window + 10_000L + i, "YES", 0.41, false, "expected net -0.${i % 10} ≤ 0")
        }
        val kept = HotSyncCoalesce.coalesceModels(listOf(older) + noise + listOf(newer, tieLoser, tieWinner, otherSide, nextWindow, otherTwin))
        assertEquals(4, kept.size)
        val yes = kept.single { it.remoteKey == "model:sf41-logistic:YES:${HotSyncCoalesce.windowBucket(window)}" }
        assertEquals("enter · last", yes.row.reason)
        assertEquals(0.77, yes.row.score, 1e-9)
        assertTrue(yes.row.entered)
        assertEquals(
            setOf(
                "model:sf41-logistic:NO:${HotSyncCoalesce.windowBucket(window)}",
                "model:sf41-logistic:YES:${HotSyncCoalesce.windowBucket(window + HotSyncCoalesce.WINDOW_MS)}",
                "model:sf41-lgbm:YES:${HotSyncCoalesce.windowBucket(window)}"
            ),
            kept.map { it.remoteKey }.toSet() - yes.remoteKey
        )
    }

    @Test
    fun gatesKeepTheOnScreenReasonAndCapTheHistogram() {
        val gaps = (0 until 20).map { i ->
            ScalpGateLog.SyncRow("26OCT261200", "sf41", String.format(Locale.US, "gap %.1f¢ under 14¢", i / 10.0), 1)
        }
        val dominant = ScalpGateLog.SyncRow("26OCT261200", "sf41", "gap 1.2¢ under 14¢", 400)
        val latest = ScalpGateLog.SyncRow("26OCT261200", "sf41", "ask above 55¢ ceiling", 2, latest = true)
        val otherCode = ScalpGateLog.SyncRow("26OCT261200", "sf42", "spread over 2¢", 9, latest = true)
        val otherWindow = ScalpGateLog.SyncRow("26OCT261215", "sf41", "stale book", 3, latest = true)
        val kept = HotSyncCoalesce.coalesceGates(gaps + listOf(dominant, latest, otherCode, otherWindow))
        assertEquals(3, kept.size)
        val sf41 = kept.single { it.remoteKey == "scalp_gate:26OCT261200:sf41" }
        assertEquals("ask above 55¢ ceiling", sf41.reason)
        assertEquals(2, sf41.count)
        assertTrue(sf41.reasons >= 21)
        assertEquals(HotSyncCoalesce.GATE_REASONS_KEPT, sf41.top.size)
        assertEquals("ask above 55¢ ceiling", sf41.top.first().first)
        assertEquals(400, sf41.top.first { it.first == "gap 1.2¢ under 14¢" }.second)
        assertEquals("scalp_gate:26OCT261200:sf42", kept.single { it.code == "sf42" }.remoteKey)
        assertEquals("scalp_gate:26OCT261215:sf41", kept.single { it.windowKey == "26OCT261215" }.remoteKey)
    }

    @Test
    fun packUploadsCoalescedHotKindsAndLeavesPaperAndScalpAlone() {
        val log = ScalpGateLog()
        repeat(30) { i ->
            log.block("sf41", "26OCT261200", String.format(Locale.US, "gap %.1f¢ under 14¢", i / 10.0))
        }
        log.block("sf41", "26OCT261200", "ask above 55¢ ceiling")
        log.block("sf42", "26OCT261200", "spread over 2¢")
        val snap = log.snapshot(listOf("sf41", "sf42"))
        assertTrue(snap.size >= 31)
        assertTrue(snap.any { it.code == "sf41" && it.latest && it.reason == "ask above 55¢ ceiling" })

        val decisions = List(25) { i ->
            model("sf41-logistic", window + i * 1_000L, "YES", 0.4, false, "tick $i")
        } + model("sf41-logistic", window + 50_000L, "NO", 0.2, false, "no side")
        val packed = SupabaseSync().pack(
            SupabaseSync.LocalBundle(
                scalpGates = snap,
                modelDecisions = decisions,
                scalps = listOf(closedScalp()),
                paperFills = listOf(paper())
            )
        )
        val gates = packed.filter { it.kind == "scalp_gate" }
        assertEquals(
            setOf("scalp:scalp_gate:26OCT261200:sf41", "scalp:scalp_gate:26OCT261200:sf42"),
            gates.map { it.key }.toSet()
        )
        val gate = JSONObject(gates.single { it.key.endsWith(":sf41") }.payload)
        assertEquals("ask above 55¢ ceiling", gate.getString("reason"))
        assertTrue(gate.getInt("reasons") >= 31)
        assertTrue(gate.getJSONArray("top").length() <= HotSyncCoalesce.GATE_REASONS_KEPT)
        val models = packed.filter { it.kind == "model_decision" }
        assertEquals(2, models.size)
        assertEquals(
            "scalp:model:sf41-logistic:YES:${HotSyncCoalesce.windowBucket(window)}",
            models.single { JSONObject(it.payload).getString("reason") == "tick 24" }.key
        )
        assertEquals(1, packed.count { it.kind == "scalp" })
        assertEquals("scalp:t-keep", packed.single { it.kind == "scalp" }.key)
        assertEquals("scalp:paper:p-keep", packed.single { it.kind == "paper" }.key)
        assertTrue(packed.none { it.kind == "scalp_gate" && it.key.contains("hash") })
    }

    private fun model(twin: String, atMs: Long, side: String, score: Double, entered: Boolean, reason: String) =
        ModelFilter.SyncRow(
            id = "mf:$twin:$atMs:$side",
            atMs = atMs,
            twinId = twin,
            score = score,
            scalar = 1.0,
            entered = entered,
            reason = reason,
            outcomeUsd = null
        )

    private fun closedScalp() = ScalpTrade(
        id = "t-keep",
        ticker = "KXBTC15M-26OCT122015-15",
        side = "YES",
        state = ScalpState.CLOSED,
        signalAtMs = 1_000L,
        signalAsk = 0.40,
        fairAtSignal = 0.50,
        contracts = 5,
        entryPrice = 0.40,
        entryAtMs = 2_000L,
        closedAtMs = 9_000L,
        netUsd = 0.20,
        ruleVersion = "v|sf41|P"
    )

    private fun paper() = PaperFill(
        id = "p-keep",
        ticker = "KXBTC15M-A",
        side = "YES",
        stakeUsd = 2.0,
        contracts = 4,
        limitPrice = 0.40,
        source = "AI hunter",
        createdAtMs = 1L,
        updatedAtMs = 80L,
        settled = true,
        won = true,
        pnlUsd = 1.0,
        note = "keep"
    )
}
