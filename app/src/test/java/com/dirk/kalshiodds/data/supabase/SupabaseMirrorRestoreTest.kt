package com.dirk.kalshiodds.data.supabase

import com.dirk.kalshiodds.data.importing.SeenKeys
import com.dirk.kalshiodds.data.prefs.DataHubSettings
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupabaseMirrorRestoreTest {
    private val hub = DataHubSettings(
        supabaseUrl = "https://example.supabase.co",
        supabaseAnonKey = "a".repeat(32),
        syncEnabled = true
    )

    @Test
    fun pagesUntilAShortPageAndKeepsBothSides() {
        val yes = syncRow(
            key = "kashi:snapshot:KXBTC15M-A:42:YES",
            kind = "snapshot",
            at = 42L,
            payload = """{"ticker":"KXBTC15M-A","series":"KXBTC15M","side":"YES","edgePp":1,"fairPp":60,"marketPp":50,"createdAtMs":42}"""
        )
        val no = syncRow(
            key = "kashi:snapshot:KXBTC15M-A:42:NO",
            kind = "snapshot",
            at = 42L,
            payload = """{"ticker":"KXBTC15M-A","series":"KXBTC15M","side":"NO","edgePp":2,"fairPp":40,"marketPp":50,"createdAtMs":42}"""
        )
        val paper = syncRow(
            key = "kashi:paper:p1",
            kind = "paper",
            at = 80L,
            payload = """{"id":"p1","ticker":"KXBTC15M-A","side":"YES","stakeUsd":2,"contracts":4,"limitPrice":0.4,"createdAtMs":80}"""
        )
        val script = Pages(
            mapOf(
                0 to "[$yes,$no]",
                2 to "[$paper]"
            )
        )
        val restore = SupabaseMirror(client(script), pageSize = 2).restore(hub, SeenKeys())
        assertEquals(listOf(0, 2), script.offsets)
        assertTrue(script.urls.all { it.contains("/diphunter_sync?") || it.contains("/diphunter_sync&") || it.contains("diphunter_sync") })
        assertTrue(script.urls.all { it.contains("order=key.asc") })
        assertTrue(script.urls.all { it.contains("key=like.kashi") })
        assertTrue(script.urls.all { it.contains("kind=in.") })
        assertTrue(script.urls.all { it.contains("snapshot") && it.contains("paper") })
        assertTrue(script.urls.none { it.contains("diphunter_snapshots") })
        assertEquals(listOf("YES", "NO"), restore.batch.snapshots.map { it.side })
        assertEquals(1, restore.paper.size)
        assertEquals("p1", restore.paper.single().id)
        assertTrue(restore.message.contains("2 snapshots"))
        assertTrue(restore.message.contains("1 paper"))
    }

    @Test
    fun emptyTableIsACleanRestore() {
        val script = Pages(emptyMap(), fallback = "[]")
        val restore = SupabaseMirror(client(script), pageSize = 2).restore(hub, SeenKeys())
        assertEquals(listOf(0), script.offsets)
        assertTrue(restore.batch.snapshots.isEmpty())
        assertTrue(restore.paper.isEmpty())
        assertTrue(restore.message.contains("no kashi rows"))
        assertFalse(restore.message.contains("diphunter_snapshots"))
        assertTrue(script.urls.single().contains("diphunter_sync"))
    }

    @Test
    fun missingSyncTableIsSoftAndDoesNotQueryNamedTables() {
        val script = Pages(
            emptyMap(),
            code = 404,
            fallback = """{"code":"PGRST205","message":"Could not find the table 'public.diphunter_sync' in the schema cache"}"""
        )
        val restore = SupabaseMirror(client(script), pageSize = 2).restore(hub, SeenKeys())
        assertEquals(1, script.urls.size)
        assertTrue(script.urls.single().contains("diphunter_sync"))
        assertTrue(script.urls.none { it.contains("diphunter_snapshots") || it.contains("diphunter_results") })
        assertTrue(restore.batch.snapshots.isEmpty())
        assertTrue(restore.message.contains("diphunter_sync is missing"))
        assertTrue(restore.message.contains("does not read diphunter_snapshots"))
        assertEquals(
            "401 bad key",
            SupabaseMirror.soften("401 bad key")
        )
    }

    private fun syncRow(key: String, kind: String, at: Long, payload: String) =
        """{"key":"$key","kind":"$kind","updated_at":$at,"payload":$payload}"""

    private fun client(script: Pages) = OkHttpClient.Builder().addInterceptor(script).build()

    private class Pages(
        private val bodies: Map<Int, String>,
        private val fallback: String = "[]",
        private val code: Int = 200
    ) : Interceptor {
        val urls = mutableListOf<String>()
        val offsets = mutableListOf<Int>()
        override fun intercept(chain: Interceptor.Chain): Response {
            val url = chain.request().url
            urls += url.toString()
            val offset = url.queryParameter("offset")?.toIntOrNull() ?: -1
            offsets += offset
            val text = if (code == 200) bodies[offset] ?: fallback else fallback
            return Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code == 200) "ok" else "missing")
                .body(text.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }
}
