package com.dirk.kalshiodds.signal.feedback

import com.dirk.kalshiodds.prediction.PredictionLogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class ScorecardBreakdownTest {

    @Test
    fun perCoinBucketsAndNotEnoughData() {
        val rows = (0 until 12).map { i ->
            entry("KXBTC15M-$i", "KXBTC15M", 1_700_000_000_000L + i)
        } + (0 until 5).map { i ->
            entry("KXETH15M-$i", "KXETH15M", 1_700_000_000_000L + i)
        }
        val coins = ScorecardMetrics.coinBreakdowns(rows)
        val btc = coins.first { it.key == "BTC" }
        val eth = coins.first { it.key == "ETH" }
        val sol = coins.first { it.key == "SOL" }
        assertEquals(12, btc.n)
        assertFalse(btc.enoughData)
        assertTrue(btc.honestLabel.contains("Not enough data"))
        assertEquals(5, eth.n)
        assertEquals(0, sol.n)
        assertFalse(sol.enoughData)
        assertEquals(listOf("BTC", "SOL", "ETH"), coins.map { it.key })
    }

    @Test
    fun enoughDataAtTwenty() {
        val rows = (0 until 20).map { i ->
            entry("KXSOL15M-$i", "KXSOL15M", 1_700_000_000_000L + i)
        }
        val sol = ScorecardMetrics.coinBreakdowns(rows).first { it.key == "SOL" }
        assertTrue(sol.enoughData)
        assertEquals(20, sol.n)
        assertTrue(sol.hitRate != null)
        assertTrue(sol.modelBrier != null)
        assertTrue(sol.marketBrier != null)
    }

    @Test
    fun fourHourEtBuckets() {
        val zone = ZoneId.of("America/New_York")
        val noonEt = java.time.ZonedDateTime.of(2026, 9, 25, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val fourEt = java.time.ZonedDateTime.of(2026, 9, 25, 16, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("12-16", ScorecardMetrics.etBucketKey(noonEt, zone))
        assertEquals("16-20", ScorecardMetrics.etBucketKey(fourEt, zone))
        val rows = (0 until 8).map { i ->
            entry("KXBTC15M-$i", "KXBTC15M", noonEt + i)
        }
        val buckets = ScorecardMetrics.timeOfDayBreakdowns(rows, zone)
        assertEquals(6, buckets.size)
        val noon = buckets.first { it.key == "12-16" }
        assertEquals(8, noon.n)
        assertFalse(noon.enoughData)
    }

    private fun entry(ticker: String, series: String, at: Long) = PredictionLogEntry(
        ticker = ticker,
        series = series,
        predictedYes = 0.60,
        predictedNo = 0.40,
        marketMid = 0.55,
        timestampMs = at,
        closeTimeMs = at,
        outcome = "yes",
        score = 1,
        predictedSide = "YES",
        edgePp = 5.0,
        settledAtMs = at
    )
}
