package com.dirk.kalshiodds.release

import com.dirk.kalshiodds.data.importing.SeenKeys
import com.dirk.kalshiodds.data.prefs.DataHubSettings
import com.dirk.kalshiodds.data.supabase.SupabaseMirror
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseGate0362Test {
    private val hub = DataHubSettings(supabaseUrl = "https://example.supabase.co", supabaseAnonKey = "a".repeat(32), syncEnabled = true)

    private fun mirror(body: (kind: String, after: String) -> String, calls: MutableList<String>) = SupabaseMirror(
        OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val url = chain.request().url
            val kind = url.queryParameter("kind")!!.removePrefix("eq.")
            val after = url.queryParameterValues("key").firstOrNull { it?.startsWith("gt.") == true }?.removePrefix("gt.") ?: ""
            calls += "$kind|$after"
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("ok")
                .body(body(kind, after).toResponseBody("application/json".toMediaType())).build()
        }).build(), pageSize = 2)

    private fun row(kind: String, key: String) = """{"key":"$key","kind":"$kind","updated_at":1,"payload":{}}"""

    @Test
    fun endlessAdvancingTableStillTerminatesWithinPageCaps() {
        val calls = mutableListOf<String>()
        var n = 0
        val r = mirror({ kind, _ -> n += 2; "[${row(kind, "kashi:$kind:%09d".format(n))},${row(kind, "kashi:$kind:%09d".format(n + 1))}]" }, calls)
            .restore(hub, SeenKeys())
        assertTrue(calls.size <= SupabaseMirror.MAX_PAGES)
        assertTrue(calls.groupBy { it.substringBefore('|') }.values.all { it.size <= SupabaseMirror.MAX_PAGES_PER_KIND })
        assertTrue(r.message.isNotEmpty())
    }

    @Test
    fun cursorThatDoesNotAdvanceStopsImmediately() {
        val calls = mutableListOf<String>()
        val r = mirror({ kind, _ -> "[${row(kind, "kashi:$kind:a")},${row(kind, "kashi:$kind:b")}]" }, calls).restore(hub, SeenKeys())
        assertTrue(calls.size <= 2)
        assertTrue(r.message.contains("did not advance"))
    }
}
