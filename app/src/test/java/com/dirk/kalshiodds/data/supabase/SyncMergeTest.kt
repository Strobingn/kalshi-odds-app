package com.dirk.kalshiodds.data.supabase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncMergeTest {

    @Test
    fun remoteNewerWins() {
        val local = listOf(rec("a", 100, """{"ticker":"BTC"}"""))
        val remote = listOf(rec("a", 200, """{"ticker":"ETH"}"""))
        val r = SyncMerge.merge(local, remote)
        assertEquals("ETH", org.json.JSONObject(r.upserts.single().payload).getString("ticker"))
        assertEquals(1, r.conflictsWon)
    }

    @Test
    fun localNewerKeptOnTieOrOlderRemote() {
        val local = listOf(rec("a", 300, """{"ticker":"BTC"}"""))
        val remote = listOf(rec("a", 200, """{"ticker":"ETH"}"""))
        val r = SyncMerge.merge(local, remote)
        assertEquals("BTC", org.json.JSONObject(r.upserts.single().payload).getString("ticker"))
        assertEquals(1, r.conflictsLost)
    }

    @Test
    fun equalTimestampKeepsLocal() {
        val local = listOf(rec("a", 50, """{"v":1}"""))
        val remote = listOf(rec("a", 50, """{"v":2}"""))
        val r = SyncMerge.merge(local, remote)
        assertEquals(1, org.json.JSONObject(r.upserts.single().payload).getInt("v"))
    }

    @Test
    fun rejectsSecrets() {
        assertTrue(SyncMerge.isForbiddenPayload("""{"apiKeyId":"abc"}"""))
        assertTrue(SyncMerge.isForbiddenPayload("""{"private_key_pem":"-----BEGIN PRIVATE KEY-----"}"""))
        assertTrue(SyncMerge.isForbiddenPayload("-----BEGIN EC PRIVATE KEY-----"))
        assertFalse(SyncMerge.isForbiddenPayload("""{"ticker":"KXBTC15M","edgePp":4.0}"""))
        val remote = listOf(rec("secret", 1, """{"api_key_id":"leak"}"""))
        val r = SyncMerge.merge(emptyList(), remote)
        assertEquals(0, r.upserts.size)
        assertEquals(1, r.skipped)
    }

    @Test
    fun outgoingFiltersOldAndSecrets() {
        val rows = listOf(
            rec("old", 10, """{"ok":true}"""),
            rec("new", 50, """{"ok":true}"""),
            rec("pem", 80, """{"privateKeyPem":"x"}""")
        )
        val out = SyncMerge.outgoing(rows, lastPushMs = 40)
        assertEquals(1, out.size)
        assertEquals("new", out.single().key)
    }

    private fun rec(key: String, at: Long, payload: String) =
        SyncMerge.Record(key = key, kind = "ticket", updatedAtMs = at, payload = payload)
}
