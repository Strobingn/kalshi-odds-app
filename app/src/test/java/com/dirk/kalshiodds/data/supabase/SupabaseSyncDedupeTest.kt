package com.dirk.kalshiodds.data.supabase

import com.dirk.kalshiodds.data.local.results.InMemoryResultsStore
import com.dirk.kalshiodds.data.local.results.ScoredSnapshotRow
import com.dirk.kalshiodds.data.prefs.DataHubSettings
import com.dirk.kalshiodds.signal.paper.PaperFill
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupabaseSyncDedupeTest {
    private val hub = DataHubSettings(
        supabaseUrl = "https://example.supabase.co",
        supabaseAnonKey = "a".repeat(32),
        syncEnabled = true
    )

    @Test
    fun dedupeKeepsNewestRowForARepeatedConflictKey() {
        val older = SyncMerge.Record("kashi:paper:dup", "paper", 10L, """{"id":"dup","v":1}""")
        val newer = SyncMerge.Record("kashi:paper:dup", "paper", 40L, """{"id":"dup","v":2}""")
        val tie = SyncMerge.Record("kashi:paper:dup", "paper", 40L, """{"id":"dup","v":3}""")
        val other = SyncMerge.Record("kashi:snapshot:a:1", "snapshot", 5L, """{"ticker":"A"}""")
        val kept = SupabaseSync.dedupeBatch(listOf(older, other, newer, tie))
        assertEquals(2, kept.size)
        val paper = kept.single { it.kind == "paper" }
        assertEquals(40L, paper.updatedAtMs)
        assertTrue(paper.payload.contains("\"v\":3"))
    }

    @Test
    fun uploadDropsDuplicateKeysAndAFailedBatchDoesNotBlockTheOther() {
        val dupOld = paper("dup", 10L, 1.0)
        val dupNew = paper("dup", 80L, 4.0)
        val script = ScriptedHttp()
        val status = SupabaseSync(client(script)).roundTrip(
            settings = hub,
            local = SupabaseSync.LocalBundle(
                snapshots = listOf(
                    ScoredSnapshotRow(
                        ticker = "KXBTC15M-A",
                        series = "KXBTC15M",
                        side = "YES",
                        edgePp = 1.0,
                        fairPp = 60.0,
                        marketPp = 50.0,
                        regime = null,
                        uncertainty = null,
                        createdAtMs = 3L
                    )
                ),
                paperFills = listOf(dupOld, dupNew)
            ),
            store = InMemoryResultsStore(),
            lastPushMs = 0L,
            onSettings = {},
            onPaper = {}
        )
        val paperBody = script.posts.single { it.contains("\"kind\":\"paper\"") }
        assertEquals(1, Regex("\"key\":\"kashi:paper:dup\"").findAll(paperBody).count())
        assertTrue(paperBody.contains("\"updated_at\":80"))
        assertFalse(paperBody.contains("\"updated_at\":10"))
        assertTrue(script.posts.any { it.contains("\"kind\":\"snapshot\"") })
        assertFalse(status.ok)
        assertTrue(status.advanced)
        assertFalse(status.acknowledgePaper)
        assertTrue(status.message.contains("diphunter_sync paper"))
        assertTrue(status.message.contains("500") || status.message.contains("21000"))
        assertTrue(status.pushed >= 1)
    }

    private fun paper(id: String, at: Long, pnl: Double) = PaperFill(
        id = id,
        ticker = "KXBTC15M-A",
        side = "YES",
        stakeUsd = 2.0,
        contracts = 4,
        limitPrice = 0.40,
        source = "AI hunter",
        createdAtMs = 1L,
        updatedAtMs = at,
        settled = true,
        won = true,
        pnlUsd = pnl,
        note = "dup"
    )

    private fun client(script: ScriptedHttp) = OkHttpClient.Builder().addInterceptor(script).build()

    private class ScriptedHttp : Interceptor {
        val posts = mutableListOf<String>()
        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            val body = req.body?.let { raw ->
                val buffer = Buffer()
                raw.writeTo(buffer)
                buffer.readUtf8()
            }.orEmpty()
            val failPaper = req.method == "POST" && body.contains("\"kind\":\"paper\"")
            if (req.method == "POST") posts += body
            val code = if (failPaper) 500 else 200
            val text = if (failPaper) {
                """{"code":"21000","message":"ON CONFLICT DO UPDATE command cannot affect row a second time","hint":"Ensure that no rows proposed for insertion within the same command have duplicate constrained values"}"""
            } else {
                "[]"
            }
            return Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (failPaper) "error" else "ok")
                .body(text.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }
}
