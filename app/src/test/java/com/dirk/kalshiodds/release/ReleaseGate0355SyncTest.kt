package com.dirk.kalshiodds.release

import com.dirk.kalshiodds.data.local.results.AlertRow
import com.dirk.kalshiodds.data.local.results.AsyncResultsWriter
import com.dirk.kalshiodds.data.local.results.InMemoryResultsStore
import com.dirk.kalshiodds.data.local.results.ScoredSnapshotRow
import com.dirk.kalshiodds.data.local.results.TicketAttemptRow
import com.dirk.kalshiodds.data.prefs.DataHubSettings
import com.dirk.kalshiodds.data.supabase.SnapshotUploadFilter
import com.dirk.kalshiodds.data.supabase.SupabaseSync
import com.dirk.kalshiodds.decision.ForecastScalp
import com.dirk.kalshiodds.decision.ScalpState
import com.dirk.kalshiodds.decision.ScalpTrade
import com.dirk.kalshiodds.signal.paper.PaperFill
import com.dirk.kalshiodds.signal.paper.ShadowTicket
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseGate0355SyncTest {
    private class FakeSupabase : Interceptor {
        val posted = ArrayList<Pair<String, JSONArray>>() // kind → rows
        val getUrls = ArrayList<String>()
        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            val body = if (req.method == "POST") {
                val b = Buffer(); req.body!!.writeTo(b)
                val arr = JSONArray(b.readUtf8())
                posted += arr.getJSONObject(0).getString("kind") to arr
                ""
            } else { getUrls += req.url.toString(); "[]" }
            return Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(if (req.method == "POST") 201 else 200)
                .message("ok").body(body.toResponseBody("application/json".toMediaType())).build()
        }
        fun kinds() = posted.map { it.first }.toSet()
        fun rows(kind: String) = posted.filter { it.first == kind }.sumOf { it.second.length() }
    }

    private val hub = DataHubSettings(supabaseUrl = "https://x.supabase.co", supabaseAnonKey = "anon-key-anon-key-anon-key", syncEnabled = true)

    @Test fun everyRecordTypeEnqueuesAndUploads() {
        SnapshotUploadFilter.reset()
        val now = 1_791_800_000_000L
        val store = InMemoryResultsStore()
        val writer = AsyncResultsWriter(store)
        writer.enqueueSnapshot(ScoredSnapshotRow(ticker = "KXBTC15M-26OCT121000-00", series = "KXBTC15M", side = "YES", edgePp = 4.0, fairPp = 55.0, marketPp = 51.0, regime = null, uncertainty = null, createdAtMs = now, note = "long note"))
        writer.enqueueAlert(AlertRow(alertId = "a1", ticker = "KXBTC15M-26OCT121000-00", series = "KXBTC15M", side = "YES", edgePp = 9.0, fairPp = 60.0, marketPp = 51.0, reason = "r", regime = null, createdAtMs = now))
        writer.enqueueTicket(TicketAttemptRow(ticker = "KXBTC15M-26OCT121000-00", side = "YES", stakeUsd = 5.0, approved = false, result = "paper", createdAtMs = now))
        writer.flushNow()
        val paper = PaperFill(id = "p1", ticker = "KXBTC15M-26OCT121000-00", side = "YES", stakeUsd = 5.0, contracts = 10, limitPrice = 0.5, source = "ladder", createdAtMs = now, note = "", updatedAtMs = now)
        val scalp = ScalpTrade(id = "s1", ticker = "KXBTC15M-26OCT121000-00", side = "YES", state = ScalpState.CLOSED, signalAtMs = now, signalAsk = 0.5,
            fairAtSignal = 0.55, contracts = 10, entryPrice = 0.5, entryAtMs = now, soldContracts = 10, proceedsUsd = 5.4, closedAtMs = now + 60_000, netUsd = 0.2,
            ruleVersion = "v|${ForecastScalp.variantId(true, 600)}|P", entryFeeUsd = .10, exitFeeUsd = .10,
            exitDecidedAtMs = now + 55_000, note = "context ".repeat(400) + " · scalpOrder=$now,0.49000000 · scalpFill=${now + 60_000},10,0.54000000,0.10000000,S")
        val shadow = ShadowTicket(id = "sh1", ticker = "KXBTC15M-26OCT121000-00", side = "YES", bookSide = "yes", count = 10, limitPrice = 0.5, yesLimitPrice = 0.5,
            clientOrderId = "c", createdAtMs = now - 3_600_000L, reason = "shadow", payloadJson = "{}")
        val fake = FakeSupabase()
        val sync = SupabaseSync(OkHttpClient.Builder().addInterceptor(fake).build())
        val st = sync.roundTrip(hub, SupabaseSync.LocalBundle(
            snapshots = SnapshotUploadFilter.select(store.recentSnapshots(4000)), alerts = store.recentAlerts(200), tickets = store.recentTickets(200),
            paperFills = listOf(paper), scalps = listOf(scalp), shadow = listOf(shadow)
        ), store, lastPushMs = now - 1_000L, onSettings = {}, onPaper = {})
        assertTrue(st.message, st.ok)
        for (k in listOf("snapshot", "alert", "ticket", "paper", "scalp", "shadow")) assertTrue("missing $k: ${fake.kinds()}", k in fake.kinds())
        // shadow was created before lastPush but still goes up (settles in place without a new timestamp)
        assertEquals(1, fake.rows("shadow"))
        // compact snapshot payload: no note
        val snap = fake.posted.first { it.first == "snapshot" }.second.getJSONObject(0).getJSONObject("payload")
        assertFalse(snap.has("note"))
        assertEquals("Forecast ride", fake.posted.first { it.first == "scalp" }.second.getJSONObject(0).getJSONObject("payload").getString("algo"))
        val scalpPayload = fake.posted.first { it.first == "scalp" }.second.getJSONObject(0).getJSONObject("payload")
        val restored = com.dirk.kalshiodds.data.supabase.SyncRows.scalp(scalpPayload)!!
        assertEquals(scalp.signalAtMs, restored.signalAtMs)
        assertEquals(scalp.entryFeeUsd, restored.entryFeeUsd, 1e-9)
        assertEquals(scalp.exitFeeUsd, restored.exitFeeUsd, 1e-9)
        assertEquals(scalp.exitDecidedAtMs, restored.exitDecidedAtMs)
        assertEquals(1, com.dirk.kalshiodds.decision.ScalpFills.sales(restored).size)
        assertEquals(now + 60_000, com.dirk.kalshiodds.decision.ScalpFills.sales(restored).single().atMs)
        assertEquals(.54, com.dirk.kalshiodds.decision.ScalpFills.sales(restored).single().price, 1e-9)
        assertEquals(.49, com.dirk.kalshiodds.decision.ScalpFills.orders(restored).single().price, 1e-9)
        // pull never fetches snapshots back
        assertEquals(SupabaseSync.PULL_KINDS.size, fake.getUrls.size)
        assertTrue(fake.getUrls.all { u -> SupabaseSync.PULL_KINDS.any { u.contains("kind=eq.$it") } && !u.contains("snapshot,") && !u.contains("eq.scalp") && !u.contains("eq.shadow") })
    }

    @Test fun snapshotUploadsAreThrottled() {
        SnapshotUploadFilter.reset()
        val t0 = 1_791_800_000_000L
        // one row per second for 24 h on 3 tickers with a slowly drifting edge → ≈ 259k scored rows
        var kept = 0
        val tks = listOf("KXBTC15M-A", "KXETH15M-A", "KXSOL15M-A")
        var batch = ArrayList<ScoredSnapshotRow>()
        for (s in 0 until 86_400) {
            for ((i, tk) in tks.withIndex()) {
                val edge = 5.0 * kotlin.math.sin((s + i * 100) / 400.0)
                batch += ScoredSnapshotRow(ticker = tk, series = "S", side = if (edge > 0) "YES" else "NO", edgePp = edge, fairPp = 50.0, marketPp = 50.0,
                    regime = null, uncertainty = null, createdAtMs = t0 + s * 1000L)
            }
            if (s % 900 == 899) { val k = SnapshotUploadFilter.select(batch); SnapshotUploadFilter.commit(k); kept += k.size; batch = ArrayList() }
        }
        // ≤ 1/min per ticker on change, ≥ heartbeat 5 min: expect a few thousand a day, not 38k–260k
        assertTrue("kept=$kept", kept in 864..4_400)
    }
}
