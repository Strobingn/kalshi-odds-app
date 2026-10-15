package com.dirk.kalshiodds.signal.scalp

import android.content.Context
import com.dirk.kalshiodds.data.api.KalshiApi
import com.dirk.kalshiodds.signal.SignalHub
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Local paper capital only. No credentials, portfolio, create-order or cancel-order dependency. */
class PaperScalpRuntime(context: Context, private val hub: SignalHub) {
    private val prefs = context.getSharedPreferences("edge_adaptive_paper_v1", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val engines = ConcurrentHashMap<String, AdaptivePaperScalp>()
    private val saved = ConcurrentHashMap<String, Pair<Long, PaperScalpState>>()
    private val _state = MutableStateFlow(PaperScalpState())
    val state = _state.asStateFlow()
    private val http = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).callTimeout(4, TimeUnit.SECONDS).build()
    private var marketTicker: String? = null
    private var marketClose = 0L
    private var listAfter = 0L
    private var bookAfter = 0L
    private var retryAfter = 0L
    private var environment = ""
    private var revision = System.currentTimeMillis() * 1000
    private var lastWsRevision: Long? = null
    private fun nextRevision(): Long { revision = maxOf(revision + 1, System.currentTimeMillis() * 1000); return revision }
    private fun source() = if (hub.settings.kalshiDemoEnabled) "demo" else "production"
    private fun engine(key: String): AdaptivePaperScalp = engines.getOrPut(key) {
        val initial = prefs.getString(key, null)?.let { json.decodeFromString<PaperScalpState>(it) }
            ?: PaperScalpState(source = key)
        AdaptivePaperScalp(initial, persist = { next -> save(key, next) })
    }
    @Synchronized private fun save(key: String, value: PaperScalpState) {
        val previous = saved[key]
        fun material(s: PaperScalpState) = s.accounts.map { a -> a.copy(positions = a.positions.map { p -> p.copy(lastBid = 0.0, markAtMs = 0, lowBid = 0.0) }) }
        val now = System.currentTimeMillis()
        val important = previous == null || value.paused != previous.second.paused || material(value) != material(previous.second)
        if (!important && now - previous!!.first < 2000) return
        check(prefs.edit().putString(key, json.encodeToString(value)).commit()) { "Paper checkpoint failed; entries paused until storage recovers" }
        saved[key] = now to value
    }
    fun pause(paused: Boolean) {
        val key = source()
        try { engine(key).setPaused(paused); _state.value = engine(key).snapshot() }
        catch (e: Exception) { _state.value = _state.value.copy(status = "Paper storage error: ${e.message}") }
    }
    fun stopped() { _state.value = _state.value.copy(status = "Monitor stopped. Resume from the paper lab; positions remain recorded.") }
    fun openTickers(): Set<String> = engines.values.flatMap { it.openTickers() }.toSet()
    fun settle(ticker: String, yesWon: Boolean, atMs: Long) {
        val key = source()
        // Settlement callback belongs only to the environment currently queried by the repository.
        engine(key).settle(ticker, yesWon, atMs)
        if (key == source()) _state.value = engine(key).snapshot()
    }
    suspend fun tick() {
        val key = source()
        try {
            if (environment != key) {
                environment = key; lastWsRevision = null; marketTicker = null; marketClose = 0; listAfter = 0; bookAfter = 0; retryAfter = 0
            }
            val lab = engine(key)
            val now = System.currentTimeMillis()
            lab.expirePending(now)
            if (now < retryAfter) { _state.value = lab.snapshot().copy(status = "Market-data backoff; no simulated fills from stale quotes"); return }
            if (marketTicker == null || marketClose <= now) {
                val candidate = hub.watchTickers.value.firstOrNull { it.startsWith("KXBTC15M-") && (hub.scoring.book.closeTime(it) ?: 0) > now }
                if (candidate != null) { marketTicker = candidate; marketClose = hub.scoring.book.closeTime(candidate)!! }
                else if (now >= listAfter) {
                    listAfter = now + 3000
                    discover(key, now)
                }
            }
            val ticker = marketTicker
            if (ticker == null || marketClose <= now) { _state.value = lab.snapshot().copy(status = "Waiting for an open BTC 15-minute market"); return }
            var depth: PaperDepth? = null
            if (hub.hasFreshBook(ticker, now)) {
                val book = hub.scoring.book.snapshotBook(ticker)
                val revision = hub.bookRevision(ticker)
                if (book != null && revision != null && revision != lastWsRevision) {
                    lastWsRevision = revision
                    val score = hub.latestScores()[ticker]
                    depth = PaperDepth(ticker, now, nextRevision(), marketClose, book.yes, book.no,
                        score?.let { it.importedModelPp ?: it.aiPp ?: it.fairValuePp }?.div(100.0))
                }
            } else if (now >= bookAfter) {
                bookAfter = now + 1000
                val base = if (key == "demo") KalshiApi.DEMO_TRADE_BASE_URL else KalshiApi.TRADE_BASE_URL
                val body = get("${base}markets/$ticker/orderbook?depth=100") ?: return
                if (key != source()) return
                val at = System.currentTimeMillis()
                val fp = JSONObject(body).getJSONObject("orderbook_fp")
                depth = PaperDepth(ticker, at, nextRevision(), marketClose,
                    levels(fp, "yes_dollars"), levels(fp, "no_dollars"))
            }
            if (key != source()) return
            if (depth != null) lab.onBook(depth)
            _state.value = lab.snapshot().copy(status = if (depth == null) "Waiting for the next fresh book; no stale fills" else
                "${if (hub.hasFreshBook(ticker, now)) "WebSocket" else "REST 1s"} depth · 250ms minimum simulated order delay")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            retryAfter = System.currentTimeMillis() + 3000
            _state.value = _state.value.copy(status = "Paper engine paused on error: ${e.message}. Existing stops remain recorded.")
        }
    }
    private fun discover(key: String, now: Long) {
        val base = if (key == "demo") KalshiApi.DEMO_TRADE_BASE_URL else KalshiApi.TRADE_BASE_URL
        val body = get("${base}markets?series_ticker=KXBTC15M&status=open&limit=100") ?: return
        if (key != source()) return
        val markets = JSONObject(body).getJSONArray("markets")
        for (i in 0 until markets.length()) {
            val m = markets.getJSONObject(i)
            val close = runCatching { Instant.parse(m.getString("close_time")).toEpochMilli() }.getOrNull() ?: continue
            val open = runCatching { Instant.parse(m.getString("open_time")).toEpochMilli() }.getOrDefault(close - 900000)
            if (now in open until close) { marketTicker = m.getString("ticker"); marketClose = close; return }
        }
    }
    private fun get(url: String): String? = http.newCall(Request.Builder().url(url).header("User-Agent", "BitcoinEdge-PaperLab").build()).execute().use { response ->
        if (response.code == 429 || response.code == 503) {
            val seconds = response.header("Retry-After")?.toLongOrNull()?.coerceIn(1, 3600) ?: 15
            retryAfter = System.currentTimeMillis() + seconds * 1000
            return@use null
        }
        check(response.isSuccessful) { "Market data HTTP ${response.code}" }
        response.body?.string() ?: error("Empty order book")
    }
    companion object {
        fun levels(fp: JSONObject, name: String): List<Pair<Double, Double>> {
            val rows = fp.optJSONArray(name) ?: return emptyList()
            return (0 until rows.length()).mapNotNull { i ->
                val row = rows.getJSONArray(i)
                val p = row.getString(0).toDoubleOrNull(); val n = row.getString(1).toDoubleOrNull()
                if (p != null && n != null && p.isFinite() && p > 0 && p < 1 && n.isFinite() && n > 0) p to n else null
            }.groupBy { it.first }.map { (price, levels) -> price to levels.sumOf { it.second } }
        }
    }
}
