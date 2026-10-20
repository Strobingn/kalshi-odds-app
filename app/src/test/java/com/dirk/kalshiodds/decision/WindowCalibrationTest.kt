package com.dirk.kalshiodds.decision

import com.dirk.kalshiodds.data.local.ledger.LedgerRow
import com.dirk.kalshiodds.signal.paper.PaperFill
import com.dirk.kalshiodds.ui.charts.ChartGallery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowCalibrationTest {
    private val ticker = "KXBTC15M-26OCT181530-30"
    private val otherStrike = "KXBTC15M-26OCT181530-31"
    private val eth = "KXETH15M-26OCT181530-30"
    private val close = ScalpTicker.closeMs(ticker)!!
    private val now = close + 60_000L

    @Test fun horizonIsLastForecastBeforeClosePerCoin() {
        assertEquals("last-before-close", WindowCalibration.HORIZON)
        val early = row(ticker, close - 120_000, 0.40, "yes", id = 1)
        val last = row(otherStrike, close - 5_000, 0.72, "no", id = 2)
        val after = row(ticker, close + 1_000, 0.99, "yes", id = 3)
        val ethRow = row(eth, close - 8_000, 0.61, "yes", id = 4, market = 0.50)
        val daily = row("KXBTCD-26OCT1817", close - 8_000, 0.80, "yes", id = 5)
        val oldClose = ScalpTicker.closeMs("KXBTC15M-26SEP011530-30")!!
        val old = row("KXBTC15M-26SEP011530-30", oldClose - 5_000, 0.2, "yes", id = 6)
        val samples = WindowCalibration.fromLedger(listOf(early, last, after, ethRow, daily, old), now, now - WindowCalibration.LOOKBACK_MS)
        assertEquals(listOf("BTC", "ETH"), samples.map { it.coin }.sorted())
        val btc = samples.first { it.coin == "BTC" }
        assertEquals(72.0, btc.aiUp, 1e-9)
        assertEquals(false, btc.settledUp)
        assertEquals(close - 5_000, btc.atMs)
        assertEquals(61.0, samples.first { it.coin == "ETH" }.aiUp, 1e-9)
        assertTrue(samples.none { it.key.contains("KXBTCD") })
    }

    @Test fun ledgerBeatsScalpAndPaperOnTheSameWindow() {
        val ledger = row(ticker, close - 10_000, 0.80, "yes", market = 0.55)
        val scalp = settledScalp(side = "YES", fair = 0.20, settlementPrice = 1.0)
        val paper = paper(ai = 10.0, won = false, side = "NO")
        val report = WindowCalibration.merge(listOf(ledger), listOf(scalp), listOf(paper), now)
        assertEquals(1, report.count)
        assertEquals(80.0, report.samples.single().aiUp, 1e-9)
        assertTrue(report.samples.single().settledUp)
        assertEquals(55.0, report.samples.single().marketUp!!, 1e-6)
    }

    @Test fun paperFillsAWindowTheLedgerMissed() {
        val report = WindowCalibration.merge(emptyList(), emptyList(), listOf(paper(ai = 70.0, won = true, side = "YES")), now)
        assertEquals(1, report.count)
        assertEquals(70.0, report.samples.single().aiUp, 1e-9)
        assertTrue(report.samples.single().settledUp)
        assertEquals(40.0, report.samples.single().marketUp!!, 1e-6)
    }

    @Test fun scalpUsesSettlementFillNotTheTradeResult() {
        val held = settledScalp(side = "NO", fair = 0.66, settlementPrice = 0.0)
        val early = held.copy(
            id = "early",
            note = "",
            state = ScalpState.CLOSED,
            netUsd = 1.0,
            fairAtSignal = 0.9
        )
        val samples = WindowCalibration.fromScalp(listOf(early, held), now, now - WindowCalibration.LOOKBACK_MS)
        assertEquals(1, samples.size)
        assertEquals(34.0, samples.single().aiUp, 1e-9)
        assertTrue(samples.single().settledUp)
        assertTrue(WindowCalibration.fromScalp(listOf(early), now, 0L).isEmpty())
        val noChance = settledScalp(side = "YES", fair = 0.0, settlementPrice = 1.0)
        assertTrue(WindowCalibration.fromScalp(listOf(noChance), now, 0L).isEmpty())
    }

    @Test fun brierAndCollectingLabel() {
        val samples = (0 until 10).map { i ->
            WindowCalibration.Sample("k$i", "BTC", close - i, close - i, if (i < 5) 100.0 else 0.0, i < 5, if (i == 0) null else 50.0)
        }
        val report = WindowCalibration.report(samples)
        assertEquals(10, report.count)
        assertTrue(!report.collecting)
        assertEquals(0.0, report.brier!!, 1e-12)
        assertEquals(0.25, report.marketBrier!!, 1e-12)
        assertEquals(9, report.marketSamples)
        assertEquals("Brier 0.000 · market 0.250 (n=9)", report.brierLabel())
        val few = WindowCalibration.report(samples.take(4))
        assertTrue(few.collecting)
        assertEquals("Collecting: 4 of 10 windows settled", few.collectingLabel())
        assertNull(WindowCalibration.report(emptyList()).brier)
        assertNull(WindowCalibration.report(emptyList()).brierLabel())
    }

    @Test fun dailyRowsSpanMoreThanOneEasternDay() {
        val m0 = ScalpModels.ALL[0]
        val m1 = ScalpModels.ALL[1]
        val rows = listOf(
            ScalpDaily(m0.code, "2026-10-18", 2, 1, 1.0, 0.1),
            ScalpDaily(m1.code, "2026-10-18", 2, 1, -2.0, 0.1),
            ScalpDaily(m0.code, "2026-10-19", 2, 1, 3.0, 0.1),
            ScalpDaily(m1.code, "2026-10-19", 2, 1, -4.0, 0.1)
        )
        val windows = WindowCalibration.windowsFromDaily(rows)
        assertEquals(2, windows.size)
        val corr = ChartGallery.dailyCorrelation(windows, Long.MAX_VALUE, listOf(m0, m1))
        assertNotNull(corr)
        assertEquals(2, corr!!.labels.size)
        assertEquals(-1.0, corr.matrix[0][1]!!, 1e-9)
    }

    private fun row(
        ticker: String,
        at: Long,
        p: Double?,
        result: String?,
        id: Long = 0,
        market: Double? = 0.4
    ) = LedgerRow(
        id = id,
        timestampMs = at,
        modelVersion = "t",
        ticker = ticker,
        series = ticker.substringBefore('-'),
        closeTimeMs = ScalpTicker.closeMs(ticker) ?: at,
        finalProb = p,
        marketMid = market,
        settlementResult = result,
        settledAtMs = result?.let { at }
    )

    private fun paper(ai: Double, won: Boolean, side: String) = PaperFill(
        id = "p-$side",
        ticker = ticker,
        side = side,
        stakeUsd = 1.0,
        contracts = 1,
        limitPrice = 0.40,
        source = "ai",
        createdAtMs = close - 30_000,
        settled = true,
        outcome = if (won) side.lowercase() else if (side == "YES") "no" else "yes",
        won = won,
        note = "",
        aiPct = ai,
        marketPct = 40.0
    )

    private fun settledScalp(side: String, fair: Double, settlementPrice: Double): ScalpTrade {
        val open = ScalpTrade(
            id = "s-$side-$fair",
            ticker = ticker,
            side = side,
            state = ScalpState.OPEN,
            signalAtMs = close - 60_000,
            signalAsk = 0.42,
            fairAtSignal = fair,
            contracts = 1,
            entryPrice = 0.42,
            entryAtMs = close - 50_000,
            ruleVersion = "v|fair|P"
        )
        return ScalpFills.sell(open, close, 1, settlementPrice, 0.0, settlement = true)
            .copy(state = ScalpState.CLOSED, closedAtMs = close, netUsd = settlementPrice - 0.42)
    }
}
