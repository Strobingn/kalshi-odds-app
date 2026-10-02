package com.dirk.kalshiodds.arb.data

import com.dirk.kalshiodds.arb.scan.MarketBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Spaces calls at least `1 / perSecond` apart. */
class RateLimiter(perSecond: Double, private val nanoTime: () -> Long = System::nanoTime) {
    private val intervalNanos = (1_000_000_000.0 / perSecond).toLong()
    private val mutex = Mutex()
    private var next = 0L

    suspend fun acquire() {
        mutex.withLock {
            val now = nanoTime()
            val wait = next - now
            if (wait > 0) delay(wait / 1_000_000L + 1)
            next = maxOf(now, next) + intervalNanos
        }
    }
}

/**
 * Unauthenticated GETs against Kalshi's public market-data API. There is
 * deliberately no auth, no key storage, and no order endpoint here.
 */
class KalshiPublicClient(
    private val baseUrl: String = BASE_URL,
    private val limiter: RateLimiter = RateLimiter(REQUESTS_PER_SECOND),
    private val http: OkHttpClient = defaultClient
) {

    suspend fun eventsPage(cursor: String?, limit: Int = 200): KalshiParser.EventsPage {
        val url = "${baseUrl}events".toHttpUrl().newBuilder()
            .addQueryParameter("status", "open")
            .addQueryParameter("with_nested_markets", "true")
            .addQueryParameter("limit", limit.toString())
            .apply { if (!cursor.isNullOrBlank()) addQueryParameter("cursor", cursor) }
            .build()
            .toString()
        return KalshiParser.parseEventsPage(get(url))
    }

    suspend fun orderbook(ticker: String): MarketBook {
        val url = "${baseUrl}markets".toHttpUrl().newBuilder()
            .addPathSegment(ticker)
            .addPathSegment("orderbook")
            .build()
            .toString()
        return KalshiParser.parseOrderbook(ticker, get(url))
    }

    private suspend fun get(url: String): String {
        var attempt = 0
        while (true) {
            limiter.acquire()
            val (code, body) = withContext(Dispatchers.IO) {
                val req = Request.Builder().url(url)
                    .header("Accept", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .get()
                    .build()
                http.newCall(req).execute().use { r -> r.code to (r.body?.string() ?: "") }
            }
            if (code in 200..299) return body
            if ((code == 429 || code >= 500) && attempt < 2) {
                attempt++
                delay(1_000L * attempt)
                continue
            }
            throw IOException("HTTP $code for $url")
        }
    }

    companion object {
        const val BASE_URL = "https://api.elections.kalshi.com/trade-api/v2/"
        const val REQUESTS_PER_SECOND = 8.0
        const val USER_AGENT = "ArbHunter/1.0 (Android; read-only)"

        private val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
        }

        /** Kalshi's web page for a series (opens in the browser; never trades). */
        fun marketPageUrl(seriesTicker: String, eventTicker: String): String {
            val series = seriesTicker.ifBlank { eventTicker.substringBefore('-') }
            return "https://kalshi.com/markets/${series.lowercase()}"
        }
    }
}
