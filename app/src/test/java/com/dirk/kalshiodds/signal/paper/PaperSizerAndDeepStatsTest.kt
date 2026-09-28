package com.dirk.kalshiodds.signal.paper

import com.dirk.kalshiodds.prediction.PredictionLogEntry
import com.dirk.kalshiodds.signal.feedback.ScorecardDeepStats
import com.dirk.kalshiodds.signal.trade.KalshiFee
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaperSizerAndDeepStatsTest {

    // --- Kelly sizing -------------------------------------------------------------

    @Test
    fun kellyFractionFormula() {
        // p = 0.60, q = 0.50 → (0.60 − 0.50) / 0.50 = 0.20
        assertEquals(0.20, PaperSizer.kellyFraction(0.60, 0.50), 1e-12)
        assertEquals(0.0, PaperSizer.kellyFraction(0.40, 0.50), 0.0)
    }

    @Test
    fun sizesByEdgeNotAFixedClip() {
        val big = PaperSizer.size(winProb = 0.80, price = 0.50, equityUsd = 1_000.0, cashUsd = 1_000.0)!!
        val small = PaperSizer.size(winProb = 0.56, price = 0.50, equityUsd = 1_000.0, cashUsd = 1_000.0)!!
        assertTrue("strong edge stakes far more than $5", big.costUsd > 200.0)
        assertTrue(small.costUsd < big.costUsd)
        // Never spends more than the half-Kelly target.
        assertTrue(big.costUsd <= big.targetUsd + 1e-9)
        assertEquals(KalshiFee.totalCost(big.contracts, 0.50), big.costUsd, 1e-9)
    }

    @Test
    fun noEdgeAfterFeesMeansNoBet() {
        // 51% at a 50¢ ask: the ~1.75¢ taker fee eats the edge.
        assertNull(PaperSizer.size(winProb = 0.51, price = 0.50, equityUsd = 100.0, cashUsd = 100.0))
        assertNull(PaperSizer.size(winProb = 0.30, price = 0.50, equityUsd = 100.0, cashUsd = 100.0))
    }

    @Test
    fun cappedByCash() {
        val s = PaperSizer.size(winProb = 0.95, price = 0.40, equityUsd = 10_000.0, cashUsd = 20.0)!!
        assertTrue(s.costUsd <= 20.0 + 1e-9)
    }

    @Test
    fun paperAlertFillUsesKellyAndPaysTheFee() {
        val book = PaperBook(PaperBookState(startingUsd = 100.0, cashUsd = 100.0), idFactory = { "id" }, nowMs = { 1L })
        val alert = com.dirk.kalshiodds.signal.model.SignalAlert(
            id = "a",
            ticker = "KXBTC15M-26SEP281200-00",
            series = "KXBTC15M",
            deltaPp = 20.0,
            fairValuePp = 70.0,
            marketMidPp = 50.0,
            reason = "test",
            createdAtMs = 1L,
            receiveElapsedNanos = 0L,
            predictedSide = "YES",
            confidence = 0.8
        )
        val fill = book.considerAlert(alert, ask = 0.50, enabled = true)
        assertNotNull(fill)
        // half-Kelly of (0.70 − ~0.5175)/(1 − ~0.5175) ≈ 18.9% of $100 ≈ $18 — not the old $5.
        assertTrue("stake ${fill!!.stakeUsd}", fill.stakeUsd > 10.0)
        assertEquals(KalshiFee.totalCost(fill.contracts, 0.50), fill.stakeUsd, 1e-9)
        assertEquals(100.0 - fill.stakeUsd, book.snapshot().cashUsd, 1e-9)
    }

    // --- deep stats ---------------------------------------------------------------

    private fun fill(id: String, won: Boolean, pnl: Double, stake: Double, ai: Double?, mkt: Double?, t: Long) = PaperFill(
        id = id, ticker = "KXBTC15M-$id", side = "YES", stakeUsd = stake, contracts = 10, limitPrice = 0.5,
        source = "AI signal", createdAtMs = t, settled = true, outcome = if (won) "yes" else "no", won = won,
        pnlUsd = pnl, note = "", aiPct = ai, marketPct = mkt, pickSource = "AI alert"
    )

    @Test
    fun deepStatsAccountRiskAndStreaks() {
        val fills = listOf(
            fill("1", true, 5.0, 5.0, 70.0, 50.0, 1_000L),
            fill("2", true, 5.0, 5.0, 65.0, 50.0, 2_000L),
            fill("3", false, -5.0, 5.0, 60.0, 50.0, 3_000L),
            fill("4", false, -5.0, 5.0, 55.0, 50.0, 4_000L),
            fill("5", false, -5.0, 5.0, 52.0, 50.0, 5_000L)
        )
        val state = PaperBookState(startingUsd = 100.0, cashUsd = 95.0, fills = fills)
        val sections = ScorecardDeepStats.compute(state, emptyList())
        val all = sections.flatMap { s -> s.rows.map { "${s.title}|${it.label}" to it.value } }.toMap()
        assertEquals("5 / 2 / 3 / 0", all["Paper account|Settled / wins / losses / void"])
        assertEquals("40.0%", all["Paper account|Win rate"])
        assertEquals("0.67", all["Paper account|Profit factor (gross win ÷ gross loss)"])
        assertEquals("−$15.00", all["Paper risk & significance|Max drawdown (realized)"])
        assertEquals("2 / 3", all["Paper risk & significance|Longest win / loss streak"])
        assertEquals("3 losses", all["Paper risk & significance|Current streak"])
        assertTrue(sections.any { it.title == "AI win % vs actual (paper)" })
        assertTrue(sections.any { it.title == "Engine state" })
    }

    @Test
    fun deepStatsSignalLogComparesModelToMarket() {
        val entries = (0 until 20).map { i ->
            PredictionLogEntry(
                ticker = "KXBTC15M-$i", series = "KXBTC15M", predictedYes = if (i % 2 == 0) 0.8 else 0.2,
                predictedNo = 0.0, marketMid = 0.5, timestampMs = i.toLong(), closeTimeMs = null,
                outcome = if (i % 2 == 0) "yes" else "no", tteBucket = "LATE"
            )
        }
        val sections = ScorecardDeepStats.compute(PaperBookState(), entries)
        val sig = sections.first { it.title.startsWith("Signal log") }
        val brier = sig.rows.first { it.label == "Brier model / market" }
        assertEquals("0.0400 / 0.2500", brier.value)
        assertEquals(ScorecardDeepStats.Tone.GOOD, brier.tone)
        assertTrue(sections.any { it.title.startsWith("Reliability") })
    }
}
