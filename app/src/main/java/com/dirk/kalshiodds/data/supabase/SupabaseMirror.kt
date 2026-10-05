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
        val settingsSnapshot: String? = null
    )

    fun restore(settings: DataHubSettings, seen: SeenKeys): Restore {
        if (!settings.supabaseConfigured) {
            return Restore(ImportBatch(), emptyList(), "Supabase is not configured")
        }
        val rows = ArrayList<SyncMerge.Record>()
        var offset = 0
        var previousFirstKey: String? = null
        var pages = 0
        var hitPageCap = false
        while (pages < MAX_PAGES) {
            val page = try {
                fetchPage(settings, offset)
            } catch (e: SyncHttpException) {
                return Restore(ImportBatch(), emptyList(), soften(e.message ?: "restore failed"))
            }
            val first = page.firstOrNull()?.key
            if (first != null && first == previousFirstKey) {
                return Restore(
                    ImportBatch(),
                    emptyList(),
                    "Restore stopped: diphunter_sync pages did not advance."
                )
            }
            previousFirstKey = first
            rows += page
            pages++
            if (page.size < pageSize) break
            offset += page.size
            if (pages >= MAX_PAGES) hitPageCap = true
        }
        return assemble(rows, seen, hitPageCap)
    }

    private fun fetchPage(settings: DataHubSettings, offset: Int): List<SyncMerge.Record> {
        val url = settings.supabaseUrl.trimEnd('/').toHttpUrl().newBuilder()
            .addPathSegments("rest/v1/${SupabaseSync.TABLE}")
            .addEncodedQueryParameter("select", "key,kind,updated_at,payload")
            .addEncodedQueryParameter("key", "like.${SupabaseSync.KEY_PREFIX}*")
            .addEncodedQueryParameter("kind", "in.(${RESTORE_KINDS.joinToString(",")})")
            .addEncodedQueryParameter("order", "key.asc")
            .addEncodedQueryParameter("limit", pageSize.toString())
            .addEncodedQueryParameter("offset", offset.toString())
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
                "${tickets.size} tickets, ${settingsChanges.size} settings, ${paper.size} paper.$cap"
        }
        return Restore(
            batch = batch,
            settled = emptyList(),
            message = message,
            paper = paper,
            settingsSnapshot = settingsSnapshot
        )
    }

    companion object {
        const val PAGE_SIZE = 1000
        const val MAX_PAGES = 200
        val RESTORE_KINDS = listOf(
            "snapshot",
            "alert",
            "ticket",
            "settings",
            "settings_snapshot",
            "paper"
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
