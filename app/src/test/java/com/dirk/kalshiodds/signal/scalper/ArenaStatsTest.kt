package com.dirk.kalshiodds.signal.scalper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** Per-strategy stats, drawdown, the grids, hold time and the live-chart markers. */
class ArenaStatsTest {

    private val day0 = LocalDate.of(2026, 10, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun trade(
        strategy: ScalpStrategy,
        closedAt: Long,
        pnl: Double,
        entry: Double = 0.50,
        exit: Double = 0.52,
        heldSec: Long = 20,
        kind: String = "TARGET",
        side: String = "YES",
        ticker: String = "KXBTC15M-X",
        id: Long = closedAt
    ) = ScalpTrade(
        closedAtMs = closedAt, run = 1L, id = id, strategy = strategy.name, ticker = ticker, side = side,
        contracts = 10, entry = entry, exit = exit, kind = kind, entryFeeUsd = 0.0, exitFeeUsd = 0.0,
        pnlUsd = pnl, postedAtMs = closedAt - heldSec * 1000, filledAtMs = closedAt - heldSec * 1000,
        queueAhead = 0.0, queueKnown = true, predictionCents = 0.0, expectedCents = 0.0
    )

    @Test
    fun statsPerStrategy() {
        val b = ScalpStrategy.BOUNCE
        val trades = listOf(
            trade(b, 1_000, 1.0, heldSec = 10),
            trade(b, 2_000, 0.5, heldSec = 30),
            trade(b, 3_000, -2.0, heldSec = 20),
            trade(b, 4_000, -1.0, heldSec = 40),
            trade(b, 5_000, 2.0, heldSec = 100),
            trade(ScalpStrategy.LONG_SHOT, 6_000, -1.5, kind = "SETTLED")
        )
        val rows = ArenaStats.strategyStats(trades)
        assertEquals(listOf(ScalpStrategy.BOUNCE, ScalpStrategy.LONG_SHOT), rows.map { it.strategy })
        val r = rows.first()
        assertEquals(5, r.trades)
        assertEquals(3, r.wins)
        assertEquals(0.5, r.netUsd, 1e-12)
        assertEquals("won 3.5 ÷ lost 3.0", 3.5 / 3.0, r.profitFactor!!, 1e-12)
        assertEquals("peak 1.5, trough −1.5", 3.0, r.maxDrawdownUsd, 1e-12)
        assertEquals(40.0, r.avgHoldSec!!, 1e-12)
        assertEquals(2, r.longestWinStreak)
        assertEquals(2, r.longestLossStreak)
        assertEquals("mean of 10, 5, −20, −10, 20 cents", 1.0, r.expectancyCents, 1e-12)
        assertNull("nothing won and something lost: PF 0", rows[1].profitFactor?.takeIf { it > 0 })
        assertNull(ArenaStats.strategyStats(listOf(trade(b, 1, 0.3))).first().profitFactor)
    }

    @Test
    fun drawdownSitsUnderTheRunningHigh() {
        val pts = listOf(0.0, 2.0, 1.0, 3.0, -1.0, 0.5).mapIndexed { i, v -> CurvePoint(i.toLong(), v) }
        assertEquals(listOf(0.0, 0.0, -1.0, 0.0, -4.0, -2.5), ArenaStats.drawdown(pts).map { it.pnlUsd })
        assertEquals(4.0, ArenaStats.maxDrawdown(pts), 1e-12)
        assertEquals(0.0, ArenaStats.maxDrawdown(emptyList()), 1e-12)
    }

    @Test
    fun priceBandsUseTheSideBoughtsEntry() {
        val trades = listOf(
            trade(ScalpStrategy.DIP_HUNTER, 1, 0.2, entry = 0.42),
            trade(ScalpStrategy.DIP_HUNTER, 2, -0.4, entry = 0.47),
            trade(ScalpStrategy.LATE_FAVORITE, 3, 1.0, entry = 0.91),
            trade(ScalpStrategy.LONG_SHOT, 4, -1.2, entry = 0.12)
        )
        val g = ArenaStats.priceBands(trades)
        assertEquals(10, g.columns.size)
        assertEquals(listOf(ScalpStrategy.DIP_HUNTER, ScalpStrategy.LATE_FAVORITE, ScalpStrategy.LONG_SHOT), g.rows)
        val dip = g.cells[0][4]!!
        assertEquals(2, dip.trades)
        assertEquals(-0.2, dip.netUsd, 1e-12)
        assertEquals("mean of +2¢ and −4¢", -1.0, dip.avgCents, 1e-12)
        assertNull(g.cells[0][5])
        assertEquals(1, g.cells[1][9]!!.trades)
        assertEquals(1, g.cells[2][1]!!.trades)
        assertEquals("40–50¢", ArenaStats.priceBandLabel(4))
        assertTrue(ArenaStats.priceBands(emptyList()).isEmpty)
    }

    @Test
    fun dailyGridCoversTheLastFourteenDays() {
        val today = LocalDate.of(2026, 10, 14)
        val trades = listOf(
            trade(ScalpStrategy.BOUNCE, day0 + 3_600_000, 1.0),               // Oct 1 01:00
            trade(ScalpStrategy.BOUNCE, day0 + 13 * 86_400_000L + 1, -0.5),  // Oct 14
            trade(ScalpStrategy.BOUNCE, day0 - 86_400_000L, 9.0)             // Sep 30: outside
        )
        val g = ArenaStats.daily(trades, today, days = 14, zone = ZoneOffset.UTC)
        assertEquals(14, g.columns.size)
        assertEquals("Oct 1", g.columns.first())
        assertEquals("Oct 14", g.columns.last())
        assertEquals(1.0, g.cells[0][0]!!.netUsd, 1e-12)
        assertEquals(-0.5, g.cells[0][13]!!.netUsd, 1e-12)
        assertEquals(2, g.cells[0].count { it != null })
    }

    @Test
    fun holdBucketsAndTheSettlement() {
        val s = ScalpStrategy.DIP_HUNTER
        val trades = listOf(
            trade(s, 1, 0.1, heldSec = 5),
            trade(s, 2, -0.3, heldSec = 45),
            trade(s, 3, 0.3, heldSec = 50),
            trade(s, 4, -0.2, heldSec = 400),
            trade(ScalpStrategy.LONG_SHOT, 5, -1.5, heldSec = 600, kind = "SETTLED")
        )
        val b = ArenaStats.holdBuckets(trades)
        assertEquals(ArenaStats.HOLD_LABELS, b.map { it.label })
        assertEquals(listOf(1, 0, 2, 0, 0, 1, 1), b.map { it.trades })
        assertEquals(0.0, b[2].avgCents!!, 1e-12)
        assertNull(b[1].avgCents)
        assertEquals(-15.0, b[6].avgCents!!, 1e-12)
        assertEquals(0, ArenaStats.holdBuckets(trades, ScalpStrategy.DIP_HUNTER)[6].trades)
    }

    @Test
    fun liveMarkersSitOnTheYesLine() {
        val open = 1_800_000L
        val ticker = "KXBTC15M-LIVE"
        val window = LiveWindow(
            ticker = ticker, openMs = open, mids = listOf(0 to 0.5, 1 to 0.51),
            open = listOf(
                ScalperEngine.OpenPosition(
                    ScalpOrder(
                        id = 9, strategy = ScalpStrategy.LATE_FAVORITE, ticker = ticker, side = "NO", price = 0.85,
                        contracts = 10, queueAhead = 0.0, queueKnown = true, postedAtMs = open + 600_000,
                        predictionCents = 0.0, expectedCents = 0.0
                    ),
                    filledAtMs = open + 600_000
                )
            )
        )
        val trades = listOf(
            trade(ScalpStrategy.BOUNCE, open + 120_000, 0.7, entry = 0.40, exit = 0.48, heldSec = 60, side = "NO", ticker = ticker, id = 1),
            trade(ScalpStrategy.BOUNCE, open + 200_000, 0.7, ticker = "KXBTC15M-OTHER", id = 2)
        )
        val m = ArenaStats.markers(window, trades)
        assertEquals(3, m.size)
        val entry = m.first { it.kind == ArenaStats.MarkerKind.ENTRY }
        assertEquals(60.0, entry.second, 1e-9)
        assertEquals("a DOWN entry at 40¢ is 60¢ on the UP line", 0.60, entry.yesPrice, 1e-12)
        val exit = m.first { it.kind == ArenaStats.MarkerKind.EXIT }
        assertEquals(120.0, exit.second, 1e-9)
        assertEquals(0.52, exit.yesPrice, 1e-12)
        assertEquals(entry.key, exit.key)
        val o = m.first { it.kind == ArenaStats.MarkerKind.OPEN }
        assertEquals(0.15, o.yesPrice, 1e-12)
        assertNull(o.key)
        assertEquals("Bounce · buy now · DOWN · 40¢ → 48¢ · +$0.70", ArenaStats.markerLabel(exit))
        assertEquals("Late favorite · buy, hold · DOWN · open at 85¢", ArenaStats.markerLabel(o))
        assertEquals("2:00", ArenaStats.clock(exit.second))
    }
}
