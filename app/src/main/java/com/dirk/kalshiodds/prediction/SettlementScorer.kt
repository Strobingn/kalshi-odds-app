package com.dirk.kalshiodds.prediction

import com.dirk.kalshiodds.data.api.KalshiApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Periodically resolves open prediction-log entries (and extra paper tickers)
 * against settled Kalshi markets.
 */
class SettlementScorer(
    private val resolveApi: () -> KalshiApi,
    private val logStore: PredictionLogStore,
    private val extraOpenTickers: () -> Set<String> = { emptySet() },
    private val onMarketSettled: (ticker: String, result: String) -> Unit = { _, _ -> }
) {
    constructor(
        api: KalshiApi,
        logStore: PredictionLogStore,
        extraOpenTickers: () -> Set<String> = { emptySet() },
        onMarketSettled: (ticker: String, result: String) -> Unit = { _, _ -> }
    ) : this({ api }, logStore, extraOpenTickers, onMarketSettled)
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
        val extra = extraOpenTickers()
        if (open.isEmpty() && extra.isEmpty()) return
        val bySeries = LinkedHashMap<String, MutableSet<String>>()
        for (e in open) {
            val series = e.series.ifBlank { inferSeries(e.ticker) }
            if (series.isBlank()) continue
            bySeries.getOrPut(series) { mutableSetOf() }.add(e.ticker)
        }
        for (t in extra) {
            val series = inferSeries(t)
            if (series.isBlank()) continue
            bySeries.getOrPut(series) { mutableSetOf() }.add(t)
        }
        for ((series, tickers) in bySeries) {
            try {
                val settled = resolveApi().getMarkets(seriesTicker = series, status = "settled", limit = 100)
                for (m in settled.markets) {
                    if (m.ticker in tickers) {
                        applyResult(m.ticker, m.result)
                    }
                }
                for (t in tickers) {
                    val stillOpen = logStore.readAll().any { it.ticker == t && it.outcome == null } ||
                        extra.any { it.equals(t, ignoreCase = true) }
                    if (!stillOpen) continue
                    try {
                        val resp = resolveApi().getMarkets(seriesTicker = series, status = "settled", ticker = t, limit = 5)
                        val hit = resp.markets.firstOrNull { it.ticker == t }
                        applyResult(t, hit?.result)
                    } catch (_: Exception) {
                        // ignore per-ticker misses
                    }
                }
            } catch (_: Exception) {
                // scoring is best-effort; never break polling
            }
        }
    }

    private suspend fun applyResult(ticker: String, raw: String?) {
        val result = normalizeResult(raw) ?: return
        logStore.applySettlement(ticker, result)
        runCatching { onMarketSettled(ticker, result) }
    }

    private fun inferSeries(ticker: String): String =
        com.dirk.kalshiodds.domain.CryptoMarkets.inferSeries(ticker)

    companion object {
        fun normalizeResult(raw: String?): String? {
            val n = raw?.lowercase()?.trim().orEmpty()
            return when (n) {
                "yes", "no", "void" -> n
                "scalar", "cancelled", "canceled", "invalid" -> "void"
                else -> null
            }
        }
    }
}
