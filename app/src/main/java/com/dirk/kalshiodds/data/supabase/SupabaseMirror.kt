package com.dirk.kalshiodds.data.supabase

import com.dirk.kalshiodds.data.api.NetworkModule
import com.dirk.kalshiodds.data.importing.ImportBatch
import com.dirk.kalshiodds.data.importing.ResultsImporter
import com.dirk.kalshiodds.data.importing.SeenKeys
import com.dirk.kalshiodds.data.local.archive.SettledWindowRow
import com.dirk.kalshiodds.data.local.history.SettingsChange
import com.dirk.kalshiodds.data.local.results.AlertRow
import com.dirk.kalshiodds.data.local.results.ScoredSnapshotRow
import com.dirk.kalshiodds.data.local.results.TicketAttemptRow
import com.dirk.kalshiodds.data.prefs.DataHubSettings
import com.dirk.kalshiodds.signal.paper.PaperFill
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Optional restore from the user's Supabase project.
 *
 * Phone rows live in [SupabaseSync.TABLE] (`diphunter_sync`) under the
 * `kashi:` key prefix. Named tables such as `diphunter_snapshots` are
 * not written by this app. Publishable/anon key only — never a
 * service_role key.
 *
 * Pages are ordered by the primary key and fetched until a short page
 * comes back. PostgREST often caps a response at 1000 rows, so a single
 * `limit` cannot read the whole table.
 */
class SupabaseMirror(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
    private val pageSize: Int = PAGE_SIZE
) {
    data class Restore(
        val batch: ImportBatch,
        val settled: List<SettledWindowRow>,
        val message: String,
        val paper: List<PaperFill> = emptyList(),
        val settingsSnapshot: String? = null,
        val scalps: List<com.dirk.kalshiodds.decision.ScalpTrade> = emptyList(),
        val shadow: List<com.dirk.kalshiodds.signal.paper.ShadowTicket> = emptyList()
    )

    fun restore(settings: DataHubSettings, seen: SeenKeys): Restore {
        if (!settings.supabaseConfigured) {
            return Restore(ImportBatch(), emptyList(), "Supabase is not configured")
        }
        // 0.3.61 keyset paging per kind (kind=eq.K, key > last key, order key.asc) on the (kind,key) index —
        // replaces kind=in.(…) + OFFSET, which rescanned skipped rows and hit the 3 s anon statement_timeout.
        val rows = ArrayList<SyncMerge.Record>()
        var pages = 0
        var hitPageCap = false
        for (kind in RESTORE_KINDS) {
            var after: String? = null
            var kindPages = 0
            while (pages < MAX_PAGES && kindPages < MAX_PAGES_PER_KIND) {
                val page = try {
                    fetchPage(settings, kind, after)
                } catch (e: SyncHttpException) {
                    return Restore(ImportBatch(), emptyList(), soften(e.message ?: "restore failed"))
                }
                val last = page.lastOrNull()?.key
                if (last != null && after != null && last <= after) {
                    return Restore(ImportBatch(), emptyList(), "Restore stopped: diphunter_sync pages did not advance.")
                }
                rows += page.filter { it.kind == kind }
                pages++; kindPages++
                if (page.size < pageSize || last == null) break
                after = last
                if (pages >= MAX_PAGES || kindPages >= MAX_PAGES_PER_KIND) hitPageCap = true
            }
        }
        return assemble(rows, seen, hitPageCap)
    }

    private fun fetchPage(settings: DataHubSettings, kind: String, afterKey: String?): List<SyncMerge.Record> {
        val b = settings.supabaseUrl.trimEnd('/').toHttpUrl().newBuilder()
            .addPathSegments("rest/v1/${SupabaseSync.TABLE}")
            .addEncodedQueryParameter("select", "key,kind,updated_at,payload")
            .addEncodedQueryParameter("kind", "eq.$kind")
            .addEncodedQueryParameter("key", "like.${SupabaseSync.KEY_PREFIX}*")
        if (afterKey != null) b.addQueryParameter("key", "gt.$afterKey")
        val url = b
            .addEncodedQueryParameter("order", "key.asc")
            .addEncodedQueryParameter("limit", pageSize.toString())
            .build()
        val req = Request.Builder()
            .url(url)
            .header("apikey", settings.supabaseAnonKey)
            .header("Authorization", "Bearer ${settings.supabaseAnonKey}")
            .header("Accept", "application/json")
            .header("User-Agent", NetworkModule.USER_AGENT)
            .get()
            .build()
        val text = execute(req)
        return SupabaseSync.parseRows(text)
    }

    private fun execute(req: Request): String {
        val resp = try {
            http.newCall(req).execute()
        } catch (e: Exception) {
            throw SyncHttpException(SupabaseSync.describeFailure(0, null, e))
        }
        resp.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                throw SyncHttpException(SupabaseSync.describeFailure(it.code, text, null))
            }
            return text
        }
    }

    private fun assemble(rows: List<SyncMerge.Record>, seen: SeenKeys, hitPageCap: Boolean): Restore {
        val snapshots = ArrayList<ScoredSnapshotRow>()
        val alerts = ArrayList<AlertRow>()
        val tickets = ArrayList<TicketAttemptRow>()
        val settingsChanges = ArrayList<SettingsChange>()
        val paper = ArrayList<PaperFill>()
        val scalps = ArrayList<com.dirk.kalshiodds.decision.ScalpTrade>()
        val shadow = ArrayList<com.dirk.kalshiodds.signal.paper.ShadowTicket>()
        var settingsSnapshot: String? = null
        var settingsSnapshotAt = Long.MIN_VALUE
        for (rec in rows) {
            val o = runCatching { JSONObject(rec.payload) }.getOrNull() ?: continue
            when (rec.kind) {
                "snapshot" -> {
                    val row = SyncRows.snapshot(o, rec.updatedAtMs)
                    if (row.ticker.isBlank()) continue
                    if (seen.snapshots.add(seen.snapshotKey(row.ticker, row.createdAtMs, row.side))) {
                        snapshots.add(row)
                    }
                }
                "alert" -> {
                    val row = SyncRows.alert(o, rec)
                    if (seen.alerts.add(seen.alertKey(row.alertId, row.ticker, row.createdAtMs))) {
                        alerts.add(row)
                    }
                }
                "ticket" -> {
                    val row = SyncRows.ticket(o, rec)
                    val key = row.clientOrderId?.takeIf { it.isNotBlank() }
                        ?: "${row.ticker}|${row.createdAtMs}|${row.side}"
                    if (seen.tickets.add(key)) tickets.add(row)
                }
                "settings" -> SyncRows.settingsChange(o, rec.updatedAtMs)?.let { settingsChanges.add(it) }
                "settings_snapshot" -> {
                    val snap = SyncRows.settingsPayload(o, rec) ?: continue
                    if (rec.updatedAtMs >= settingsSnapshotAt) {
                        settingsSnapshotAt = rec.updatedAtMs
                        settingsSnapshot = snap
                    }
                }
                "paper" -> paper.add(SyncRows.paper(o, rec))
                "scalp" -> SyncRows.scalp(o)?.let { scalps.add(it) }
                "shadow" -> SyncRows.shadow(o)?.let { shadow.add(it) }
            }
        }
        val batch = ImportBatch(
            snapshots = snapshots,
            alerts = alerts,
            tickets = tickets,
            settingsChanges = settingsChanges
        )
        val cap = if (hitPageCap) " Stopped after $MAX_PAGES pages." else ""
        val message = if (rows.isEmpty()) {
            "diphunter_sync has no kashi rows to restore."
        } else {
            "Restored diphunter_sync: ${snapshots.size} snapshots, ${alerts.size} alerts, " +
                "${tickets.size} tickets, ${settingsChanges.size} settings, ${paper.size} paper, " +
                "${scalps.size} scalp round trips, ${shadow.size} shadow tickets.$cap"
        }
        return Restore(
            batch = batch,
            settled = emptyList(),
            message = message,
            paper = paper,
            settingsSnapshot = settingsSnapshot,
            scalps = scalps,
            shadow = shadow
        )
    }

    companion object {
        const val PAGE_SIZE = 1000
        /** 0.3.62: total and per-kind caps keep restore memory bounded (snapshot alone has 200k+ rows; loading
         *  all of them at once ran the phone out of memory once keyset paging made every page fast). */
        const val MAX_PAGES = 40
        const val MAX_PAGES_PER_KIND = 10
        val RESTORE_KINDS = listOf(
            "snapshot",
            "alert",
            "ticket",
            "settings",
            "settings_snapshot",
            "paper",
            "scalp",
            "shadow"
        )

        /**
         * A missing `diphunter_sync` table is reported as itself. The older
         * names are not queried; this app never writes them.
         */
        fun soften(message: String): String {
            val missing = message.contains("table missing", ignoreCase = true) ||
                message.contains("PGRST205", ignoreCase = true)
            if (!missing) return message
            return "diphunter_sync is missing, so nothing was restored. " +
                "This app does not read diphunter_snapshots, diphunter_results, " +
                "diphunter_settled, diphunter_history, or diphunter_settings."
        }
    }
}

/** Visible for tests / restore path. */
internal fun ResultsImporter.parseJsonObjectSafe(raw: String, seen: SeenKeys) =
    parse(java.io.StringReader(raw), seen, raw.trim().takeIf { it.startsWith("{") || it.startsWith("[") })
