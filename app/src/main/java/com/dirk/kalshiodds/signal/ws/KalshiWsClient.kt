package com.dirk.kalshiodds.signal.ws

import android.os.SystemClock
import android.util.Log
import com.dirk.kalshiodds.domain.CryptoMarkets
import com.dirk.kalshiodds.signal.model.MarketTick
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Authenticated Kalshi Trade API WebSocket (public ticker / trade / orderbook).
 * Auto-reconnects with exponential backoff and rotates primary ↔ elections host.
 * Analysis / alerts only — never subscribes to fills, portfolio, or order channels.
 */
class KalshiWsClient(
    private val scope: CoroutineScope,
    private val onTick: (MarketTick) -> Unit,
    private val onState: (State) -> Unit,
    private val onLog: (String) -> Unit = {},
    private val onBookSnapshot: (KalshiWsMessages.Parsed.OrderbookSnapshot) -> Unit = {},
    private val onBookDelta: (KalshiWsMessages.Parsed.OrderbookDelta) -> Unit = {},
    private val httpClient: OkHttpClient = defaultClient(),
    private val urls: List<String> = KalshiWsAuth.WS_URLS
) {
    data class State(
        val connected: Boolean,
        val reconnecting: Boolean,
        val host: String?,
        val detail: String?
    )

    private val running = AtomicBoolean(false)
    private val msgId = AtomicInteger(1)
    private var socket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var hostIndex = 0
    private var backoffMs = INITIAL_BACKOFF_MS
    private var keyId: String = ""
    private var pem: String = ""
    @Volatile private var channels: List<String> = listOf("ticker", "orderbook_delta")
    @Volatile private var marketTickers: List<String> = emptyList()
    private val subscribedSids = java.util.concurrent.CopyOnWriteArrayList<Int>()

    fun start(keyId: String, pem: String, channels: List<String>, marketTickers: List<String>) {
        this.keyId = keyId
        this.pem = pem
        this.channels = channels.ifEmpty { listOf("ticker", "orderbook_delta") }.toList()
        this.marketTickers = marketTickers.toList()
        if (running.getAndSet(true)) {
            resubscribe()
            return
        }
        backoffMs = INITIAL_BACKOFF_MS
        connect()
    }

    fun updateSubscriptions(channels: List<String>, marketTickers: List<String>) {
        this.channels = channels.ifEmpty { listOf("ticker", "orderbook_delta") }.toList()
        this.marketTickers = marketTickers.toList()
        if (running.get() && socket != null) {
            resubscribe()
        }
    }

    fun stop() {
        running.set(false)
        reconnectJob?.cancel()
        reconnectJob = null
        socket?.close(1000, "client stop")
        socket = null
        runCatching { onState(State(connected = false, reconnecting = false, host = null, detail = "stopped")) }
    }

    private fun connect() {
        if (!running.get()) return
        val host = urls[hostIndex % urls.size]
        onState(State(connected = false, reconnecting = hostIndex > 0 || backoffMs > INITIAL_BACKOFF_MS, host = host, detail = "connecting"))
        val headers = try {
            KalshiWsAuth.handshakeHeaders(keyId, pem)
        } catch (e: Exception) {
            onLog("auth failed: ${e.message}")
            onState(State(connected = false, reconnecting = false, host = host, detail = "auth failed"))
            scheduleReconnect()
            return
        }
        val builder = Request.Builder()
            .url(host)
            .header("User-Agent", "DipHunter/0.2.4 (Android; signals)")
            .header("Accept", "application/json")
        headers.asMap().forEach { (k, v) -> builder.header(k, v) }
        socket?.cancel()
        socket = httpClient.newWebSocket(builder.build(), listener)
    }

    private fun resubscribe() {
        val ws = socket ?: return
        val id = msgId.getAndIncrement()
        val payload = KalshiWsMessages.subscribe(id, channels, marketTickers.takeIf { it.isNotEmpty() })
        ws.send(payload)
        onLog("subscribe id=$id channels=$channels tickers=${marketTickers.size}")
    }

    private fun scheduleReconnect() {
        if (!running.get()) return
        reconnectJob?.cancel()
        val delayMs = backoffMs
        backoffMs = min(backoffMs * 2, MAX_BACKOFF_MS)
        hostIndex += 1
        onState(
            State(
                connected = false,
                reconnecting = true,
                host = urls[hostIndex % urls.size],
                detail = "retry in ${delayMs}ms"
            )
        )
        reconnectJob = scope.launch {
            delay(delayMs)
            if (isActive && running.get()) connect()
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            runCatching {
                backoffMs = INITIAL_BACKOFF_MS
                val host = webSocket.request().url.toString()
                onLog("ws open $host")
                onState(State(connected = true, reconnecting = false, host = host, detail = null))
                resubscribe()
            }.onFailure { t ->
                onLog("onOpen failed: ${t.message}")
                scheduleReconnect()
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val recv = runCatching { SystemClock.elapsedRealtimeNanos() }.getOrElse { System.nanoTime() }
            val parsed = runCatching { KalshiWsMessages.parse(text, recv) }.getOrNull() ?: return
            when (parsed) {
                is KalshiWsMessages.Parsed.Ticker -> {
                    if (!CryptoMarkets.isCryptoTicker(parsed.tick.ticker)) return
                    Log.d(TAG, "tick ticker=${parsed.tick.ticker} mid=${parsed.tick.midPp} recvNanos=$recv src=ticker")
                    runCatching { onTick(parsed.tick) }
                }
                is KalshiWsMessages.Parsed.Trade -> {
                    if (!CryptoMarkets.isCryptoTicker(parsed.tick.ticker)) return
                    Log.d(TAG, "tick ticker=${parsed.tick.ticker} mid=${parsed.tick.midPp} recvNanos=$recv src=trade size=${parsed.tick.tradeSize}")
                    runCatching { onTick(parsed.tick) }
                }
                is KalshiWsMessages.Parsed.OrderbookSnapshot -> {
                    if (!CryptoMarkets.isCryptoTicker(parsed.ticker)) return
                    Log.d(TAG, "book snapshot ticker=${parsed.ticker} yes=${parsed.yesLevels.size} no=${parsed.noLevels.size} seq=${parsed.seq}")
                    runCatching { onBookSnapshot(parsed) }
                }
                is KalshiWsMessages.Parsed.OrderbookDelta -> {
                    if (!CryptoMarkets.isCryptoTicker(parsed.ticker)) return
                    Log.d(TAG, "book delta ticker=${parsed.ticker} side=${parsed.side} px=${parsed.price} d=${parsed.delta} seq=${parsed.seq}")
                    runCatching { onBookDelta(parsed) }
                }
                is KalshiWsMessages.Parsed.Subscribed -> {
                    parsed.sid?.let { subscribedSids += it }
                    onLog("subscribed sid=${parsed.sid}")
                }
                is KalshiWsMessages.Parsed.Error -> {
                    onLog("ws error ${parsed.code}: ${parsed.message}")
                    if (parsed.code == 9) {
                        onState(State(connected = false, reconnecting = false, host = null, detail = "auth required"))
                    }
                }
                is KalshiWsMessages.Parsed.Other -> {
                    // ignore keepalive / unknown
                }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            onLog("ws closed $code $reason")
            if (running.get()) scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            onLog("ws failure: ${t.message}")
            if (running.get()) scheduleReconnect()
        }
    }

    companion object {
        private const val TAG = "DipHunterTick"
        const val INITIAL_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L

        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .pingInterval(20, TimeUnit.SECONDS)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build()
    }
}
