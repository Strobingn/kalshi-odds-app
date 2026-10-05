package com.dirk.kalshiodds.data.supabase

import com.dirk.kalshiodds.data.importing.ImportBatch
import com.dirk.kalshiodds.data.importing.ResultsImporter
import com.dirk.kalshiodds.data.importing.SeenKeys
import com.dirk.kalshiodds.data.local.archive.SettledWindowRow
import com.dirk.kalshiodds.data.prefs.DataHubSettings
import okhttp3.OkHttpClient
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * Optional restore from a user-configured Supabase project.
 * Reads `diphunter_results` (JSON rows) or the split tables
 * `diphunter_snapshots` / `diphunter_settled` if they exist.
 * Publishable/anon key only — never a service_role key.
 */
class SupabaseMirror(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
    private val pageSize: Int = SYNC_PAGE_SIZE
) {
    data class Restore(
        val batch: ImportBatch,
        val settled: List<SettledWindowRow>,
        val message: String
    )

    fun restore(settings: DataHubSettings, seen: SeenKeys): Restore {
        if (!settings.supabaseConfigured) {
            return Restore(ImportBatch(), emptyList(), "Supabase is not configured")
        }
        val errors = ArrayList<String>()
        fun read(table: String): String? = try {
            get(settings, table)
        } catch (t: Throwable) {
            errors += (t.message ?: "error reading $table")
            null
        }
        val snapshots = read("diphunter_snapshots") ?: read("diphunter_results")
        val settledJson = read("diphunter_settled")
        val historyJson = read("diphunter_history") ?: read("diphunter_settings")
        if (snapshots == null && settledJson == null && errors.isEmpty()) {
            return Restore(
                ImportBatch(),
                emptyList(),
                "No diphunter_snapshots / diphunter_results / diphunter_settled tables (or RLS blocked the anon key)."
            )
        }
        if (snapshots == null && settledJson == null) {
            return Restore(
                ImportBatch(),
                emptyList(),
                FAILURE_PREFIX + errors.joinToString("; ")
            )
        }
        val parsed = if (snapshots != null) {
            ResultsImporter.parseJsonObjectSafe(snapshots, seen)
        } else {
            ResultsImporter.parse(java.io.StringReader("[]"), seen)
        }
        val historyBatch = historyJson?.let { ResultsImporter.parseJsonObjectSafe(it, seen).batch }
        val settled = ArrayList<SettledWindowRow>()
        settledJson?.let { raw ->
            val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return@let
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val ticker = o.optString("ticker")
                if (ticker.isBlank()) continue
                val result = o.optString("result").lowercase()
                if (result != "yes" && result != "no") continue
                settled.add(
                    SettledWindowRow(
                        ticker = ticker,
                        series = o.optString("series").ifBlank {
                            com.dirk.kalshiodds.domain.CryptoMarkets.inferSeries(ticker)
                        },
                        result = result,
                        strikeUsd = o.optDouble("strike").takeIf { o.has("strike") },
                        openMs = o.optLong("open_ms").takeIf { o.has("open_ms") },
                        closeMs = o.optLong("close_ms").takeIf { o.has("close_ms") },
                        source = "supabase"
                    )
                )
            }
        }
        val merged = if (historyBatch == null) {
            parsed.batch
        } else {
            parsed.batch.copy(
                settingsChanges = parsed.batch.settingsChanges + historyBatch.settingsChanges,
                sessions = parsed.batch.sessions + historyBatch.sessions
            )
        }
        val summary = "Supabase: ${parsed.summary.message}; ${settled.size} settled windows; " +
            "${merged.settingsChanges.size} settings; ${merged.sessions.size} sessions"
        val message = if (errors.isEmpty()) summary else FAILURE_PREFIX + errors.joinToString("; ") + ". " + summary
        return Restore(
            batch = merged,
            settled = settled,
            message = message
        )
    }

    private fun get(settings: DataHubSettings, table: String): String? =
        SupabaseRest(http, pageSize).downloadObjectArray(
            settings = settings,
            table = table,
            keyPrefix = null,
            orders = listOf("key.asc", "id.asc", "ticker.asc"),
            optional = true
        )

    companion object {
        const val FAILURE_PREFIX = "Supabase restore failed: "
    }
}

/** Visible for tests / restore path. */
internal fun ResultsImporter.parseJsonObjectSafe(raw: String, seen: SeenKeys) =
    parse(java.io.StringReader(raw), seen, raw.trim().takeIf { it.startsWith("{") || it.startsWith("[") })
