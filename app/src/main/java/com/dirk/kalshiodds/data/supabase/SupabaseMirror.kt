package com.dirk.kalshiodds.data.supabase

import com.dirk.kalshiodds.data.api.NetworkModule
import com.dirk.kalshiodds.data.importing.ImportBatch
import com.dirk.kalshiodds.data.importing.ResultsImporter
import com.dirk.kalshiodds.data.importing.SeenKeys
import com.dirk.kalshiodds.data.local.archive.SettledWindowRow
import com.dirk.kalshiodds.data.prefs.DataHubSettings
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
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
        .build()
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
        val base = settings.supabaseUrl.trimEnd('/')
        val key = settings.supabaseAnonKey
        val snapshots = get(base, key, "diphunter_snapshots")
            ?: get(base, key, "diphunter_results")
        val settledJson = get(base, key, "diphunter_settled")
        if (snapshots == null && settledJson == null) {
            return Restore(
                ImportBatch(),
                emptyList(),
                "No diphunter_snapshots / diphunter_results / diphunter_settled tables (or RLS blocked the anon key)."
            )
        }
        val parsed = if (snapshots != null) {
            ResultsImporter.parseJsonObjectSafe(snapshots, seen)
        } else {
            ResultsImporter.parse(java.io.StringReader("[]"), seen)
        }
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
        return Restore(
            batch = parsed.batch,
            settled = settled,
            message = "Supabase: ${parsed.summary.message}; ${settled.size} settled windows"
        )
    }

    private fun get(base: String, key: String, table: String): String? {
        val url = "$base/rest/v1/$table?select=*&limit=1000"
        val req = Request.Builder()
            .url(url)
            .header("apikey", key)
            .header("Authorization", "Bearer $key")
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
}

/** Visible for tests / restore path. */
internal fun ResultsImporter.parseJsonObjectSafe(raw: String, seen: SeenKeys) =
    parse(java.io.StringReader(raw), seen, raw.trim().takeIf { it.startsWith("{") || it.startsWith("[") })
