package com.dirk.kalshiodds.data.importing

import com.dirk.kalshiodds.data.local.results.ResultsExporter
import com.dirk.kalshiodds.data.local.results.ResultsBundle
import com.dirk.kalshiodds.data.local.results.ScoredSnapshotRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

class ResultsImporterTest {
    @Test
    fun appCsvRoundTripAndDedupe() {
        val bundle = ResultsBundle(
            snapshots = listOf(
                ScoredSnapshotRow(
                    ticker = "KXBTC15M-A",
                    series = "KXBTC15M",
                    side = "YES",
                    edgePp = 6.5,
                    fairPp = 42.0,
                    marketPp = 35.5,
                    regime = null,
                    uncertainty = null,
                    createdAtMs = 1_700_000_000_000L
                )
            )
        )
        val csv = ResultsExporter.csv(bundle)
        val first = ResultsImporter.parse(StringReader(csv))
        assertEquals(1, first.batch.snapshots.size)
        assertEquals(1, first.summary.imported)
        val seen = SeenKeys()
        seen.snapshots.add("KXBTC15M-A|1700000000000")
        val second = ResultsImporter.parse(StringReader(csv), seen)
        assertEquals(0, second.batch.snapshots.size)
        assertEquals(1, second.summary.skipped)
    }

    @Test
    fun jsonRoundTrip() {
        val bundle = ResultsBundle(
            snapshots = listOf(
                ScoredSnapshotRow(
                    ticker = "KXETH15M-B",
                    series = "KXETH15M",
                    side = "NO",
                    edgePp = -3.0,
                    fairPp = 40.0,
                    marketPp = 43.0,
                    regime = null,
                    uncertainty = null,
                    createdAtMs = 1_700_000_100_000L
                )
            )
        )
        val parsed = ResultsImporter.parse(StringReader(ResultsExporter.json(bundle)))
        assertEquals(1, parsed.batch.snapshots.size)
        assertEquals("KXETH15M-B", parsed.batch.snapshots[0].ticker)
    }

    @Test
    fun kalshiFillCsv() {
        val csv = """
            Fill ID,Ticker,Side,Count,Yes Price,Created Time
            f1,KXBTC15M-26SEP241700-00,yes,10,0.12,2026-09-24T21:00:00Z
            f1,KXBTC15M-26SEP241700-00,yes,10,0.12,2026-09-24T21:00:00Z
            f2,KXWTI15M-OIL,yes,5,0.40,2026-09-24T21:01:00Z
            f3,KXETH15M-26SEP241700-00,no,2,0.55,2026-09-24T21:02:00Z
        """.trimIndent()
        val parsed = ResultsImporter.parse(StringReader(csv))
        assertEquals(2, parsed.batch.fills.size)
        assertEquals(2, parsed.summary.skipped) // duplicate f1 + WTI dropped
        assertTrue(parsed.batch.fills.any { it.id == "f1" })
        assertTrue(parsed.batch.fills.any { it.ticker.startsWith("KXETH") })
    }

    @Test
    fun parseTsEpochAndIso() {
        assertEquals(1_700_000_000_000L, ResultsImporter.parseTs("1700000000000"))
        assertEquals(1_700_000_000_000L, ResultsImporter.parseTs("1700000000"))
        assertTrue(ResultsImporter.parseTs("2026-09-24T21:00:00Z")!! > 0L)
    }
}
