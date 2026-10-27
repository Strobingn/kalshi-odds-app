package com.dirk.kalshiodds.signal.scalper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Compare table (filters, sorts, cells) and the one-strategy page's numbers and rules. */
class ArenaTableTest {

    private fun trade(st: ScalpStrategy, closedAt: Long, pnl: Double, held: Long = 20_000L) = ScalpTrade(
        closedAtMs = closedAt, run = 1L, id = closedAt, strategy = st.name, ticker = "KXBTC15M-X", side = "YES",
        contracts = 10, entry = 0.50, exit = 0.50 + pnl / 10, kind = "TARGET", entryFeeUsd = 0.0, exitFeeUsd = 0.0,
        pnlUsd = pnl, postedAtMs = closedAt - held, filledAtMs = closedAt - held,
        queueAhead = 0.0, queueKnown = true, predictionCents = 0.0, expectedCents = 0.0
    )

    private fun state(vararg s: Pair<ScalpStrategy, ScalpStats>): ScalperState =
        ScalperState.fresh().let { st -> st.copy(groups = st.groups + s.associate { it.first.name to it.second }) }

    private fun stats(closed: Int, wins: Int, net: Double, won: Double, lost: Double, posted: Int = closed, filled: Int = closed) =
        ScalpStats(posted = posted, filled = filled, closed = closed, wins = wins, losses = closed - wins, pnlUsd = net, wonUsd = won, lostUsd = lost)

    @Test
    fun everyStrategyHasARowAndAFamily() {
        val rows = ArenaTable.rows(ScalperState.fresh(), emptyList(), ScalpStrategy.values().toList())
        assertEquals(ScalpStrategy.values().toList(), rows.map { it.strategy })
        assertEquals(ArenaTable.Family.HOLD, ArenaTable.familyOf(ScalpStrategy.LATE_FAVORITE))
        assertEquals(ArenaTable.Family.HOLD, ArenaTable.familyOf(ScalpStrategy.FLOW_FADE))
        assertEquals(ArenaTable.Family.BUY_NOW, ArenaTable.familyOf(ScalpStrategy.BOUNCE))
        assertEquals(ArenaTable.Family.RESTING, ArenaTable.familyOf(ScalpStrategy.QUIET_MAKER))
        assertEquals("the 1.9.4 fifteen, Kashi's five, the opening ride, late edge, the 20-scalper fleet and the 4 micro scalpers", 46, rows.count { ArenaTable.matches(it, ArenaTable.Family.NEW) })
        assertEquals(4, rows.count { ArenaTable.matches(it, ArenaTable.Family.MICRO) })
        assertEquals(0, rows.count { ArenaTable.matches(it, ArenaTable.Family.TRADED) })
        val byFamily = listOf(ArenaTable.Family.BUY_NOW, ArenaTable.Family.RESTING, ArenaTable.Family.HOLD)
            .sumOf { f -> rows.count { ArenaTable.matches(it, f) } }
        assertEquals("every strategy is in exactly one of the three", rows.size, byFamily)
        assertTrue(rows.all { it.verdict == "no trades yet" })
        assertEquals("–", ArenaTable.format(rows.first(), ArenaTable.SortKey.NET))
    }

    @Test
    fun totalsFromTheLedgerPerTradeNumbersFromTheFile() {
        val b = ScalpStrategy.BOUNCE
        val s = state(b to stats(closed = 4, wins = 3, net = 0.5, won = 0.9, lost = 0.4))
        val trades = listOf(trade(b, 1_000, 0.3), trade(b, 2_000, -0.4), trade(b, 3_000, 0.3), trade(b, 4_000, 0.3))
        val r = ArenaTable.rows(s, trades, ScalpStrategy.values().toList()).first { it.strategy == b }
        assertEquals(4, r.trades)
        assertEquals(0.75, r.winRate!!, 1e-12)
        assertEquals(0.9 / 0.4, r.profitFactor!!, 1e-12)
        assertEquals("(3 + −4 + 3 + 3) ÷ 4 cents", 1.25, r.cents!!, 1e-9)
        assertEquals(0.4, r.maxDrawdownUsd, 1e-9)
        assertEquals(20.0, r.avgHoldSec!!, 1e-9)
        assertNull("buy now: no fill rate", r.fillRate)
        assertEquals("+1.25", ArenaTable.format(r, ArenaTable.SortKey.CENTS))
        assertEquals("2.25", ArenaTable.format(r, ArenaTable.SortKey.PROFIT_FACTOR))
        assertEquals("75%", ArenaTable.format(r, ArenaTable.SortKey.WIN_RATE))
        assertEquals("20s", ArenaTable.format(r, ArenaTable.SortKey.HOLD_TIME))
        val rest = ArenaTable.rows(state(ScalpStrategy.QUIET_MAKER to stats(2, 1, 0.0, 0.1, 0.1, posted = 8, filled = 2)), emptyList(), ScalpStrategy.values().toList())
            .first { it.strategy == ScalpStrategy.QUIET_MAKER }
        assertEquals(0.25, rest.fillRate!!, 1e-12)
    }

    @Test
    fun sortsBestFirstOrWorstFirstWithBlanksLast() {
        val s = state(
            ScalpStrategy.BOUNCE to stats(5, 3, 2.0, 3.0, 1.0),
            ScalpStrategy.DIP_HUNTER to stats(9, 2, -1.0, 1.0, 2.0),
            ScalpStrategy.LONG_SHOT to stats(1, 1, 0.5, 0.5, 0.0)
        )
        val rows = ArenaTable.rows(s, emptyList(), ScalpStrategy.values().toList())
        val net = ArenaTable.view(rows, ArenaTable.Family.ALL, ArenaTable.SortKey.NET)
        assertEquals(listOf(ScalpStrategy.BOUNCE, ScalpStrategy.LONG_SHOT, ScalpStrategy.DIP_HUNTER), net.take(3).map { it.strategy })
        assertEquals("all 61 still listed, the idle ones after", 61, net.size)
        val worst = ArenaTable.view(rows, ArenaTable.Family.TRADED, ArenaTable.SortKey.NET, bestFirst = false)
        assertEquals(listOf(ScalpStrategy.DIP_HUNTER, ScalpStrategy.LONG_SHOT, ScalpStrategy.BOUNCE), worst.map { it.strategy })
        val pf = ArenaTable.view(rows, ArenaTable.Family.TRADED, ArenaTable.SortKey.PROFIT_FACTOR)
        assertEquals("nothing lost yet: no profit factor, so last", ScalpStrategy.LONG_SHOT, pf.last().strategy)
        val holds = ArenaTable.view(rows, ArenaTable.Family.HOLD, ArenaTable.SortKey.TRADES)
        assertEquals(ScalpStrategy.LONG_SHOT, holds.first().strategy)
        assertTrue(holds.all { it.strategy.hold })
    }

    @Test
    fun theExtraColumnShowsTheSortUnlessItIsAlreadyAColumn() {
        assertEquals(ArenaTable.SortKey.CENTS, ArenaTable.extraColumn(ArenaTable.SortKey.NET))
        assertEquals(ArenaTable.SortKey.CENTS, ArenaTable.extraColumn(ArenaTable.SortKey.WIN_RATE))
        assertEquals(ArenaTable.SortKey.DRAWDOWN, ArenaTable.extraColumn(ArenaTable.SortKey.DRAWDOWN))
        assertEquals(ArenaTable.SortKey.FILL_RATE, ArenaTable.extraColumn(ArenaTable.SortKey.FILL_RATE))
    }

    @Test
    fun everyStrategyExplainsItselfInWords() {
        for (st in ScalpStrategy.values()) {
            val r = ArenaTable.rules(st)
            assertEquals(st.name, 5, r.size)
            assertTrue(st.name, r[0].isNotBlank())
            if (st.hold) assertTrue(st.name, r[2].startsWith("Holds to the settlement"))
            else assertTrue(st.name, r[2].startsWith("Out at +"))
            assertTrue(st.name, r[1].startsWith(if (st.taker) "Buys at the ask" else "Rests a bid"))
        }
        assertEquals("Out at +8¢ (resting offer), or sold at the bid at −6¢ or after 120 s (taker fee).", ArenaTable.rules(ScalpStrategy.BOUNCE)[2])
        // Every strategy buys the arena's band now; its own prices come back only if you set them.
        assertEquals("Trades between 15¢ and 58¢.", ArenaTable.rules(ScalpStrategy.HEAVY_FAVORITE)[3])
        val own = Tuned(ScalpStrategy.HEAVY_FAVORITE, ScalpKnobs(entryMinCents = 92.0, entryMaxCents = 97.0), 10)
        assertEquals("Trades only between 92¢ and 97¢.", ArenaTable.rules(ScalpStrategy.HEAVY_FAVORITE, own)[3])
    }

    @Test
    fun theStrategyPageHasItsOwnTradesBestAndWorst() {
        val b = ScalpStrategy.BOUNCE
        val trades = listOf(trade(b, 1_000, 0.3), trade(b, 2_000, -0.4), trade(ScalpStrategy.DIP_HUNTER, 2_500, 5.0), trade(b, 3_000, 0.2))
        val d = ArenaTable.detail(b, state(b to stats(3, 2, 0.1, 0.5, 0.4)), trades)
        assertEquals(listOf(3_000L, 2_000L, 1_000L), d.recent.map { it.closedAtMs })
        assertEquals(0.3, d.best!!.pnlUsd, 1e-12)
        assertEquals(-0.4, d.worst!!.pnlUsd, 1e-12)
        assertEquals(1 to 1, d.streaks)
        assertEquals(3, d.sampled)
        val none = ArenaTable.detail(ScalpStrategy.LONG_SHOT, ScalperState.fresh(), trades)
        assertNull(none.best)
        val winless = ArenaTable.detail(b, ScalperState.fresh(), listOf(trade(b, 1, -0.2), trade(b, 2, -0.5)))
        assertEquals("the best of a winless record is its smallest loss", -0.2, winless.best!!.pnlUsd, 1e-12)
        assertEquals(-0.5, winless.worst!!.pnlUsd, 1e-12)
        assertTrue(none.recent.isEmpty())
    }
}
