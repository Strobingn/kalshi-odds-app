package com.dirk.kalshiodds.prediction

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Item 3: old scorecard rows stay readable after the raw-blend columns land.
 * New fields default; displayed [predictedYes] is never dropped.
 */
class PredictionLogRawMigrationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun oldRowWithoutRawColumnsSurvives() {
        val raw = """
            {
              "ticker":"KXBTC15M-OLD",
              "series":"KXBTC15M",
              "predictedYes":0.72,
              "predictedNo":0.28,
              "marketMid":0.55,
              "timestampMs":1700000000000,
              "closeTimeMs":1700000900000,
              "outcome":"yes",
              "score":1,
              "brier":0.0784,
              "predictedSide":"YES",
              "edgePp":17.0
            }
        """.trimIndent()
        val e = json.decodeFromString(PredictionLogEntry.serializer(), raw)
        assertEquals("KXBTC15M-OLD", e.ticker)
        assertEquals(0.72, e.predictedYes, 1e-9)
        assertEquals(0.28, e.predictedNo, 1e-9)
        assertEquals("yes", e.outcome)
        assertEquals(1, e.score)
        assertNull(e.rawPredictedYes)
        assertNull(e.displayedYes)
        assertTrue(e.calSamples.isEmpty())
    }

    @Test
    fun newRowKeepsDisplayedAndRaw() {
        val e = PredictionLogEntry(
            ticker = "KXBTC15M-NEW",
            series = "KXBTC15M",
            predictedYes = 0.61,
            predictedNo = 0.39,
            marketMid = 0.50,
            timestampMs = 1L,
            closeTimeMs = 2L,
            rawPredictedYes = 0.70,
            displayedYes = 0.61,
            tteSeconds = 400,
            calSamples = listOf(CalSample(0.70, 400, "m10_5"))
        )
        val again = json.decodeFromString(
            PredictionLogEntry.serializer(),
            json.encodeToString(PredictionLogEntry.serializer(), e)
        )
        assertEquals(0.70, again.rawPredictedYes!!, 1e-9)
        assertEquals(0.61, again.displayedYes!!, 1e-9)
        assertEquals(0.61, again.predictedYes, 1e-9)
        assertEquals(1, again.calSamples.size)
        assertEquals("m10_5", again.calSamples[0].bucket)
    }
}
