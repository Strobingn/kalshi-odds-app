package com.dirk.kalshiodds.signal.scalper

import com.dirk.kalshiodds.signal.trade.TakerCost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Calibration, the resampled fan, the rolling edge, minute of window and correlation. */
class ArenaEdgeTest {

    private fun trade(
        strategy: ScalpStrategy,
        closedAt: Long,
        pnl: Double,
        entry: Double = 0.50,
        exit: Double = 0.52,
        kind: String = "TARGET",
        ticker: String = "KXBTC15M-X",
        filledAt: Long = closedAt - 20_000,
        entryFee: Double = 0.0,
        contracts: Int = 10
    ) = ScalpTrade(
        closedAtMs = closedAt, run = 1L, id = closedAt, strategy = strategy.name, ticker = ticker, side = "YES",
        contracts = contracts, entry = entry, exit = exit, kind = kind, entryFeeUsd = entryFee, exitFeeUsd = 0.0,
        pnlUsd = pnl, postedAtMs = filledAt, filledAtMs = filledAt,
        queueAhead = 0.0, queueKnown = true, predictionCents = 0.0, expectedCents = 0.0
    )

    private fun settled(st: ScalpStrategy, at: Long, entry: Double, won: Boolean, fee: Double = 0.0) =
        trade(st, at, ((if (won) 1.0 else 0.0) - entry) * 10 - fee, entry = entry, exit = if (won) 1.0 else 0.0, kind = "SETTLED", entryFee = fee)

    @Test
    fun calibrationBandsSettledTradesByPrice() {
        val lf = ScalpStrategy.LATE_FAVORITE
        val trades = (0 until 10).map { settled(lf, it.toLong(), 0.85, won = it < 9, fee = 0.10) } +
            (0 until 4).map { settled(ScalpStrategy.LONG_SHOT, 100L + it, 0.15, won = false) } +
            trade(lf, 200, 0.2, entry = 0.85) + // a scalp exit: not a settlement, left out
            trade(lf, 300, 0.0, entry = 0.62, exit = 0.62, kind = "SETTLED") // a void: left out
        val bands = ArenaEdge.calibration(trades)
        assertEquals(listOf(0.1, 0.8), bands.map { it.lo })
        val fav = bands[1]
        assertEquals(10, fav.trades)
        assertEquals(0.9, fav.winRate, 1e-12)
        assertEquals(0.85, fav.avgPrice, 1e-12)
        assertEquals("1¢ entry fee a contract", 0.86, fav.breakEven, 1e-12)
        assertFalse("9 of 10 is not clearly above 86%", fav.clearlyAbove)
        assertFalse(fav.clearlyBelow)
        val ls = bands[0]
        assertEquals(0.0, ls.winRate, 1e-12)
        assertTrue("SE floors at half a trade, so 0 of 4 still has a whisker", ls.stdErr > 0)
        assertFalse("under ${ArenaEdge.MIN_BAND_TRADES} trades nothing is clear", ls.clearlyBelow)
        assertEquals(listOf(0.1), ArenaEdge.calibration(trades, ScalpStrategy.LONG_SHOT).map { it.lo })
    }

    @Test
    fun aBandThatWinsFarMoreThanThePriceIsClearlyAbove() {
        val st = ScalpStrategy.EARLY_FAVORITE
        val trades = (0 until 60).map { settled(st, it.toLong(), 0.70, won = it % 20 != 0) } // 95% won at 70¢
        val b = ArenaEdge.calibration(trades).single()
        assertTrue(b.clearlyAbove)
        val bad = (0 until 60).map { settled(st, it.toLong(), 0.70, won = it % 2 == 0) } // 50% won at 70¢
        assertTrue(ArenaEdge.calibration(bad).single().clearlyBelow)
    }

    @Test
    fun projectionIsReproducibleAndBracketsTheMiddle() {
        val st = ScalpStrategy.BOUNCE
        assertNull(ArenaEdge.projection((0 until 9).map { trade(st, it.toLong(), 1.0) }, st))
        val trades = (0 until 50).map { trade(st, it.toLong(), if (it % 5 == 0) -1.0 else 0.5) }
        val a = ArenaEdge.projection(trades, st, horizon = 100, paths = 300)
        val b = ArenaEdge.projection(trades, st, horizon = 100, paths = 300)
        assertNotNull(a)
        assertEquals("same seed, same fan", a, b)
        a!!
        assertEquals(100, a.steps.last())
        assertEquals(50, a.sample)
        for (i in a.steps.indices) {
            assertTrue(a.p10[i] <= a.p50[i])
            assertTrue(a.p50[i] <= a.p90[i])
        }
        // Mean +0.2 a trade, so the middle path after 100 trades sits near +$20 and nearly all paths finish up.
        assertEquals(20.0, a.p50.last(), 5.0)
        assertTrue(a.pProfit > 0.95)
        val losing = (0 until 50).map { trade(st, it.toLong(), if (it % 5 == 0) 1.0 else -0.5) }
        assertTrue(ArenaEdge.projection(losing, st)!!.pProfit < 0.05)
    }

    @Test
    fun rollingAveragesTheLastTrades() {
        val st = ScalpStrategy.BOUNCE
        val trades = (1..6).map { trade(st, it * 1000L, it * 0.1) } // 1, 2, … 6 cents a contract
        val r = ArenaEdge.rolling(trades, st, window = 3)
        assertEquals(listOf(3000L, 4000L, 5000L, 6000L), r.map { it.tMs })
        assertEquals(listOf(2.0, 3.0, 4.0, 5.0), r.map { it.pnlUsd }.map { Math.round(it * 1e9) / 1e9 })
        assertTrue(ArenaEdge.rolling(trades.take(2), st, window = 3).isEmpty())
        assertTrue("other strategies are left out", ArenaEdge.rolling(trades, ScalpStrategy.LONG_SHOT, 3).isEmpty())
    }

    @Test
    fun entryMinuteComesFromTheTickersCloseTime() {
        val ticker = "KXBTC15M-26OCT091315-15"
        val close = TakerCost.closeEpochMs(ticker)!!
        val open = close - 900_000L
        fun at(sec: Long) = trade(ScalpStrategy.BOUNCE, open + sec * 1000 + 30_000, 0.1, ticker = ticker, filledAt = open + sec * 1000)
        assertEquals(0, ArenaEdge.entryMinute(at(0)))
        assertEquals(0, ArenaEdge.entryMinute(at(59)))
        assertEquals(7, ArenaEdge.entryMinute(at(7 * 60 + 5)))
        assertEquals(14, ArenaEdge.entryMinute(at(899)))
        assertNull("before the window opened", ArenaEdge.entryMinute(at(-1)))
        assertNull("after it closed", ArenaEdge.entryMinute(at(900)))
        assertNull(ArenaEdge.entryMinute(trade(ScalpStrategy.BOUNCE, 1, 0.1, ticker = "NOPE")))
        val g = ArenaEdge.byMinute(listOf(at(10), at(20), at(7 * 60)))
        assertEquals(listOf(ScalpStrategy.BOUNCE), g.rows)
        assertEquals(2, g.cells[0][0]!!.trades)
        assertEquals(1, g.cells[0][7]!!.trades)
        assertNull(g.cells[0][1])
    }

    @Test
    fun correlationOverSharedWindows() {
        val a = ScalpStrategy.BOUNCE
        val b = ScalpStrategy.DIP_HUNTER
        val c = ScalpStrategy.LONG_SHOT
        val trades = ArrayList<ScalpTrade>()
        for (w in 0 until 12) {
            val x = if (w % 3 == 0) -1.0 else (w % 4) * 0.5
            trades += trade(a, w * 10L, x, ticker = "W$w")
            trades += trade(b, w * 10L + 1, 2 * x + 0.1, ticker = "W$w") // moves with a
            trades += trade(c, w * 10L + 2, -x, ticker = "W$w") // moves against a
        }
        trades += trade(ScalpStrategy.LATE_FAVORITE, 999, 1.0, ticker = "W0") // too few windows: no row
        val g = ArenaEdge.correlation(trades)
        assertEquals(listOf(a, b, c).sortedBy { it.ordinal }, g.rows)
        val ia = g.rows.indexOf(a)
        val ib = g.rows.indexOf(b)
        val ic = g.rows.indexOf(c)
        assertNull("no diagonal", g.cells[ia][ia])
        assertEquals(1.0, g.cells[ia][ib]!!.avgCents, 1e-9)
        assertEquals(-1.0, g.cells[ia][ic]!!.avgCents, 1e-9)
        assertEquals(12, g.cells[ia][ib]!!.trades)
        assertEquals(g.cells[ib][ic]!!.avgCents, g.cells[ic][ib]!!.avgCents, 1e-12)
        assertNull(ArenaEdge.pearson(listOf(1.0, 1.0, 1.0), listOf(1.0, 2.0, 3.0)))
    }
}
