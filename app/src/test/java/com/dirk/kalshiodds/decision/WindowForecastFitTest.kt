package com.dirk.kalshiodds.decision

import com.dirk.kalshiodds.data.local.ledger.LedgerRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowForecastFitTest {
    @Test fun recorderRowsDoNotEnterTheDecisionFit() {
        val decision = row("gbm+pav", 0.42, "T1")
        val forecast = row(ScalpForecastRecorder.MODEL, 0.01, "T2").copy(marketMid = 0.99, settlementResult = "yes")
        val samples = DecisionRuntime.samplesFrom(listOf(forecast, decision))
        assertEquals(1, samples.size)
        assertEquals(0.42, samples.single().rawProbability, 1e-12)
        assertEquals("T1", samples.single().cluster)
        assertTrue(DecisionRuntime.samplesFrom(listOf(forecast)).isEmpty())
    }

    private fun row(model: String, raw: Double, ticker: String) = LedgerRow(
        timestampMs = 1_000L,
        modelVersion = model,
        ticker = ticker,
        series = "KXBTC15M",
        settlementRule = null,
        settlementSource = null,
        secondsRemaining = 400.0,
        spot = 100.0,
        targetStrike = 100.0,
        zDistance = 0.0,
        rawModelProb = raw,
        calibratedProb = null,
        marketMid = 0.40,
        bid = null,
        ask = null,
        spread = null,
        depthAtBest = null,
        imbalance = null,
        bookAgeMs = null,
        decision = "NO BET",
        sizeContracts = null,
        reasonCodes = "test",
        settlementResult = "no",
        settledAtMs = 2_000L,
        finalProb = raw
    )
}
