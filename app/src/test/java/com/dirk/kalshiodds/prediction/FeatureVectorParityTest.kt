package com.dirk.kalshiodds.prediction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln

/**
 * Item 6: AI-net features must match training (1-candle HL, 8-minute volume),
 * not the last 8 WS calls or the market's all-time volume.
 */
class FeatureVectorParityTest {

    @Test
    fun volatilityIsLastOneMinuteRangeNotLastEightCalls() {
        val now = 900_000L
        val series = mutableListOf<FeatureHistory.Point>()
        // Older prints in the same minute sit at 0.30 — training's one-candle HL includes them.
        for (i in 0 until 12) {
            series.add(
                FeatureHistory.Point(
                    mid = 0.30,
                    volume = 8_000.0,
                    nowMs = now - 50_000 + i * 2_000L,
                    closeEpochMs = now + 60_000
                )
            )
        }
        // Last 8 WS calls hug 0.70 — the old phone bug used only this 0.07 spread.
        for (i in 0 until 8) {
            series.add(
                FeatureHistory.Point(
                    mid = 0.70 + i * 0.01,
                    volume = 10_000.0,
                    nowMs = now - 2_000 + i * 200L,
                    closeEpochMs = now + 60_000
                )
            )
        }
        val raw = FeatureVector.build(
            mid = 0.77,
            volume = 10_000.0,
            closeEpochMs = now + 60_000,
            nowMs = now,
            series = series,
            ticker = "KXBTC15M-X"
        )
        assertEquals(0.47f, raw[3], 1e-5f)
        assertTrue("must not use last-8-calls spread 0.07", kotlin.math.abs(raw[3] - 0.07f) > 0.2f)
    }

    @Test
    fun volumeNormUsesEightMinuteDeltaNotAllTimeTotal() {
        val now = 1_200_000L
        val series = (0..10).map { i ->
            FeatureHistory.Point(
                mid = 0.50,
                volume = 1_000.0 * (i + 1),
                nowMs = now - (10 - i) * 60_000L,
                closeEpochMs = now + 120_000
            )
        }
        val raw = FeatureVector.build(
            mid = 0.50,
            volume = 50_000.0,
            closeEpochMs = now + 120_000,
            nowMs = now,
            series = series,
            ticker = "KXBTC15M-X"
        )
        val win = series.filter { it.nowMs >= now - 8 * 60_000L }
        val delta = win.last().volume - win.first().volume
        val expected = (ln(1.0 + delta) / ln(1.0 + 1_000_000.0)).toFloat()
        assertEquals(expected, raw[1], 1e-5f)
        val allTime = (ln(1.0 + 50_000.0) / ln(1.0 + 1_000_000.0)).toFloat()
        assertTrue("must not use all-time volume $allTime", kotlin.math.abs(raw[1] - allTime) > 1e-4)
    }

    @Test
    fun emptyHistoryDoesNotFallBackToAllTimeVolume() {
        val raw = FeatureVector.build(
            mid = 0.40,
            volume = 10_000.0,
            closeEpochMs = 900_000L,
            nowMs = 300_000L,
            series = emptyList(),
            ticker = "KXBTC15M-X",
            openInterest = 500.0
        )
        assertEquals(0f, raw[1], 1e-6f)
        assertEquals(0.05f, raw[3], 1e-6f)
    }
}
