package com.dirk.kalshiodds.signal.external

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Streaming spot from the **public** Coinbase Exchange WebSocket `ticker`
 * channel (BTC-USD / ETH-USD / SOL-USD; no auth, market data only).
 *
 * Spot vs strike decides these contracts, and Kalshi quotes take a few
 * seconds to reprice after spot moves. The REST snapshot is cached for 25 s,
 * so scoring overlays this stream's last trade and exact 60 s / 300 s returns
 * ([overlay]) on every Kalshi tick instead.
 *
 * Self-managing: [ensureRunning] (called by scoring and the REST cache)
 * connects on demand; the socket closes after [IDLE_MS] without demand and
 * reconnects with backoff after failures.
 */
class SpotStream(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build(),
    private val url: String = URL,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val rings = mapOf("BTC" to Ring(), "ETH" to Ring(), "SOL" to Ring())

    @Volatile private var socket: WebSocket? = null
    @Volatile private var lastDemandMs = 0L
    @Volatile private var nextConnectMs = 0L
    @Volatile private var backoffMs = INITIAL_BACKOFF_MS

    val connected: Boolean get() = socket != null

    @Synchronized
    fun ensureRunning(nowMs: Long = clock()) {
        lastDemandMs = nowMs
        if (socket != null || nowMs < nextConnectMs) return
        val req = Request.Builder().url(url).build()
        socket = runCatching { http.newWebSocket(req, Listener()) }.getOrNull()
        if (socket == null) scheduleRetry(nowMs)
    }

    @Synchronized
    fun stop() {
        socket?.close(1000, "idle")
        socket = null
    }

    /**
     * Live price and exact-horizon returns over [base] (the REST snapshot).
     * Leaves [base] alone when the stream has nothing fresh for that asset.
     */
    fun overlay(base: AssetSpotFeatures, nowMs: Long = clock()): AssetSpotFeatures {
        ensureRunning(nowMs)
        val ring = rings[base.asset.uppercase()] ?: return base
        val last = ring.latest() ?: return base
        if (nowMs - last.tMs > STALE_MS) return base
        val r1 = ring.priceAt(nowMs - 60_000L)?.let { last.px / it - 1.0 } ?: base.spotReturn1m
        val r5 = ring.priceAt(nowMs - 300_000L)?.let { last.px / it - 1.0 } ?: base.spotReturn5m
        return base.copy(
            lastPrice = last.px,
            spotReturn1m = r1,
            spotReturn5m = r5,
            source = "coinbase-ws",
            fetchedAtMs = last.tMs
        )
    }

    /**
     * Spot return over the last [horizonMs] from the live ring alone
     * (fraction, e.g. 0.0006 = 6bp). Null when the ring doesn't reach back
     * that far — callers fall back to coarser features. Used by the
     * scalper's SPOT_LEAD / OPEN_DRIVE strategies.
     */
    fun returnOver(asset: String, horizonMs: Long, nowMs: Long = clock()): Double? {
        val ring = rings[asset.uppercase()] ?: return null
        val last = ring.latest() ?: return null
        if (nowMs - last.tMs > STALE_MS) return null
        val base = ring.priceAt(nowMs - horizonMs) ?: return null
        if (base <= 0.0) return null
        return last.px / base - 1.0
    }

    /** Parse one WebSocket frame. Public for tests. */
    fun ingest(text: String, nowMs: Long = clock()) {
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return
        if (o.optString("type") != "ticker") return
        val asset = PRODUCT_TO_ASSET[o.optString("product_id")] ?: return
        val px = o.optString("price").toDoubleOrNull()?.takeIf { it > 0.0 && it.isFinite() } ?: return
        rings[asset]?.add(nowMs, px)
        if (nowMs - lastDemandMs > IDLE_MS) stop()
    }

    private fun scheduleRetry(nowMs: Long) {
        nextConnectMs = nowMs + backoffMs
        backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            backoffMs = INITIAL_BACKOFF_MS
            val sub = JSONObject()
                .put("type", "subscribe")
                .put("product_ids", JSONArray(PRODUCT_TO_ASSET.keys.toList()))
                .put("channels", JSONArray(listOf("ticker")))
            webSocket.send(sub.toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) = ingest(text)

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped(webSocket, retry = false)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = dropped(webSocket, retry = true)

        private fun dropped(webSocket: WebSocket, retry: Boolean) {
            synchronized(this@SpotStream) {
                if (socket === webSocket) socket = null
                if (retry) scheduleRetry(clock())
            }
        }
    }

    /** ≤ 1 sample per second per asset, [WINDOW_MS] deep. */
    class Ring {
        data class Sample(val tMs: Long, val px: Double)

        private val q = ArrayDeque<Sample>()

        @Synchronized
        fun add(tMs: Long, px: Double) {
            val last = q.lastOrNull()
            if (last != null && tMs / 1000L == last.tMs / 1000L) q.removeLast()
            if (last == null || tMs >= last.tMs) q.addLast(Sample(tMs, px))
            while (q.isNotEmpty() && tMs - q.first().tMs > WINDOW_MS) q.removeFirst()
        }

        @Synchronized
        fun latest(): Sample? = q.lastOrNull()

        /** Last trade at or before [tMs]; null when the ring does not reach back that far. */
        @Synchronized
        fun priceAt(tMs: Long): Double? {
            if (q.isEmpty() || q.first().tMs > tMs) return null
            return q.lastOrNull { it.tMs <= tMs }?.px
        }
    }

    companion object {
        const val URL = "wss://ws-feed.exchange.coinbase.com"
        const val STALE_MS = 10_000L
        const val IDLE_MS = 120_000L
        const val WINDOW_MS = 20 * 60_000L
        const val INITIAL_BACKOFF_MS = 2_000L
        const val MAX_BACKOFF_MS = 60_000L
        val PRODUCT_TO_ASSET = mapOf("BTC-USD" to "BTC", "ETH-USD" to "ETH", "SOL-USD" to "SOL")
    }
}
