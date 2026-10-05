package com.dirk.kalshiodds.data.supabase

import android.util.Log
import com.dirk.kalshiodds.data.local.history.SettingsChange
import com.dirk.kalshiodds.data.local.history.SettingsRestore
import com.dirk.kalshiodds.data.local.results.AlertRow
import com.dirk.kalshiodds.data.local.results.ResultsStore
import com.dirk.kalshiodds.data.local.results.ScoredSnapshotRow
import com.dirk.kalshiodds.data.local.results.TicketAttemptRow
import com.dirk.kalshiodds.data.prefs.DataHubSettings
import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.paper.PaperFill
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Incremental History / bets / signals / settings sync.
 * Never uploads the Kalshi private key or API secret.
 * Writes and reads only [SYNC_NAMESPACE] rows.
 */
class SupabaseSync(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build(),
    private val pageSize: Int = SYNC_PAGE_SIZE
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
        val raw = rest().downloadObjectArray(
            settings = settings,
            table = TABLE,
            keyPrefix = SYNC_NAMESPACE,
            orders = listOf("key.asc"),
            optional = false
        ) ?: throw SupabaseHttpException("diphunter_sync is missing")
        val arr = JSONArray(raw)
        val out = ArrayList<SyncMerge.Record>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val key = o.optString("key")
            val kind = o.optString("kind")
            val payload = o.opt("payload")?.toString() ?: continue
            if (!ownsNamespace(key) || SyncMerge.isForbiddenPayload(payload)) continue
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
        val prepared = dedupeUpsertBatch(
            rows.map { row ->
                if (ownsNamespace(row.key)) row else row.copy(key = namespacedKey(row.key))
            }.filter { !SyncMerge.isForbiddenPayload(it.payload) }
        )
        if (prepared.isEmpty()) return 0
        val client = rest()
        var sent = 0
        for (chunk in prepared.chunked(UPSERT_CHUNK)) {
            client.upsert(settings, TABLE, upsertBody(chunk))
            sent += chunk.size
        }
        return sent
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
            val side = s.side.ifBlank { "_" }
            out.add(rec("snapshot:${s.ticker}:$side:${s.createdAtMs}", "snapshot", s.createdAtMs, payload))
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
        return out.filter { !SyncMerge.isForbiddenPayload(it.payload) && ownsNamespace(it.key) }
    }

    fun apply(
        merged: List<SyncMerge.Record>,
        store: ResultsStore,
        onSettings: (String) -> Unit,
        onPaper: (PaperFill) -> Unit
    ) {
        val snapshots = ArrayList<ScoredSnapshotRow>()
        for (rec in merged) {
            if (!ownsNamespace(rec.key)) continue
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
        return try {
            val packed = pack(local)
            val remote = pull(settings)
            val incoming = SyncMerge.incomingWinners(packed, remote)
            apply(incoming, store, onSettings, onPaper)
            // Rows missing from this namespace still upload after a failed run
            // moved [lastPushMs] forward. Presence on the server decides.
            val outgoing = SyncMerge.pendingUpload(packed, remote)
            val pushed = push(settings, outgoing)
            runCatching {
                Log.i(TAG, "sync ok cursor=$lastPushMs pulled=${remote.size} pushed=$pushed")
            }
            Status(
                ok = true,
                message = "Synced · pulled ${remote.size} · merged ${incoming.size} · pushed $pushed",
                pulled = remote.size,
                pushed = pushed
            )
        } catch (t: Throwable) {
            failure(t)
        }
    }

    private fun rest() = SupabaseRest(http, pageSize)

    private fun rec(key: String, kind: String, at: Long, payload: JSONObject) =
        SyncMerge.Record(namespacedKey(key), kind, at, payload.toString())

    private fun failure(t: Throwable): Status {
        val detail = (t.message ?: "unknown error").replace(Regex("\\s+"), " ").take(280)
        val message = if (detail.startsWith(FAILURE_PREFIX)) detail else "$FAILURE_PREFIX$detail"
        runCatching { Log.e(TAG, message, t) }
        return Status(ok = false, message = message)
    }

    companion object {
        const val TABLE = "diphunter_sync"
        const val FAILURE_PREFIX = "Sync failed: "
        private const val TAG = "SupabaseSync"
        private const val UPSERT_CHUNK = 400
        val FORBIDDEN_SETTING_KEYS = setOf(
            "api_key_id", "apikeyid", "private_key_pem", "pem", "github_token", "githubtoken"
        )

        fun isFailureMessage(message: String): Boolean = message.startsWith(FAILURE_PREFIX)

        /** A failed sync must not move the cursor, or the next run skips the same rows. */
        fun nextSyncCursor(ok: Boolean, statusAtMs: Long, previousAtMs: Long): Long =
            if (ok) statusAtMs else previousAtMs

        internal fun upsertBody(rows: List<SyncMerge.Record>): String {
            val arr = JSONArray()
            val seen = HashSet<String>(rows.size)
            for (r in rows) {
                if (!ownsNamespace(r.key)) {
                    throw SupabaseHttpException("refusing to upload a key outside $SYNC_NAMESPACE")
                }
                if (!seen.add(r.key)) {
                    throw SupabaseHttpException("refusing to upload duplicate key ${r.key}")
                }
                val o = JSONObject()
                o.put("key", r.key)
                o.put("kind", r.kind)
                o.put("updated_at", r.updatedAtMs)
                o.put("payload", JSONObject(r.payload))
                arr.put(o)
            }
            return arr.toString()
        }
    }
}
