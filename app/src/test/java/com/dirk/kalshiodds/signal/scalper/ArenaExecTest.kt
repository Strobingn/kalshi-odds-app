package com.dirk.kalshiodds.signal.scalper

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The payoff map, buy-now vs resting twins, fill rate, wait and queue grids, the model check and the rank race. */
class ArenaExecTest {

    private fun trade(
        strategy: ScalpStrategy,
        closedAt: Long,
        pnl: Double,
        posted: Long = closedAt - 30_000,
        filled: Long = closedAt - 20_000,
        queue: Double = 0.0,
        queueKnown: Boolean = true,
        expected: Double = 0.0,
        ticker: String = "KXBTC15M-X"
    ) = ScalpTrade(
        closedAtMs = closedAt, run = 1L, id = closedAt, strategy = strategy.name, ticker = ticker, side = "YES",
        contracts = 10, entry = 0.50, exit = 0.50 + pnl / 10, kind = "TARGET", entryFeeUsd = 0.0, exitFeeUsd = 0.0,
        pnlUsd = pnl, postedAtMs = posted, filledAtMs = filled,
        queueAhead = queue, queueKnown = queueKnown, predictionCents = expected, expectedCents = expected
    )

    @Test
    fun payoffMapSplitsWinRateFromSize() {
        val st = ScalpStrategy.BOUNCE
        // 3 wins of +$0.40 (4¢ a contract), 7 losses of −$0.10 (1¢): wins 30% but wins big.
        val trades = (0 until 3).map { trade(st, it.toLong(), 0.4) } +
            (3 until 10).map { trade(st, it.toLong(), -0.1) } +
            trade(st, 99, 0.0) // a flat: neither a win nor a loss
        val d = ArenaExec.payoffMap(trades).single()
        assertEquals(11, d.trades)
        assertEquals(0.3, d.hitRate, 1e-12)
        assertEquals(4.0, d.payoff, 1e-9)
        assertEquals(7.0 / 3.0, d.breakEvenPayoff, 1e-12)
        assertTrue("3 × 4¢ beats 7 × 1¢", d.ahead)
        assertEquals(0.5, d.netUsd, 1e-9)
        assertEquals(0.2, ArenaExec.breakEvenHitRate(4.0), 1e-12)
    }

    @Test
    fun payoffMapNeedsEnoughTradesAndBothOutcomes() {
        val st = ScalpStrategy.BOUNCE
        assertTrue(ArenaExec.payoffMap((0 until 9).map { trade(st, it.toLong(), if (it % 2 == 0) 0.1 else -0.1) }).isEmpty())
        assertTrue("all wins: no payoff ratio", ArenaExec.payoffMap((0 until 12).map { trade(st, it.toLong(), 0.1) }).isEmpty())
        val losing = (0 until 12).map { trade(st, it.toLong(), if (it < 6) 0.1 else -0.2) }
        assertFalse(ArenaExec.payoffMap(losing).single().ahead)
    }

    @Test
    fun twinsPairEachSignalsBuyNowAndRestingHalves() {
        val trades = (0 until 4).map { trade(ScalpStrategy.DIP_HUNTER, it.toLong(), -0.2) } +
            (0 until 4).map { trade(ScalpStrategy.DIP_HUNTER_REST, 10L + it, if (it % 2 == 0) 0.3 else 0.1) } +
            trade(ScalpStrategy.MOMENTUM_SNIPER, 50, 0.1)
        var state = ScalperState.fresh()
        state = state.copy(groups = state.groups + (ScalpStrategy.DIP_HUNTER_REST.name to ScalpStats(posted = 8, filled = 4)))
        val tw = ArenaExec.twins(trades, state)
        assertEquals(listOf("Dip", "Momentum"), tw.map { it.signal })
        val dip = tw[0]
        assertEquals(-2.0, dip.taker!!.avgCents, 1e-9)
        assertEquals(0.0, dip.taker!!.stdErrCents, 1e-12)
        assertEquals(2.0, dip.maker!!.avgCents, 1e-9)
        // Sample SD of (3, 1, 3, 1) is 1.1547; ÷ √4.
        assertEquals(1.1547005 / 2, dip.maker!!.stdErrCents, 1e-6)
        assertEquals(0.5, dip.makerFillRate!!, 1e-12)
        assertNull(tw[1].maker)
        assertNull("no posts yet", tw[1].makerFillRate)
    }

    @Test
    fun fillRatesListOnlyRestingStrategiesThatPosted() {
        var state = ScalperState.fresh()
        state = state.copy(
            groups = state.groups +
                (ScalpStrategy.ML_REST.name to ScalpStats(posted = 10, filled = 3)) +
                (ScalpStrategy.DIP_HUNTER.name to ScalpStats(posted = 5, filled = 5))
        )
        val rows = ArenaExec.fillRates(state)
        assertEquals(listOf(ScalpStrategy.ML_REST), rows.map { it.strategy })
        assertEquals(0.3, rows.single().rate, 1e-12)
    }

    @Test
    fun waitAndQueueColumnsBucketRestingFills() {
        val st = ScalpStrategy.IMPROVE_1C
        fun waited(ms: Long) = trade(st, 1_000_000, 0.1, posted = 900_000 - ms, filled = 900_000)
        assertEquals(0, ArenaExec.waitColumn(waited(0)))
        assertEquals(0, ArenaExec.waitColumn(waited(999)))
        assertEquals(1, ArenaExec.waitColumn(waited(1_000)))
        assertEquals(3, ArenaExec.waitColumn(waited(59_999)))
        assertEquals(4, ArenaExec.waitColumn(waited(600_000)))
        assertEquals("a fill stamped before its post counts as instant", 0, ArenaExec.waitColumn(waited(-5_000)))
        fun queued(q: Double, known: Boolean = true) = trade(st, 1, 0.1, queue = q, queueKnown = known)
        assertEquals(0, ArenaExec.queueColumn(queued(0.0)))
        assertEquals(1, ArenaExec.queueColumn(queued(1.0)))
        assertEquals(2, ArenaExec.queueColumn(queued(50.0)))
        assertEquals(4, ArenaExec.queueColumn(queued(5_000.0)))
        assertNull(ArenaExec.queueColumn(queued(10.0, known = false)))
        assertNull(ArenaExec.queueColumn(queued(Double.NaN)))
        val g = ArenaExec.fillSpeed(listOf(waited(0), waited(2_000), trade(ScalpStrategy.BOUNCE, 5, 0.1)))
        assertEquals("buy-now strategies are left out", listOf(st), g.rows)
        assertEquals(1, g.cells[0][0]!!.trades)
        assertEquals(1, g.cells[0][1]!!.trades)
        val q = ArenaExec.queueDepth(listOf(queued(0.0), queued(300.0), queued(1.0, known = false)))
        assertEquals(1, q.cells[0][0]!!.trades)
        assertEquals(1, q.cells[0][3]!!.trades)
        assertEquals(2, q.cells[0].filterNotNull().sumOf { it.trades })
    }

    @Test
    fun modelCheckBandsTheMlScalpersForecast() {
        val ml = ScalpStrategy.ML_REST
        val trades = listOf(
            trade(ml, 1, -0.1, expected = -0.5),
            trade(ml, 2, 0.05, expected = 0.2),
            trade(ml, 3, 0.15, expected = 0.4),
            trade(ml, 4, 0.3, expected = 2.5),
            trade(ScalpStrategy.BOUNCE, 5, 1.0, expected = 1.5) // not the ML scalper
        )
        val b = ArenaExec.modelCheck(trades)
        assertEquals(listOf("<0¢", "0–0.5¢", "2¢+"), b.map { it.label })
        assertEquals(2, b[1].trades)
        assertEquals(0.3, b[1].expectedCents, 1e-12)
        assertEquals(1.0, b[1].realizedCents, 1e-9)
        assertEquals(3.0, b[2].realizedCents, 1e-9)
        assertEquals(0.0, b[2].stdErrCents, 1e-12)
    }

    @Test
    fun rankRaceRanksRunningNetAtEachDaysEnd() {
        val zone = ZoneOffset.UTC
        val today = LocalDate.of(2026, 10, 10)
        fun at(day: LocalDate, hour: Int) = day.atStartOfDay(zone).toInstant().toEpochMilli() + hour * 3_600_000L
        val d0 = today.minusDays(2)
        val d1 = today.minusDays(1)
        val a = ScalpStrategy.BOUNCE
        val b = ScalpStrategy.DIP_HUNTER
        val c = ScalpStrategy.LONG_SHOT
        val trades = listOf(
            trade(a, at(d0.minusDays(5), 1), 1.0), // before the race starts: still counts
            trade(b, at(d0, 3), 2.0),
            trade(a, at(d1, 3), 2.0),
            trade(c, at(today, 3), -1.0)
        )
        val race = ArenaExec.rankRace(trades, today, days = 3, zone = zone)
        assertEquals(listOf("Oct 8", "Oct 9", "Oct 10"), race.days)
        assertEquals("enum order", listOf(b, a, c), race.lines.map { it.strategy })
        val ra = race.lines.first { it.strategy == a }
        val rb = race.lines.first { it.strategy == b }
        val rc = race.lines.first { it.strategy == c }
        assertEquals(listOf(2, 1, 1), ra.ranks)
        assertEquals(listOf(1.0, 3.0, 3.0), ra.netUsd)
        assertEquals(listOf(1, 2, 2), rb.ranks)
        assertEquals(listOf(null, null, 3), rc.ranks)
        assertEquals(3, race.field)
        assertTrue(ArenaExec.rankRace(emptyList(), today, zone = zone).isEmpty)
    }

    @Test
    fun aPartialOldestDayIsLeftOutOfTheRace() {
        val zone = ZoneOffset.UTC
        val today = LocalDate.of(2026, 10, 10)
        fun at(day: LocalDate, hour: Int) = day.atStartOfDay(zone).toInstant().toEpochMilli() + hour * 3_600_000L
        val a = ScalpStrategy.BOUNCE
        val b = ScalpStrategy.DIP_HUNTER
        // The slice starts late on Oct 9 with a big win for a; from Oct 10 b does better.
        val trades = listOf(trade(a, at(today.minusDays(1), 22), 5.0), trade(a, at(today, 1), 0.5), trade(b, at(today, 2), 1.0))
        val whole = ArenaExec.rankRace(trades, today, days = 2, zone = zone)
        assertEquals("counted whole, a leads both days", listOf(1, 1), whole.lines.first { it.strategy == a }.ranks)
        val cut = ArenaExec.rankRace(trades, today, days = 2, zone = zone, partialOldestDay = true)
        assertEquals("a's half-day lead is not counted", listOf(null, 2), cut.lines.first { it.strategy == a }.ranks)
        assertEquals(listOf(null, 1), cut.lines.first { it.strategy == b }.ranks)
    }

    @Test
    fun rankRaceTiesKeepTheEnumOrder() {
        val zone = ZoneOffset.UTC
        val today = LocalDate.of(2026, 10, 10)
        val t = today.atStartOfDay(zone).toInstant().toEpochMilli() + 1
        val race = ArenaExec.rankRace(
            listOf(trade(ScalpStrategy.LONG_SHOT, t, 1.0), trade(ScalpStrategy.ML_REST, t + 1, 1.0)), today, days = 1, zone = zone
        )
        assertEquals(listOf(1), race.lines.first { it.strategy == ScalpStrategy.ML_REST }.ranks)
        assertEquals(listOf(2), race.lines.first { it.strategy == ScalpStrategy.LONG_SHOT }.ranks)
    }
}
