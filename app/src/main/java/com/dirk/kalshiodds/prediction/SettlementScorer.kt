package com.dirk.kalshiodds.prediction

import com.dirk.kalshiodds.data.api.KalshiApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Periodically resolves open prediction-log entries against settled Kalshi markets.
 */
class SettlementScorer(
    private val api: KalshiApi,
    private val logStore: PredictionLogStore
) {
    private val mutex = Mutex()
    @Volatile private var lastRunMs: Long = 0L

    suspend fun maybeScore(nowMs: Long = System.currentTimeMillis(), minIntervalMs: Long = 15_000L) {
        if (nowMs - lastRunMs < minIntervalMs) return
        mutex.withLock {
            if (nowMs - lastRunMs < minIntervalMs) return
            lastRunMs = nowMs
            scoreOnce()
        }
    }

    private suspend fun scoreOnce() {
        val open = logStore.readAll().filter { it.outcome == null }
        if (open.isEmpty()) return
        val bySeries = open.groupBy { it.series.ifBlank { inferSeries(it.ticker) } }
        for ((series, entries) in bySeries) {
            if (series.isBlank()) continue
            val tickers = entries.map { it.ticker }.toSet()
            try {
                val settled = api.getMarkets(seriesTicker = series, status = "settled", limit = 100)
                for (m in settled.markets) {
                    if (m.ticker in tickers) {
                        val result = m.result ?: continue
                        logStore.applySettlement(m.ticker, result)
                    }
                }
                // Also try fetching by ticker for any still open (API may support ticker filter)
                for (t in tickers) {
                    val stillOpen = logStore.readAll().any { it.ticker == t && it.outcome == null }
                    if (!stillOpen) continue
                    try {
                        val resp = api.getMarkets(seriesTicker = series, status = "settled", ticker = t, limit = 5)
                        val hit = resp.markets.firstOrNull { it.ticker == t }
                        val result = hit?.result ?: continue
                        logStore.applySettlement(t, result)
                    } catch (_: Exception) {
                        // ignore per-ticker misses
                    }
                }
            } catch (_: Exception) {
                // scoring is best-effort; never break polling
            }
        }
    }

    private fun inferSeries(ticker: String): String =
        when {
            ticker.uppercase().contains("WTI") -> KalshiApi.SERIES_WTI
            else -> KalshiApi.SERIES_BTC
        }
}
