package com.dirk.kalshiodds.domain

import com.dirk.kalshiodds.data.api.KalshiApi

/**
 * Home stays Bitcoin ([KalshiApi.SERIES_BTC]). Autopilot paper, shadow, and
 * limited live also watch ETH and SOL 15-minute markets. Daily, XRP, HYPE,
 * sports, and other series stay out. WTI and other non-crypto contracts stay rejected.
 */
object CryptoMarkets {
    /** Home card. Not the Autopilot watchlist. */
    val DEFAULT_SERIES: List<String> = listOf(KalshiApi.SERIES_BTC)

    /** BTC, ETH, and SOL 15-minute markets. */
    val FIFTEEN_SERIES: List<String> = listOf(
        KalshiApi.SERIES_BTC,
        KalshiApi.SERIES_ETH,
        KalshiApi.SERIES_SOL
    )

    /** Daily 5 PM ET above/below: BTC, ETH, SOL. */
    val DAILY_SERIES: List<String> = listOf(
        KalshiApi.SERIES_BTCD,
        KalshiApi.SERIES_ETHD,
        KalshiApi.SERIES_SOLD
    )

    /** 15-minute plus daily. Home card stays Bitcoin 15m. */
    val AUTOPILOT_SERIES: List<String> = FIFTEEN_SERIES + DAILY_SERIES

    fun isLiveSeries(series: String): Boolean =
        DEFAULT_SERIES.any { it.equals(series.trim(), ignoreCase = true) }

    fun isLiveTicker(ticker: String): Boolean =
        ticker.isNotBlank() && isLiveSeries(inferSeries(ticker))

    fun liveTickers(tickers: Iterable<String>): List<String> =
        tickers.map { it.trim() }.filter { it.isNotEmpty() && isLiveTicker(it) }.distinct()

    fun isAutopilotSeries(series: String): Boolean =
        AUTOPILOT_SERIES.any { it.equals(series.trim(), ignoreCase = true) }

    fun isDailySeries(series: String): Boolean =
        DAILY_SERIES.any { it.equals(series.trim(), ignoreCase = true) }

    fun isDailyTicker(ticker: String): Boolean {
        val u = ticker.trim().uppercase()
        return DAILY_SERIES.any { u.startsWith(it) }
    }

    /** 15-minute BTC/ETH/SOL and the daily 5 PM ET series. */
    fun isAutopilotTicker(ticker: String): Boolean {
        val u = ticker.trim().uppercase()
        if (u.isEmpty()) return false
        return AUTOPILOT_SERIES.any { u.startsWith(it) }
    }

    /** Nothing in the Autopilot 15m set is retired. Other series are simply not watched. */
    fun isRetiredTicker(ticker: String): Boolean = false

    /** Tokens that identify a crypto-denominated Kalshi series or ticker. */
    private val CRYPTO_TOKENS = listOf(
        "BTC", "ETH", "SOL", "XRP", "DOGE", "ADA", "AVAX", "DOT", "LINK",
        "MATIC", "SHIB", "LTC", "BCH", "UNI", "ATOM", "NEAR", "APT", "SUI",
        "PEPE", "BONK", "WIF", "HYPE", "CRYPTO", "BITCOIN", "ETHEREUM", "SOLANA"
    )

    private val EXCLUDED_TOKENS = listOf("WTI", "CRUDE", "OIL", "GAS", "NATGAS", "CL-")

    fun isCryptoTicker(ticker: String): Boolean {
        val u = ticker.uppercase()
        if (u.isBlank()) return false
        if (EXCLUDED_TOKENS.any { u.contains(it) }) return false
        if (DEFAULT_SERIES.any { u.startsWith(it) }) return true
        return CRYPTO_TOKENS.any { u.contains(it) }
    }

    fun filterCrypto(tickers: Iterable<String>): List<String> =
        tickers.map { it.trim() }.filter { it.isNotEmpty() && isCryptoTicker(it) }.distinct()

    fun inferSeries(ticker: String): String {
        val u = ticker.uppercase()
        return when {
            u.startsWith(KalshiApi.SERIES_BTCD) -> KalshiApi.SERIES_BTCD
            u.startsWith(KalshiApi.SERIES_ETHD) -> KalshiApi.SERIES_ETHD
            u.startsWith(KalshiApi.SERIES_SOLD) -> KalshiApi.SERIES_SOLD
            u.startsWith(KalshiApi.SERIES_ETH) || (u.contains("ETH") && !u.contains("BTC")) -> KalshiApi.SERIES_ETH
            u.startsWith(KalshiApi.SERIES_SOL) || u.contains("SOL") -> KalshiApi.SERIES_SOL
            u.startsWith(KalshiApi.SERIES_BTC) || u.contains("BTC") -> KalshiApi.SERIES_BTC
            else -> u.substringBefore("-").ifBlank { KalshiApi.SERIES_BTC }
        }
    }

    fun kindFor(ticker: String): SeriesKind = when (inferSeries(ticker)) {
        KalshiApi.SERIES_ETH -> SeriesKind.ETH
        KalshiApi.SERIES_SOL -> SeriesKind.SOL
        KalshiApi.SERIES_BTC, KalshiApi.SERIES_BTCD -> SeriesKind.BTC
        else -> SeriesKind.CRYPTO
    }
}
