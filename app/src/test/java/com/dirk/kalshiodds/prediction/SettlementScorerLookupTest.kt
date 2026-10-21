package com.dirk.kalshiodds.prediction

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dirk.kalshiodds.data.api.KalshiApi
import com.dirk.kalshiodds.data.dto.MarketDto
import com.dirk.kalshiodds.data.dto.MarketsResponse
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SettlementScorerLookupTest {
    private val now = 1_800_000_000_000L

    @Test fun staleTickerSettlesFromTheTickerLookup() = runBlocking {
        val old = "KXBTC15M-26SEP010130-00"
        val calls = ArrayList<String>()
        val api = api { name, args ->
            calls += name
            if (name == "getMarketsByTickers") {
                // Other suites share the prediction log, so the batch may include their open tickers.
                val requested = args[2].toString().split(',').map { it.trim() }
                assertTrue(requested.any { it.equals(old, true) })
                MarketsResponse(markets = requested.map { MarketDto(ticker = it, result = if (it.equals(old, true)) "yes" else "no", status = "settled") })
            } else {
                MarketsResponse(markets = listOf(MarketDto(ticker = "KXBTC15M-OTHER", result = "no", status = "settled")))
            }
        }
        val settled = ArrayList<Pair<String, String>>()
        scorer(api, setOf(old), now - 2 * 3_600_000L) { t, r -> settled += t to r }.maybeScore(now, 0L)
        assertEquals("yes", settled.first { it.first.equals(old, true) }.second)
        assertEquals(listOf("getMarketsByTickers"), calls)
    }

    @Test fun freshTickerStillUsesTheNewestSettledPage() = runBlocking {
        val fresh = "KXBTC15M-26OCT181545-45"
        val calls = ArrayList<String>()
        val api = api { name, _ ->
            calls += name
            MarketsResponse(markets = listOf(MarketDto(ticker = fresh, result = "yes", status = "settled")))
        }
        val settled = ArrayList<String>()
        scorer(api, setOf(fresh), now - 30 * 60_000L) { t, _ -> settled += t }.maybeScore(now, 0L)
        assertEquals(listOf(fresh), settled)
        assertEquals(listOf("getMarkets"), calls)
    }

    @Test fun absentTickerPagesByMinCloseAndBacksOff() = runBlocking {
        val old = "KXBTC15M-26SEP010145-00"
        val calls = ArrayList<String>()
        var pages = 0
        val api = api { name, args ->
            val cursor = if (name == "getSettledSince") args.getOrNull(4)?.toString().orEmpty() else ""
            calls += if (cursor.isEmpty()) name else "$name:$cursor"
            when (name) {
                "getMarketsByTickers" -> MarketsResponse()
                "getSettledSince" -> {
                    pages++
                    if (pages == 1) {
                        MarketsResponse(cursor = "c2")
                    } else {
                        MarketsResponse(markets = listOf(MarketDto(ticker = old, result = "no", status = "settled")))
                    }
                }
                else -> error(name)
            }
        }
        val settled = ArrayList<String>()
        val scorer = scorer(api, setOf(old), now - 3 * 3_600_000L) { t, _ -> settled += t }
        scorer.maybeScore(now, 0L)
        assertTrue(settled.isEmpty())
        scorer.maybeScore(now + 30_000L, 0L)
        assertEquals(1, calls.count { it == "getMarketsByTickers" || it.startsWith("getMarketsByTickers:") })
        scorer.maybeScore(now + 120_000L, 0L)
        assertEquals(listOf(old), settled)
        assertTrue(calls.contains("getSettledSince:c2"))
    }

    private fun scorer(
        api: KalshiApi,
        tickers: Set<String>,
        closeMs: Long,
        onSettled: (String, String) -> Unit
    ): SettlementScorer {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        return SettlementScorer(
            api = api,
            logStore = PredictionLogStore(ctx),
            extraOpenTickers = { tickers },
            onMarketSettled = onSettled,
            closeTimeOf = { closeMs },
            nowMs = { now }
        )
    }

    private fun api(on: (String, Array<out Any?>) -> MarketsResponse): KalshiApi =
        Proxy.newProxyInstance(KalshiApi::class.java.classLoader, arrayOf(KalshiApi::class.java)) { _, method, args ->
            when (method.name) {
                "getMarkets", "getMarketsByTickers", "getSettledSince" -> on(method.name, args ?: emptyArray())
                else -> error(method.name)
            }
        } as KalshiApi
}
