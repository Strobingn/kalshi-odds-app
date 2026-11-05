package com.dirk.kalshiodds.data.supabase

import com.dirk.kalshiodds.data.api.NetworkModule
import com.dirk.kalshiodds.data.local.history.SettingsChange
import com.dirk.kalshiodds.data.local.history.SettingsRestore
import com.dirk.kalshiodds.data.local.results.AlertRow
import com.dirk.kalshiodds.data.local.results.ResultsStore
import com.dirk.kalshiodds.data.local.results.ScoredSnapshotRow
import com.dirk.kalshiodds.data.local.results.SyncKeys
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
            out.add(rec(SyncKeys.snapshot(s.ticker, s.createdAtMs), "snapshot", s.createdAtMs, payload))
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
            out.add(rec(SyncKeys.alert(a.alertId), "alert", a.createdAtMs, payload))
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
            out.add(rec(SyncKeys.ticket(t.ticker, t.createdAtMs, t.side), "ticket", t.createdAtMs, payload))
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
            out.add(rec("paper:${f.id}", "paper", f.createdAtMs, payload))
        }
        bundle.settingsSnapshot?.takeIf { !SyncMerge.isForbiddenPayload(it) }?.let { snap ->
            out.add(
                rec(
                    SETTINGS_CURRENT,
                    "settings_snapshot",
                    System.currentTimeMillis(),
                    JSONObject(snap)
                )
            )
        }
        return out.filter { !SyncMerge.isForbiddenPayload(it.payload) }
    }

    /**
     * Writes remote records into this device. Pass only the records the merge took ([SyncMerge.Result.incoming]) and
     * the keys already stored ([ResultsStore.syncKeys]): a snapshot, alert or ticket this device has is skipped, so a
     * sync never stores the same row twice. Settings come back only from the `settings:current` snapshot; the
     * settings change log is history, not something to restore (restoring it brought old settings back).
     */
    fun apply(
        merged: List<SyncMerge.Record>,
        store: ResultsStore,
        onSettings: (String) -> Unit,
        onPaper: (PaperFill) -> Unit,
        existing: Set<String> = emptySet()
    ) {
        val seen = HashSet(existing)
        val snapshots = ArrayList<ScoredSnapshotRow>()
        for (rec in merged) {
            val o = runCatching { JSONObject(rec.payload) }.getOrNull() ?: continue
            when (rec.kind) {
                "snapshot" -> {
                    val created = o.optLong("createdAtMs", rec.updatedAtMs)
                    if (!seen.add(SyncKeys.snapshot(o.optString("ticker"), created))) continue
                    snapshots.add(
                    ScoredSnapshotRow(
                        ticker = o.optString("ticker"),
                        series = o.optString("series"),
                        side = o.optString("side"),
                        edgePp = o.optDouble("edgePp"),
                        fairPp = o.optDouble("fairPp"),
                        marketPp = o.optDouble("marketPp"),
                        regime = o.optString("regime").takeIf { it.isNotBlank() },
                        uncertainty = null,
                        createdAtMs = created,
                        note = o.optString("note").takeIf { it.isNotBlank() }
                    )
                )
                }
                "alert" -> {
                    val alertId = o.optString("alertId").ifBlank { rec.key }
                    if (!seen.add(SyncKeys.alert(alertId))) continue
                    store.insertAlert(
                    AlertRow(
                        alertId = alertId,
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
                }
                "ticket" -> {
                    val created = o.optLong("createdAtMs", rec.updatedAtMs)
                    if (!seen.add(SyncKeys.ticket(o.optString("ticker"), created, o.optString("side")))) continue
                    store.insertTicket(
                    TicketAttemptRow(
                        ticker = o.optString("ticker"),
                        side = o.optString("side"),
                        stakeUsd = o.optDouble("stakeUsd"),
                        approved = o.optBoolean("approved"),
                        result = o.optString("result"),
                        createdAtMs = created,
                        note = o.optString("note").takeIf { it.isNotBlank() }
                    )
                )
                }
                "settings_snapshot" -> {
                    if (rec.key == SETTINGS_CURRENT && !SyncMerge.isForbiddenPayload(rec.payload)) onSettings(rec.payload)
                }
                "paper" -> {
                    val fill = PaperFill(
                        id = o.optString("id").ifBlank { rec.key },
                        ticker = o.optString("ticker"),
                        side = o.optString("side"),
                        stakeUsd = o.optDouble("stakeUsd"),
                        contracts = o.optInt("contracts"),
                        limitPrice = o.optDouble("limitPrice"),
                        source = "supabase",
                        createdAtMs = o.optLong("createdAtMs", rec.updatedAtMs),
                        settled = o.optBoolean("settled"),
                        pnlUsd = o.optDouble("pnlUsd").takeIf { o.has("pnlUsd") },
                        note = o.optString("note")
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
        // Rows written while this sync runs must not fall behind the saved sync time, so it is taken at the start.
        val startedMs = System.currentTimeMillis()
        return try {
            val packed = pack(local)
            val remote = pull(settings)
            val merged = SyncMerge.merge(packed, remote)
            val have = runCatching { store.syncKeys() }.getOrElse { emptySet() }
            apply(merged.incoming, store, onSettings, onPaper, existing = have)
            val outgoing = SyncMerge.outgoing(packed, lastPushMs)
            val pushed = push(settings, outgoing)
            // A failed upsert pushes 0; calling that a success moved the sync time past rows never sent, so they
            // were skipped on every later sync.
            val pushOk = outgoing.none { !SyncMerge.isForbiddenPayload(it.payload) } || pushed > 0
            Status(
                ok = pushOk,
                atMs = startedMs,
                message = (if (pushOk) "Synced" else "Push failed, will retry") + " · pulled ${remote.size} · new here ${merged.incoming.size} · pushed $pushed",
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
        /** The one record a sync may restore settings from: this device's whole settings snapshot. */
        const val SETTINGS_CURRENT = "settings:current"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        val FORBIDDEN_SETTING_KEYS = setOf(
            "api_key_id", "apikeyid", "private_key_pem", "pem", "github_token", "githubtoken"
        )
    }
}
