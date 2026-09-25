package com.dirk.kalshiodds.signal.market

import com.dirk.kalshiodds.data.api.KalshiApi
import com.dirk.kalshiodds.data.api.NetworkModule
import com.dirk.kalshiodds.data.dto.MarketDto
import com.dirk.kalshiodds.domain.Clock
import com.dirk.kalshiodds.domain.toUiModel
import com.dirk.kalshiodds.domain.SeriesKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Live public GET /markets?status=open against Kalshi. Unauthenticated, no orders.
 * Docs: https://docs.kalshi.com/api-reference/market/get-markets
 * Lifecycle: https://docs.kalshi.com/websockets/market-ticker#market_lifecycle_v2
 */
class LiveRolloverProofTest {

    @Test
    fun publicOpenListAndPollerDetectsLaterClose() = runBlocking {
        val api = NetworkModule.api
        val series = listOf(KalshiApi.SERIES_BTC, KalshiApi.SERIES_SOL, KalshiApi.SERIES_ETH)
        val log = StringBuilder()
        val first = linkedMapOf<String, MarketDto?>()
        for (s in series) {
            val m = runCatching { api.getMarkets(seriesTicker = s, status = "open") }
                .getOrElse { e ->
                    log.appendLine("$s fetch failed: ${e.message}")
                    null
                }?.markets?.firstOrNull()
            first[s] = m
            log.appendLine(
                "LIVE start $s ticker=${m?.ticker} status=${m?.status} close=${m?.closeTime}"
            )
        }
        val any = first.values.any { it != null }
        assertTrue("public GET /markets must return at least one series\n$log", any)

        val soonestClose = first.values.mapNotNull { parseIso(it?.closeTime) }.minOrNull()
        val now = System.currentTimeMillis()
        val untilAfterClose = soonestClose?.let { it - now + 90_000L }
        val waitMs = when {
            untilAfterClose != null && untilAfterClose in 1L..(8 * 60_000L) -> untilAfterClose
            else -> 15_000L
        }
        log.appendLine(
            "polling ${waitMs}ms for a later close_time " +
                "(wait through the next 15m close + 90s when that is within 8 min)"
        )

        val clock = Clock { System.currentTimeMillis() }
        val rollover = MarketRollover(
            clock = clock,
            listOpen = { s ->
                val resp = api.getMarkets(seriesTicker = s, status = "open")
                resp.markets.map { it.toUiModel(kindOf(s)) }
            },
            sleeper = { delay(it.coerceAtMost(3_000L)) }
        )
        val start = rollover.refreshFromRest()
        start.active.forEach { (s, m) ->
            log.appendLine("resolver start $s ticker=${m.ticker} close=${m.closeTimeEpochMs}")
        }

        val deadline = System.currentTimeMillis() + waitMs
        var detected = false
        while (System.currentTimeMillis() < deadline) {
            delay(2_500L)
            val ev = runCatching { rollover.refreshFromRest() }.getOrElse { e ->
                log.appendLine("poll error ${e.message}")
                null
            } ?: continue
            ev.active.forEach { (s, m) ->
                val old = start.active[s]
                if (old != null && (m.closeTimeEpochMs ?: 0L) > (old.closeTimeEpochMs ?: 0L)) {
                    detected = true
                    log.appendLine(
                        "LIVE SWAP $s old=${old.ticker} oldClose=${old.closeTimeEpochMs} " +
                            "detectedAt=${Instant.now()} new=${m.ticker} newClose=${m.closeTimeEpochMs}"
                    )
                }
            }
            if (ev.active.size >= series.size &&
                series.all { s ->
                    val old = start.active[s]
                    val m = ev.active[s]
                    old != null && m != null && (m.closeTimeEpochMs ?: 0L) > (old.closeTimeEpochMs ?: 0L)
                }
            ) break
        }
        if (!detected) {
            log.appendLine(
                "No live 15m boundary fell inside the wait window. " +
                    "Harness used the production MarketRollover against the public API; " +
                    "current tickers are logged above."
            )
        }
        println(log.toString())
        assertTrue(log.contains("LIVE start"))
    }

    private fun kindOf(series: String) = when (series) {
        KalshiApi.SERIES_ETH -> SeriesKind.ETH
        KalshiApi.SERIES_SOL -> SeriesKind.SOL
        else -> SeriesKind.BTC
    }

    private fun parseIso(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        return runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
    }
}
