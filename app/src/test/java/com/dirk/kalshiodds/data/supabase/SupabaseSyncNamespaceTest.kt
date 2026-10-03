package com.dirk.kalshiodds.data.supabase

import com.dirk.kalshiodds.data.local.history.SettingsChange
import com.dirk.kalshiodds.data.prefs.DataHubSettings
import com.dirk.kalshiodds.ui.CloudSyncStatus
import java.time.ZoneId
import java.time.ZonedDateTime
import com.dirk.kalshiodds.signal.paper.PaperFill
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

class SupabaseSyncNamespaceTest {
    private val hub = DataHubSettings(
        supabaseUrl = "https://example.supabase.co",
        supabaseAnonKey = "a".repeat(32),
        syncEnabled = true
    )

    @Test
    fun pullIgnoresUnprefixedGrokbotAndHealthcheckRows() {
        val body = """
            [
              {"key":"ticket:plain","kind":"ticket","updated_at":10,"payload":{"ticker":"KXBTC15M-A","side":"YES"}},
              {"key":"grokbot:healthcheck","kind":"healthcheck","updated_at":11,"payload":{"ok":true}},
              {"key":"kashi:healthcheck","kind":"healthcheck","updated_at":12,"payload":{"ok":true}},
              {"key":"kashi:paper:p1","kind":"paper","updated_at":13,"payload":{"id":"p1","ticker":"KXBTC15M-A","side":"NO"}}
            ]
        """.trimIndent()
        val fake = ScriptedHttp(code = 200, body = body)
        val rows = SupabaseSync(client(fake)).pull(hub)
        assertTrue(fake.url.contains("key=like.kashi"))
        assertEquals(listOf("kashi:paper:p1"), rows.map { it.key })
        assertEquals("paper", rows.single().kind)
    }

    @Test
    fun httpFailuresBecomeLastSyncMessages() {
        assertEquals("401 bad key", SupabaseSync.describeFailure(401, """{"message":"Invalid API key"}""", null))
        assertEquals("404 table missing", SupabaseSync.describeFailure(404, """{"code":"PGRST205","message":"Could not find the table"}""", null))
        assertEquals("403 RLS denied", SupabaseSync.describeFailure(403, "new row violates row-level security policy", null))
        assertEquals("offline", SupabaseSync.describeFailure(0, null, java.io.IOException("network")))

        val denied = ScriptedHttp(code = 401, body = """{"message":"JWT expired"}""")
        val status = SupabaseSync(client(denied)).roundTrip(
            settings = hub,
            local = SupabaseSync.LocalBundle(),
            store = com.dirk.kalshiodds.data.local.results.InMemoryResultsStore(),
            lastPushMs = 0L,
            onSettings = {},
            onPaper = {}
        )
        assertFalse(status.ok)
        assertEquals("401 bad key", status.message)
    }

    @Test
    fun lastSyncedLineUsesLocalTimeAndKeepsTheError() {
        val zone = ZoneId.of("America/New_York")
        val at = ZonedDateTime.of(2026, 10, 3, 13, 14, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("Last synced: Oct 3, 2026, 1:14 PM", CloudSyncStatus.lastSynced(at, zone))
        val line = CloudSyncStatus.line(
            hub.copy(lastSyncAtMs = at, lastSyncMessage = "401 bad key"),
            zone
        )
        assertTrue(line.contains("Last synced: Oct 3, 2026, 1:14 PM"))
        assertTrue(line.contains("401 bad key"))
        assertTrue(CloudSyncStatus.isError("401 bad key"))
        assertTrue(CloudSyncStatus.isError("404 table missing"))
        assertTrue(CloudSyncStatus.isError("403 RLS denied"))
        assertTrue(CloudSyncStatus.isError("offline"))
        assertFalse(CloudSyncStatus.isError("Synced · pulled 1 · merged 1 · pushed 1"))
    }

    @Test
    fun packPrefixesKeysAndDropsKalshiKey() {
        val sync = SupabaseSync()
        val packed = sync.pack(
            SupabaseSync.LocalBundle(
                paperFills = listOf(
                    PaperFill(
                        id = "p1",
                        ticker = "KXBTC15M-A",
                        side = "YES",
                        stakeUsd = 1.0,
                        contracts = 2,
                        limitPrice = 0.4,
                        source = "test",
                        createdAtMs = 9L,
                        note = "namespaced"
                    )
                ),
                settingsChanges = listOf(
                    SettingsChange(
                        createdAtMs = 8L,
                        key = "api_key_id",
                        oldValue = "old",
                        newValue = "secret-key"
                    ),
                    SettingsChange(
                        createdAtMs = 7L,
                        key = "edge_threshold_pp",
                        oldValue = "5",
                        newValue = "6"
                    )
                ),
                settingsSnapshot = """{"private_key_pem":"-----BEGIN PRIVATE KEY-----"}"""
            )
        )
        assertTrue(packed.all { it.key.startsWith("kashi:") })
        assertTrue(packed.any { it.key == "kashi:paper:p1" })
        assertTrue(packed.none { it.payload.contains("api_key_id") || it.payload.contains("secret-key") })
        assertTrue(packed.none { it.payload.contains("PRIVATE KEY") })
        assertTrue(packed.any { it.key.startsWith("kashi:settings:edge_threshold_pp:") })
        assertTrue(SupabaseSync.FORBIDDEN_SETTING_KEYS.contains("api_key_id"))
        assertTrue(SupabaseSync.FORBIDDEN_SETTING_KEYS.contains("private_key_pem"))
    }

    private fun client(script: ScriptedHttp) = OkHttpClient.Builder().addInterceptor(script).build()

    private class ScriptedHttp(private val code: Int, private val body: String) : Interceptor {
        var url: String = ""
        override fun intercept(chain: Interceptor.Chain): Response {
            url = chain.request().url.toString()
            return Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("scripted")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }
}
