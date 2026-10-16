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
    private val onLifecycle: (ticker: String, eventType: String) -> Unit = { _, _ -> },
    private val onCf: (CfBenchmarks.Tick) -> Unit = {},
    /** 0.3.43: a real orderbook seq gap on a sid — books for these tickers are invalid until re-snapshot. */
    private val onBookGap: (List<String>) -> Unit = {},
    private val jitter: () -> Long = { (0L..200L).random() },
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
    private var reconnectAttempt = 0
    private var keyId: String = ""
    private var pem: String = ""
    @Volatile private var channels: List<String> = listOf("ticker", "orderbook_delta")
    @Volatile private var marketTickers: List<String> = emptyList()
    private val subscribedSids = java.util.concurrent.CopyOnWriteArrayList<Int>()
    private var cfCommandId: Int = -1
    private var cfSid: Int? = null
    private val bookSeq = OrderbookSequencer()
    private val resnapshotGate = com.dirk.kalshiodds.data.api.ResnapshotGate()

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

    fun currentTickers(): List<String> = marketTickers

    fun updateSubscriptions(channels: List<String>, marketTickers: List<String>) {
        val nextChannels = channels.ifEmpty { listOf("ticker", "orderbook_delta") }.toList()
        val nextTickers = marketTickers.toList()
        val previousTickers = this.marketTickers
        this.channels = nextChannels
        this.marketTickers = nextTickers
        if (!running.get() || socket == null) return
        val cmds = WsSubscriptionSwitch.replace(
            idStart = msgId.get(),
            channels = nextChannels,
            previousTickers = previousTickers,
            nextTickers = nextTickers,
            sids = subscribedSids.toList()
        )
        if (cmds.isEmpty()) return
        msgId.addAndGet(cmds.size)
        val ws = socket ?: return
        for (cmd in cmds) {
            ws.send(cmd.json)
            onLog("${cmd.cmd} tickers=${cmd.marketTickers.size} sids=${cmd.sids}")
            if (cmd.cmd == "unsubscribe") subscribedSids.clear()
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
        val cfId = msgId.getAndIncrement()
        cfCommandId = cfId
        ws.send(CfBenchmarks.subscribeJson(cfId))
        onLog("subscribe id=$cfId channel=${CfBenchmarks.CHANNEL} indexes=${CfBenchmarks.INDEX_IDS}")
    }

    /** 0.3.43: invalidate every book on the sid and ask Kalshi for fresh snapshots (no reconnect needed). */
    private fun onSeqGap(ws: WebSocket, sid: Int?, ticker: String, seq: Int?) {
        // 0.3.44: resnapshot only the gapped market, at most once per 30 s per market (dedupe + cooldown).
        val tickers = resnapshotGate.allow(listOf(ticker), System.currentTimeMillis())
        if (tickers.isEmpty()) {
            onLog("orderbook seq gap sid=$sid on $ticker — resnapshot cooldown, skipped")
            return
        }
        com.dirk.kalshiodds.signal.debug.PriceDebugLog.record("WS_BOOK", ticker, "seq", null,
            com.dirk.kalshiodds.signal.debug.PriceDebugLog.GAP, sid = sid, seq = seq)
        onLog("orderbook seq gap sid=$sid seq=$seq → get_snapshot ${tickers.size} tickers")
        runCatching { onBookGap(tickers) }
        if (sid == null) { resubscribe(); return }
        val id = msgId.getAndIncrement()
        runCatching { ws.send(KalshiWsMessages.updateSubscription(id, sid, "get_snapshot", tickers)) }
            .onFailure { resubscribe() }
    }

    private fun scheduleReconnect() {
        if (!running.get()) return
        reconnectJob?.cancel()
        // 0.3.49: 0.5 s, 1 s, 2 s … cap 10 s, ±20 % jitter (was 1 s doubling to 30 s).
        val delayMs = com.dirk.kalshiodds.signal.engine.WsReconnectSchedule.delayMs(reconnectAttempt, kotlin.random.Random.nextDouble(-1.0, 1.0))
        reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(10)
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
                reconnectAttempt = 0
                subscribedSids.clear()
                bookSeq.reset()
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
                    when (bookSeq.accept(parsed.sid, parsed.seq)) {
                        OrderbookSequencer.Verdict.STALE -> {
                            com.dirk.kalshiodds.signal.debug.PriceDebugLog.record("WS_BOOK", parsed.ticker, "snapshot", null,
                                com.dirk.kalshiodds.signal.debug.PriceDebugLog.DUPLICATE, sid = parsed.sid, seq = parsed.seq)
                            return
                        }
                        OrderbookSequencer.Verdict.GAP -> onSeqGap(webSocket, parsed.sid, parsed.ticker, parsed.seq)
                        OrderbookSequencer.Verdict.APPLY -> Unit
                    }
                    // A snapshot is a full book: always applied (also after a gap).
                    runCatching { onBookSnapshot(parsed) }
                }
                is KalshiWsMessages.Parsed.OrderbookDelta -> {
                    if (!CryptoMarkets.isCryptoTicker(parsed.ticker)) return
                    Log.d(TAG, "book delta ticker=${parsed.ticker} side=${parsed.side} px=${parsed.price} d=${parsed.delta} seq=${parsed.seq}")
                    when (bookSeq.accept(parsed.sid, parsed.seq)) {
                        OrderbookSequencer.Verdict.STALE -> {
                            com.dirk.kalshiodds.signal.debug.PriceDebugLog.record("WS_BOOK", parsed.ticker, "delta", parsed.price,
                                com.dirk.kalshiodds.signal.debug.PriceDebugLog.DUPLICATE, parsed.exchangeTsMs, parsed.sid, parsed.seq)
                            return
                        }
                        OrderbookSequencer.Verdict.GAP -> {
                            // Missed messages: this delta is not safe to apply on top of the stale book.
                            onSeqGap(webSocket, parsed.sid, parsed.ticker, parsed.seq)
                            return
                        }
                        OrderbookSequencer.Verdict.APPLY -> Unit
                    }
                    runCatching { onBookDelta(parsed) }
                }
                is KalshiWsMessages.Parsed.Subscribed -> {
                    val cf = parsed.commandId == cfCommandId || parsed.channel == CfBenchmarks.CHANNEL
                    if (cf) cfSid = parsed.sid else parsed.sid?.let { subscribedSids += it }
                    onLog("subscribed sid=${parsed.sid} cf=$cf")
                }
                is KalshiWsMessages.Parsed.CfValue -> {
                    runCatching { onCf(parsed.tick) }
                }
                is KalshiWsMessages.Parsed.Unsubscribed -> {
                    subscribedSids.removeAll(parsed.sids.toSet())
                    onLog("unsubscribed sids=${parsed.sids}")
                }
                is KalshiWsMessages.Parsed.Lifecycle -> {
                    onLog("lifecycle ${parsed.eventType} ${parsed.ticker}")
                    runCatching { onLifecycle(parsed.ticker, parsed.eventType) }
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
        const val INITIAL_BACKOFF_MS = com.dirk.kalshiodds.signal.engine.WsReconnectSchedule.INITIAL_MS
        const val MAX_BACKOFF_MS = com.dirk.kalshiodds.signal.engine.WsReconnectSchedule.CAP_MS

        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .pingInterval(20, TimeUnit.SECONDS)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build()
    }
}
