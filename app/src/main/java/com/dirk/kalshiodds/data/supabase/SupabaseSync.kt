package com.dirk.kalshiodds.data.supabase

import com.dirk.kalshiodds.data.api.NetworkModule
import com.dirk.kalshiodds.data.local.history.SettingsChange
import com.dirk.kalshiodds.data.local.history.SettingsRestore
import com.dirk.kalshiodds.data.local.results.AlertRow
import com.dirk.kalshiodds.data.local.results.ResultsStore
import com.dirk.kalshiodds.data.local.results.ScoredSnapshotRow
import com.dirk.kalshiodds.data.local.results.TicketAttemptRow
import com.dirk.kalshiodds.data.prefs.DataHubSettings
import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.paper.PaperFill
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Incremental History / bets / signals / settings sync.
 * Never uploads the Kalshi private key or API secret.
 */
class SupabaseSync(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()
) {
    data class Status(
        val ok: Boolean,
        val message: String,
        val pulled: Int = 0,
        val pushed: Int = 0,
        val atMs: Long = System.currentTimeMillis()
    )

    data class LocalBundle(
        val snapshots: List<ScoredSnapshotRow> = emptyList(),
        val alerts: List<AlertRow> = emptyList(),
        val tickets: List<TicketAttemptRow> = emptyList(),
        val settingsChanges: List<SettingsChange> = emptyList(),
        val paperFills: List<PaperFill> = emptyList(),
        val settingsSnapshot: String? = null
    )

    fun pull(settings: DataHubSettings): List<SyncMerge.Record> {
        if (!settings.supabaseConfigured) return emptyList()
        val raw = restGet(settings, TABLE) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = ArrayList<SyncMerge.Record>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val key = o.optString("key")
            val kind = o.optString("kind")
            val payload = o.opt("payload")?.toString() ?: continue
            if (key.isBlank() || SyncMerge.isForbiddenPayload(payload)) continue
            out.add(
                SyncMerge.Record(
                    key = key,
                    kind = kind.ifBlank { "unknown" },
                    updatedAtMs = o.optLong("updated_at", o.optLong("updated_at_ms")),
                    payload = payload
                )
            )
        }
        return out
    }

    fun push(settings: DataHubSettings, rows: List<SyncMerge.Record>): Int {
        if (!settings.supabaseConfigured || rows.isEmpty()) return 0
        val safe = rows.filter { !SyncMerge.isForbiddenPayload(it.payload) }
        if (safe.isEmpty()) return 0
        val arr = JSONArray()
        for (r in safe) {
            val o = JSONObject()
            o.put("key", r.key)
            o.put("kind", r.kind)
            o.put("updated_at", r.updatedAtMs)
            o.put("payload", JSONObject(r.payload))
            arr.put(o)
        }
        val ok = restUpsert(settings, arr.toString())
        return if (ok) safe.size else 0
    }

    fun pack(bundle: LocalBundle): List<SyncMerge.Record> {
        val out = ArrayList<SyncMerge.Record>()
        bundle.snapshots.forEach { s ->
            val payload = JSONObject()
                .put("ticker", s.ticker)
                .put("series", s.series)
                .put("side", s.side)
                .put("edgePp", s.edgePp)
                .put("fairPp", s.fairPp)
                .put("marketPp", s.marketPp)
                .put("createdAtMs", s.createdAtMs)
                .put("note", s.note)
            out.add(rec("snapshot:${s.ticker}:${s.createdAtMs}", "snapshot", s.createdAtMs, payload))
        }
        bundle.alerts.forEach { a ->
            val payload = JSONObject()
                .put("alertId", a.alertId)
                .put("ticker", a.ticker)
                .put("series", a.series)
                .put("side", a.side)
                .put("edgePp", a.edgePp)
                .put("reason", a.reason)
                .put("createdAtMs", a.createdAtMs)
            out.add(rec("alert:${a.alertId}", "alert", a.createdAtMs, payload))
        }
        bundle.tickets.forEach { t ->
            val payload = JSONObject()
                .put("ticker", t.ticker)
                .put("side", t.side)
                .put("stakeUsd", t.stakeUsd)
                .put("approved", t.approved)
                .put("result", t.result)
                .put("createdAtMs", t.createdAtMs)
                .put("note", t.note)
            out.add(rec("ticket:${t.ticker}:${t.createdAtMs}:${t.side}", "ticket", t.createdAtMs, payload))
        }
        bundle.settingsChanges.forEach { c ->
            if (FORBIDDEN_SETTING_KEYS.contains(c.key.lowercase())) return@forEach
            val payload = JSONObject()
                .put("key", c.key)
                .put("oldValue", c.oldValue)
                .put("newValue", c.newValue)
                .put("createdAtMs", c.createdAtMs)
            if (!c.snapshotJson.isNullOrBlank() && !SyncMerge.isForbiddenPayload(c.snapshotJson)) {
                payload.put("snapshotJson", c.snapshotJson)
            }
            out.add(rec("settings:${c.key}:${c.createdAtMs}", "settings", c.createdAtMs, payload))
        }
        bundle.paperFills.forEach { f ->
            val payload = JSONObject()
                .put("id", f.id)
                .put("ticker", f.ticker)
                .put("side", f.side)
                .put("stakeUsd", f.stakeUsd)
                .put("contracts", f.contracts)
                .put("limitPrice", f.limitPrice)
                .put("createdAtMs", f.createdAtMs)
                .put("settled", f.settled)
                .put("pnlUsd", f.pnlUsd)
                .put("note", f.note)
            f.kellyF?.takeIf { it.isFinite() }?.let { payload.put("kellyF", it) }
            f.kellyFraction?.takeIf { it.isFinite() }?.let { payload.put("kellyFraction", it) }
            f.bankrollAfterUsd?.takeIf { it.isFinite() }?.let { payload.put("bankrollAfterUsd", it) }
            out.add(rec("paper:${f.id}", "paper", f.createdAtMs, payload))
        }
        bundle.settingsSnapshot?.takeIf { !SyncMerge.isForbiddenPayload(it) }?.let { snap ->
            out.add(
                rec(
                    "settings:current",
                    "settings_snapshot",
                    System.currentTimeMillis(),
                    JSONObject(snap)
                )
            )
        }
        return out.filter { !SyncMerge.isForbiddenPayload(it.payload) }
    }

    fun apply(
        merged: List<SyncMerge.Record>,
        store: ResultsStore,
        onSettings: (String) -> Unit,
        onPaper: (PaperFill) -> Unit
    ) {
        val snapshots = ArrayList<ScoredSnapshotRow>()
        for (rec in merged) {
            val o = runCatching { JSONObject(rec.payload) }.getOrNull() ?: continue
            when (rec.kind) {
                "snapshot" -> snapshots.add(
                    ScoredSnapshotRow(
                        ticker = o.optString("ticker"),
                        series = o.optString("series"),
                        side = o.optString("side"),
                        edgePp = o.optDouble("edgePp"),
                        fairPp = o.optDouble("fairPp"),
                        marketPp = o.optDouble("marketPp"),
                        regime = o.optString("regime").takeIf { it.isNotBlank() },
                        uncertainty = null,
                        createdAtMs = o.optLong("createdAtMs", rec.updatedAtMs),
                        note = o.optString("note").takeIf { it.isNotBlank() }
                    )
                )
                "alert" -> store.insertAlert(
                    AlertRow(
                        alertId = o.optString("alertId").ifBlank { rec.key },
                        ticker = o.optString("ticker"),
                        series = o.optString("series"),
                        side = o.optString("side"),
                        edgePp = o.optDouble("edgePp"),
                        fairPp = o.optDouble("fairPp"),
                        marketPp = o.optDouble("marketPp"),
                        reason = o.optString("reason"),
                        regime = null,
                        createdAtMs = o.optLong("createdAtMs", rec.updatedAtMs)
                    )
                )
                "ticket" -> store.insertTicket(
                    TicketAttemptRow(
                        ticker = o.optString("ticker"),
                        side = o.optString("side"),
                        stakeUsd = o.optDouble("stakeUsd"),
                        approved = o.optBoolean("approved"),
                        result = o.optString("result"),
                        createdAtMs = o.optLong("createdAtMs", rec.updatedAtMs),
                        note = o.optString("note").takeIf { it.isNotBlank() }
                    )
                )
                "settings", "settings_snapshot" -> {
                    val snap = o.optString("snapshotJson").ifBlank { rec.payload }
                    if (!SyncMerge.isForbiddenPayload(snap)) onSettings(snap)
                }
                "paper" -> {
                    val fill = PaperFill(
                        id = o.optString("id").ifBlank { rec.key },
                        ticker = o.optString("ticker"),
                        side = o.optString("side"),
                        stakeUsd = o.optDouble("stakeUsd"),
                        contracts = o.optInt("contracts"),
                        limitPrice = o.optDouble("limitPrice"),
                        source = o.optString("source").ifBlank { "supabase" },
                        createdAtMs = o.optLong("createdAtMs", rec.updatedAtMs),
                        settled = o.optBoolean("settled"),
                        pnlUsd = o.optDouble("pnlUsd").takeIf { o.has("pnlUsd") },
                        note = o.optString("note"),
                        aiPct = o.optDouble("aiPct").takeIf { o.has("aiPct") },
                        aiConfidence = o.optDouble("aiConfidence").takeIf { o.has("aiConfidence") },
                        marketPct = o.optDouble("marketPct").takeIf { o.has("marketPct") },
                        pickSource = o.optString("pickSource").takeIf { it.isNotBlank() },
                        kellyF = o.optDouble("kellyF").takeIf { o.has("kellyF") && !o.isNull("kellyF") },
                        kellyFraction = o.optDouble("kellyFraction").takeIf {
                            o.has("kellyFraction") && !o.isNull("kellyFraction")
                        },
                        bankrollAfterUsd = o.optDouble("bankrollAfterUsd").takeIf {
                            o.has("bankrollAfterUsd") && !o.isNull("bankrollAfterUsd")
                        }
                    )
                    onPaper(fill)
                }
            }
        }
        if (snapshots.isNotEmpty()) store.insertSnapshots(snapshots)
    }

    fun settingsJson(s: SignalSettings): String = SettingsRestore.snapshot(s)

    fun roundTrip(
        settings: DataHubSettings,
        local: LocalBundle,
        store: ResultsStore,
        lastPushMs: Long,
        onSettings: (String) -> Unit,
        onPaper: (PaperFill) -> Unit
    ): Status {
        if (!settings.syncEnabled) {
            return Status(ok = true, message = "Cloud sync is off")
        }
        if (!settings.supabaseConfigured) {
            return Status(ok = false, message = "Supabase is not configured")
        }
        return try {
            val packed = pack(local)
            val remote = pull(settings)
            val merged = SyncMerge.merge(packed, remote)
            apply(merged.upserts, store, onSettings, onPaper)
            val outgoing = SyncMerge.outgoing(packed, lastPushMs)
            val pushed = push(settings, outgoing)
            Status(
                ok = true,
                message = "Synced · pulled ${remote.size} · merged ${merged.upserts.size} · pushed $pushed",
                pulled = remote.size,
                pushed = pushed
            )
        } catch (t: Throwable) {
            Status(ok = false, message = t.message ?: "Sync failed")
        }
    }

    private fun rec(key: String, kind: String, at: Long, payload: JSONObject) =
        SyncMerge.Record(key, kind, at, payload.toString())

    private fun restGet(settings: DataHubSettings, table: String): String? {
        val url = "${settings.supabaseUrl.trimEnd('/')}/rest/v1/$table?select=*&limit=2000"
        val req = Request.Builder()
            .url(url)
            .header("apikey", settings.supabaseAnonKey)
            .header("Authorization", "Bearer ${settings.supabaseAnonKey}")
            .header("Accept", "application/json")
            .header("User-Agent", NetworkModule.USER_AGENT)
            .get()
            .build()
        return try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.string()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun restUpsert(settings: DataHubSettings, body: String): Boolean {
        val url = "${settings.supabaseUrl.trimEnd('/')}/rest/v1/$TABLE"
        val req = Request.Builder()
            .url(url)
            .header("apikey", settings.supabaseAnonKey)
            .header("Authorization", "Bearer ${settings.supabaseAnonKey}")
            .header("Accept", "application/json")
            .header("Prefer", "resolution=merge-duplicates,return=minimal")
            .header("User-Agent", NetworkModule.USER_AGENT)
            .post(body.toRequestBody(JSON))
            .build()
        return try {
            http.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        const val TABLE = "diphunter_sync"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        val FORBIDDEN_SETTING_KEYS = setOf(
            "api_key_id", "apikeyid", "private_key_pem", "pem", "github_token", "githubtoken"
        )
    }
}
