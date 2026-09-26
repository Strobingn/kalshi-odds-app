package com.dirk.kalshiodds.domain

import com.dirk.kalshiodds.data.api.KalshiApi

/**
 * Live universe for Dip Hunter. 0.3.14 is **Bitcoin-only** ([KalshiApi.SERIES_BTC]).
 * ETH / SOL / extras are recognized so stored rows can be filtered out, but
 * they are never subscribed, polled, scored, alerted, or paper-traded.
 * WTI and other non-crypto contracts stay rejected.
 */
object CryptoMarkets {
    /** Single live watchlist. Home, rollover, WS, scoring, and paper all read this. */
    val DEFAULT_SERIES: List<String> = listOf(KalshiApi.SERIES_BTC)

    fun isLiveSeries(series: String): Boolean =
        DEFAULT_SERIES.any { it.equals(series.trim(), ignoreCase = true) }

    fun isLiveTicker(ticker: String): Boolean =
        ticker.isNotBlank() && isLiveSeries(inferSeries(ticker))

    fun liveTickers(tickers: Iterable<String>): List<String> =
        tickers.map { it.trim() }.filter { it.isNotEmpty() && isLiveTicker(it) }.distinct()

    /** KXETH15M / KXSOL15M — recognized so stored rows can be filtered, never live. */
    fun isRetiredTicker(ticker: String): Boolean {
        val series = inferSeries(ticker)
        return series.equals(KalshiApi.SERIES_ETH, ignoreCase = true) ||
            series.equals(KalshiApi.SERIES_SOL, ignoreCase = true)
    }

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
            u.startsWith(KalshiApi.SERIES_ETH) || (u.contains("ETH") && !u.contains("BTC")) -> KalshiApi.SERIES_ETH
            u.startsWith(KalshiApi.SERIES_SOL) || u.contains("SOL") -> KalshiApi.SERIES_SOL
            u.startsWith(KalshiApi.SERIES_BTC) || u.contains("BTC") -> KalshiApi.SERIES_BTC
            else -> u.substringBefore("-").ifBlank { KalshiApi.SERIES_BTC }
        }
    }

    fun kindFor(ticker: String): SeriesKind = when (inferSeries(ticker)) {
        KalshiApi.SERIES_ETH -> SeriesKind.ETH
        KalshiApi.SERIES_SOL -> SeriesKind.SOL
        KalshiApi.SERIES_BTC -> SeriesKind.BTC
        else -> SeriesKind.CRYPTO
    }
}
