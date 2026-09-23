package com.dirk.kalshiodds.domain

import com.dirk.kalshiodds.data.api.KalshiApi

/**
 * Crypto-only universe for Dip Hunter.
 *
 * Watched by default: BTC / ETH / SOL 15-minute series.
 * Extra tickers are accepted only when they look crypto-denominated.
 * WTI, oil, and other non-crypto contracts are rejected everywhere
 * (REST, WebSocket subscribe, scoring, alerts, settings).
 */
object CryptoMarkets {
    val DEFAULT_SERIES: List<String> = listOf(
        KalshiApi.SERIES_BTC,
        KalshiApi.SERIES_ETH,
        KalshiApi.SERIES_SOL
    )

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
