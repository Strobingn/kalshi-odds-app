package com.dirk.kalshiodds.data.supabase

import android.content.Context
import com.dirk.kalshiodds.BuildConfig
import com.dirk.kalshiodds.signal.paper.PaperBookState
import com.dirk.kalshiodds.signal.trade.ScalpSignal
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * One-row-per-installation experiment ledger sync. This is intentionally a
 * snapshot upload rather than a merge: the phone remains the source of truth
 * and the reviewer receives every active/settled/archived paper fill.
 */
class PaperScalpCloudSync(context: Context) {
    private val identityStore = PaperScalpIdentityStore(context)
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
    private val json = Json { encodeDefaults = true }

    fun upload(state: PaperBookState): Status {
        val identity = identityStore.loadOrCreate().getOrElse {
            return Status(false, "Paper scalp cloud sync unavailable: secure device identity failed", 0L)
        }
        val now = System.currentTimeMillis()
        val row = JSONObject()
            .put("device_id", identity.deviceId)
            .put("secret_hash", identity.secretHash)
            .put("updated_at", now)
            .put("payload", payload(state, now))
        val body = JSONArray().put(row).toString().toRequestBody(JSON_MEDIA)
        val upsert = requestBuilder("$REST_URL?on_conflict=device_id", identity)
            .post(body)
            .header("Prefer", "resolution=merge-duplicates,return=minimal")
            .build()
        val response = runCatching { http.newCall(upsert).execute() }
            .getOrElse { return Status(false, "Paper scalp cloud sync failed: network unavailable", now) }
        response.use {
            if (!it.isSuccessful) return Status(false, "Paper scalp cloud sync failed: HTTP ${it.code}", now)
        }

        // Verify RLS permits this exact capability to read only its own row.
        val verifyUrl = "$REST_URL?device_id=eq.${identity.deviceId}&select=updated_at"
        val verify = requestBuilder(verifyUrl, identity).get().build()
        val verifiedAt = runCatching {
            http.newCall(verify).execute().use { result ->
                if (!result.isSuccessful) return@use null
                val array = JSONArray(result.body?.string().orEmpty())
                array.optJSONObject(0)?.optLong("updated_at", 0L)
            }
        }.getOrNull()
        return if (verifiedAt == now) {
            Status(true, "Paper scalp cloud synced · ${state.fills.size} fills · secure per-device ledger", now)
        } else {
            Status(false, "Paper scalp cloud sync could not verify its secure ledger row", now)
        }
    }

    private fun payload(state: PaperBookState, syncedAtMs: Long): JSONObject = JSONObject()
        .put("schemaVersion", SCHEMA_VERSION)
        .put("appPackage", BuildConfig.APPLICATION_ID)
        .put("formulaVersion", ScalpSignal.FORMULA_VERSION)
        .put("syncedAtMs", syncedAtMs)
        .put("paper", JSONObject(json.encodeToString(PaperBookState.serializer(), state)))

    private fun requestBuilder(url: String, identity: PaperScalpIdentity): Request.Builder = Request.Builder()
        .url(url)
        .header("Accept", "application/json")
        .header("apikey", BuildConfig.PAPER_SCALP_SYNC_PUBLISHABLE_KEY)
        .header("Authorization", "Bearer ${BuildConfig.PAPER_SCALP_SYNC_PUBLISHABLE_KEY}")
        .header("X-Paper-Sync-Device", identity.deviceId)
        .header("X-Paper-Sync-Secret-Hash", identity.secretHash)

    data class Status(val ok: Boolean, val message: String, val atMs: Long)

    private companion object {
        const val SCHEMA_VERSION = 1
        val REST_URL = "${BuildConfig.PAPER_SCALP_SYNC_URL}/rest/v1/paper_scalp_sync"
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
