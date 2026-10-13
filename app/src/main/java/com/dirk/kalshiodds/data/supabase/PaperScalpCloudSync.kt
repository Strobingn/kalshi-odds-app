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
        val body = JSONObject()
            .put("p_device_id", identity.deviceId)
            .put("p_secret_hash", identity.secretHash)
            .put("p_updated_at", now)
            .put("p_payload", payload(state, now))
            .toString()
            .toRequestBody(JSON_MEDIA)
        val upsert = requestBuilder(REST_URL)
            .post(body)
            .build()
        val response = runCatching { http.newCall(upsert).execute() }
            .getOrElse { return Status(false, "Paper scalp cloud sync failed: network unavailable", now) }
        // Direct table reads are denied to the public APK key. The RPC checks
        // the device capability inside Postgres and echoes this exact timestamp
        // only after the guarded insert/update has succeeded.
        val verifiedAt = response.use { result ->
            if (!result.isSuccessful) return Status(false, "Paper scalp cloud sync failed: HTTP ${result.code}", now)
            runCatching { result.body?.string()?.trim()?.toLongOrNull() }.getOrNull()
        }
        return if (verifiedAt == now) {
            Status(true, "Paper scalp cloud synced · ${state.fills.size} fills · secure per-device ledger", now)
        } else {
            Status(false, "Paper scalp cloud sync could not verify its secure ledger write", now)
        }
    }

    private fun payload(state: PaperBookState, syncedAtMs: Long): JSONObject = JSONObject()
        .put("schemaVersion", SCHEMA_VERSION)
        .put("appPackage", BuildConfig.APPLICATION_ID)
        .put("formulaVersion", ScalpSignal.FORMULA_VERSION)
        .put("syncedAtMs", syncedAtMs)
        .put("paper", JSONObject(json.encodeToString(PaperBookState.serializer(), state)))

    private fun requestBuilder(url: String): Request.Builder = Request.Builder()
        .url(url)
        .header("Accept", "application/json")
        .header("apikey", BuildConfig.PAPER_SCALP_SYNC_PUBLISHABLE_KEY)
        .header("Authorization", "Bearer ${BuildConfig.PAPER_SCALP_SYNC_PUBLISHABLE_KEY}")

    data class Status(val ok: Boolean, val message: String, val atMs: Long)

    private companion object {
        const val SCHEMA_VERSION = 1
        val REST_URL = "${BuildConfig.PAPER_SCALP_SYNC_URL}/rest/v1/rpc/upsert_paper_scalp_sync"
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
