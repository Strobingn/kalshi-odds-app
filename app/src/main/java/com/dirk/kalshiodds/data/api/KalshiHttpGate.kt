package com.dirk.kalshiodds.data.api

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * One OkHttp gate in front of every Kalshi REST client.
 *
 *  - reads wait on [KalshiTokenBucket]; writes fail fast (no silent retry)
 *  - identical in-flight GETs share one network call
 *  - successful GETs are reused for [KalshiPollBudget.GET_CACHE_MS]
 *  - HTTP 429 honors Retry-After (or exponential backoff with jitter) and
 *    is not retried in a loop. POST/DELETE are never retried.
 */
class KalshiHttpGate(
    private val bucket: KalshiTokenBucket,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val sleep: (Long) -> Unit = { ms ->
        if (ms > 0L) Thread.sleep(ms)
    },
    private val cacheTtlMs: Long = KalshiPollBudget.GET_CACHE_MS
) : Interceptor {

    private val cache = ConcurrentHashMap<String, Snap>()
    private val flights = ConcurrentHashMap<String, Flight>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!isKalshi(request)) return chain.proceed(request)
        return if (isWrite(request)) interceptWrite(chain, request) else interceptRead(chain, request)
    }

    private fun interceptWrite(chain: Interceptor.Chain, request: Request): Response {
        val wait = bucket.tryAcquireWrite()
        if (wait != null) {
            return synthetic429(request, wait)
        }
        val response = chain.proceed(request)
        if (response.code == 429) {
            bucket.noteWrite429(retryAfterMs(response))
        } else if (response.code in 200..299) {
            cache.clear()
        }
        return response
    }

    private fun interceptRead(chain: Interceptor.Chain, request: Request): Response {
        val key = request.url.toString()
        fresh(key)?.let { return it.toResponse(request) }
        val mine = Flight()
        val existing = flights.putIfAbsent(key, mine)
        if (existing != null) {
            if (!existing.latch.await(30, TimeUnit.SECONDS)) {
                throw IOException("Kalshi GET still in flight")
            }
            existing.error?.let { throw it }
            return (existing.snap ?: error("missing inflight response")).toResponse(request)
        }
        try {
            fresh(key)?.let { snap ->
                mine.snap = snap
                return snap.toResponse(request)
            }
            val denied = awaitRead()
            if (denied != null) {
                val snap = Snap.from(synthetic429(request, denied))
                mine.snap = snap
                return snap.toResponse(request)
            }
            val network = chain.proceed(request)
            val snap = Snap.from(network)
            network.close()
            if (snap.code == 429) {
                bucket.noteRead429(snap.retryAfterMs())
            } else if (snap.code in 200..299) {
                cache[key] = snap.copy(untilMs = nowMs() + cacheTtlMs)
            }
            mine.snap = snap
            return snap.toResponse(request)
        } catch (e: IOException) {
            mine.error = e
            throw e
        } finally {
            mine.latch.countDown()
            flights.remove(key, mine)
        }
    }

    /** Null when a read may hit the network. Otherwise Retry-After milliseconds. */
    private fun awaitRead(): Long? {
        var slept = 0L
        while (true) {
            val wait = bucket.reserveRead()
            if (wait == 0L) return null
            if (slept + wait > KalshiPollBudget.MAX_INLINE_WAIT_MS) return wait
            sleep(wait)
            slept += wait
        }
    }

    /** Drop cached GETs so the next read hits Kalshi. Writes call this on success. */
    fun clearReadCache() {
        cache.clear()
    }

    private fun fresh(key: String): Snap? {
        val snap = cache[key] ?: return null
        if (snap.untilMs <= nowMs()) {
            cache.remove(key, snap)
            return null
        }
        return snap
    }

    private class Flight {
        val latch = CountDownLatch(1)
        @Volatile var snap: Snap? = null
        @Volatile var error: IOException? = null
    }

    data class Snap(
        val code: Int,
        val message: String,
        val bytes: ByteArray,
        val contentType: String,
        val headerLines: List<Pair<String, String>>,
        val untilMs: Long
    ) {
        fun retryAfterMs(): Long? = KalshiRequestStatus.parseRetryAfter(
            headerLines.firstOrNull { it.first.equals("Retry-After", true) }?.second
        )

        fun toResponse(request: Request): Response {
            val builder = Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(message.ifBlank { if (code == 200) "OK" else "ERR" })
                .body(bytes.toResponseBody(contentType.toMediaType()))
            headerLines.forEach { (name, value) -> builder.header(name, value) }
            return builder.build()
        }

        companion object {
            fun from(response: Response): Snap {
                val body = response.body
                val type = body?.contentType()?.toString() ?: "application/json"
                val bytes = body?.bytes() ?: ByteArray(0)
                val headers = response.headers.map { it.first to it.second }
                return Snap(
                    code = response.code,
                    message = response.message,
                    bytes = bytes,
                    contentType = type,
                    headerLines = headers,
                    untilMs = 0L
                )
            }
        }
    }

    companion object {
        fun isKalshi(request: Request): Boolean {
            val host = request.url.host.lowercase()
            return host == "kalshi.com" || host.endsWith(".kalshi.com") || host.endsWith(".kalshi.co")
        }

        fun isWrite(request: Request): Boolean {
            val method = request.method.uppercase()
            return method != "GET" && method != "HEAD"
        }

        fun synthetic429(request: Request, retryAfterMs: Long): Response {
            val seconds = ((retryAfterMs.coerceAtLeast(1L) + 999L) / 1_000L).coerceAtLeast(1L)
            val json = """{"error":{"code":"too_many_requests","message":"${KalshiTokenBucket.WRITE_DENIED}"}}"""
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(429)
                .message("Too Many Requests")
                .header("Retry-After", seconds.toString())
                .header("Content-Type", "application/json")
                .body(json.toResponseBody("application/json".toMediaType()))
                .build()
        }

        fun retryAfterMs(response: Response): Long? =
            KalshiRequestStatus.parseRetryAfter(response.header("Retry-After"))
    }
}
