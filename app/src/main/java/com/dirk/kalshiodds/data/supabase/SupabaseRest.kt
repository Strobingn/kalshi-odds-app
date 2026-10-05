package com.dirk.kalshiodds.data.supabase

import com.dirk.kalshiodds.BuildConfig
import com.dirk.kalshiodds.data.prefs.DataHubSettings
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

internal class SupabaseHttpException(message: String) : Exception(message)

/** User-Agent for diphunter REST calls. Server logs keep this header. */
internal fun syncUserAgent(): String =
    "DipHunter/${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}; Android)"

/**
 * Paged PostgREST reads and upserts. A full page is not treated as the end of
 * the table. HTTP errors throw [SupabaseHttpException] instead of an empty list.
 */
internal class SupabaseRest(
    private val http: OkHttpClient,
    private val pageSize: Int = SYNC_PAGE_SIZE
) {
    fun downloadObjectArray(
        settings: DataHubSettings,
        table: String,
        keyPrefix: String?,
        orders: List<String>,
        optional: Boolean
    ): String? {
        val base = settings.supabaseUrl.trimEnd('/')
        val apiKey = settings.supabaseAnonKey
        var chosenOrder: String? = null
        var first: JSONArray? = null
        var lastColumnError: String? = null
        for (order in orders) {
            when (val page = getPage(base, apiKey, table, keyPrefix, order, 0)) {
                is Page.Missing -> {
                    if (optional) return null
                    throw SupabaseHttpException(page.detail)
                }
                is Page.UndefinedColumn -> lastColumnError = page.detail
                is Page.Ok -> {
                    if (!page.body.trim().startsWith("[")) {
                        if (optional) return page.body
                        throw SupabaseHttpException("HTTP ${page.code} expected a JSON array from $table")
                    }
                    chosenOrder = order
                    first = JSONArray(page.body)
                    break
                }
            }
        }
        val order = chosenOrder ?: throw SupabaseHttpException(
            lastColumnError ?: "no order column for $table"
        )
        val firstPage = first ?: throw SupabaseHttpException("empty page from $table")
        val all = JSONArray()
        accumulate(firstPage, all, keyPrefix)
        if (firstPage.length() < pageSize) return all.toString()
        var offset = firstPage.length()
        var previous = marker(firstPage)
        var pages = 1
        while (true) {
            if (pages >= MAX_PAGES) {
                throw SupabaseHttpException("stopped after $MAX_PAGES pages on $table")
            }
            when (val page = getPage(base, apiKey, table, keyPrefix, order, offset)) {
                is Page.Missing -> throw SupabaseHttpException(page.detail)
                is Page.UndefinedColumn -> throw SupabaseHttpException(page.detail)
                is Page.Ok -> {
                    val batch = JSONArray(page.body)
                    if (batch.length() == 0) break
                    val mark = marker(batch)
                    if (mark == previous) {
                        throw SupabaseHttpException(
                            "paged response repeated at offset $offset on $table"
                        )
                    }
                    previous = mark
                    accumulate(batch, all, keyPrefix)
                    pages++
                    if (batch.length() < pageSize) break
                    offset += batch.length()
                }
            }
        }
        return all.toString()
    }

    fun upsert(settings: DataHubSettings, table: String, jsonArray: String) {
        val url = "${settings.supabaseUrl.trimEnd('/')}/rest/v1/$table".toHttpUrl()
        val req = Request.Builder()
            .url(url)
            .header("apikey", settings.supabaseAnonKey)
            .header("Authorization", "Bearer ${settings.supabaseAnonKey}")
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("Prefer", "resolution=merge-duplicates,return=minimal")
            .header("User-Agent", syncUserAgent())
            .post(jsonArray.toRequestBody(JSON))
            .build()
        when (val page = execute(req)) {
            is Page.Ok -> Unit
            is Page.Missing -> throw SupabaseHttpException(page.detail)
            is Page.UndefinedColumn -> throw SupabaseHttpException(page.detail)
        }
    }

    private fun getPage(
        base: String,
        apiKey: String,
        table: String,
        keyPrefix: String?,
        order: String,
        offset: Int
    ): Page {
        val url = "$base/rest/v1/$table".toHttpUrl().newBuilder()
            .addQueryParameter("select", "*")
            .addQueryParameter("order", order)
            .addQueryParameter("limit", pageSize.toString())
            .addQueryParameter("offset", offset.toString())
            .apply {
                if (!keyPrefix.isNullOrBlank()) {
                    addQueryParameter("key", "like.$keyPrefix*")
                }
            }
            .build()
        val req = Request.Builder()
            .url(url)
            .header("apikey", apiKey)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .header("User-Agent", syncUserAgent())
            .get()
            .build()
        return execute(req)
    }

    private fun execute(req: Request): Page {
        val resp = try {
            http.newCall(req).execute()
        } catch (e: Exception) {
            throw SupabaseHttpException(e.message ?: "network error")
        }
        resp.use {
            val text = it.body?.string().orEmpty()
            if (it.isSuccessful) return Page.Ok(it.code, text)
            val detail = httpDetail(it.code, text)
            if (isMissingTable(it.code, text)) return Page.Missing(detail)
            if (isUndefinedColumn(it.code, text)) return Page.UndefinedColumn(detail)
            throw SupabaseHttpException(detail)
        }
    }

    private sealed interface Page {
        data class Ok(val code: Int, val body: String) : Page
        data class Missing(val detail: String) : Page
        data class UndefinedColumn(val detail: String) : Page
    }

    companion object {
        private const val MAX_PAGES = 500
        private val JSON = "application/json; charset=utf-8".toMediaType()

        private fun accumulate(page: JSONArray, into: JSONArray, keyPrefix: String?) {
            for (i in 0 until page.length()) {
                val row = page.optJSONObject(i) ?: continue
                if (keyPrefix != null && !row.optString("key").startsWith(keyPrefix)) continue
                into.put(row)
            }
        }

        private fun marker(page: JSONArray): String {
            val first = page.optJSONObject(0)?.toString().orEmpty()
            val last = page.optJSONObject(page.length() - 1)?.toString().orEmpty()
            return "${page.length()}|$first|$last"
        }

        private fun isMissingTable(code: Int, body: String): Boolean {
            if (code == 404) return true
            val lower = body.lowercase()
            return lower.contains("pgrst205")
        }

        private fun isUndefinedColumn(code: Int, body: String): Boolean {
            if (code != 400) return false
            val lower = body.lowercase()
            return lower.contains("42703") ||
                lower.contains("pgrst204") ||
                (lower.contains("column") && lower.contains("does not exist"))
        }

        private fun httpDetail(code: Int, body: String): String {
            val parsed = runCatching { JSONObject(body) }.getOrNull()
            val message = parsed?.optString("message").orEmpty().ifBlank {
                parsed?.optString("error").orEmpty()
            }.ifBlank {
                parsed?.optString("hint").orEmpty()
            }.ifBlank { body }
            return "HTTP $code ${message.replace(Regex("\\s+"), " ").trim()}".take(240)
        }
    }
}
