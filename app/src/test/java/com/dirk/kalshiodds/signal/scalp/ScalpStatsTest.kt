package com.dirk.kalshiodds.signal.scalp

import com.dirk.kalshiodds.signal.trade.KalshiFee
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ScalpStatsTest {

    private val utc = TimeZone.getTimeZone("UTC")

    private fun utcMs(year: Int, month0: Int, day: Int, hour: Int, min: Int = 0): Long =
        Calendar.getInstance(utc).apply {
            set(year, month0, day, hour, min, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun trade(
        id: String,
        pnlCents: Int,
        entryFeeCents: Int = 3,
        exitFeeCents: Int = 4,
        entryHour: Int = 12,
        strategy: ScalpStrategy = ScalpStrategy.DIP_HUNT,
        ticker: String = "KXBTC15M-260101",
        entryPriceCents: Int = 40,
        exitPriceCents: Int = 45,
        contracts: Int = 10,
        day: Int = 1,
        reason: ExitReason = ExitReason.TARGET
    ) = ClosedTrade(
        positionId = id,
        ticker = ticker,
        strategy = strategy,
        entryPriceCents = entryPriceCents,
        exitPriceCents = exitPriceCents,
        contracts = contracts,
        entryTimeMs = utcMs(2026, 0, day, entryHour, 15),
        exitTimeMs = utcMs(2026, 0, day, entryHour, 22),
        pnlCents = pnlCents,
        entryFeeCents = entryFeeCents,
        exitFeeCents = exitFeeCents,
        reason = reason
    )

    @Test
    fun emptyListYieldsZeroStatsAndSeedBankroll() {
        val stats = ScalpStatsMath.compute(emptyList(), nowMs = 1_000L)
        assertEquals(0L, stats.totalNetCents)
        assertEquals(0L, stats.grossWonCents)
        assertEquals(0L, stats.grossLostCents)
        assertEquals(0, stats.winCount)
        assertEquals(0, stats.lossCount)
        assertEquals(0.0, stats.avgWinCents, 1e-9)
        assertEquals(0.0, stats.avgLossCents, 1e-9)
        assertEquals(0L, stats.maxWinCents)
        assertEquals(0L, stats.maxLossCents)
        assertEquals(0L, stats.totalFeesCents)
        assertEquals(0, stats.tradeCount)
        assertEquals(0.0, stats.winRate, 1e-9)
        assertTrue(stats.bankroll == listOf(BankrollPoint(1_000L, 10_000L)))
        assertTrue(stats.byStrategy.isEmpty())
        assertTrue(stats.byCoin.isEmpty())
        assertTrue(stats.byHourUtc.isEmpty())
        assertTrue(!stats.hasTrades)
    }

    @Test
    fun singleTradeTotals() {
        val t = trade("p1", pnlCents = 250, entryFeeCents = 3, exitFeeCents = 4)
        val stats = ScalpStatsMath.compute(listOf(t))
        assertEquals(250L, stats.totalNetCents)
        assertEquals(250L, stats.grossWonCents)
        assertEquals(0L, stats.grossLostCents)
        assertEquals(1, stats.winCount)
        assertEquals(0, stats.lossCount)
        assertEquals(250.0, stats.avgWinCents, 1e-9)
        assertEquals(0.0, stats.avgLossCents, 1e-9)
        assertEquals(250L, stats.maxWinCents)
        assertEquals(0L, stats.maxLossCents)
        assertEquals(1.0, stats.winRate, 1e-9)
        // Fees are read from the stored per-leg rows (3 + 4), not recomputed.
        assertEquals(7L, stats.totalFeesCents)
        assertEquals(
            listOf(
                BankrollPoint(t.entryTimeMs, 10_000L),
                BankrollPoint(t.exitTimeMs, 10_250L)
            ),
            stats.bankroll
        )
    }

    @Test
    fun knownTotalsAcrossMixedTrades() {
        val trades = listOf(
            trade("p1", pnlCents = 300, entryFeeCents = 2, exitFeeCents = 2, entryHour = 9),
            trade("p2", pnlCents = -150, entryFeeCents = 5, exitFeeCents = 5, entryHour = 14,
                strategy = ScalpStrategy.MOMENTUM_SNIPER, day = 2),
            trade("p3", pnlCents = 100, entryFeeCents = 1, exitFeeCents = 1, entryHour = 9,
                strategy = ScalpStrategy.MOMENTUM_SNIPER, day = 3),
            trade("p4", pnlCents = -50, entryFeeCents = 3, exitFeeCents = 3, entryHour = 23,
                strategy = ScalpStrategy.EXTREME_REVERSAL, day = 4)
        )
        val stats = ScalpStatsMath.compute(trades)

        assertEquals(200L, stats.totalNetCents)                 // 300 − 150 + 100 − 50
        assertEquals(400L, stats.grossWonCents)                 // 300 + 100
        assertEquals(-200L, stats.grossLostCents)               // −150 − 50
        assertEquals(2, stats.winCount)
        assertEquals(2, stats.lossCount)
        assertEquals(200.0, stats.avgWinCents, 1e-9)            // 400 / 2
        assertEquals(-100.0, stats.avgLossCents, 1e-9)          // −200 / 2
        assertEquals(300L, stats.maxWinCents)
        assertEquals(-150L, stats.maxLossCents)
        assertEquals(22L, stats.totalFeesCents)                 // (2+2)+(5+5)+(1+1)+(3+3)
        assertEquals(4, stats.tradeCount)
        assertEquals(0.5, stats.winRate, 1e-9)

        // Bankroll: $100 seed → +300 → −150 → +100 → −50 = $102.00
        assertEquals(10_000L, stats.bankroll.first().bankrollCents)
        assertEquals(10_200L, stats.bankroll.last().bankrollCents)
        assertEquals(5, stats.bankroll.size) // seed + 4 exits

        // Strategy breakdown, sorted by net desc: DIP_HUNT +300, MOMENTUM −50, EXTREME −50
        val dip = stats.byStrategy.getValue(ScalpStrategy.DIP_HUNT)
        assertEquals(1, dip.tradeCount)
        assertEquals(1, dip.wins)
        assertEquals(300L, dip.netCents)
        val mom = stats.byStrategy.getValue(ScalpStrategy.MOMENTUM_SNIPER)
        assertEquals(2, mom.tradeCount)
        assertEquals(1, mom.wins)
        assertEquals(1, mom.losses)
        assertEquals(-50L, mom.netCents)
        assertEquals(-25.0, mom.avgCents, 1e-9)
        assertEquals(
            listOf(ScalpStrategy.DIP_HUNT, ScalpStrategy.MOMENTUM_SNIPER, ScalpStrategy.EXTREME_REVERSAL),
            stats.byStrategy.keys.toList()
        )

        // Hour breakdown keyed by UTC entry hour, sorted ascending.
        assertEquals(setOf(9, 14, 23), stats.byHourUtc.keys.toSet())
        assertEquals(listOf(9, 14, 23), stats.byHourUtc.keys.toList())
        assertEquals(2, stats.byHourUtc.getValue(9).tradeCount)
        assertEquals(400L, stats.byHourUtc.getValue(9).netCents)   // 300 + 100
        assertEquals(1, stats.byHourUtc.getValue(14).losses)

        // All BTC here.
        assertEquals(setOf("BTC"), stats.byCoin.keys.toSet())
        assertEquals(200L, stats.byCoin.getValue("BTC").netCents)
    }

    @Test
    fun coinBreakdownUsesSeriesMappingForEthAndSol() {
        val trades = listOf(
            trade("p1", pnlCents = 100, ticker = "KXETH15M-260101"),
            trade("p2", pnlCents = -40, ticker = "KXSOL15M-260101"),
            trade("p3", pnlCents = 25, ticker = "KXBTC15M-260101")
        )
        val stats = ScalpStatsMath.compute(trades)
        assertEquals(100L, stats.byCoin.getValue("ETH").netCents)
        assertEquals(-40L, stats.byCoin.getValue("SOL").netCents)
        assertEquals(25L, stats.byCoin.getValue("BTC").netCents)
        assertEquals(3, stats.byCoin.size)
    }

    @Test
    fun zeroPnlTradeCountsAsNeitherWinNorLossButAddsFeesAndNet() {
        val t = trade("p0", pnlCents = 0, entryFeeCents = 6, exitFeeCents = 6)
        val stats = ScalpStatsMath.compute(listOf(t))
        assertEquals(1, stats.tradeCount)
        assertEquals(0, stats.winCount)
        assertEquals(0, stats.lossCount)
        assertEquals(0.0, stats.winRate, 1e-9)
        assertEquals(12L, stats.totalFeesCents)
        assertEquals(0L, stats.totalNetCents)
    }

    @Test
    fun openPositionMarkAddsUnrealizedBankrollEndpoint() {
        val closed = trade("p1", pnlCents = 100, day = 1)
        val open = OpenPositionMark(
            position = ScalpPosition(
                id = "p2",
                ticker = "KXBTC15M-260105",
                entryPriceCents = 40,
                contracts = 10,
                entryTimeMs = utcMs(2026, 0, 5, 10, 0),
                mode = ScalpMode.PAPER,
                strategy = ScalpStrategy.DIP_HUNT,
                entryFeeCents = 3
            ),
            markPriceCents = 44
        )
        // unrealized = (44 − 40) × 10 − 3 = 37
        assertEquals(37, open.unrealizedCents)
        val now = utcMs(2026, 0, 5, 10, 5)
        val stats = ScalpStatsMath.compute(listOf(closed), open = open, nowMs = now)
        // Realized stats only cover the closed trade.
        assertEquals(1, stats.tradeCount)
        assertEquals(100L, stats.totalNetCents)
        // Bankroll ends at seed + 100 + 37 = $101.37 at `now`.
        val last = stats.bankroll.last()
        assertEquals(now, last.atMs)
        assertEquals(10_137L, last.bankrollCents)
    }

    @Test
    fun pairRowsMatchesEnterAndExitByPositionId() {
        val entry = scalpTradeRow("p1", ScalpPositionStore.ACTION_ENTER, priceCents = 40,
            feeCents = 3, pnlCents = null, reason = null, createdAtMs = 1_000L)
        val exit = scalpTradeRow("p1", ScalpPositionStore.ACTION_EXIT, priceCents = 47,
            feeCents = 4, pnlCents = 60, reason = "TARGET", createdAtMs = 2_000L)
        val paired = ScalpStatsMath.pairRows(listOf(exit, entry))
        assertEquals(1, paired.size)
        val t = paired.single()
        assertEquals(40, t.entryPriceCents)
        assertEquals(47, t.exitPriceCents)
        assertEquals(3, t.entryFeeCents)
        assertEquals(4, t.exitFeeCents)
        assertEquals(1_000L, t.entryTimeMs)
        assertEquals(2_000L, t.exitTimeMs)
        assertEquals(60, t.pnlCents)
        assertEquals(ExitReason.TARGET, t.reason)
    }

    @Test
    fun pairRowsSkipsUnmatchedEnterRows() {
        val openOnly = scalpTradeRow("p9", ScalpPositionStore.ACTION_ENTER, priceCents = 40,
            feeCents = 3, pnlCents = null, reason = null, createdAtMs = 1_000L)
        assertTrue(ScalpStatsMath.pairRows(listOf(openOnly)).isEmpty())
    }

    @Test
    fun fromLedgerRowsAggregatesPairedRows() {
        val rows = listOf(
            scalpTradeRow("p1", ScalpPositionStore.ACTION_ENTER, priceCents = 40,
                feeCents = 3, pnlCents = null, reason = null, createdAtMs = 1_000L),
            scalpTradeRow("p1", ScalpPositionStore.ACTION_EXIT, priceCents = 47,
                feeCents = 4, pnlCents = 60, reason = "TARGET", createdAtMs = 2_000L)
        )
        val stats = ScalpStatsMath.fromLedgerRows(rows, nowMs = 3_000L)
        assertEquals(60L, stats.totalNetCents)
        assertEquals(7L, stats.totalFeesCents)
        assertEquals(10_060L, stats.bankroll.last().bankrollCents)
    }

    @Test
    fun storedFeesMatchKalshiFeeMath() {
        // The engine stores round(KalshiFee.total(contracts, price01) * 100)
        // per leg — verify that formula end-to-end so the "fees are stored,
        // not recomputed" claim in ScalpStats stays honest.
        val contracts = 25
        val price01 = 0.42
        val expectedCents = kotlin.math.round(KalshiFee.total(contracts, price01) * 100.0).toInt()
        val entry = scalpTradeRow("p1", ScalpPositionStore.ACTION_ENTER, priceCents = 42,
            feeCents = expectedCents, contracts = contracts,
            pnlCents = null, reason = null, createdAtMs = 1_000L)
        val exit = scalpTradeRow("p1", ScalpPositionStore.ACTION_EXIT, priceCents = 50,
            feeCents = expectedCents, contracts = contracts,
            pnlCents = 150, reason = "STOP", createdAtMs = 2_000L)
        val stats = ScalpStatsMath.fromLedgerRows(listOf(entry, exit), nowMs = 3_000L)
        assertEquals(expectedCents * 2L, stats.totalFeesCents)
        assertTrue(expectedCents > 0)
    }

    private fun scalpTradeRow(
        positionId: String,
        action: String,
        priceCents: Int,
        feeCents: Int,
        contracts: Int = 10,
        pnlCents: Int?,
        reason: String?,
        createdAtMs: Long
    ) = ScalpTradeRow(
        id = 1L,
        positionId = positionId,
        ticker = "KXBTC15M-260101",
        action = action,
        priceCents = priceCents,
        contracts = contracts,
        feeCents = feeCents,
        pnlCents = pnlCents,
        reason = reason,
        mode = "PAPER",
        strategy = ScalpStrategy.DIP_HUNT.name,
        clientOrderId = null,
        createdAtMs = createdAtMs
    )
}
