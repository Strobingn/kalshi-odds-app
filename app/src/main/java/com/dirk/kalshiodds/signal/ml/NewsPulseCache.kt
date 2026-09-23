package com.dirk.kalshiodds.signal.ml

import com.dirk.kalshiodds.signal.config.SignalConstants
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Fail-soft public headline fetch + cache. Any network error leaves the
 * last snapshot (or empty). Never throws into scoring.
 */
class NewsPulseCache(
    private val ttlMs: Long = 15 * 60_000L,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(SignalConstants.EXTERNAL_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(SignalConstants.EXTERNAL_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(6_000L, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .build()
) {
    @Volatile
    private var snap: NewsPulseSnapshot = NewsPulseSnapshot()

    @Volatile
    private var lastAttemptMs: Long = 0L

    fun latest(): NewsPulseSnapshot = snap

    @Synchronized
    fun refreshIfStale(nowMs: Long = System.currentTimeMillis()): NewsPulseSnapshot {
        if (nowMs - lastAttemptMs < ttlMs && snap.fetchedAtMs > 0L) return snap
        lastAttemptMs = nowMs
        val headlines = runCatching { fetchHeadlines() }.getOrElse { emptyList() }
        if (headlines.isNotEmpty()) {
            snap = NewsPulse.scoreHeadlines(headlines, nowMs, source = "rss")
        } else if (snap.fetchedAtMs == 0L) {
            snap = NewsPulseSnapshot(fetchedAtMs = nowMs, source = "empty")
        }
        return snap
    }

    /** Test hook — inject headlines without HTTP. */
    @Synchronized
    fun putHeadlines(headlines: List<String>, nowMs: Long = System.currentTimeMillis()) {
        snap = NewsPulse.scoreHeadlines(headlines, nowMs, source = "inject")
        lastAttemptMs = nowMs
    }

    internal fun fetchHeadlines(): List<String> {
        val url = "https://www.coindesk.com/arc/outboundfeeds/rss/"
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/rss+xml, application/xml, text/xml")
            .header("User-Agent", "DipHunter/0.3.0 (Android; news-prior)")
            .build()
        val body = http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            resp.body?.string().orEmpty()
        }
        return parseTitles(body).take(24)
    }

    companion object {
        fun parseTitles(xml: String): List<String> {
            if (xml.isBlank()) return emptyList()
            val titles = Regex("<title>(?:<!\\[CDATA\\[)?(.*?)(?:]]>)?</title>", RegexOption.IGNORE_CASE)
                .findAll(xml)
                .map { it.groupValues[1].trim() }
                .filter { it.isNotBlank() && !it.equals("CoinDesk", true) }
                .toList()
            return titles.drop(1) // channel title
        }
    }
}
