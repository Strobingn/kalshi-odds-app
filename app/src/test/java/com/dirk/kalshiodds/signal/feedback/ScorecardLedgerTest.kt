package com.dirk.kalshiodds.signal.feedback

import com.dirk.kalshiodds.data.local.archive.SettledWindowRow
import com.dirk.kalshiodds.prediction.PredictionLogEntry
import com.dirk.kalshiodds.signal.paper.PaperFill
import com.dirk.kalshiodds.signal.paper.PaperTileBuy
import com.dirk.kalshiodds.ui.HomeFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        assertEquals(0.0, orphan.picks.single().pnlUsd!!, 1e-9)
        assertNull(orphan.picks.single().feeUsd)
        assertTrue(orphan.picks.single().entryNotRecorded)
    }

    @Test
    fun breakdownsReconcileToCombinedIncludingMissingPriceAndVoids() {
        val entries = listOf(
            pick("KXBTC15M-A", "YES", won = true, at = 1_700_000_000_000L, yes = 0.70, mid = 0.40, ask = 0.30, contracts = 10, stake = 3.21, fee = 0.21),
            pick("KXBTC15M-B", "NO", won = false, at = 1_700_000_003_600_000L, yes = 0.28, mid = 0.60, ask = 0.66, contracts = 5, stake = 3.45, fee = 0.15),
            pick("KXBTC15M-LEGACY", "YES", won = true, at = 1_700_000_007_200_000L, yes = 0.62, mid = 0.45),
            pick("KXBTC15M-VOID", "YES", won = true, at = 1_700_000_010_800_000L, yes = 0.80, mid = 0.50, ask = 0.40).copy(outcome = "void", score = null),
            PredictionLogEntry(
                ticker = "KXETH15M-SKIP",
                series = "KXETH15M",
                predictedYes = 0.70,
                predictedNo = 0.30,
                marketMid = 0.55,
                timestampMs = 1_700_000_000_000L,
                closeTimeMs = 1_700_000_000_000L,
                outcome = "yes",
                score = 1,
                predictedSide = "YES",
                settledAtMs = 1_700_000_000_000L,
                entryAsk = 0.34,
                contracts = 8,
                stakeUsd = 2.90,
                feeUsd = 0.18
            )
        )
        val fills = listOf(
            fill("ai-a", "KXBTC15M-A", "YES", 0.30, 10, 3.21, 6.79, "AI hunter", 1_700_000_000_000L, "Paper · fee $0.21"),
            fill("man-1", "KXBTC15M-M1", "YES", 0.34, 29, 10.26, 18.74, PaperTileBuy.SOURCE, 1_700_000_014_400_000L, "Paper tile $10 · fee $0.40"),
            fill("eth", "KXETH15M-SKIP", "YES", 0.34, 8, 2.90, 5.10, "AI hunter", 1_700_000_000_000L, "fee $0.18")
        )
        val snap = ScorecardLedger.of(entries, fills)
        assertEquals(1, snap.voidCount)
        assertTrue(snap.picks.none { it.ticker.contains("ETH") })
        assertTrue(snap.picks.any { it.entryNotRecorded && it.ticker.contains("LEGACY") })
        assertEquals(0.0, snap.picks.first { it.entryNotRecorded }.pnlUsd!!, 1e-9)
        assertEquals(snap.combined.wins, snap.picks.filter { !it.noBetWouldHave }.count { it.won })
        assertReconciles(snap.bySide, snap.combined)
        assertReconciles(snap.byPrice, snap.combined)
        assertReconciles(snap.byTime, snap.combined)
        assertReconciles(snap.byConfidence, snap.combined)
        assertReconciles(snap.bySource, snap.combined)
        val unknownPrice = snap.byPrice.first { it.key == ScorecardLedger.UNKNOWN_KEY }
        assertEquals("Unknown price", unknownPrice.label)
        assertEquals(1, unknownPrice.settledCount)
        assertEquals(0.0, unknownPrice.pnlUsd, 1e-9)
        assertEquals(snap.combined.money.pnlUsd, snap.bySide.sumOf { it.pnlUsd }, 1e-9)
    }

    @Test
    fun dropsNonBtcRowsFromTotalsAndList() {
        val entries = listOf(
            pick("KXBTC15M-ONLY", "YES", won = true, at = 1_000L, yes = 0.70, ask = 0.34, contracts = 8, stake = 2.90, fee = 0.18),
            pick("KXETH15M-NO", "YES", won = true, at = 2_000L, yes = 0.70, ask = 0.34, contracts = 8, stake = 2.90, fee = 0.18).copy(series = "KXETH15M", ticker = "KXETH15M-NO"),
            pick("KXSOL15M-NO", "NO", won = false, at = 3_000L, yes = 0.30, ask = 0.66, contracts = 5, stake = 3.45, fee = 0.15).copy(series = "KXSOL15M", ticker = "KXSOL15M-NO")
        )
        val fills = listOf(
            fill("btc", "KXBTC15M-ONLY", "YES", 0.34, 8, 2.90, 5.10, "AI hunter", 1_000L, "fee $0.18"),
            fill("eth", "KXETH15M-NO", "YES", 0.34, 8, 2.90, 5.10, "AI hunter", 2_000L, "fee $0.18")
        )
        val snap = ScorecardLedger.of(entries, fills)
        assertEquals(1, snap.combined.settledCount)
        assertEquals(1, snap.combined.wins)
        assertEquals(5.10, snap.combined.money.pnlUsd, 1e-9)
        assertEquals(listOf("KXBTC15M-ONLY"), snap.picks.map { it.ticker })
        assertTrue(ScorecardLedger.isScorecardTicker("KXBTC15M-26SEP251200-00"))
        assertTrue(!ScorecardLedger.isScorecardTicker("KXETH15M-26SEP251200-00"))
        val src = java.io.File("app/src/main/java/com/dirk/kalshiodds/signal/feedback/ScorecardLedger.kt").let { f ->
            if (f.isFile) f.readText() else java.io.File("src/main/java/com/dirk/kalshiodds/signal/feedback/ScorecardLedger.kt").readText()
        }
        assertTrue(src.contains("CryptoMarkets.isLiveTicker"))
        assertTrue(src.contains("isScorecardTicker"))
    }

    @Test
    fun logRowWithoutFillCountsInWinLossAtZeroDollars() {
        val ask = 0.34
        val entry = pick("KXBTC15M-DERIVE", "YES", won = true, at = 1_000L, yes = 0.70, ask = ask)
        val snap = ScorecardLedger.of(listOf(entry), emptyList())
        val row = snap.picks.single()
        assertFalse(row.entryNotRecorded)
        assertNull(row.contracts)
        assertNull(row.stakeUsd)
        assertEquals(0.0, row.pnlUsd!!, 1e-9)
        assertEquals(1, snap.combined.wins)
        assertEquals(0.0, snap.combined.money.pnlUsd, 1e-9)
        assertEquals(0.0, snap.ai.money.pnlUsd, 1e-9)
        assertEquals(1, snap.hypotheticalPicks)
        assertEquals(1.0 - ask, snap.hypotheticalPerContractUsd, 1e-9)
        assertReconciles(snap.byPrice, snap.combined)
        val penny = pick("KXBTC15M-PENNY", "YES", won = true, at = 2_000L, yes = 0.70, ask = 0.001)
        val cheap = ScorecardLedger.of(listOf(penny), emptyList())
        assertEquals(0.0, cheap.combined.money.pnlUsd, 1e-9)
        assertEquals(0, cheap.hypotheticalPicks)
        assertTrue(cheap.picks.single().entryNotRecorded)
    }

    @Test
    fun newAiPicksPersistEntryAskContractsAndFee() {
        val sized = ScorecardLedger.captureEntryFromBook(
            sideYes = true,
            yesAsk = 0.34,
            noAsk = 0.67,
            yesBid = 0.33
        )
        assertEquals(0.34, sized.entryAsk!!, 1e-9)
        assertNull(sized.contracts)
        assertNull(sized.stakeUsd)
        assertNull(sized.feeUsd)
        assertNull(ScorecardLedger.paperClipFromAsk(0.001))
        assertNull(ScorecardLedger.paperClipFromAsk(0.99))
        assertNull(ScorecardLedger.captureEntryFromBook(sideYes = true, yesAsk = 0.001, noAsk = 0.50).entryAsk)
        assertNull(ScorecardLedger.captureEntryFromBook(sideYes = false, yesAsk = 0.40, noAsk = 0.995, yesBid = 0.001).entryAsk)
        assertNull(ScorecardLedger.paperClipFromAsk(0.02))
        assertNull(ScorecardLedger.paperClipFromAsk(0.98))
        val hub = java.io.File("app/src/main/java/com/dirk/kalshiodds/signal/SignalHub.kt").let { f ->
            if (f.isFile) f.readText() else java.io.File("src/main/java/com/dirk/kalshiodds/signal/SignalHub.kt").readText()
        }
        val repo = java.io.File("app/src/main/java/com/dirk/kalshiodds/data/repo/MarketRepository.kt").let { f ->
            if (f.isFile) f.readText() else java.io.File("src/main/java/com/dirk/kalshiodds/data/repo/MarketRepository.kt").readText()
        }
        assertTrue(hub.contains("captureEntryFromBook"))
        assertTrue(hub.contains("entryAsk = sized.entryAsk"))
        assertTrue(repo.contains("captureEntryFromBook"))
        assertTrue(repo.contains("entryAsk = sized.entryAsk"))
    }

    @Test
    fun sampleScorecardDetailIsBtcOnlyReconciledAndMostlyPopulated() {
        val entries = HomeFixtures.sampleSettledEntries() + HomeFixtures.sampleStoredNonBtcEntries()
        val fills = HomeFixtures.sampleSettledFills()
        val snap = ScorecardLedger.of(entries, fills)
        assertTrue(snap.picks.none { it.ticker.contains("ETH") || it.ticker.contains("SOL") })
        assertTrue(snap.picks.any { it.entryNotRecorded })
        assertEquals(1, snap.picks.count { it.entryNotRecorded })
        val scored = snap.picks.filter { !it.noBetWouldHave }
        scored.filter { !it.entryNotRecorded }.forEach { row ->
            assertTrue(row.ticker, row.entryAsk != null && row.contracts != null && row.stakeUsd != null && row.feeUsd != null && row.pnlUsd != null)
        }
        assertReconciles(snap.bySide, snap.combined)
        assertReconciles(snap.byPrice, snap.combined)
        assertReconciles(snap.byTime, snap.combined)
        assertReconciles(snap.byConfidence, snap.combined)
        assertReconciles(snap.bySource, snap.combined)
        assertEquals(snap.combined.wins + snap.combined.losses, snap.combined.settledCount)
    }

    private fun assertReconciles(buckets: List<ScorecardLedger.Bucket>, combined: ScorecardLedger.Record) {
        assertEquals(combined.wins, buckets.sumOf { it.wins })
        assertEquals(combined.losses, buckets.sumOf { it.losses })
        assertEquals(combined.money.pnlUsd, buckets.sumOf { it.pnlUsd }, 1e-9)
    }

    private fun pick(
        ticker: String,
        side: String,
        won: Boolean,
        at: Long,
        yes: Double,
        mid: Double = 0.50,
        ask: Double? = null,
        contracts: Int? = null,
        stake: Double? = null,
        fee: Double? = null
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
            settledAtMs = at,
            entryAsk = ask,
            contracts = contracts,
            stakeUsd = stake,
            feeUsd = fee
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
