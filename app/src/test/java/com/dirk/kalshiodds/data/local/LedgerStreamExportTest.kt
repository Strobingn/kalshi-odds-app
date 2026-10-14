package com.dirk.kalshiodds.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dirk.kalshiodds.data.local.ledger.LedgerRow
import com.dirk.kalshiodds.data.local.results.ResultsFileExport
import com.dirk.kalshiodds.data.local.results.SqliteResultsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 0.3.69: ledger export streams keyset pages into a gzip file; heap stays flat however big the CSV gets. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LedgerStreamExportTest {
    @Test fun largeLedgerExportsUnderBoundedMemory() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.deleteDatabase(SqliteResultsStore.DB_NAME)
        val store = SqliteResultsStore(ctx)
        val n = 60_000
        val pad = "x".repeat(300) // fat reason text → ~0.5 KB/row, ~30 MB of CSV
        val db = store.writableForTest()
        db.beginTransaction()
        try {
            for (i in 1..n) store.insertLedger(LedgerRow(timestampMs = i.toLong(), modelVersion = "t", ticker = "KXBTC15M-$i", series = "KXBTC15M",
                settlementRule = "cf", settlementSource = "cfbenchmarks_value", secondsRemaining = 40.0, spot = 100.0, targetStrike = 109.0,
                zDistance = 0.2, rawModelProb = 0.6, calibratedProb = null, marketMid = 0.55, bid = 0.54, ask = 0.56, spread = 0.02,
                depthAtBest = 20.0, imbalance = 0.1, bookAgeMs = 100L, decision = "ABSTAIN", sizeContracts = 0.0,
                reasonCodes = "r,$pad", cfIndexId = "BRTI", cfValue = 100.0))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }

        val rt = Runtime.getRuntime()
        fun used(): Long { System.gc(); System.gc(); return rt.totalMemory() - rt.freeMemory() }
        val base = used()
        var peak = 0L
        var chars = 0L
        var rows = 0L
        val f = java.io.File(ctx.cacheDir, "ledger-test.csv.gz")
        ResultsFileExport.writeTo(f, gzip = true) { w ->
            val counting = object : Appendable {
                private var lines = 0L
                override fun append(c: Char): Appendable { chars++; w.write(c.code); if (c == '\n' && ++lines % 10_000 == 0L) peak = maxOf(peak, used() - base); return this }
                override fun append(s: CharSequence?): Appendable { val t = s ?: "null"; chars += t.length; w.append(t); return this }
                override fun append(s: CharSequence?, a: Int, b: Int): Appendable = append((s ?: "null").subSequence(a, b))
            }
            rows = store.writeLedgerCsv(counting)
        }
        assertEquals(n.toLong(), rows)
        assertTrue("csv chars $chars", chars > 25_000_000)
        // a whole-CSV String would need ≥ 2 bytes/char ≈ 50+ MB; streaming keeps the heap growth to a few pages.
        assertTrue("peak heap growth ${peak / 1_048_576} MB", peak < 16L * 1_048_576)
        var lines = 0
        java.util.zip.GZIPInputStream(f.inputStream()).bufferedReader().useLines { s -> s.forEach { lines++ } }
        assertEquals(n + 1, lines)
        println("LEDGER_STREAM rows=$rows chars=$chars gz=${f.length()} peakGrowthMB=${peak / 1_048_576}")
    }
}
