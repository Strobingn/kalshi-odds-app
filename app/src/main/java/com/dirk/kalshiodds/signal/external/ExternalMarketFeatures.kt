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
 * Sources (no paid keys, market-data only — never places exchange orders):
 *  1. Binance spot ticker + 1m klines + USDT-M funding (`premiumIndex`)
 *  2. Coinbase Exchange public ticker + 1m candles as REST fallback
 *
 * Timeouts are short; results are cached; failures are swallowed so
 * scoring continues without this feature (weight drops out).
 *
 * This REST snapshot is the fallback. While [CoinbaseSpotStream] is fresh,
 * scoring takes price, returns and σ from the stream instead ([SpotMerge]);
 * funding always comes from here.
 *
 * [AssetSpotFeatures.realizedVol15m] is the std of **1-minute** log returns
 * (not annualized). Both sources fill it in that unit.
 */
data class AssetSpotFeatures(
    val asset: String,
    val spotReturn1m: Double? = null,
    val spotReturn5m: Double? = null,
    /** Return over the trailing 15 minutes at this market's open — the
     * previous window's move (reversal-tilt feature; parity with the
     * trainer's prev_window_return). */
    val prevWindowReturn: Double? = null,
    val realizedVol15m: Double? = null,
    val fundingRate: Double? = null,
    val lastPrice: Double? = null,
    val source: String = "none",
    val fetchedAtMs: Long = 0L
)

data class ExternalSnapshot(
    val btc: AssetSpotFeatures? = null,
    val eth: AssetSpotFeatures? = null,
    val sol: AssetSpotFeatures? = null,
    val fetchedAtMs: Long = 0L
) {
    fun forSeries(series: String): AssetSpotFeatures? = when (assetOf(series)) {
        "BTC" -> btc
        "ETH" -> eth
        "SOL" -> sol
        else -> null
    }

    companion object {
        /** "BTC" / "ETH" / "SOL" for a Kalshi series or ticker (KXBTC15M…), else null. */
        fun assetOf(series: String): String? {
            val u = series.uppercase()
            return when {
                u.contains("BTC") -> "BTC"
                u.contains("ETH") -> "ETH"
                u.contains("SOL") -> "SOL"
                else -> null
            }
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
        val binance = runCatching { fetchBinance(asset, now) }.getOrNull()
        if (binance != null) return binance
        return runCatching { fetchCoinbase(asset, now) }.getOrNull()
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

    private fun fetchBinance(asset: String, now: Long): AssetSpotFeatures? {
        val symbol = when (asset.uppercase()) {
            "BTC" -> "BTCUSDT"
            "ETH" -> "ETHUSDT"
            "SOL" -> "SOLUSDT"
            else -> return null
        }
        val klines = getJsonArray("https://api.binance.com/api/v3/klines?symbol=$symbol&interval=1m&limit=20")
            ?: return null
        val closes = (0 until klines.length()).mapNotNull { i ->
            klines.optJSONArray(i)?.optString(4)?.toDoubleOrNull()
        }
        if (closes.size < 3) return null
        val last = closes.last()
        val ret1 = if (closes.size >= 2 && closes[closes.size - 2] > 0) {
            (last - closes[closes.size - 2]) / closes[closes.size - 2]
        } else null
        val ret5 = if (closes.size >= 6 && closes[closes.size - 6] > 0) {
            (last - closes[closes.size - 6]) / closes[closes.size - 6]
        } else null
        val funding = getJsonObject("https://fapi.binance.com/fapi/v1/premiumIndex?symbol=$symbol")
            ?.optString("lastFundingRate")
            ?.toDoubleOrNull()
        return AssetSpotFeatures(
            asset = asset.uppercase(),
            spotReturn1m = ret1,
            spotReturn5m = ret5,
            realizedVol15m = realizedVol(closes.takeLast(64)),
            fundingRate = funding,
            lastPrice = last,
            source = "binance",
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
        val ticker = getJsonObject("https://api.exchange.coinbase.com/products/$product/ticker")
        val last = ticker?.optString("price")?.toDoubleOrNull()
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
        if (closes.size < 3 && last == null) return null
        val px = closes.lastOrNull() ?: last ?: return null
        val series = if (closes.size >= 3) closes else listOf(px)
        val ret1 = if (series.size >= 2 && series[series.size - 2] > 0) {
            (px - series[series.size - 2]) / series[series.size - 2]
        } else null
        val ret5 = if (series.size >= 6 && series[series.size - 6] > 0) {
            (px - series[series.size - 6]) / series[series.size - 6]
        } else null
        return AssetSpotFeatures(
            asset = asset.uppercase(),
            spotReturn1m = ret1,
            spotReturn5m = ret5,
            realizedVol15m = realizedVol(series.takeLast(64)),
            fundingRate = null,
            lastPrice = px,
            source = "coinbase",
            fetchedAtMs = now
        )
    }

    /**
     * EWMA sigma over 1-minute log returns (lambda = 0.86, up to ~64 bars).
     * Sigma is the digital fair's only parameter; the 16-bar sample std was
     * noisy enough to move the fair ~30% farther from the market in testing.
     * Parity: ml/train_edge.py realized_vol_annual uses the same lambda and
     * unbiased-start formula, so trainer and phone agree.
     */
    private fun realizedVol(closes: List<Double>): Double? {
        if (closes.size < 4) return null
        val rets = closes.zipWithNext { a, b ->
            if (a > 0.0 && b > 0.0) ln(b / a) else 0.0
        }
        if (rets.isEmpty()) return null
        val mean = rets.average()
        var var_ = rets.map { val d = it - mean; d * d }.average()
        var wSum = 1.0
        val lam = 0.86
        for (r in rets) {
            val d = r - mean
            var_ = lam * var_ + (1.0 - lam) * d * d
            wSum = lam * wSum + (1.0 - lam)
        }
        return sqrt((var_ / wSum).coerceAtLeast(0.0))
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
            .header("User-Agent", "DipHunter/0.2.1 (Android; market-data)")
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
