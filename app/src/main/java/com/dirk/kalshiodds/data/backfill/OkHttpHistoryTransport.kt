package com.dirk.kalshiodds.data.backfill

import com.dirk.kalshiodds.data.api.KalshiApi
import com.dirk.kalshiodds.data.api.NetworkModule
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class OkHttpHistoryTransport(
    private val base: String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) : HistoryTransport {
    override fun get(path: String, query: Map<String, String>): String? {
        val root = if (base.endsWith("/")) base.dropLast(1) else base
        val p = if (path.startsWith("/")) path else "/$path"
        val url = (root + p).toHttpUrl().newBuilder().apply {
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", NetworkModule.USER_AGENT)
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            return when (resp.code) {
                200, 201 -> body
                404 -> null
                429 -> throw HistoryTransport.RateLimited(
                    retryAfterMs = (resp.header("Retry-After")?.toLongOrNull() ?: 2L) * 1000L
                )
                else -> throw HistoryTransport.HttpFail(resp.code, body.take(240).ifBlank { resp.message })
            }
        }
    }

    companion object {
        fun kalshi(): OkHttpHistoryTransport =
            OkHttpHistoryTransport(KalshiApi.BASE_URL.trimEnd('/'))

        fun coinbase(): OkHttpHistoryTransport =
            OkHttpHistoryTransport("https://api.exchange.coinbase.com")
    }
}
