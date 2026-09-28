package com.dirk.kalshiodds.signal.feedback

import com.dirk.kalshiodds.prediction.PredictionLogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class ScorecardBreakdownTest {

    @Test
    fun perCoinBucketsAreBitcoinOnly() {
        val rows = (0 until 12).map { i ->
            entry("KXBTC15M-$i", "KXBTC15M", 1_700_000_000_000L + i)
        } + (0 until 5).map { i ->
            entry("KXETH15M-$i", "KXETH15M", 1_700_000_000_000L + i)
        } + (0 until 8).map { i ->
            entry("KXSOL15M-$i", "KXSOL15M", 1_700_000_000_000L + i)
        }
        val coins = ScorecardMetrics.coinBreakdowns(rows)
        assertEquals(listOf("BTC"), coins.map { it.key })
        val btc = coins.single { it.key == "BTC" }
        assertEquals(12, btc.n)
        assertTrue(btc.enoughData)
        assertFalse(coins.any { it.key == "ETH" || it.key == "SOL" })
    }

    @Test
    fun enoughDataAtFivePerSlot() {
        val rows = (0 until 5).map { i ->
            entry("KXBTC15M-$i", "KXBTC15M", 1_700_000_000_000L + i)
        }
        val btc = ScorecardMetrics.coinBreakdowns(rows).single { it.key == "BTC" }
        assertTrue(btc.enoughData)
        assertEquals(5, btc.n)
        assertTrue(btc.hitRate != null)
        assertTrue(btc.modelBrier != null)
        assertTrue(btc.marketBrier != null)
        val four = ScorecardMetrics.coinBreakdowns(rows.take(4)).single { it.key == "BTC" }
        assertFalse(four.enoughData)
        assertTrue(four.honestLabel.contains("Not enough data (4/5)"))
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
        assertTrue(noon.enoughData)
        val thin = ScorecardMetrics.timeOfDayBreakdowns(rows.take(4), zone).first { it.key == "12-16" }
        assertFalse(thin.enoughData)
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
