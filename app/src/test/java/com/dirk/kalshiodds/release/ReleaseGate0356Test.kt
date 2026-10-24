package com.dirk.kalshiodds.release

import com.dirk.kalshiodds.data.local.results.ScoredSnapshotRow
import com.dirk.kalshiodds.data.supabase.SnapshotUploadFilter
import com.dirk.kalshiodds.data.supabase.SupabaseMirror
import com.dirk.kalshiodds.data.supabase.SupabaseSync
import com.dirk.kalshiodds.decision.ForecastScalp
import com.dirk.kalshiodds.decision.KeptScalpers
import com.dirk.kalshiodds.decision.ScalpState
import com.dirk.kalshiodds.decision.ScalpTrade
import com.dirk.kalshiodds.signal.paper.ShadowTicket
import com.dirk.kalshiodds.data.importing.SeenKeys
import com.dirk.kalshiodds.data.prefs.DataHubSettings
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReleaseGate0356Test {
    private fun row(tk: String, at: Long, edge: Double = 1.0) = ScoredSnapshotRow(ticker = tk, series = "S", side = "YES", edgePp = edge,
        fairPp = 50.0, marketPp = 50.0, regime = null, uncertainty = null, createdAtMs = at)

    @Test fun snapshotFilterCommitsOnlyAfterSuccess() {
        SnapshotUploadFilter.reset()
        val a = listOf(row("T56", 1_000L))
        assertEquals(1, SnapshotUploadFilter.select(a).size)
        // upload failed → not committed → same row is selected again next sync
        assertEquals(1, SnapshotUploadFilter.select(a).size)
        SnapshotUploadFilter.commit(a)
        assertEquals(0, SnapshotUploadFilter.select(listOf(row("T56", 30_000L))).size) // within heartbeat, no move
        assertEquals(1, SnapshotUploadFilter.select(listOf(row("T56", 400_000L))).size) // heartbeat
    }

    @Test fun failedSnapshotBatchIsNotOk() {
        val failing = Interceptor { ch ->
            val req = ch.request()
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(if (req.method == "POST") 500 else 200).message("x")
                .body((if (req.method == "POST") "boom" else "[]").toResponseBody("application/json".toMediaType())).build()
        }
        val hub = DataHubSettings(supabaseUrl = "https://x.supabase.co", supabaseAnonKey = "anon-key-anon-key-anon-key")
        val st = SupabaseSync(OkHttpClient.Builder().addInterceptor(failing).build()).roundTrip(hub,
            SupabaseSync.LocalBundle(snapshots = listOf(row("T57", 5L))), com.dirk.kalshiodds.data.local.results.InMemoryResultsStore(), 0L, {}, {})
        assertFalse(st.snapshotsOk)
        val worker = File("src/main/java/com/dirk/kalshiodds/worker/SyncWorker.kt").readText()
        assertTrue(worker.contains("if (result.snapshotsOk) com.dirk.kalshiodds.data.supabase.SnapshotUploadFilter.commit(picked)"))
    }

    @Test fun noPerTickFc53Computation() {
        val src = File("src/main/java/com/dirk/kalshiodds/decision/Scalp.kt").readText()
        assertFalse(src.contains("ShortHorizonForecaster.predict("))
        assertFalse(src.contains("ShortHorizonForecaster.features("))
    }

    @Test fun restorePullsScalpAndShadow() {
        assertTrue("scalp" in SupabaseMirror.RESTORE_KINDS && "shadow" in SupabaseMirror.RESTORE_KINDS)
        val now = 1_791_800_000_000L
        val scalp = ScalpTrade(id = "s9", ticker = "KXBTC15M-26OCT121000-00", side = "YES", state = ScalpState.CLOSED, signalAtMs = now, signalAsk = 0.5,
            fairAtSignal = 0.5, contracts = 10, entryPrice = 0.5, entryFeeUsd = 0.1, entryAtMs = now, soldContracts = 10, proceedsUsd = 5.4,
            exitFeeUsd = 0.1, closedAtMs = now + 1, netUsd = 0.2, ruleVersion = "v|${ForecastScalp.variantId(false, 120)}|P")
        val shadow = ShadowTicket(id = "sh9", ticker = "KXBTC15M-26OCT121000-00", side = "NO", bookSide = "no", count = 10, limitPrice = 0.4,
            yesLimitPrice = 0.6, clientOrderId = "c", createdAtMs = now, reason = "r", payloadJson = "{}", booked = true, stakeUsd = 4.1, settled = true, pnlUsd = 5.9)
        val packed = SupabaseSync().pack(SupabaseSync.LocalBundle(scalps = listOf(scalp), shadow = listOf(shadow)))
        val arr = JSONArray()
        packed.forEach { arr.put(JSONObject().put("key", it.key).put("kind", it.kind).put("updated_at", it.updatedAtMs).put("payload", JSONObject(it.payload))) }
        val fake = Interceptor { ch -> Response.Builder().request(ch.request()).protocol(Protocol.HTTP_1_1).code(200).message("ok")
            .body(arr.toString().toResponseBody("application/json".toMediaType())).build() }
        val hub = DataHubSettings(supabaseUrl = "https://x.supabase.co", supabaseAnonKey = "anon-key-anon-key-anon-key")
        val r = SupabaseMirror(OkHttpClient.Builder().addInterceptor(fake).build()).restore(hub, SeenKeys())
        assertEquals(KeptScalpers.REMOVED_LABEL, r.scalps.single().algoLabel)
        assertEquals(0.2, r.scalps.single().netUsd!!, 1e-9)
        assertEquals("sh9", r.shadow.single().id)
        assertEquals(5.9, r.shadow.single().pnlUsd!!, 1e-9)
    }

    @Test fun staleIndexHoldsInsteadOfForecastTurnedExit() {
        // NaN = no fresh forecast: no forecast-turned / flip / horizon exit; TP and pre-close still apply
        assertNull(ForecastScalp.wantsExit(Double.NaN, 0.55, 900.0, 300, 600.0))
        assertEquals("take profit ≥ 90¢", ForecastScalp.wantsExit(Double.NaN, 0.92, 10.0, 300, 600.0))
        assertEquals("exit before close", ForecastScalp.wantsExit(Double.NaN, 0.55, 10.0, 300, 20.0))
        assertNull(ForecastScalp.wantsExit(0.0, 0.55, 10.0, 300, 600.0))
        assertNull(ForecastScalp.wantsExit(0.005, 0.55, 10.0, 300, 600.0))
        assertNull(ForecastScalp.wantsExit(-0.009, 0.55, 10.0, 300, 600.0))
        assertEquals("forecast turned (move < exit fee)", ForecastScalp.wantsExit(-0.01, 0.55, 10.0, 300, 600.0))
    }
}
