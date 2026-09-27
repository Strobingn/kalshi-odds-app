package com.dirk.kalshiodds.signal.external

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Coinbase Exchange public WebSocket (`ticker` + `heartbeat`) for the coins
 * the app scores: BTC-USD today (the live universe is Bitcoin-only, see
 * [com.dirk.kalshiodds.domain.CryptoMarkets.DEFAULT_SERIES]); ETH / SOL-USD
 * follow automatically if those series return. Market data only: no keys,
 * never places exchange orders.
 *
 * Spot vs strike decides these contracts and Kalshi quotes take a few seconds
 * to reprice after spot moves, so every print goes into [book] (scoring reads
 * it through [SpotMerge]) and to [onPrint] (the hub re-scores that coin).
 *
 * Runs while it is [setEnabled] (Settings → "Stream spot") **and** at least
 * one owner holds it ([OWNER_UI] while the app is visible, [OWNER_LIVE] while
 * Live signals runs). Reconnects with exponential backoff; a silent socket is
 * dropped by a watchdog. Fail-soft: nothing here throws, and while it is down
 * or stale the REST poller ([ExternalMarketCache]) keeps supplying spot.
 */
class CoinbaseSpotStream(
    private val book: SpotStreamBook,
    private val onPrint: (asset: String, price: Double, receiveElapsedNanos: Long) -> Unit = { _, _, _ -> },
    private val onLog: (String) -> Unit = { msg -> runCatching { Log.d(TAG, msg) } },
    private val scope: CoroutineScope = defaultScope(),
    private val httpClient: OkHttpClient = defaultClient(),
    private val url: String = WS_URL,
    private val productIds: List<String> =
        CoinbaseTickerMessages.productsFor(com.dirk.kalshiodds.domain.CryptoMarkets.DEFAULT_SERIES)
) {
    private val lock = Any()
    private val owners = HashSet<String>()
    private var enabled = false
    private var running = false
    @Volatile private var socket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var watchdogJob: Job? = null
    @Volatile private var backoffMs = INITIAL_BACKOFF_MS
    @Volatile private var connected = false
    @Volatile private var lastMessageMs = 0L
    @Volatile private var lastLagLogMs = 0L
    @Volatile private var subscribed = false

    /** Set if the feed rejects `heartbeat`; later connects ask for `ticker` alone. */
    @Volatile private var tickerOnly = false

    val isRunning: Boolean get() = synchronized(lock) { running }
    val isConnected: Boolean get() = connected

    fun setEnabled(on: Boolean) = synchronized(lock) {
        enabled = on
        reconcileLocked()
    }

    fun acquire(owner: String) = synchronized(lock) {
        owners.add(owner)
        reconcileLocked()
    }

    fun release(owner: String) = synchronized(lock) {
        owners.remove(owner)
        reconcileLocked()
    }

    private fun reconcileLocked() {
        val want = enabled && owners.isNotEmpty()
        if (want == running) return
        running = want
        if (want) {
            backoffMs = INITIAL_BACKOFF_MS
            connectLocked()
            startWatchdogLocked()
        } else {
            reconnectJob?.cancel()
            reconnectJob = null
            watchdogJob?.cancel()
            watchdogJob = null
            val ws = socket
            socket = null
            connected = false
            runCatching { ws?.close(1000, "client stop") }
            onLog("spot ws stopped")
        }
    }

    private fun connectLocked() {
        if (!running) return
        socket?.cancel()
        socket = null
        // The watchdog measures silence from here, so a socket that never
        // opens or never gets a message is dropped too.
        lastMessageMs = System.currentTimeMillis()
        socket = try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build()
            httpClient.newWebSocket(req, listener)
        } catch (t: Throwable) {
            onLog("spot ws connect failed: ${t.message}")
            null
        }
        if (socket == null) scheduleReconnectLocked()
    }

    private fun scheduleReconnectLocked() {
        if (!running) return
        reconnectJob?.cancel()
        val waitMs = backoffMs
        backoffMs = min(backoffMs * 2, MAX_BACKOFF_MS)
        onLog("spot ws retry in ${waitMs}ms")
        reconnectJob = scope.launch {
            delay(waitMs)
            synchronized(lock) {
                if (running && socket == null) connectLocked()
            }
        }
    }

    private fun startWatchdogLocked() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                val silent = synchronized(lock) {
                    val ws = socket
                    val quietMs = System.currentTimeMillis() - lastMessageMs
                    if (running && ws != null && quietMs > SILENT_RECONNECT_MS) ws else null
                }
                if (silent != null) {
                    onLog("spot ws silent > ${SILENT_RECONNECT_MS}ms, reconnecting")
                    // onFailure follows and schedules the reconnect.
                    runCatching { silent.cancel() }
                }
            }
        }
    }

    private fun handleText(webSocket: WebSocket, text: String) {
        if (webSocket !== socket) return
        val recvMs = System.currentTimeMillis()
        lastMessageMs = recvMs
        when (val parsed = CoinbaseTickerMessages.parse(text) ?: return) {
            is CoinbaseTickerMessages.Parsed.Tick -> {
                val recvNanos = runCatching { SystemClock.elapsedRealtimeNanos() }.getOrElse { System.nanoTime() }
                if (backoffMs != INITIAL_BACKOFF_MS) {
                    synchronized(lock) { backoffMs = INITIAL_BACKOFF_MS }
                }
                if (book.onPrint(parsed.asset, parsed.price, recvMs)) {
                    runCatching { onPrint(parsed.asset, parsed.price, recvNanos) }
                }
                maybeLogLag(parsed, recvMs)
            }
            is CoinbaseTickerMessages.Parsed.Heartbeat -> book.touch(parsed.asset, recvMs)
            is CoinbaseTickerMessages.Parsed.Subscribed -> {
                subscribed = true
                onLog("spot ws subscribed ${parsed.channels}")
            }
            is CoinbaseTickerMessages.Parsed.Error -> {
                onLog("spot ws error: ${parsed.message}")
                // A rejected subscribe must not leave the socket silent:
                // retry once with the ticker channel alone.
                if (!subscribed && !tickerOnly) {
                    tickerOnly = true
                    webSocket.send(CoinbaseTickerMessages.subscribe(productIds, listOf("ticker")))
                }
            }
            is CoinbaseTickerMessages.Parsed.Other -> Unit
        }
    }

    private fun maybeLogLag(tick: CoinbaseTickerMessages.Parsed.Tick, recvMs: Long) {
        if (recvMs - lastLagLogMs < LAG_LOG_INTERVAL_MS) return
        lastLagLogMs = recvMs
        val lag = CoinbaseTickerMessages.parseTimeMs(tick.time)?.let { recvMs - it }
        onLog("spot ${tick.productId} ${tick.price} lag=${lag ?: "?"}ms")
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            runCatching {
                if (webSocket !== socket) return
                connected = true
                subscribed = false
                lastMessageMs = System.currentTimeMillis()
                book.markBreak()
                val channels = if (tickerOnly) listOf("ticker") else CoinbaseTickerMessages.CHANNELS
                webSocket.send(CoinbaseTickerMessages.subscribe(productIds, channels))
                onLog("spot ws open ${productIds.joinToString()} $channels")
            }.onFailure { t -> onLog("spot ws onOpen failed: ${t.message}") }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            runCatching { handleText(webSocket, text) }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            runCatching { webSocket.close(code, reason) }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            onDropped(webSocket, "closed $code $reason")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            onDropped(webSocket, "failure: ${t.message}")
        }
    }

    private fun onDropped(webSocket: WebSocket, why: String) {
        runCatching {
            synchronized(lock) {
                if (webSocket !== socket) return
                socket = null
                connected = false
                onLog("spot ws $why")
                scheduleReconnectLocked()
            }
        }
    }

    companion object {
        private const val TAG = "DipHunterSpot"
        const val WS_URL = "wss://ws-feed.exchange.coinbase.com"
        const val OWNER_UI = "ui"
        const val OWNER_LIVE = "live-signals"
        const val INITIAL_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L
        const val WATCHDOG_INTERVAL_MS = 10_000L

        /** Heartbeats arrive every second per product; 20 s of nothing = dead. */
        const val SILENT_RECONNECT_MS = 20_000L
        const val LAG_LOG_INTERVAL_MS = 60_000L
        private const val USER_AGENT = "DipHunter/1.0 (Android; market-data)"

        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .pingInterval(20, TimeUnit.SECONDS)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build()

        fun defaultScope(): CoroutineScope = CoroutineScope(
            SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, t ->
                runCatching { Log.w(TAG, "spot stream: ${t.message}") }
            }
        )
    }
}
