package com.dirk.kalshiodds.data.supabase

import com.dirk.kalshiodds.BuildConfig
import com.dirk.kalshiodds.data.importing.SeenKeys
import com.dirk.kalshiodds.data.local.results.InMemoryResultsStore
import com.dirk.kalshiodds.data.local.results.ScoredSnapshotRow
import com.dirk.kalshiodds.data.prefs.DataHubSettings
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import okio.Buffer

class SupabaseSyncTest {

    @Test
    fun yesAndNoSnapshotsFromTheSameMillisecondDoNotShareAKey() {
        val rows = SupabaseSync().pack(
            SupabaseSync.LocalBundle(
                snapshots = listOf(snap("YES", 1_700_000_000_000), snap("NO", 1_700_000_000_000))
            )
        )
        assertEquals(2, rows.size)
        assertNotEquals(rows[0].key, rows[1].key)
        assertTrue(rows.all { it.key.startsWith("grokbot:") })
        assertTrue(rows.any { it.key.contains(":YES:") })
        assertTrue(rows.any { it.key.contains(":NO:") })
    }

    @Test
    fun upsertBatchCollapsesIdenticalRowsAndKeepsDistinctPayloads() {
        val yes = SyncMerge.Record(
            key = "grokbot:snapshot:T:1",
            kind = "snapshot",
            updatedAtMs = 1,
            payload = """{"side":"YES","ticker":"T"}"""
        )
        val no = yes.copy(payload = """{"side":"NO","ticker":"T"}""")
        val fake = FakeHttp()
        fake.handler = { 201 to "" }
        val sent = SupabaseSync(fake.client()).push(hub(), listOf(yes, yes, no))
        assertEquals(2, sent)
        val batch = JSONArray(fake.bodies.single())
        val keys = (0 until batch.length()).map { batch.getJSONObject(it).getString("key") }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(keys.all { it.startsWith("grokbot:") })
        assertFalse(keys.any { it.startsWith("kashi:") })
        val sides = (0 until batch.length()).map {
            batch.getJSONObject(it).getJSONObject("payload").getString("side")
        }
        assertTrue(sides.contains("YES"))
        assertTrue(sides.contains("NO"))
    }

    @Test
    fun identicalDuplicateUsesTheOriginalKeyOnce() {
        val row = SyncMerge.Record(
            key = "grokbot:paper:abc",
            kind = "paper",
            updatedAtMs = 4,
            payload = """{"id":"abc","ticker":"T","side":"YES"}"""
        )
        val fake = FakeHttp()
        fake.handler = { 201 to "" }
        assertEquals(1, SupabaseSync(fake.client()).push(hub(), listOf(row, row)))
        val batch = JSONArray(fake.bodies.single())
        assertEquals(1, batch.length())
        assertEquals(row.key, batch.getJSONObject(0).getString("key"))
    }

    @Test
    fun failedDownloadIsNotASuccessfulPushOfZero() {
        val fake = FakeHttp()
        fake.handler = {
            500 to """{"message":"permission denied for table diphunter_sync"}"""
        }
        val status = SupabaseSync(fake.client(), pageSize = 2).roundTrip(
            hub(),
            SupabaseSync.LocalBundle(snapshots = listOf(snap("YES", 5))),
            InMemoryResultsStore(),
            lastPushMs = 0,
            onSettings = {},
            onPaper = {}
        )
        assertFalse(status.ok)
        assertTrue(status.message.startsWith(SupabaseSync.FAILURE_PREFIX))
        assertTrue(status.message.contains("HTTP 500"))
        assertTrue(status.message.contains("permission denied"))
        assertFalse(status.message.contains("Synced"))
        assertEquals(0, status.pushed)
    }

    @Test
    fun failedUploadIsNotASuccessfulPushOfZero() {
        val fake = FakeHttp()
        fake.handler = { req ->
            if (req.method == "GET") {
                200 to "[]"
            } else {
                500 to """{"code":"21000","message":"ON CONFLICT DO UPDATE command cannot affect row a second time"}"""
            }
        }
        val status = SupabaseSync(fake.client(), pageSize = 2).roundTrip(
            hub(),
            SupabaseSync.LocalBundle(
                snapshots = listOf(snap("YES", 9), snap("YES", 9), snap("NO", 9))
            ),
            InMemoryResultsStore(),
            lastPushMs = 50,
            onSettings = {},
            onPaper = {}
        )
        assertFalse(status.ok)
        assertTrue(status.message.contains("HTTP 500"))
        assertTrue(status.message.contains("cannot affect row a second time"))
        assertFalse(status.message.contains("Synced"))
        val batch = JSONArray(fake.bodies.single())
        val keys = (0 until batch.length()).map { batch.getJSONObject(it).getString("key") }
        assertEquals(2, keys.size)
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(keys.all { it.startsWith("grokbot:") })
    }

    @Test
    fun downloadPagesPastTheSupabaseCapAndDropsOtherNamespaces() {
        val fake = FakeHttp()
        fake.handler = { req ->
            val offset = req.url.queryParameter("offset")!!.toInt()
            val body = when (offset) {
                0 -> "[${syncRow("grokbot:a", "A")},${syncRow("kashi:secret", "KASHI")}]"
                2 -> "[${syncRow("grokbot:c", "C")}]"
                else -> "[]"
            }
            200 to body
        }
        val store = InMemoryResultsStore()
        val status = SupabaseSync(fake.client(), pageSize = 2).roundTrip(
            hub(),
            SupabaseSync.LocalBundle(),
            store,
            lastPushMs = 10,
            onSettings = {},
            onPaper = {}
        )
        assertTrue(status.ok)
        assertEquals(2, status.pulled)
        assertEquals(setOf("A", "C"), store.recentSnapshots().map { it.ticker }.toSet())
        assertEquals(listOf("0", "2"), fake.calls.map { it.url.queryParameter("offset") })
        fake.calls.forEach { req ->
            assertEquals("key.asc", req.url.queryParameter("order"))
            assertEquals("like.grokbot:*", req.url.queryParameter("key"))
            assertEquals("2", req.url.queryParameter("limit"))
            assertEquals(syncUserAgent(), req.header("User-Agent"))
            assertTrue(req.header("User-Agent")!!.contains(BuildConfig.VERSION_NAME))
            assertTrue(req.header("User-Agent")!!.contains(BuildConfig.VERSION_CODE.toString()))
            assertFalse(req.header("User-Agent")!!.contains("0.3.11"))
            assertFalse(req.header("User-Agent")!!.contains("Dirk"))
        }
        assertTrue(fake.bodies.isEmpty())
    }

    @Test
    fun productionPageSizeIsTheSupabaseCap() {
        val fake = FakeHttp()
        fake.handler = { 200 to "[]" }
        SupabaseSync(fake.client()).pull(hub())
        val url = fake.calls.single().url
        assertEquals("1000", url.queryParameter("limit"))
        assertEquals("0", url.queryParameter("offset"))
        assertEquals("key.asc", url.queryParameter("order"))
        assertEquals("like.grokbot:*", url.queryParameter("key"))
        assertFalse(url.query!!.contains("limit=2000"))
        assertEquals(syncUserAgent(), fake.calls.single().header("User-Agent"))
    }

    @Test
    fun restorePagesPastOneThousandRows() {
        val fake = FakeHttp()
        fake.handler = { req ->
            val table = req.url.pathSegments.last()
            if (table != "diphunter_settled") {
                404 to """{"code":"PGRST205","message":"Could not find the table"}"""
            } else {
                val offset = req.url.queryParameter("offset")!!.toInt()
                val body = when (offset) {
                    0 -> "[${settled("A")},${settled("B")}]"
                    2 -> "[${settled("C")}]"
                    else -> "[]"
                }
                200 to body
            }
        }
        val restore = SupabaseMirror(fake.client(), pageSize = 2).restore(hub(), SeenKeys())
        assertEquals(listOf("A", "B", "C"), restore.settled.map { it.ticker })
        assertFalse(restore.message.startsWith(SupabaseMirror.FAILURE_PREFIX))
        val settledCalls = fake.calls.filter { it.url.pathSegments.last() == "diphunter_settled" }
        assertEquals(listOf("0", "2"), settledCalls.map { it.url.queryParameter("offset") })
        settledCalls.forEach { req ->
            assertEquals("key.asc", req.url.queryParameter("order"))
            assertEquals(syncUserAgent(), req.header("User-Agent"))
        }
    }

    @Test
    fun restoreHttpFailureIsVisible() {
        val fake = FakeHttp()
        fake.handler = { req ->
            if (req.url.pathSegments.last() == "diphunter_settled") {
                500 to """{"message":"database timeout"}"""
            } else {
                404 to """{"code":"PGRST205","message":"missing"}"""
            }
        }
        val restore = SupabaseMirror(fake.client(), pageSize = 2).restore(hub(), SeenKeys())
        assertTrue(restore.message.startsWith(SupabaseMirror.FAILURE_PREFIX))
        assertTrue(restore.message.contains("HTTP 500"))
        assertTrue(restore.message.contains("database timeout"))
        assertTrue(restore.settled.isEmpty())
    }

    @Test
    fun failureDoesNotAdvanceTheSyncCursor() {
        assertEquals(50L, SupabaseSync.nextSyncCursor(ok = true, statusAtMs = 50L, previousAtMs = 10L))
        assertEquals(10L, SupabaseSync.nextSyncCursor(ok = false, statusAtMs = 50L, previousAtMs = 10L))
        assertTrue(SupabaseSync.isFailureMessage("Sync failed: HTTP 500"))
        assertFalse(SupabaseSync.isFailureMessage("Synced · pulled 0 · merged 0 · pushed 0"))
    }

    private fun hub() = DataHubSettings(
        supabaseUrl = "https://diphunter.example.test",
        supabaseAnonKey = "anon-key-0123456789abcdef",
        syncEnabled = true
    )

    private fun snap(side: String, at: Long) = ScoredSnapshotRow(
        ticker = "KXBTC15M-26OCT051200-00",
        series = "KXBTC15M",
        side = side,
        edgePp = 1.0,
        fairPp = 55.0,
        marketPp = 54.0,
        regime = null,
        uncertainty = null,
        createdAtMs = at
    )

    private fun syncRow(key: String, ticker: String) =
        """{"key":"$key","kind":"snapshot","updated_at":5,"payload":{"ticker":"$ticker","series":"KXBTC15M","side":"YES","edgePp":1,"fairPp":2,"marketPp":3,"createdAtMs":5}}"""

    private fun settled(ticker: String) =
        """{"ticker":"$ticker","series":"KXBTC15M","result":"yes"}"""

    private class FakeHttp : Interceptor {
        val calls = ArrayList<Request>()
        val bodies = ArrayList<String>()
        var handler: (Request) -> Pair<Int, String> = { 200 to "[]" }

        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            calls += req
            req.body?.let { body ->
                val buffer = Buffer()
                body.writeTo(buffer)
                bodies += buffer.readUtf8()
            }
            val (code, payload) = handler(req)
            return Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("test")
                .body(payload.toResponseBody(JSON))
                .build()
        }

        fun client(): OkHttpClient = OkHttpClient.Builder().addInterceptor(this).build()
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
