package com.dirk.kalshiodds.prediction.ledger

import com.dirk.kalshiodds.prediction.PredictionLogEntry
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerReportTest {
    private val day = 86_400_000L

    private fun row(i: Int, model: Double, mid: Double, yes: Boolean, dayIdx: Int, bet: Boolean? = null) = LedgerRow(
        ticker = "KXBTC15M-T$i", series = "KXBTC15M", calledAtMs = dayIdx * day, closeTimeMs = null,
        modelYes = model, marketMid = mid, entryAsk = null, pickedSide = null, edgePp = null,
        uncertainty = null, regime = null, tteBucket = null, wouldBet = bet, modelVersion = "v1",
        outcome = if (yes) "yes" else "no", settledAtMs = dayIdx * day + i
    )

    /** Outcomes drawn from [truth]; model and market see it with different noise. */
    private fun sample(n: Int, days: Int, modelNoise: Double, marketNoise: Double): List<LedgerRow> {
        val rnd = Random(1)
        return (0 until n).map { i ->
            val p = 0.1 + 0.8 * rnd.nextDouble()
            val yes = rnd.nextDouble() < p
            fun noisy(s: Double) = (p + (rnd.nextDouble() - 0.5) * 2 * s).coerceIn(0.01, 0.99)
            row(i, noisy(modelNoise), noisy(marketNoise), yes, i % days)
        }
    }

    @Test
    fun emptyLedgerHasNoReport() {
        assertNull(LedgerReport.compute(emptyList()))
    }

    @Test
    fun brierIsMeanSquaredError() {
        val r = LedgerReport.compute(listOf(row(1, 0.8, 0.6, true, 0), row(2, 0.3, 0.5, false, 1)))!!
        assertEquals((0.04 + 0.09) / 2, r.modelBrier, 1e-12)
        assertEquals((0.16 + 0.25) / 2, r.marketBrier, 1e-12)
        assertEquals(LedgerReport.Verdict.NOT_ENOUGH_DATA, r.verdict)
    }

    @Test
    fun tooFewDaysIsNeverAWin() {
        val r = LedgerReport.compute(sample(600, 3, modelNoise = 0.0, marketNoise = 0.3))!!
        assertTrue(r.diff < 0)
        assertEquals(LedgerReport.Verdict.NOT_ENOUGH_DATA, r.verdict)
    }

    @Test
    fun aClearlyBetterMarketIsReported() {
        val r = LedgerReport.compute(sample(2_000, 10, modelNoise = 0.35, marketNoise = 0.02))!!
        assertEquals(LedgerReport.Verdict.MARKET_BETTER, r.verdict)
        assertTrue(r.diffLow!! > 0)
    }

    @Test
    fun aClearlyBetterModelIsReported() {
        val r = LedgerReport.compute(sample(2_000, 10, modelNoise = 0.02, marketNoise = 0.35))!!
        assertEquals(LedgerReport.Verdict.MODEL_BETTER, r.verdict)
        assertTrue(r.diffHigh!! < 0)
    }

    @Test
    fun equalForecastsShowNoDifference() {
        val same = sample(2_000, 10, modelNoise = 0.1, marketNoise = 0.1).map { it.copy(modelYes = it.marketMid) }
        val r = LedgerReport.compute(same)!!
        assertEquals(0.0, r.diff, 1e-12)
        assertEquals(LedgerReport.Verdict.NO_DIFFERENCE, r.verdict)
    }

    @Test
    fun bucketsAreCalibrationTable() {
        val rows = listOf(row(1, 0.72, 0.5, true, 0), row(2, 0.78, 0.5, false, 0), row(3, 0.15, 0.5, false, 0))
        val b = LedgerReport.buckets(rows) { it.modelYes }
        assertEquals(listOf(10, 70), b.map { it.lowPct })
        assertEquals(2, b[1].n)
        assertEquals(0.75, b[1].meanForecast, 1e-12)
        assertEquals(0.5, b[1].actualYes, 1e-12)
    }

    @Test
    fun betSubsetIsScoredSeparately() {
        val r = LedgerReport.compute(listOf(row(1, 0.9, 0.6, true, 0, bet = true), row(2, 0.4, 0.5, false, 0, bet = false)))!!
        assertEquals(1, r.betN)
        assertEquals(0.01, r.betModelBrier!!, 1e-12)
    }

    @Test
    fun rowsComeOnlyFromSettledYesNoEntries() {
        val e = PredictionLogEntry("KXBTC15M-X", "KXBTC15M", 0.7, 0.3, 0.6, 1L, 2L)
        assertNull(LedgerRow.from(e, "v"))
        assertNull(LedgerRow.from(e.copy(outcome = "void"), "v"))
        assertNull(LedgerRow.from(e.copy(outcome = "yes", predictedYes = Double.NaN), "v"))
        val r = LedgerRow.from(e.copy(outcome = "YES", settledAtMs = 9L, wouldAlert = true), "v1.7")
        assertNotNull(r)
        assertEquals("yes", r!!.outcome)
        assertEquals(9L, r.settledAtMs)
        assertEquals(true, r.wouldBet)
    }
}
