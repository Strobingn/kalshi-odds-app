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
import okhttp3.HttpUrl.Companion.toHttpUrl
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
        val atMs: Long = System.currentTimeMillis(),
        /**
         * At least one upsert batch landed (or there was nothing to push).
         * Last synced may move forward even when another batch failed.
         */
        val advanced: Boolean = false,
        /** Safe to drop the paper sync tail. False when the paper batch failed. */
        val acknowledgePaper: Boolean = false,
        /** 0.3.56: the snapshot batch landed (or there was none) — safe to commit SnapshotUploadFilter. */
        val snapshotsOk: Boolean = false,
        /** 0.3.65: the scalp batch landed (or there was none) — safe to advance the scalp ack watermark. */
        val scalpsOk: Boolean = false
    )

    data class PushOutcome(
        val pushed: Int,
        val errors: List<String>,
        val paperOk: Boolean,
        val failedKinds: Set<String> = emptySet()
    )

    data class LocalBundle(
        val snapshots: List<ScoredSnapshotRow> = emptyList(),
        val alerts: List<AlertRow> = emptyList(),
        val tickets: List<TicketAttemptRow> = emptyList(),
        val settingsChanges: List<SettingsChange> = emptyList(),
        val paperFills: List<PaperFill> = emptyList(),
        val settingsSnapshot: String? = null,
        /** 0.3.55: the 8 paper scalpers' closed round trips (never synced before). */
        val scalps: List<com.dirk.kalshiodds.decision.ScalpTrade> = emptyList(),
        /** 0.3.55: Autopilot SHADOW tickets (Autopilot has been in SHADOW since Oct 10 01:27 ET; never synced before). */
        val shadow: List<com.dirk.kalshiodds.signal.paper.ShadowTicket> = emptyList()
    )

    fun pull(settings: DataHubSettings): List<SyncMerge.Record> {
        if (!settings.supabaseConfigured) return emptyList()
        // 0.3.60: one indexed query per kind (kind, updated_at, key index) instead of kind=not.in(...) over the
        // whole table, which scanned ~110k snapshot rows and hit the 3 s anon statement timeout (500 / 57014).
        return PULL_KINDS.flatMap { parseRows(restGet(settings, TABLE, it)) }.distinctBy { it.key }
    }

    fun push(settings: DataHubSettings, rows: List<SyncMerge.Record>): Int =
        pushBatches(settings, rows).pushed

    /**
     * Upsert [rows] to [TABLE] (`diphunter_sync`). Each kind is its own
     * batch so one failure does not roll back the others. Every batch is
     * deduped on the conflict key (`key`, already `kashi:`-prefixed)
     * before POST — Postgres error 21000 is "ON CONFLICT DO UPDATE
     * cannot affect row a second time" when one command repeats a key.
     * Newest [SyncMerge.Record.updatedAtMs] wins; a tie keeps the last row.
     */
    fun pushBatches(settings: DataHubSettings, rows: List<SyncMerge.Record>): PushOutcome {
        if (!settings.supabaseConfigured || rows.isEmpty()) {
            return PushOutcome(pushed = 0, errors = emptyList(), paperOk = true)
        }
        val safe = rows
            .filter { !SyncMerge.isForbiddenPayload(it.payload) }
            .map { it.copy(key = namespaceKey(it.key)) }
        if (safe.isEmpty()) return PushOutcome(0, emptyList(), paperOk = true)
        val deduped = dedupeBatch(safe)
        var pushed = 0
        val errors = ArrayList<String>()
        var sawPaper = false
        val failed = HashSet<String>()
        var paperOk = true
        for ((kind, batch) in deduped.groupBy { it.kind }) {
            if (kind == "paper") sawPaper = true
            try {
                restUpsert(settings, TABLE, batchBody(batch))
                pushed += batch.size
            } catch (t: Throwable) {
                val reason = t.message?.takeIf { it.isNotBlank() } ?: "sync failed"
                errors += "$TABLE $kind: $reason"
                failed += kind
                if (kind == "paper") paperOk = false
            }
        }
        if (!sawPaper) paperOk = true
        return PushOutcome(pushed, errors, paperOk, failed)
    }

    /** 0.3.65 Data-tab line: size vs the 500 MB free-plan cap, warning at 80 %. */
    fun storageLine(bytes: Long?, unsyncedScalps: Int): Pair<String, Boolean> {
        val un = "Scalp trades waiting to upload: $unsyncedScalps"
        if (bytes == null) return "Supabase size: unavailable. $un" to false
        val mb = bytes / 1_048_576.0
        val pct = mb / FREE_CAP_MB * 100.0
        val warn = pct >= 80.0
        return String.format(java.util.Locale.US, "Supabase size: %.0f MB of %d MB free plan (%.0f%%)%s. Rows are never deleted. %s",
            mb, FREE_CAP_MB, pct, if (warn) " — WARNING: over 80%, upgrade or export soon" else "", un) to warn
    }

    fun pack(bundle: LocalBundle): List<SyncMerge.Record> {
        val out = ArrayList<SyncMerge.Record>()
        // 0.3.55: only meaningful snapshot changes (or one per ticker per 5 min), compact payload (no note, 0.1pp).
        bundle.snapshots.forEach { s -> // 0.3.55: SyncWorker passes SnapshotUploadFilter.select(...) rows
            val payload = JSONObject()
                .put("ticker", s.ticker)
                .put("series", s.series)
                .put("side", s.side)
                .putFinite("edgePp", SnapshotUploadFilter.r1(s.edgePp))
                .putFinite("fairPp", SnapshotUploadFilter.r1(s.fairPp))
                .putFinite("marketPp", SnapshotUploadFilter.r1(s.marketPp))
                .put("createdAtMs", s.createdAtMs)
            out.add(rec("snapshot:${s.ticker}:${s.createdAtMs}:${s.side}", "snapshot", s.createdAtMs, payload))
        }
        bundle.scalps.forEach { t ->
            if (t.state != com.dirk.kalshiodds.decision.ScalpState.CLOSED) return@forEach
            val at = t.closedAtMs ?: return@forEach
            val payload = JSONObject()
                .put("id", t.id).put("ticker", t.ticker).put("side", t.side)
                .put("algo", t.algoLabel).put("variant", t.variantId).put("primary", t.isPrimary)
                .put("contracts", t.contracts).putFinite("entryPrice", t.entryPrice)
                .putFinite("exitAvg", if (t.contracts > 0) t.proceedsUsd / t.contracts else null)
                .putFinite("feesUsd", t.entryFeeUsd + t.exitFeeUsd).putFinite("netUsd", t.netUsd)
                .put("entryAtMs", t.entryAtMs ?: JSONObject.NULL).put("closedAtMs", at)
                .put("exitReason", t.exitReason ?: "")
                // 0.3.65 full detail: signal, fees per leg, exit decision time, adverse marks, note (entry/exit book, spot, fair, model, peak bid, stop).
                .put("signalAtMs", t.signalAtMs).putFinite("signalAsk", t.signalAsk).putFinite("fairAtSignal", t.fairAtSignal)
                .putFinite("entryFeeUsd", t.entryFeeUsd).putFinite("exitFeeUsd", t.exitFeeUsd).put("soldContracts", t.soldContracts)
                .put("exitDecidedAtMs", t.exitDecidedAtMs ?: JSONObject.NULL).putFinite("adverse30", t.adverse30).putFinite("adverse60", t.adverse60)
                .put("stopLevel", com.dirk.kalshiodds.decision.ScalpModels.modelOf(t)?.let { com.dirk.kalshiodds.decision.ScalpModels.stopLabel(it) } ?: "")
                .put("group", if (com.dirk.kalshiodds.decision.ScalpV2.isV2(t.variantId)) "v2" else (com.dirk.kalshiodds.decision.ShockFav.byVariantId(t.variantId)?.group ?: ""))
                .put("note", t.note.take(2_000))
                .put("executionNotes", com.dirk.kalshiodds.decision.ScalpFills.executionNotes(t.note))
            out.add(rec("scalp:${t.id}", "scalp", at, payload))
        }
        bundle.shadow.forEach { s ->
            val payload = JSONObject()
                .put("id", s.id).put("ticker", s.ticker).put("side", s.side).put("action", s.action)
                .put("count", s.count).putFinite("limitPrice", s.limitPrice).put("booked", s.booked)
                .putFinite("stakeUsd", s.stakeUsd).putFinite("feeUsd", s.feeUsd).put("settled", s.settled)
                .put("outcome", s.outcome ?: JSONObject.NULL).putFinite("pnlUsd", s.pnlUsd)
                .put("reason", s.reason.take(200)).put("createdAtMs", s.createdAtMs)
            out.add(rec("shadow:${s.id}", "shadow", s.createdAtMs, payload))
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
                .put("updatedAtMs", f.syncAtMs())
                .put("settled", f.settled)
                .put("pnlUsd", f.pnlUsd)
                .put("source", f.source)
                .put("note", f.note)
            f.outcome?.let { payload.put("outcome", it) }
            f.won?.let { payload.put("won", it) }
            f.aiPct?.takeIf { it.isFinite() }?.let { payload.put("aiPct", it) }
            f.aiConfidence?.takeIf { it.isFinite() }?.let { payload.put("aiConfidence", it) }
            f.marketPct?.takeIf { it.isFinite() }?.let { payload.put("marketPct", it) }
            f.pickSource?.takeIf { it.isNotBlank() }?.let { payload.put("pickSource", it) }
            f.kellyF?.takeIf { it.isFinite() }?.let { payload.put("kellyF", it) }
            f.kellyFraction?.takeIf { it.isFinite() }?.let { payload.put("kellyFraction", it) }
            f.bankrollAfterUsd?.takeIf { it.isFinite() }?.let { payload.put("bankrollAfterUsd", it) }
            out.add(rec("paper:${f.id}", "paper", f.syncAtMs(), payload))
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
                "snapshot" -> snapshots.add(SyncRows.snapshot(o, rec.updatedAtMs))
                "alert" -> store.insertAlert(SyncRows.alert(o, rec))
                "ticket" -> store.insertTicket(SyncRows.ticket(o, rec))
                "settings", "settings_snapshot" -> SyncRows.settingsPayload(o, rec)?.let(onSettings)
                "paper" -> onPaper(SyncRows.paper(o, rec))
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
            val pushed = pushBatches(settings, outgoing)
            val head = "Synced · pulled ${remote.size} · merged ${merged.upserts.size} · pushed ${pushed.pushed}"
            val message = if (pushed.errors.isEmpty()) head else (listOf(head) + pushed.errors).joinToString("\n")
            Status(
                ok = pushed.errors.isEmpty(),
                message = message,
                pulled = remote.size,
                pushed = pushed.pushed,
                advanced = pushed.errors.isEmpty() || pushed.pushed > 0,
                acknowledgePaper = pushed.paperOk,
                snapshotsOk = "snapshot" !in pushed.failedKinds,
                scalpsOk = "scalp" !in pushed.failedKinds
            )
        } catch (t: Throwable) {
            Status(ok = false, message = t.message?.takeIf { it.isNotBlank() } ?: "Sync failed")
        }
    }

    private fun rec(key: String, kind: String, at: Long, payload: JSONObject) =
        SyncMerge.Record(namespaceKey(key), kind, at, payload.toString())

    private fun restGet(settings: DataHubSettings, table: String, kind: String): String {
        val url = settings.supabaseUrl.trimEnd('/').toHttpUrl().newBuilder()
            .addPathSegments("rest/v1/$table")
            .addEncodedQueryParameter("select", "*")
            .addEncodedQueryParameter("limit", PULL_LIMIT_PER_KIND.toString())
            .addEncodedQueryParameter("key", "like.kashi:*")
            // 0.3.55: never pull snapshots/scalp/shadow back; 0.3.60: positive per-kind equality (index-backed).
            .addEncodedQueryParameter("kind", "eq.$kind")
            // Newest first; direction matches the (kind, updated_at, key) index so it is a plain backward index scan.
            .addEncodedQueryParameter("order", "updated_at.desc,key.desc")
            .build()
        val req = Request.Builder()
            .url(url)
            .header("apikey", settings.supabaseAnonKey)
            .header("Authorization", "Bearer ${settings.supabaseAnonKey}")
            .header("Accept", "application/json")
            .header("User-Agent", NetworkModule.USER_AGENT)
            .get()
            .build()
        return execute(req)
    }

    private fun batchBody(rows: List<SyncMerge.Record>): String {
        val arr = JSONArray()
        for (r in rows) {
            val o = JSONObject()
            o.put("key", r.key)
            o.put("kind", r.kind)
            o.put("updated_at", r.updatedAtMs)
            o.put("payload", JSONObject(r.payload))
            arr.put(o)
        }
        return arr.toString()
    }

    /** 0.3.65 read-only: database size in bytes via the db_size_bytes() RPC (pg_database_size). Null on failure. */
    fun dbSizeBytes(settings: DataHubSettings): Long? {
        if (!settings.supabaseConfigured) return null
        val req = Request.Builder()
            .url("${settings.supabaseUrl.trimEnd('/')}/rest/v1/rpc/db_size_bytes")
            .header("apikey", settings.supabaseAnonKey)
            .header("Authorization", "Bearer ${settings.supabaseAnonKey}")
            .header("Content-Type", "application/json")
            .header("User-Agent", NetworkModule.USER_AGENT)
            .post("{}".toRequestBody(JSON))
            .build()
        return runCatching { execute(req).trim().trim('"').toLong() }.getOrNull()
    }

    private fun restUpsert(settings: DataHubSettings, table: String, body: String) {
        val url = "${settings.supabaseUrl.trimEnd('/')}/rest/v1/$table"
        val req = Request.Builder()
            .url(url)
            .header("apikey", settings.supabaseAnonKey)
            .header("Authorization", "Bearer ${settings.supabaseAnonKey}")
            .header("Accept", "application/json")
            .header("Prefer", "resolution=merge-duplicates,return=minimal")
            .header("Content-Type", "application/json")
            .header("User-Agent", NetworkModule.USER_AGENT)
            .post(body.toRequestBody(JSON))
            .build()
        execute(req)
    }

    private fun execute(req: Request): String {
        val resp = try {
            http.newCall(req).execute()
        } catch (e: Exception) {
            throw SyncHttpException(describeFailure(0, null, e))
        }
        resp.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw SyncHttpException(describeFailure(it.code, text, null))
            return text
        }
    }

    companion object {
        const val FREE_CAP_MB = 500
        const val TABLE = "diphunter_sync"
        /** 0.3.60: kinds merged back on pull (everything except snapshot / scalp / shadow, which are push-only). */
        val PULL_KINDS = listOf("alert", "paper", "ticket", "settings", "settings_snapshot")
        const val PULL_LIMIT_PER_KIND = 1000
        const val KEY_PREFIX = "kashi:"
        /** PostgREST `like` filter. `*` is the wildcard. */
        const val PULL_FILTER = "key=like.kashi:*"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        val FORBIDDEN_SETTING_KEYS = setOf(
            "api_key_id", "apikeyid", "private_key_pem", "pem", "github_token", "githubtoken"
        )

        fun namespaceKey(key: String): String =
            if (key.startsWith(KEY_PREFIX)) key else KEY_PREFIX + key

        /**
         * One row per conflict key. Newest [SyncMerge.Record.updatedAtMs]
         * wins. Equal timestamps keep the last occurrence.
         */
        fun dedupeBatch(rows: List<SyncMerge.Record>): List<SyncMerge.Record> {
            val byKey = LinkedHashMap<String, SyncMerge.Record>()
            for (row in rows) {
                val prev = byKey[row.key]
                if (prev == null || row.updatedAtMs >= prev.updatedAtMs) byKey[row.key] = row
            }
            return byKey.values.toList()
        }

        fun parseRows(raw: String): List<SyncMerge.Record> {
            val arr = runCatching { JSONArray(raw) }.getOrElse {
                throw SyncHttpException("Sync failed — response was not JSON")
            }
            val out = ArrayList<SyncMerge.Record>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val key = o.optString("key")
                val kind = o.optString("kind")
                val payload = o.opt("payload")?.toString() ?: continue
                if (!acceptsPulledRow(key, kind)) continue
                if (SyncMerge.isForbiddenPayload(payload)) continue
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

        /** Only this app's rows. Other DipHunter builds share the table. */
        fun acceptsPulledRow(key: String, kind: String): Boolean {
            if (!key.startsWith(KEY_PREFIX)) return false
            if (kind.equals("healthcheck", ignoreCase = true)) return false
            if (key.startsWith("grokbot:", ignoreCase = true)) return false
            return true
        }

        fun describeFailure(code: Int, body: String?, transport: Throwable?): String {
            if (transport != null || code <= 0) return "offline"
            val lower = body.orEmpty().lowercase()
            val reason = when {
                code == 401 || lower.contains("invalid api key") || lower.contains("jwt") -> "bad key"
                code == 404 || lower.contains("does not exist") || lower.contains("pgrst205") ||
                    lower.contains("could not find the table") -> "table missing"
                code == 403 || lower.contains("row-level security") || lower.contains("rls") ||
                    lower.contains("permission denied") -> "RLS denied"
                else -> body?.lineSequence()?.firstOrNull { it.isNotBlank() }?.take(80) ?: "request failed"
            }
            return "$code $reason"
        }
    }
}

class SyncHttpException(message: String) : Exception(message)

private fun JSONObject.putFinite(k: String, v: Double?): JSONObject = put(k, v?.takeIf { it.isFinite() } ?: JSONObject.NULL)

/**
 * 0.3.55 snapshot upload throttle. The phone scores every book update; uploading the newest 400 every sync was
 * ~38k rows / ~25–50 MB a day. Per ticker we now upload a row only when the side flips or the edge moves ≥ 3pp
 * (at most one per minute), and otherwise at most one row per 5 minutes. Local SQLite keeps every row.
 */
object SnapshotUploadFilter {
    const val MIN_GAP_MS = 60_000L
    const val HEARTBEAT_MS = 300_000L
    const val EDGE_MOVE_PP = 3.0
    private data class Last(val atMs: Long, val side: String, val edgePp: Double)
    private val last = java.util.concurrent.ConcurrentHashMap<String, Last>()

    fun r1(v: Double): Double = if (v.isFinite()) Math.round(v * 10.0) / 10.0 else v

    fun reset() = last.clear()

    /**
     * 0.3.56: pure — picks rows against the last *committed* upload state and does not change it.
     * Call [commit] with the rows only after the upload succeeded, so a failed sync re-selects them.
     */
    fun select(rows: List<ScoredSnapshotRow>): List<ScoredSnapshotRow> {
        val last = HashMap(this.last)
        val out = ArrayList<ScoredSnapshotRow>()
        for (s in rows.sortedBy { it.createdAtMs }) {
            val prev = last[s.ticker]
            val keep = prev == null || run {
                val dt = s.createdAtMs - prev.atMs
                dt < 0L || dt >= HEARTBEAT_MS ||
                    (s.side != prev.side && (dt >= MIN_GAP_MS || dt == 0L)) || // YES+NO rows of one score both go
                    (dt >= MIN_GAP_MS && s.edgePp.isFinite() && kotlin.math.abs(s.edgePp - prev.edgePp) >= EDGE_MOVE_PP)
            }
            if (keep) {
                last[s.ticker] = Last(s.createdAtMs, s.side, s.edgePp)
                out += s
            }
        }
        return out
    }

    /** Record [uploaded] as the new per-ticker upload state (newest row per ticker wins). */
    fun commit(uploaded: List<ScoredSnapshotRow>) {
        for (s in uploaded.sortedBy { it.createdAtMs }) {
            val prev = last[s.ticker]
            if (prev == null || s.createdAtMs >= prev.atMs) last[s.ticker] = Last(s.createdAtMs, s.side, s.edgePp)
        }
        if (last.size > 256) last.entries.sortedBy { it.value.atMs }.take(last.size - 128).forEach { last.remove(it.key) }
    }
}

/** Maps a `diphunter_sync` payload into local rows. Shared by sync apply and restore. */
internal object SyncRows {
    private fun JSONObject.dOrNull(k: String): Double? = if (has(k) && !isNull(k)) optDouble(k).takeIf { it.isFinite() } else null

    /** 0.3.56 restore of a synced scalp round trip (closed, primary). */
    fun scalp(o: JSONObject): com.dirk.kalshiodds.decision.ScalpTrade? {
        val id = o.optString("id").ifBlank { return null }
        val closed = o.optLong("closedAtMs").takeIf { it > 0 } ?: return null
        val n = o.optInt("contracts")
        val entry = o.dOrNull("entryPrice")
        return com.dirk.kalshiodds.decision.ScalpTrade(
            id = id, ticker = o.optString("ticker"), side = o.optString("side"),
            state = com.dirk.kalshiodds.decision.ScalpState.CLOSED,
            signalAtMs = o.optLong("signalAtMs").takeIf { it > 0 } ?: o.optLong("entryAtMs").takeIf { it > 0 } ?: closed,
            signalAsk = o.dOrNull("signalAsk") ?: entry ?: 0.0, fairAtSignal = o.dOrNull("fairAtSignal") ?: entry ?: 0.0,
            contracts = n, entryPrice = entry, entryFeeUsd = o.dOrNull("entryFeeUsd") ?: ((o.dOrNull("feesUsd") ?: 0.0) - (o.dOrNull("exitFeeUsd") ?: 0.0)).coerceAtLeast(0.0),
            exitFeeUsd = o.dOrNull("exitFeeUsd") ?: 0.0,
            exitDecidedAtMs = o.optLong("exitDecidedAtMs").takeIf { it > 0 }, adverse30 = o.dOrNull("adverse30"), adverse60 = o.dOrNull("adverse60"),
            entryAtMs = o.optLong("entryAtMs").takeIf { it > 0 }, soldContracts = if (o.has("soldContracts")) o.optInt("soldContracts").coerceIn(0, n.coerceAtLeast(0)) else n,
            proceedsUsd = (o.dOrNull("exitAvg") ?: 0.0) * n, closedAtMs = closed, netUsd = o.dOrNull("netUsd"),
            exitReason = o.optString("exitReason").ifBlank { null },
            note = com.dirk.kalshiodds.decision.ScalpFills.restoreNote(if (o.isNull("note")) "" else o.optString("note"), if (o.isNull("executionNotes")) "" else o.optString("executionNotes")),
            ruleVersion = "restored|${o.optString("variant").ifBlank { com.dirk.kalshiodds.decision.ScalpParams.LEGACY_ID }}|${if (o.optBoolean("primary", true)) "P" else "S"}"
        )
    }

    /** 0.3.56 restore of a synced Autopilot SHADOW ticket. */
    fun shadow(o: JSONObject): com.dirk.kalshiodds.signal.paper.ShadowTicket? {
        val id = o.optString("id").ifBlank { return null }
        val px = o.dOrNull("limitPrice") ?: return null
        val side = o.optString("side")
        return com.dirk.kalshiodds.signal.paper.ShadowTicket(
            id = id, ticker = o.optString("ticker"), side = side, action = o.optString("action").ifBlank { "buy" },
            bookSide = side.lowercase(), count = o.optInt("count"), limitPrice = px,
            yesLimitPrice = if (side.equals("NO", true)) 1.0 - px else px, clientOrderId = "restored-$id",
            createdAtMs = o.optLong("createdAtMs"), reason = o.optString("reason"), payloadJson = "{}",
            depthFill = o.optBoolean("booked"), booked = o.optBoolean("booked"), stakeUsd = o.dOrNull("stakeUsd") ?: 0.0,
            feeUsd = o.dOrNull("feeUsd") ?: 0.0, settled = o.optBoolean("settled"),
            outcome = o.optString("outcome").takeIf { it.isNotBlank() && it != "null" }, pnlUsd = o.dOrNull("pnlUsd"),
            won = o.dOrNull("pnlUsd")?.let { it > 0.0 }
        )
    }

    fun snapshot(o: JSONObject, fallbackAt: Long) = ScoredSnapshotRow(
        ticker = o.optString("ticker"),
        series = o.optString("series"),
        side = o.optString("side"),
        edgePp = o.optDouble("edgePp"),
        fairPp = o.optDouble("fairPp"),
        marketPp = o.optDouble("marketPp"),
        regime = o.optString("regime").takeIf { it.isNotBlank() },
        uncertainty = null,
        createdAtMs = o.optLong("createdAtMs", fallbackAt),
        note = o.optString("note").takeIf { it.isNotBlank() }
    )

    fun alert(o: JSONObject, rec: SyncMerge.Record) = AlertRow(
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

    fun ticket(o: JSONObject, rec: SyncMerge.Record) = TicketAttemptRow(
        ticker = o.optString("ticker"),
        side = o.optString("side"),
        stakeUsd = o.optDouble("stakeUsd"),
        approved = o.optBoolean("approved"),
        result = o.optString("result"),
        createdAtMs = o.optLong("createdAtMs", rec.updatedAtMs),
        note = o.optString("note").takeIf { it.isNotBlank() }
    )

    fun settingsChange(o: JSONObject, fallbackAt: Long): com.dirk.kalshiodds.data.local.history.SettingsChange? {
        val key = o.optString("key")
        if (key.isBlank()) return null
        if (SupabaseSync.FORBIDDEN_SETTING_KEYS.contains(key.lowercase())) return null
        val snap = o.optString("snapshotJson").takeIf { it.isNotBlank() && !SyncMerge.isForbiddenPayload(it) }
        return com.dirk.kalshiodds.data.local.history.SettingsChange(
            createdAtMs = o.optLong("createdAtMs", fallbackAt),
            key = key,
            oldValue = o.optString("oldValue"),
            newValue = o.optString("newValue"),
            snapshotJson = snap
        )
    }

    fun settingsPayload(o: JSONObject, rec: SyncMerge.Record): String? {
        val snap = o.optString("snapshotJson").ifBlank { rec.payload }
        if (SyncMerge.isForbiddenPayload(snap)) return null
        return snap
    }

    fun paper(o: JSONObject, rec: SyncMerge.Record) = PaperFill(
        id = o.optString("id").ifBlank { rec.key },
        ticker = o.optString("ticker"),
        side = o.optString("side"),
        stakeUsd = o.optDouble("stakeUsd"),
        contracts = o.optInt("contracts"),
        limitPrice = o.optDouble("limitPrice"),
        source = o.optString("source").ifBlank { "supabase" },
        createdAtMs = o.optLong("createdAtMs", rec.updatedAtMs),
        settled = o.optBoolean("settled"),
        outcome = o.optString("outcome").takeIf { it.isNotBlank() },
        won = if (o.has("won") && !o.isNull("won")) o.optBoolean("won") else null,
        pnlUsd = o.optDouble("pnlUsd").takeIf { o.has("pnlUsd") && !o.isNull("pnlUsd") },
        note = o.optString("note"),
        aiPct = o.optDouble("aiPct").takeIf { o.has("aiPct") && !o.isNull("aiPct") },
        updatedAtMs = o.optLong("updatedAtMs", rec.updatedAtMs),
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
}
