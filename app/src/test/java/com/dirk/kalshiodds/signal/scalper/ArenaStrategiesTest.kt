package com.dirk.kalshiodds.signal.scalper

import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.engine.TopOfBook
import com.dirk.kalshiodds.signal.trade.KalshiFee
import com.dirk.kalshiodds.ui.AppRoutes
import com.dirk.kalshiodds.ui.arenaFeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The 15-strategy arena: the eight strategies added in 1.9.0, the leaderboard and the status panel. Paper only. */
class ArenaStrategiesTest {

    private val t = "KXBTC15M-26OCT091215-15"
    private val noQueue: (String, Double) -> Double? = { _, _ -> 0.0 }
    private val fee = KalshiFee.TAKER_COEFFICIENT

    private fun runner(top: () -> TopOfBook? = { null }) = PaperScalper(
        model = null,
        ledger = ScalperLedger(),
        topOfBook = { top() },
        bookLevels = { null },
        closeMs = { 900_000L }
    )

    /** One second of two-sided prints: takers sell YES at [bid] and buy YES at [ask]. */
    private fun second(s: PaperScalper, sec: Int, bid: Double, ask: Double, size: Double = 20.0) {
        s.onTrade(t, "no", bid, size, sec * 1000L + 100L)
        s.onTrade(t, "yes", ask, size, sec * 1000L + 200L)
    }

    @Test
    fun fifteenStrategiesEachWithItsOwnRow() {
        assertEquals(15, ScalpStrategy.values().size)
        val board = ScalperResults.leaderboard(ScalperState.fresh())
        assertEquals(15, board.size)
        assertEquals((1..15).toList(), board.map { it.rank })
        assertEquals("idle strategies keep their own order", ScalpStrategy.values().toList(), board.map { it.strategy })
        assertTrue(board.all { it.verdict == "no trades yet" })
        assertTrue(ScalperResults.leaderLine(ScalperState.fresh()).startsWith("No strategy has closed a trade yet"))
        val copy = ScalperSummary.of(ScalperState.fresh())
        assertEquals(15, copy.strategyLines.size)
        assertTrue(copy.strategyLines.any { it == "Bounce · buy now: no scalps yet [new · codex]" })
    }

    @Test
    fun holdStrategiesHaveNoExitAndRideToTheSettlement() {
        val e = ScalperEngine()
        val buy = e.buyNow(ScalpStrategy.LATE_FAVORITE, t, "YES", 0.85, noQueue, 0L)
        assertEquals(2, buy.size)
        // A print far above the entry, a bid far below it and ten minutes: a scalp would have closed by now.
        assertTrue(e.onTrade(t, true, 0.99, 500.0, 1_000L, noQueue).isEmpty())
        assertTrue(e.onClock(t, 600_000L, bidYes = 0.10, bidNo = 0.89).isEmpty())
        assertEquals(0 to 1, e.working(t))
        val won = e.settle(t, "yes", 900_000L).filterIsInstance<ScalpEvent.Closed>().single().scalp
        assertEquals(ExitKind.SETTLED, won.kind)
        assertEquals((1.0 - 0.85) * 10 - KalshiFee.total(10, 0.85, fee), won.pnlUsd, 1e-9)

        val lost = ScalperEngine().let { e2 ->
            e2.buyNow(ScalpStrategy.LONG_SHOT, t, "NO", 0.15, noQueue, 0L)
            e2.settle(t, "yes", 900_000L).filterIsInstance<ScalpEvent.Closed>().single().scalp
        }
        assertEquals(-0.15 * 10 - KalshiFee.total(10, 0.15, fee), lost.pnlUsd, 1e-9)
    }

    @Test
    fun eachStrategyTradesOnlyInsideItsOwnPriceBand() {
        val e = ScalperEngine()
        assertEquals(2, e.buyNow(ScalpStrategy.LATE_FAVORITE, t, "YES", 0.92, noQueue, 0L).size)
        assertTrue("late favorite starts at 80¢", e.buyNow(ScalpStrategy.LATE_FAVORITE, t, "NO", 0.75, noQueue, 0L).isEmpty())
        assertTrue("the original scalpers stop at 90¢", e.buyNow(ScalpStrategy.DIP_HUNTER, t, "YES", 0.92, noQueue, 0L).isEmpty())
        assertEquals(2, e.buyNow(ScalpStrategy.LONG_SHOT, t, "NO", 0.15, noQueue, 0L).size)
        assertTrue("a long shot is 10–20¢", e.buyNow(ScalpStrategy.LONG_SHOT, "KXBTC15M-B", "YES", 0.30, noQueue, 0L).isEmpty())
        assertEquals(2, e.buyNow(ScalpStrategy.EARLY_FAVORITE, "KXBTC15M-C", "YES", 0.70, noQueue, 0L).size)
        assertTrue(e.post(ScalpStrategy.FLOW_FADE, t, "YES", 0.50, 100.0, 0.0, 0.0, 0L) != null)
        assertTrue("one hold per side per window", e.buyNow(ScalpStrategy.LATE_FAVORITE, t, "YES", 0.90, noQueue, 1L).isEmpty())
    }

    @Test
    fun aFavoriteLateInTheWindowIsBoughtAndHeldAndTheLongShotOppositeToo() {
        val s = runner()
        // YES trades at 85 / 86 all window: NO is a 14–15¢ long shot.
        for (sec in 0..605) second(s, sec, 0.85, 0.86)
        val st = s.ledger.snapshot()
        assertEquals("late favorite: one buy, minute 10, the YES side", 1, st.stats(ScalpStrategy.LATE_FAVORITE.name).posted)
        assertEquals(1, s.engine.workingFor(ScalpStrategy.LATE_FAVORITE, t, "YES"))
        assertEquals("long shot: one buy on the NO side", 1, s.engine.workingFor(ScalpStrategy.LONG_SHOT, t, "NO"))
        assertEquals("86¢ is above the early favorite's 85¢ cap", 0, st.stats(ScalpStrategy.EARLY_FAVORITE.name).posted)
        s.settle(t, "yes", 900_000L)
        val after = s.ledger.snapshot()
        assertEquals((1.0 - 0.86) * 10 - KalshiFee.total(10, 0.86, fee), after.stats(ScalpStrategy.LATE_FAVORITE.name).pnlUsd, 1e-9)
        assertEquals(-(1.0 - 0.85) * 10 - KalshiFee.total(10, 0.15, fee), after.stats(ScalpStrategy.LONG_SHOT.name).pnlUsd, 1e-9)
        assertEquals(1, after.stats(ScalpStrategy.LATE_FAVORITE.name).wins)
        assertEquals(ScalpStrategy.LATE_FAVORITE, ScalperResults.leaderboard(after).first().strategy)
    }

    @Test
    fun flowFadeRestsABidOnTheSideTakersAreSelling() {
        val s = runner()
        for (sec in 0..69) second(s, sec, 0.50, 0.51)
        // Takers pile into YES (≥ 3:1 and ≥ 500 contracts in 30 s): fade them with a NO bid.
        for (sec in 70..75) {
            s.onTrade(t, "yes", 0.51, 400.0, sec * 1000L + 300L)
            s.onTrade(t, "no", 0.50, 20.0, sec * 1000L + 400L)
        }
        for (sec in 76..81) second(s, sec, 0.50, 0.51)
        assertTrue(s.ledger.snapshot().stats(ScalpStrategy.FLOW_FADE.name).posted >= 1)
        assertTrue(s.engine.workingFor(ScalpStrategy.FLOW_FADE, t, "NO") >= 1)
        assertEquals("nobody bought NO heavily: no YES fade", 0, s.engine.workingFor(ScalpStrategy.FLOW_FADE, t, "YES"))
    }

    @Test
    fun oneCentBetterRestsInsideAWideSpreadAfterMinuteThree() {
        val book = TopOfBook(yesBid = 0.48, yesBidQty = 2_000.0, yesAsk = 0.52, noBid = 0.48, noBidQty = 2_000.0, noAsk = 0.52)
        val s = runner { book }
        for (sec in 0..178) second(s, sec, 0.48, 0.52)
        assertEquals("not before minute 3", 0, s.ledger.snapshot().stats(ScalpStrategy.IMPROVE_1C.name).posted)
        for (sec in 179..183) second(s, sec, 0.48, 0.52)
        val st = s.ledger.snapshot().stats(ScalpStrategy.IMPROVE_1C.name)
        assertTrue("posted at 49¢ on both sides", st.posted >= 2)
        assertTrue("a seller at 48¢ goes through our 49¢ bid first", st.filled >= 1)
    }

    @Test
    fun aFiveCentDropAndTheFirstCentBackIsABounce() {
        val s = runner()
        for (sec in 0..69) second(s, sec, 0.60, 0.61)
        // Down 10¢ in five seconds, then a cent back.
        for (sec in 70..74) second(s, sec, 0.50, 0.51)
        for (sec in 75..78) second(s, sec, 0.51, 0.52)
        assertTrue(s.ledger.snapshot().stats(ScalpStrategy.BOUNCE.name).posted >= 1)
        assertEquals(1, s.engine.workingFor(ScalpStrategy.BOUNCE, t, "YES"))
    }

    @Test
    fun theVerdictSaysWhenALeadIsMoreThanLuck() {
        assertEquals("no trades yet", ScalperResults.verdict(ScalpStats()))
        assertEquals("10 of 30 trades needed to judge", ScalperResults.verdict(ScalpStats(closed = 10, wins = 10, pnlUsd = 10.0, wonUsd = 10.0)))
        val steady = ScalpStats(closed = 40, wins = 36, losses = 4, pnlUsd = 36.0 - 4.0, wonUsd = 36.0, lostUsd = 4.0)
        assertEquals("ahead by more than luck so far", ScalperResults.verdict(steady))
        val coin = ScalpStats(closed = 40, wins = 21, losses = 19, pnlUsd = 2.0, wonUsd = 21.0, lostUsd = 19.0)
        assertEquals("within luck: no winner yet", ScalperResults.verdict(coin))
        val bleed = ScalpStats(closed = 40, wins = 4, losses = 36, pnlUsd = -32.0, wonUsd = 4.0, lostUsd = 36.0)
        assertEquals("losing by more than luck", ScalperResults.verdict(bleed))
    }

    @Test
    fun oneHugeWinIsNotALeadBeyondLuck() {
        // 19 wins of 1¢, one of $20 and ten 50¢ losses: ahead, but almost all of it is one trade.
        val won = 19 * 0.01 + 20.0
        val s = ScalpStats(
            closed = 30, wins = 20, losses = 10, pnlUsd = won - 5.0, wonUsd = won, lostUsd = 5.0,
            pnlSqUsd = 19 * 0.01 * 0.01 + 400.0 + 10 * 0.25
        )
        assertEquals("within luck: no winner yet", ScalperResults.verdict(s))
        // The class-average rebuild alone would have called it a lead.
        assertEquals("ahead by more than luck so far", ScalperResults.verdict(s.copy(pnlSqUsd = 0.0)))
    }

    @Test
    fun theStatusPanelNamesWhatStopsTheArena() {
        val calls = ArrayList<String>()
        fun feed(s: SignalSettings) = arenaFeed(
            s, { calls += "arena" }, { calls += "live" }, { calls += "trades" }, { calls += "settings" }
        )
        assertNull("still loading", arenaFeed(null, {}, {}, {}, {}))
        val off = feed(SignalSettings(liveSignalsEnabled = false))!!
        assertEquals("Turn on Live signals", off.actionLabel)
        off.action()
        val noKey = feed(SignalSettings(liveSignalsEnabled = true))!!
        assertEquals("Add API key in Settings", noKey.actionLabel)
        noKey.action()
        val ready = SignalSettings(liveSignalsEnabled = true, apiKeyId = "key", hasPrivateKey = true)
        assertNull("running: no panel", feed(ready))
        val arenaOff = feed(ready.copy(scalperEnabled = false))!!
        arenaOff.action()
        feed(ready.copy(subscribeTrades = false, recordMarketData = false))!!.action()
        assertEquals(listOf("live", "settings", "arena", "trades"), calls)
    }

    @Test
    fun aSevenStrategyRecordIsArchivedNotMixedIntoTheArena() {
        var archived = 0
        val old = ScalperState(
            groups = mapOf(ScalpStrategy.DIP_HUNTER.name to ScalpStats(closed = 400, pnlUsd = 12.0)),
            version = 2
        )
        val start = ScalperState.upgraded(old) { archived++ }
        assertEquals(1, archived)
        assertEquals(ScalperState.VERSION, start.version)
        assertEquals("everyone starts together", 0, start.stats(ScalpStrategy.DIP_HUNTER.name).closed)
        val current = ScalperState.fresh().copy(groups = mapOf(ScalperState.ALL to ScalpStats(closed = 3)))
        assertEquals(current, ScalperState.upgraded(current) { archived++ })
        assertEquals(ScalperState.fresh(), ScalperState.upgraded(null) { archived++ })
        assertEquals("only the old record is archived", 1, archived)
    }

    @Test
    fun theArenaIsABottomTab() {
        assertTrue(AppRoutes.SCALPER in AppRoutes.PRIMARY)
        assertEquals(AppRoutes.SCALPER, AppRoutes.PRIMARY[1])
    }
}
