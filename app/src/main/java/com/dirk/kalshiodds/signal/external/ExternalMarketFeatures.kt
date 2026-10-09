package com.dirk.kalshiodds.signal.external

import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.fair.DigitalOptionFairValue
import kotlin.math.sqrt
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Short-horizon **public** crypto market data for BTC / ETH / SOL.
 *
 * Sources (no paid keys, market-data only — never places exchange orders):
 *  1. Coinbase Exchange BTC-**USD** ticker + 1m candles. Kalshi strikes are in
 *     USD and the trainer / backtest use these same Coinbase bars, so this is
 *     the primary.
 *  2. Binance BTC**USDT** as a fallback only. USDT trades a few bp off USD —
 *     the same size as the "< 5bp from strike" zone where the side is decided.
 *
 * Live price and exact 60 s / 300 s returns come from [SpotStream] when it is
 * connected; this REST snapshot supplies σ and the fallback.
 *
 * Timeouts are short; results are cached; failures are swallowed so
 * scoring continues without this feature (weight drops out).
 */
data class AssetSpotFeatures(
    val asset: String,
    val spotReturn1m: Double? = null,
    val spotReturn5m: Double? = null,
    /** Per-bar (1m) std of log returns over the last 16 completed bars. */
    val realizedVol15m: Double? = null,
    val fundingRate: Double? = null,
    val lastPrice: Double? = null,
    val source: String = "none",
    val fetchedAtMs: Long = 0L,
    /** Annualized σ for the digital ([DigitalOptionFairValue.sigmaFromCloses]). */
    val sigmaAnnual: Double? = null,
    /** Completed 1m closes, oldest first (σ input; also seeds [SpotStream]). */
    val closes: List<Double> = emptyList()
)

data class ExternalSnapshot(
    val btc: AssetSpotFeatures? = null,
    val eth: AssetSpotFeatures? = null,
    val sol: AssetSpotFeatures? = null,
    val fetchedAtMs: Long = 0L
) {
    fun forSeries(series: String): AssetSpotFeatures? {
        val u = series.uppercase()
        return when {
            u.contains("BTC") -> btc
            u.contains("ETH") && !u.contains("BTC") -> eth
            u.contains("SOL") -> sol
            else -> null
        }
    }
}

/**
 * Pure math shared by both sources so returns / σ have one definition.
 * Python twin: `tools/backtest/simulate.spot_inputs` + `pipeline.sigma_annual_from_closes`.
 */
object SpotBars {
    const val BAR_MS = 60_000L

    /** One completed 1m bar: [startMs] is the bucket start; it closed at startMs + 60 s. */
    data class Bar(val startMs: Long, val close: Double)

    fun features(
        asset: String,
        source: String,
        livePrice: Double?,
        bars: List<Bar>,
        nowMs: Long
    ): AssetSpotFeatures? {
        val done = bars.filter { it.startMs + BAR_MS <= nowMs && it.close > 0.0 && it.close.isFinite() }
            .sortedBy { it.startMs }
        val px = livePrice?.takeIf { it > 0.0 && it.isFinite() } ?: done.lastOrNull()?.close ?: return null
        val closes = done.map { it.close }
        return AssetSpotFeatures(
            asset = asset.uppercase(),
            spotReturn1m = returnOver(px, done, nowMs, 60_000L),
            spotReturn5m = returnOver(px, done, nowMs, 300_000L),
            realizedVol15m = barStd(closes.takeLast(17)),
            fundingRate = null,
            lastPrice = px,
            source = source,
            fetchedAtMs = nowMs,
            sigmaAnnual = DigitalOptionFairValue.sigmaFromCloses(closes),
            closes = closes.takeLast(DigitalOptionFairValue.SIGMA_BARS + 1)
        )
    }

    /** px / (price [horizonMs] ago) − 1, using the last bar that had closed by then. */
    fun returnOver(px: Double, done: List<Bar>, nowMs: Long, horizonMs: Long): Double? {
        val t = nowMs - horizonMs
        val bar = done.lastOrNull { it.startMs + BAR_MS <= t } ?: return null
        // Too old to stand in for "horizon ago" (gap in the candles).
        if (t - (bar.startMs + BAR_MS) > 2 * BAR_MS || bar.close <= 0.0) return null
        return px / bar.close - 1.0
    }

    /** Sample std of 1m log returns (Python: pipeline.realized_vol_bar_std). */
    fun barStd(closes: List<Double>): Double? {
        val rets = DigitalOptionFairValue.logReturns(closes)
        if (rets.size < 4) return null
        val mean = rets.average()
        val v = rets.sumOf { (it - mean) * (it - mean) } / (rets.size - 1)
        return sqrt(v.coerceAtLeast(0.0)).takeIf { it > 0.0 }
    }

    /** Coinbase `[time_s, low, high, open, close, volume]`, newest first. */
    fun fromCoinbase(candles: JSONArray): List<Bar> =
        (0 until candles.length()).mapNotNull { i ->
            val row = candles.optJSONArray(i) ?: return@mapNotNull null
            val t = row.optLong(0, -1L).takeIf { it > 0L } ?: return@mapNotNull null
            val c = row.optDouble(4).takeIf { !it.isNaN() } ?: return@mapNotNull null
            Bar(t * 1000L, c)
        }

    /** Binance kline `[openTimeMs, open, high, low, close, …]`, oldest first. */
    fun fromBinance(klines: JSONArray): List<Bar> =
        (0 until klines.length()).mapNotNull { i ->
            val row = klines.optJSONArray(i) ?: return@mapNotNull null
            val t = row.optLong(0, -1L).takeIf { it > 0L } ?: return@mapNotNull null
            val c = row.optString(4).toDoubleOrNull() ?: return@mapNotNull null
            Bar(t, c)
        }
}

class ExternalMarketClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(SignalConstants.EXTERNAL_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(SignalConstants.EXTERNAL_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(
            SignalConstants.EXTERNAL_CONNECT_TIMEOUT_MS + SignalConstants.EXTERNAL_READ_TIMEOUT_MS,
            TimeUnit.MILLISECONDS
        )
        .followRedirects(true)
        .build()
) {
    fun fetchAsset(asset: String): AssetSpotFeatures? {
        val now = System.currentTimeMillis()
        val coinbase = runCatching { fetchCoinbase(asset, now) }.getOrNull()
        if (coinbase != null) return coinbase
        return runCatching { fetchBinance(asset, now) }.getOrNull()
    }

    fun fetchAll(): ExternalSnapshot {
        val now = System.currentTimeMillis()
        return ExternalSnapshot(
            btc = fetchAsset("BTC"),
            eth = fetchAsset("ETH"),
            sol = fetchAsset("SOL"),
            fetchedAtMs = now
        )
    }

    private fun fetchCoinbase(asset: String, now: Long): AssetSpotFeatures? {
        val product = COINBASE_PRODUCT[asset.uppercase()] ?: return null
        // Live ticker first: the latest 1m candle can lag the tape by up to a minute.
        val live = getJsonObject("https://api.exchange.coinbase.com/products/$product/ticker")
            ?.optString("price")?.toDoubleOrNull()
        val candles = getJsonArray("https://api.exchange.coinbase.com/products/$product/candles?granularity=60")
        val bars = candles?.let { SpotBars.fromCoinbase(it) }.orEmpty()
        return SpotBars.features(asset, "coinbase", live, bars, now)
    }

    private fun fetchBinance(asset: String, now: Long): AssetSpotFeatures? {
        val symbol = BINANCE_SYMBOL[asset.uppercase()] ?: return null
        val klines = getJsonArray(
            "https://api.binance.com/api/v3/klines?symbol=$symbol&interval=1m&limit=${DigitalOptionFairValue.SIGMA_BARS + 3}"
        ) ?: return null
        val bars = SpotBars.fromBinance(klines)
        // In-progress kline close ≈ last trade.
        val live = bars.maxByOrNull { it.startMs }?.close
        return SpotBars.features(asset, "binance-usdt", live, bars, now)
    }

    private fun getJsonObject(url: String): JSONObject? {
        val body = get(url) ?: return null
        return runCatching { JSONObject(body) }.getOrNull()
    }

    private fun getJsonArray(url: String): JSONArray? {
        val body = get(url) ?: return null
        return runCatching { JSONArray(body) }.getOrNull()
    }

    private fun get(url: String): String? {
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "DipHunter/0.3.18 (Android; market-data)")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            return resp.body?.string()
        }
    }

    companion object {
        val COINBASE_PRODUCT = mapOf("BTC" to "BTC-USD", "ETH" to "ETH-USD", "SOL" to "SOL-USD")
        val BINANCE_SYMBOL = mapOf("BTC" to "BTCUSDT", "ETH" to "ETHUSDT", "SOL" to "SOLUSDT")
    }
}

/**
 * In-memory cache + refresh helper. Fail-soft: a miss leaves the last
 * good snapshot (or null). [stream] (when given) is kept running while
 * callers keep asking for data.
 */
class ExternalMarketCache(
    private val client: ExternalMarketClient = ExternalMarketClient(),
    private val ttlMs: Long = SignalConstants.EXTERNAL_CACHE_MS,
    val stream: SpotStream? = null
) {
    @Volatile
    private var snapshot: ExternalSnapshot = ExternalSnapshot()

    @Volatile
    private var lastAttemptMs: Long = 0L

    fun latest(): ExternalSnapshot = snapshot

    @Synchronized
    fun refreshIfStale(nowMs: Long = System.currentTimeMillis()): ExternalSnapshot {
        stream?.ensureRunning(nowMs)
        if (nowMs - lastAttemptMs < ttlMs && snapshot.fetchedAtMs > 0L) return snapshot
        lastAttemptMs = nowMs
        val next = runCatching { client.fetchAll() }.getOrNull()
        if (next != null && (next.btc != null || next.eth != null || next.sol != null)) {
            snapshot = next
        }
        return snapshot
    }
}

/** Kept for tests that want a deterministic snapshot without HTTP. */
@Serializable
data class ExternalTickerDto(
    @SerialName("symbol") val symbol: String? = null,
    @SerialName("price") val price: String? = null
)
