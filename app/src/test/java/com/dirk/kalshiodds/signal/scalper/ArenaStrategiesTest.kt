package com.dirk.kalshiodds.signal.scalper

import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.engine.TopOfBook
import com.dirk.kalshiodds.signal.trade.KalshiFee
import com.dirk.kalshiodds.ui.AppRoutes
import com.dirk.kalshiodds.ui.arenaFeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The arena: the eight strategies added in 1.9.0, the leaderboard and the status panel. Paper only. */
class ArenaStrategiesTest {

    private val t = "KXBTC15M-26OCT091215-15"
    private val noQueue: (String, Double) -> Double? = { _, _ -> 0.0 }
    private val fee = KalshiFee.TAKER_COEFFICIENT

    private fun runner(top: () -> TopOfBook? = { null }) = PaperScalper(
        model = null,
        ledger = ScalperLedger(),
        topOfBook = { top() },
        bookLevels = { null },
        closeMs = { 900_000L }, strategies = ScalpStrategy.values().toList()
    )

    /** One second of two-sided prints: takers sell YES at [bid] and buy YES at [ask]. */
    private fun second(s: PaperScalper, sec: Int, bid: Double, ask: Double, size: Double = 20.0) {
        s.onTrade(t, "no", bid, size, sec * 1000L + 100L)
        s.onTrade(t, "yes", ask, size, sec * 1000L + 200L)
    }

    @Test
    fun everyStrategyHasItsOwnRow() {
        assertEquals(57, ScalpStrategy.values().size)
        val board = ScalperResults.leaderboard(ScalperState.fresh(), ScalpStrategy.values().toList())
        assertEquals(57, board.size)
        assertEquals((1..57).toList(), board.map { it.rank })
        assertEquals("idle strategies keep their own order", ScalpStrategy.values().toList(), board.map { it.strategy })
        assertTrue(board.all { it.verdict == "no trades yet" })
        assertTrue(ScalperResults.leaderLine(ScalperState.fresh()).startsWith("No strategy has closed a trade yet"))
        val copy = ScalperSummary.of(ScalperState.fresh(), shown = ScalpStrategy.values().toList())
        assertEquals(57, copy.strategyLines.size)
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
        assertEquals(ScalpStrategy.LATE_FAVORITE, ScalperResults.leaderboard(after, ScalpStrategy.values().toList()).first().strategy)
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
    fun aStaleBookFarFromThePrintsIsIgnored() {
        // The cached book still says 60/61¢ while every print trades at 85/86¢: the book is stale. Priced off the
        // book, the late favourite would see a 61¢ ask (outside its 80–95¢ band) and never buy.
        val stale = TopOfBook(yesBid = 0.60, yesBidQty = 2_000.0, yesAsk = 0.61, noBid = 0.39, noBidQty = 2_000.0, noAsk = 0.40)
        val s = runner { stale }
        for (sec in 0..610) second(s, sec, 0.85, 0.86)
        assertTrue("priced off the live prints instead", s.ledger.snapshot().stats(ScalpStrategy.LATE_FAVORITE.name).posted >= 1)
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
    fun aResetWhoseArchiveFailsChangesNothing() {
        var archiveOk = false
        val s = PaperScalper(
            model = null, ledger = ScalperLedger(), topOfBook = { null }, bookLevels = { null },
            closeMs = { 900_000L }, onReset = { archiveOk }, strategies = ScalpStrategy.values().toList()
        )
        for (sec in 0..69) second(s, sec, 0.60, 0.61)
        for (sec in 70..74) second(s, sec, 0.50, 0.51)
        for (sec in 75..78) second(s, sec, 0.51, 0.52)
        val posted = s.ledger.snapshot().stats(ScalperState.ALL).posted
        assertTrue(posted >= 1)
        assertFalse("the files stayed live, so the totals stay too", s.reset())
        assertEquals(posted, s.ledger.snapshot().stats(ScalperState.ALL).posted)
        archiveOk = true
        assertTrue(s.reset())
        assertEquals(0, s.ledger.snapshot().stats(ScalperState.ALL).posted)
    }

    @Test
    fun aLowThenARallyThenAFallIsNotABounce() {
        val s = runner()
        for (sec in 0..64) second(s, sec, 0.50, 0.51)
        // The low comes first, then a 7¢ rally, then a fall back to 2¢ above the low: the latest move is down.
        for (sec in 65..67) second(s, sec, 0.49, 0.50)
        for (sec in 68..72) second(s, sec, 0.56, 0.57)
        for (sec in 73..76) second(s, sec, 0.51, 0.52)
        assertEquals(0, s.ledger.snapshot().stats(ScalpStrategy.BOUNCE.name).posted)
        assertEquals(0, s.engine.workingFor(ScalpStrategy.BOUNCE, t, "YES"))
    }

    /** A record built close by close, one market window per close. */
    private fun oneWindowEach(results: List<Double>): ScalpStats {
        var s = ScalpStats()
        results.forEachIndexed { i, p ->
            s = s.copy(
                closed = s.closed + 1, pnlUsd = s.pnlUsd + p,
                wins = s.wins + (if (p > 0) 1 else 0), losses = s.losses + (if (p < 0) 1 else 0),
                wonUsd = s.wonUsd + maxOf(p, 0.0), lostUsd = s.lostUsd + maxOf(-p, 0.0)
            ).withWindowClose("KXBTC15M-W$i", p)
        }
        return s
    }

    @Test
    fun theVerdictSaysWhenALeadIsMoreThanLuck() {
        assertEquals("no trades yet", ScalperResults.verdict(ScalpStats()))
        assertEquals("10 of 30 trades needed to judge", ScalperResults.verdict(ScalpStats(closed = 10, wins = 10, pnlUsd = 10.0, wonUsd = 10.0)))
        val steady = oneWindowEach(List(36) { 1.0 } + List(4) { -1.0 })
        assertEquals("ahead by more than luck so far", ScalperResults.verdict(steady))
        val coin = oneWindowEach(List(21) { 1.0 } + List(19) { -1.0 })
        assertEquals("within luck: no winner yet", ScalperResults.verdict(coin))
        val bleed = oneWindowEach(List(4) { 1.0 } + List(36) { -1.0 })
        assertEquals("losing by more than luck", ScalperResults.verdict(bleed))
        // Totals from before the window sums (1.9.0–1.9.1) get no verdict, not one from class averages.
        val legacy = ScalpStats(closed = 40, wins = 36, losses = 4, pnlUsd = 32.0, wonUsd = 36.0, lostUsd = 4.0)
        assertEquals(ScalperResults.NO_WINDOW_HISTORY, ScalperResults.verdict(legacy))
    }

    @Test
    fun oneHugeWinIsNotALeadBeyondLuck() {
        // 19 wins of 1¢, one of $20 and ten 50¢ losses, each in its own window: ahead, but almost all of it is one trade.
        val s = oneWindowEach(List(19) { 0.01 } + 20.0 + List(10) { -0.5 })
        assertEquals("within luck: no winner yet", ScalperResults.verdict(s))
        // Without complete window sums there is no verdict (a class-average rebuild would have called it a lead).
        assertEquals(ScalperResults.NO_WINDOW_HISTORY, ScalperResults.verdict(s.copy(windowCloses = 0)))
        // Totals from before the window sums plus one new close: the partial sums are not used.
        val legacy = s.copy(windows = 0, winSqUsd = 0.0, winCrossUsd = 0.0, winN2 = 0.0, curTicker = "", curUsd = 0.0, curN = 0, windowCloses = 0)
        val oneMore = legacy.copy(closed = legacy.closed + 1, pnlUsd = legacy.pnlUsd + 0.01, wins = legacy.wins + 1, wonUsd = legacy.wonUsd + 0.01)
            .withWindowClose("KXBTC15M-NEW", 0.01)
        assertEquals(ScalperResults.NO_WINDOW_HISTORY, ScalperResults.verdict(oneMore))
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
        val start = ScalperState.upgraded(old) { archived++; true }
        assertEquals(1, archived)
        assertEquals(ScalperState.VERSION, start.version)
        assertEquals("everyone starts together", 0, start.stats(ScalpStrategy.DIP_HUNTER.name).closed)
        val current = ScalperState.fresh().copy(groups = mapOf(ScalperState.ALL to ScalpStats(closed = 3)))
        assertEquals(current, ScalperState.upgraded(current) { archived++; true })
        assertEquals(ScalperState.fresh(), ScalperState.upgraded(null) { archived++; true })
        assertEquals("only the old record is archived", 1, archived)
        // An archive that did not complete keeps the old record (and its totals) for the next start.
        assertEquals(old, ScalperState.upgraded(old) { false })
        assertEquals(old, ScalperState.upgraded(old) { error("disk full") })
        // ...and trading on it does not stamp it current, so that next start still archives it.
        val ledger = ScalperLedger(initial = old)
        ledger.apply(ScalperEngine().buyNow(ScalpStrategy.MOMENTUM_SNIPER, t, "YES", 0.40, { _, _ -> null }, 1_000L))
        assertEquals(2, ledger.snapshot().version)
    }

    @Test
    fun aLeadFromAFewCorrelatedWindowsIsNotBeyondLuck() {
        fun stats(windows: List<List<Double>>): ScalpStats {
            var s = ScalpStats()
            windows.forEachIndexed { w, pnls ->
                for (p in pnls) {
                    s = s.copy(
                        closed = s.closed + 1, pnlUsd = s.pnlUsd + p,
                        wins = s.wins + (if (p > 0) 1 else 0), losses = s.losses + (if (p < 0) 1 else 0),
                        wonUsd = s.wonUsd + maxOf(p, 0.0), lostUsd = s.lostUsd + maxOf(-p, 0.0)
                    ).withWindowClose("KXBTC15M-W$w", p)
                }
            }
            return s
        }
        // 40 scalps in two windows: plenty of trades, two independent outcomes.
        val two = stats(listOf(List(20) { 1.0 }, List(20) { 1.0 }))
        assertEquals(2, two.windows)
        assertEquals("2 of 10 market windows needed to judge", ScalperResults.verdict(two))
        // 20 windows of 2 scalps that win or lose together: 14 up, 6 down.
        val paired = stats(List(20) { w -> List(2) { if (w < 14) 1.0 else -1.0 } })
        assertEquals(20, paired.windows)
        assertEquals(40, paired.closed)
        assertEquals("clustered by window it is within luck", "within luck: no winner yet", ScalperResults.verdict(paired))
        // Without its window sums the same record gets no verdict at all.
        assertEquals(ScalperResults.NO_WINDOW_HISTORY, ScalperResults.verdict(paired.copy(windows = 0)))
        // A held position from the last window settling between this window's scalps is still one window.
        val interleaved = ScalpStats()
            .withWindowClose("W1", 1.0).withWindowClose("W2", 1.0)
            .withWindowClose("W1", 1.0).withWindowClose("W2", 1.0).withWindowClose("W3", 1.0)
        assertEquals(3, interleaved.windows)
        assertEquals(2, interleaved.prevN)
        assertEquals(5, interleaved.windowCloses)
        // ...and one settling three windows late still joins W1.
        val late = interleaved.withWindowClose("W4", 1.0).withWindowClose("W1", 1.0)
        assertEquals(4, late.windows)
        assertEquals(listOf("W1"), late.oldTickers.drop(1))
        assertEquals(3, late.oldN.last())
        // A record whose window sums miss some closes (written before them) keeps the trade-level test.
        assertEquals(ScalperResults.verdict(paired.copy(windows = 0)), ScalperResults.verdict(paired.copy(windowCloses = 10)))
        // Many windows of steady wins still read as a lead.
        val steady = stats(List(30) { w -> List(2) { if (w % 10 == 0) -1.0 else 1.0 } })
        assertEquals("ahead by more than luck so far", ScalperResults.verdict(steady))
    }

    @Test
    fun theArenaIsABottomTab() {
        assertTrue(AppRoutes.SCALPER in AppRoutes.PRIMARY)
        assertEquals(AppRoutes.SCALPER, AppRoutes.PRIMARY[1])
    }
}
