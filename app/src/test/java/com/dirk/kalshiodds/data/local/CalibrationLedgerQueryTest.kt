package com.dirk.kalshiodds.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dirk.kalshiodds.data.local.ledger.LedgerRow
import com.dirk.kalshiodds.data.local.results.SqliteResultsStore
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
        val rows = store.calibrationLedgerRows(close - 86_400_000L, close + 1_000)
        val samples = WindowCalibration.fromLedger(rows, close + 1_000, close - 86_400_000L)
        assertEquals(1, samples.size)
        assertEquals(71.0, samples.single().aiUp, 1e-9)
        assertEquals(false, samples.single().settledUp)
        assertTrue(samples.single().coin == "BTC")
    }

    private fun ledger(ticker: String, at: Long, close: Long, p: Double, result: String) = LedgerRow(
        timestampMs = at,
        modelVersion = "t",
        ticker = ticker,
        series = ticker.substringBefore('-'),
        closeTimeMs = close,
        finalProb = p,
        marketMid = 0.5,
        settlementResult = result,
        settledAtMs = close
    )
}
