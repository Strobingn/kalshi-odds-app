package com.dirk.kalshiodds.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dirk.kalshiodds.data.local.ledger.LedgerRow
import com.dirk.kalshiodds.data.local.results.SqliteResultsStore
import com.dirk.kalshiodds.decision.DecisionRuntime
import com.dirk.kalshiodds.decision.ScalpForecastRecorder
import com.dirk.kalshiodds.decision.ScalpTicker
import com.dirk.kalshiodds.decision.WindowCalibration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CalibrationLedgerQueryTest {
    @Test fun queryKeepsTheLastForecastBeforeClose() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.deleteDatabase(SqliteResultsStore.DB_NAME)
        val store = SqliteResultsStore(ctx)
        val low = "KXBTC15M-26OCT181530-30"
        val high = "KXBTC15M-26OCT181530-31"
        val close = ScalpTicker.closeMs(low)!!
        store.insertLedger(ledger(low, close - 80_000, close, 0.40, "yes"))
        store.insertLedger(ledger(high, close - 4_000, close, 0.71, "no"))
        store.insertLedger(ledger(low, close + 5_000, close, 0.99, "yes"))
        store.insertLedger(ledger("KXBTCD-26OCT1817", close - 4_000, close, 0.2, "yes"))
        // A later decision-pipeline row must not beat the window forecast.
        store.insertLedger(ledger(high, close - 1_000, close, 0.13, "yes", model = "decision+pav"))
        val rows = store.calibrationLedgerRows(close - 86_400_000L, close + 1_000)
        val samples = WindowCalibration.fromLedger(rows, close + 1_000, close - 86_400_000L)
        assertEquals(1, samples.size)
        assertEquals(71.0, samples.single().aiUp, 1e-9)
        assertEquals(false, samples.single().settledUp)
        assertTrue(samples.single().coin == "BTC")
    }

    @Test fun forecastOlderThanTheLeadWindowIsExcluded() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.deleteDatabase(SqliteResultsStore.DB_NAME)
        val store = SqliteResultsStore(ctx)
        val ticker = "KXBTC15M-26OCT181530-30"
        val close = ScalpTicker.closeMs(ticker)!!
        val early = close - 20 * 60_000L
        store.insertLedger(ledger(ticker, early, close, 0.42, "yes"))
        assertTrue(store.calibrationLedgerRows(close - 1_000L, close + 1_000L).isEmpty())
        assertEquals(1, store.calibrationLedgerRows(early - 1_000L, close + 1_000L).size)
    }

    @Test fun settledLedgerSkipsWindowForecasts() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.deleteDatabase(SqliteResultsStore.DB_NAME)
        val store = SqliteResultsStore(ctx)
        val ticker = "KXBTC15M-26OCT181530-30"
        val close = ScalpTicker.closeMs(ticker)!!
        store.insertLedger(ledger(ticker, close - 50_000, close, 0.40, "no", model = "decision+pav"))
        store.insertLedger(ledger(ticker, close - 10_000, close, 0.99, "yes"))
        store.insertLedger(ledger(ticker, close - 5_000, close, 0.01, "yes"))
        val rows = store.settledLedger(0L, 10)
        assertEquals(listOf("decision+pav"), rows.map { it.modelVersion })
        val samples = DecisionRuntime.samplesFrom(store.recentLedger(10))
        assertEquals(1, samples.size)
        assertEquals(0.40, samples.single().rawProbability, 1e-9)
    }

    @Test fun pruneDeletesOnlyOldWindowForecasts() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.deleteDatabase(SqliteResultsStore.DB_NAME)
        val store = SqliteResultsStore(ctx)
        val ticker = "KXBTC15M-26OCT181530-30"
        val close = ScalpTicker.closeMs(ticker)!!
        val now = close
        val oldAt = now - SqliteResultsStore.FORECAST_KEEP_MS - 86_400_000L
        store.insertLedger(ledger(ticker, oldAt, oldAt, 0.2, "yes", model = "decision+pav"))
        store.insertLedger(ledger(ticker, oldAt + 1, oldAt, 0.3, "no"))
        store.insertLedger(ledger(ticker, now - 86_400_000L, close, 0.6, "yes"))
        assertEquals(1, store.pruneWindowForecasts(now))
        val left = store.recentLedger(10)
        assertEquals(setOf("decision+pav", ScalpForecastRecorder.MODEL), left.map { it.modelVersion }.toSet())
        assertTrue(left.none { it.modelVersion == ScalpForecastRecorder.MODEL && it.timestampMs == oldAt + 1 })
        assertTrue(left.any { it.modelVersion == "decision+pav" && it.timestampMs == oldAt })
    }

    @Test fun settleLedgerStoresLowercase() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.deleteDatabase(SqliteResultsStore.DB_NAME)
        val store = SqliteResultsStore(ctx)
        val ticker = "KXBTC15M-26OCT181530-30"
        val close = ScalpTicker.closeMs(ticker)!!
        store.insertLedger(ledger(ticker, close - 1_000, close, 0.4, "yes").copy(settlementResult = null, settledAtMs = null))
        assertEquals(1, store.settleLedger(ticker, "YES", close))
        assertEquals("yes", store.recentLedger(1).single().settlementResult)
    }

    private fun ledger(ticker: String, at: Long, close: Long, p: Double, result: String, model: String = ScalpForecastRecorder.MODEL) = LedgerRow(
        timestampMs = at,
        modelVersion = model,
        ticker = ticker,
        series = ticker.substringBefore('-'),
        settlementRule = null,
        settlementSource = null,
        secondsRemaining = null,
        spot = null,
        targetStrike = null,
        zDistance = null,
        rawModelProb = p,
        calibratedProb = null,
        marketMid = 0.5,
        bid = null,
        ask = null,
        spread = null,
        depthAtBest = null,
        imbalance = null,
        bookAgeMs = null,
        decision = "FORECAST",
        sizeContracts = null,
        reasonCodes = "test",
        settlementResult = result,
        settledAtMs = close,
        finalProb = p,
        closeTimeMs = close
    )
}
