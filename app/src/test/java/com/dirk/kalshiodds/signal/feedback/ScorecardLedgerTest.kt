package com.dirk.kalshiodds.signal.feedback

import com.dirk.kalshiodds.data.local.archive.SettledWindowRow
import com.dirk.kalshiodds.prediction.PredictionLogEntry
import com.dirk.kalshiodds.signal.paper.PaperFill
import com.dirk.kalshiodds.signal.paper.PaperTileBuy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScorecardLedgerTest {

    @Test
    fun winRateAndPnlSplitAiFromManualIncludingFees() {
        val entries = listOf(
            pick("KXBTC15M-A", "YES", won = true, at = 1_000L, yes = 0.70, mid = 0.40),
            pick("KXBTC15M-B", "YES", won = true, at = 2_000L, yes = 0.62, mid = 0.45),
            pick("KXBTC15M-C", "NO", won = false, at = 3_000L, yes = 0.28, mid = 0.60),
            pick("KXBTC15M-NOBET", "NO_BET", won = true, at = 4_000L, yes = 0.61, mid = 0.52)
        )
        val fills = listOf(
            fill("ai-a", "KXBTC15M-A", "YES", 0.30, 10, 3.00, 5.00, "AI hunter", 1_000L, "Paper · fee $0.21"),
            fill("ai-b", "KXBTC15M-B", "YES", 0.40, 8, 3.20, 3.00, "AI ticket", 2_000L, "Paper · fee $0.18"),
            fill("ai-c", "KXBTC15M-C", "NO", 0.66, 5, 3.30, -2.00, "AI signal", 3_000L, "Paper · fee $0.15"),
            fill("man-w", "KXBTC15M-M1", "YES", 0.34, 29, 10.00, 6.50, PaperTileBuy.SOURCE, 5_000L, "Paper tile $10 · fee $0.40"),
            fill("man-l", "KXBTC15M-M2", "NO", 0.72, 13, 10.00, -4.00, PaperTileBuy.SOURCE, 6_000L, "Paper tile $10 · fee $0.28")
        )
        val snap = ScorecardLedger.of(entries, fills)

        assertEquals(2, snap.ai.wins)
        assertEquals(1, snap.ai.losses)
        assertEquals(3, snap.ai.settledCount)
        assertEquals(2.0 / 3.0, snap.ai.hitRate!!, 1e-9)
        assertEquals(8.00, snap.ai.money.wonUsd, 1e-9)
        assertEquals(2.00, snap.ai.money.lostUsd, 1e-9)
        assertEquals(6.00, snap.ai.money.pnlUsd, 1e-9)

        assertEquals(1, snap.manual.wins)
        assertEquals(1, snap.manual.losses)
        assertEquals(2, snap.manual.settledCount)
        assertEquals(0.5, snap.manual.hitRate!!, 1e-9)
        assertEquals(6.50, snap.manual.money.wonUsd, 1e-9)
        assertEquals(4.00, snap.manual.money.lostUsd, 1e-9)

        assertEquals(3, snap.combined.wins)
        assertEquals(2, snap.combined.losses)
        assertEquals(5, snap.combined.settledCount)
        assertEquals(0.6, snap.combined.hitRate!!, 1e-9)
        assertEquals(14.50, snap.combined.money.wonUsd, 1e-9)
        assertEquals(6.00, snap.combined.money.lostUsd, 1e-9)
        assertEquals(8.50, snap.combined.money.pnlUsd, 1e-9)
        assertEquals(6.50, snap.combined.money.biggestWinUsd!!, 1e-9)
        assertEquals(-4.00, snap.combined.money.biggestLossUsd!!, 1e-9)
        assertEquals(14.50 / 3.0, snap.combined.money.avgWinUsd!!, 1e-9)
        assertEquals(-6.00 / 2.0, snap.combined.money.avgLossUsd!!, 1e-9)
        assertEquals("1L", snap.combined.streak)

        assertEquals(1, snap.noBetWouldHave.settledCount)
        assertEquals(1, snap.noBetWouldHave.wins)
        assertTrue(snap.picks.any { it.noBetWouldHave && it.ticker.contains("NOBET") })

        val matched = snap.picks.first { it.ticker == "KXBTC15M-A" }
        assertEquals(0.21, matched.feeUsd!!, 1e-9)
        assertEquals(10, matched.contracts)
        assertEquals(0.30, matched.entryAsk!!, 1e-9)
        assertEquals(70.0, matched.aiPct!!, 1e-6)
        assertEquals(40.0, matched.marketPct!!, 1e-6)
    }

    @Test
    fun priceBandsAndSideSplitUseStoredAsks() {
        val fills = listOf(
            fill("u1", "KXBTC15M-U1", "YES", 0.30, 10, 3.00, 1.00, "AI hunter", 1L, "fee $0.10"),
            fill("u2", "KXBTC15M-U2", "YES", 0.40, 10, 4.00, 1.00, "AI hunter", 2L, "fee $0.10"),
            fill("d1", "KXBTC15M-D1", "NO", 0.66, 10, 6.60, -1.00, PaperTileBuy.SOURCE, 3L, "fee $0.10"),
            fill("d2", "KXBTC15M-D2", "NO", 0.80, 10, 8.00, -1.00, PaperTileBuy.SOURCE, 4L, "fee $0.10")
        )
        val entries = listOf(
            pick("KXBTC15M-U1", "YES", won = true, at = 1L, yes = 0.80),
            pick("KXBTC15M-U2", "YES", won = true, at = 2L, yes = 0.58)
        )
        val snap = ScorecardLedger.of(entries, fills)
        val up = snap.bySide.first { it.key == "UP" }
        val down = snap.bySide.first { it.key == "DOWN" }
        assertEquals(2, up.settledCount)
        assertEquals(2, up.wins)
        assertEquals(2.00, up.pnlUsd, 1e-9)
        assertEquals(2, down.settledCount)
        assertEquals(0, down.wins)
        assertEquals(-2.00, down.pnlUsd, 1e-9)

        assertEquals(1, snap.byPrice.first { it.key == "le31" }.settledCount)
        assertEquals(1, snap.byPrice.first { it.key == "32-50" }.settledCount)
        assertEquals(1, snap.byPrice.first { it.key == "51-70" }.settledCount)
        assertEquals(1, snap.byPrice.first { it.key == "gt70" }.settledCount)

        assertEquals(0, snap.byConfidence.first { it.key == "le55" }.settledCount)
        assertEquals(1, snap.byConfidence.first { it.key == "56-70" }.settledCount)
        assertEquals(1, snap.byConfidence.first { it.key == "71-85" }.settledCount)
    }

    @Test
    fun confidenceBandsAndStrikeIfStored() {
        val entries = listOf(
            pick("KXBTC15M-HI", "YES", won = true, at = 1L, yes = 0.90),
            pick("KXBTC15M-MID", "YES", won = false, at = 2L, yes = 0.58)
        )
        val fills = listOf(
            fill("hi", "KXBTC15M-HI", "YES", 0.35, 5, 1.75, 3.25, "AI hunter", 1L, "fee $0.08"),
            fill("mid", "KXBTC15M-MID", "YES", 0.48, 5, 2.40, -2.40, "AI hunter", 2L, "fee $0.09")
        )
        val windows = listOf(
            SettledWindowRow("KXBTC15M-HI", "KXBTC15M", "yes", strikeUsd = 84_144.0)
        )
        val snap = ScorecardLedger.of(entries, fills, windows)
        assertEquals(1, snap.byConfidence.first { it.key == "gt85" }.settledCount)
        assertEquals(1, snap.byConfidence.first { it.key == "56-70" }.settledCount)
        assertEquals(0, snap.byConfidence.first { it.key == "le55" }.settledCount)
        val hi = snap.picks.first { it.ticker == "KXBTC15M-HI" }
        assertEquals(84_144.0, hi.strikeUsd!!, 1e-6)
        assertNull(hi.finalUsd)
        assertEquals(2, snap.cumulativePnl.size)
        assertEquals(1L, snap.cumulativePnl[0].first)
        assertEquals(3.25, snap.cumulativePnl[0].second, 1e-9)
        assertEquals(2L, snap.cumulativePnl[1].first)
        assertEquals(0.85, snap.cumulativePnl[1].second, 1e-9)
        assertEquals("1L", snap.ai.streak)
    }

    @Test
    fun sourceSplitNeverFabricatesPnl() {
        assertTrue(ScorecardLedger.isAiSource("AI hunter"))
        assertTrue(ScorecardLedger.isAiSource("AI ticket"))
        assertTrue(ScorecardLedger.isAiSource("AI signal"))
        assertFalse(ScorecardLedger.isAiSource(PaperTileBuy.SOURCE))
        assertFalse(ScorecardLedger.isAiSource("paper buy · manual"))
        val orphan = ScorecardLedger.of(
            entries = listOf(pick("KXBTC15M-X", "YES", won = true, at = 1L, yes = 0.66)),
            fills = emptyList()
        )
        assertEquals(1, orphan.ai.wins)
        assertEquals(0.0, orphan.ai.money.pnlUsd, 1e-9)
        assertNull(orphan.picks.single().pnlUsd)
        assertNull(orphan.picks.single().feeUsd)
    }

    private fun pick(
        ticker: String,
        side: String,
        won: Boolean,
        at: Long,
        yes: Double,
        mid: Double = 0.50
    ): PredictionLogEntry {
        val yesOutcome = when (side) {
            "YES" -> won
            "NO" -> !won
            else -> won
        }
        return PredictionLogEntry(
            ticker = ticker,
            series = "KXBTC15M",
            predictedYes = yes,
            predictedNo = 1.0 - yes,
            marketMid = mid,
            timestampMs = at,
            closeTimeMs = at,
            outcome = if (yesOutcome) "yes" else "no",
            score = if (won) 1 else 0,
            predictedSide = side,
            edgePp = 5.0,
            settledAtMs = at
        )
    }

    private fun fill(
        id: String,
        ticker: String,
        side: String,
        ask: Double,
        contracts: Int,
        stake: Double,
        pnl: Double,
        source: String,
        at: Long,
        note: String
    ) = PaperFill(
        id = id,
        ticker = ticker,
        side = side,
        stakeUsd = stake,
        contracts = contracts,
        limitPrice = ask,
        source = source,
        createdAtMs = at,
        settled = true,
        outcome = if (pnl >= 0) if (side == "YES") "yes" else "no" else if (side == "YES") "no" else "yes",
        won = pnl > 0,
        pnlUsd = pnl,
        note = note
    )
}
