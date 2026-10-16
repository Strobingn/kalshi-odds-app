package com.dirk.kalshiodds.signal.external

import com.dirk.kalshiodds.signal.config.SignalConstants
import kotlin.math.ln
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
 * Model input is Coinbase Exchange USD (`BTC-USD` ticker `price` — the
 * last trade, not a candle close). Official docs:
 * https://docs.cdp.coinbase.com/exchange/reference/exchangerestapi_getproductticker
 *
 * Binance USDT is display-only. It is never used as a model spot without
 * a USDT→USD conversion, which we do not have here.
 */
data class AssetSpotFeatures(
    val asset: String,
    val spotReturn1m: Double? = null,
    val spotReturn5m: Double? = null,
    val realizedVol15m: Double? = null,
    val fundingRate: Double? = null,
    val lastPrice: Double? = null,
    val source: String = "none",
    val fetchedAtMs: Long = 0L,
    val minuteCloses: List<Double> = emptyList(),
    val displayPrice: Double? = null,
    val displaySource: String? = null,
    val modelUsable: Boolean = source == "coinbase"
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
        val binance = runCatching { fetchBinanceDisplay(asset, now) }.getOrNull()
        return binance
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

    /**
     * Display-only USDT last. [AssetSpotFeatures.lastPrice] stays null so
     * scoring / digital-fair / the edge model never ingest an unconverted
     * USDT print against a USD strike.
     */
    private fun fetchBinanceDisplay(asset: String, now: Long): AssetSpotFeatures? {
        val symbol = when (asset.uppercase()) {
            "BTC" -> "BTCUSDT"
            "ETH" -> "ETHUSDT"
            "SOL" -> "SOLUSDT"
            else -> return null
        }
        val ticker = getJsonObject("https://api.binance.com/api/v3/ticker/price?symbol=$symbol")
        val px = ticker?.optString("price")?.toDoubleOrNull() ?: return null
        return AssetSpotFeatures(
            asset = asset.uppercase(),
            lastPrice = null,
            displayPrice = px,
            displaySource = "binance-usdt",
            source = "binance-display",
            modelUsable = false,
            fetchedAtMs = now
        )
    }

    private fun fetchCoinbase(asset: String, now: Long): AssetSpotFeatures? {
        val product = when (asset.uppercase()) {
            "BTC" -> "BTC-USD"
            "ETH" -> "ETH-USD"
            "SOL" -> "SOL-USD"
            else -> return null
        }
        // Live last trade. Do not fall back to a candle close for lastPrice.
        val ticker = getJsonObject("https://api.exchange.coinbase.com/products/$product/ticker")
            ?: return null
        val last = ticker.optString("price").toDoubleOrNull() ?: return null
        val candles = getJsonArray(
            "https://api.exchange.coinbase.com/products/$product/candles?granularity=60"
        )
        val closes = mutableListOf<Double>()
        if (candles != null) {
            // Coinbase returns newest-first: [time, low, high, open, close, volume]
            for (i in 0 until minOf(candles.length(), 20)) {
                val row = candles.optJSONArray(i) ?: continue
                row.optDouble(4).takeIf { !it.isNaN() }?.let { closes.add(it) }
            }
            closes.reverse()
        }
        val series = if (closes.size >= 2) closes else emptyList()
        val ret1 = if (series.size >= 2 && series[series.size - 2] > 0) {
            (last - series[series.size - 2]) / series[series.size - 2]
        } else null
        val ret5 = if (series.size >= 6 && series[series.size - 6] > 0) {
            (last - series[series.size - 6]) / series[series.size - 6]
        } else null
        return AssetSpotFeatures(
            asset = asset.uppercase(),
            spotReturn1m = ret1,
            spotReturn5m = ret5,
            realizedVol15m = realizedVol(series.takeLast(16)),
            fundingRate = null,
            lastPrice = last,
            source = "coinbase",
            fetchedAtMs = now,
            minuteCloses = series.takeLast(8),
            displayPrice = last,
            displaySource = "coinbase",
            modelUsable = true
        )
    }

    private fun realizedVol(closes: List<Double>): Double? {
        if (closes.size < 4) return null
        val rets = closes.zipWithNext { a, b ->
            if (a > 0.0 && b > 0.0) ln(b / a) else 0.0
        }
        if (rets.isEmpty()) return null
        val mean = rets.average()
        val var_ = rets.map { val d = it - mean; d * d }.average()
        return sqrt(var_.coerceAtLeast(0.0))
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
}

/**
 * In-memory cache + refresh helper. Fail-soft: a miss leaves the last
 * good snapshot (or null).
 */
class ExternalMarketCache(
    private val client: ExternalMarketClient = ExternalMarketClient(),
    private val ttlMs: Long = SignalConstants.EXTERNAL_CACHE_MS
) {
    @Volatile
    private var snapshot: ExternalSnapshot = ExternalSnapshot()

    @Volatile
    private var lastAttemptMs: Long = 0L

    fun latest(): ExternalSnapshot = snapshot

    @Synchronized
    fun refreshIfStale(nowMs: Long = System.currentTimeMillis()): ExternalSnapshot {
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
