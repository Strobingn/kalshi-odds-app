package com.dirk.kalshiodds.decision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A losing UP side stops new clips. DOWN and the next 15-minute window do not.
 * Winners keep the unlimited re-entry path.
 */
class WindowLossGateTest {
    private val ticker = "KXBTC15M-26OCT181315-00"
    private val nextWindow = "KXBTC15M-26OCT181330-30"

    private fun closed(id: String, side: String, net: Double, at: Long, market: String = ticker) = ScalpTrade(
        id = id, ticker = market, side = side, state = ScalpState.CLOSED,
        signalAtMs = at - 5_000L, signalAsk = 0.40, fairAtSignal = 0.70,
        contracts = 10, entryPrice = 0.40, entryAtMs = at - 4_000L,
        closedAtMs = at, netUsd = net,
        ruleVersion = "${ScalpRule.VERSION}|${KeptScalpers.FAIR_ID}|P"
    )

    @Test fun threeLosingUpFillsPauseUpAndLeaveDownOpen() {
        val rows = listOf(
            closed("a", "YES", -1.10, 1_000L),
            closed("b", "YES", -0.90, 2_000L),
            closed("c", "YES", -1.40, 3_000L),
            closed("d", "NO", 0.40, 3_500L)
        )
        val up = WindowLossGate.evaluate(rows, ticker, "YES", bid = 0.20, lossFloorUsd = 100.0, adverseFills = 3)
        assertEquals(3, up.adverseStreak)
        assertTrue(up.reason!!.startsWith("UP paused: window −$"))
        assertEquals(-3.40, up.pnlUsd, 1e-9)
        assertNull(WindowLossGate.evaluate(rows, ticker, "NO", bid = 0.70, lossFloorUsd = 100.0, adverseFills = 3).reason)
    }

    @Test fun pnlFloorPausesOnUnrealizedEvenBeforeTheStreak() {
        val open = ScalpTrade(
            id = "open", ticker = ticker, side = "YES", state = ScalpState.OPEN,
            signalAtMs = 1_000L, signalAsk = 0.46, fairAtSignal = 0.70,
            contracts = 10, entryPrice = 0.46, entryFeeUsd = 0.20, entryAtMs = 2_000L,
            ruleVersion = "${ScalpRule.VERSION}|${KeptScalpers.FAIR_ID}|P"
        )
        val marked = open.unrealizedUsd(0.20)!!
        assertTrue(marked < -2.0)
        val hit = WindowLossGate.evaluate(listOf(open), ticker, "YES", bid = 0.20, lossFloorUsd = 2.0, adverseFills = 9)
        assertTrue(hit.reason!!, hit.reason!!.startsWith("UP paused: window −$"))
        assertEquals(0, hit.adverseStreak)
        val under = WindowLossGate.evaluate(listOf(open), ticker, "YES", bid = 0.20, lossFloorUsd = 50.0, adverseFills = 9)
        assertNull(under.reason)
    }

    @Test fun aWinnerClearsTheStreakAndTheNextTickerStartsClean() {
        val recovered = listOf(
            closed("a", "YES", -1.0, 1_000L),
            closed("b", "YES", -1.0, 2_000L),
            closed("c", "YES", 3.0, 3_000L)
        )
        val up = WindowLossGate.evaluate(recovered, ticker, "YES", null, lossFloorUsd = 8.0, adverseFills = 3)
        assertEquals(0, up.adverseStreak)
        assertNull(up.reason)
        val stillDown = listOf(
            closed("a", "YES", -4.0, 1_000L),
            closed("b", "YES", -3.0, 2_000L),
            closed("c", "YES", 1.0, 3_000L)
        )
        assertTrue(WindowLossGate.evaluate(stillDown, ticker, "YES", null, lossFloorUsd = 5.0, adverseFills = 3).reason!!.contains("UP paused"))
        val other = listOf(closed("z", "YES", -20.0, 1_000L, market = ticker))
        assertNull(WindowLossGate.evaluate(other, nextWindow, "YES", null).reason)
    }

    @Test fun bookRejectsFurtherUpAfterLosingFillsAndAllowsDownAndTheNextWindow() {
        val controls = MemoryScalpControls(ScalpControls(windowSideLossUsd = 1_000.0, windowAdverseFills = 3))
        val book = ScalpBook(bankrollUsd = { 100_000.0 }, controls = controls)
        val close = ScalpTicker.closeMs(ticker)!!
        var at = close - 800_000L
        repeat(3) { at = loseUp(book, ticker, close, at) }
        val losers = book.allTrades().filter { it.ticker == ticker && it.side == "YES" && it.state == ScalpState.CLOSED && (it.netUsd ?: 0.0) < 0.0 }
        assertTrue(losers.joinToString { "${it.state} ${it.netUsd} ${it.exitReason}" }, losers.size >= 3)
        val before = book.allTrades().count { it.ticker == ticker && it.side == "YES" && it.state != ScalpState.NO_FILL }
        book.onQuote(quote(ticker, close, at, 0.34, 0.35, 101_000.0), true)
        book.onQuote(quote(ticker, close, at + 4_000L, 0.34, 0.35, 101_000.0), true)
        val after = book.allTrades().count { it.ticker == ticker && it.side == "YES" && it.state != ScalpState.NO_FILL }
        assertEquals(book.gateLine(KeptScalpers.FAIR_ID), before, after)
        val line = book.gateLine(KeptScalpers.FAIR_ID)
        assertTrue(line, line!!.contains("UP paused: window −$"))
        val downAt = at + 8_000L
        book.onQuote(quote(ticker, close, downAt, 0.64, 0.65, 99_000.0), true)
        book.onQuote(quote(ticker, close, downAt + 4_000L, 0.64, 0.65, 99_000.0), true)
        val down = book.allTrades().filter { it.ticker == ticker && it.side == "NO" && it.state != ScalpState.NO_FILL }
        assertTrue(down.joinToString { "${it.state} ${it.note}" }, down.isNotEmpty())
        val nextClose = ScalpTicker.closeMs(nextWindow)!!
        val nextAt = nextClose - 700_000L
        book.onQuote(quote(nextWindow, nextClose, nextAt, 0.34, 0.35, 101_000.0), true)
        book.onQuote(quote(nextWindow, nextClose, nextAt + 4_000L, 0.34, 0.35, 101_000.0), true)
        val restarted = book.allTrades().filter { it.ticker == nextWindow && it.side == "YES" && it.state != ScalpState.NO_FILL }
        assertTrue(restarted.joinToString { it.state.name }, restarted.isNotEmpty())
    }

    @Test fun bookPausesOnThePnlFloorBeforeTheFillStreak() {
        val controls = MemoryScalpControls(ScalpControls(windowSideLossUsd = 0.5, windowAdverseFills = 0))
        val book = ScalpBook(bankrollUsd = { 100_000.0 }, controls = controls)
        val close = ScalpTicker.closeMs(ticker)!!
        val at = loseUp(book, ticker, close, close - 800_000L)
        val closed = book.allTrades().filter { it.side == "YES" && it.state == ScalpState.CLOSED }
        assertTrue(closed.joinToString { "${it.netUsd}" }, closed.any { (it.netUsd ?: 0.0) < -0.5 })
        val before = book.allTrades().count { it.side == "YES" && it.state != ScalpState.NO_FILL }
        book.onQuote(quote(ticker, close, at, 0.34, 0.35, 101_000.0), true)
        assertEquals(before, book.allTrades().count { it.side == "YES" && it.state != ScalpState.NO_FILL })
        assertTrue(book.gateLine(KeptScalpers.FAIR_ID)!!.contains("UP paused: window −$"))
    }

    @Test fun chartNamesThePausedSideFromTheSameRows() {
        val rows = listOf(
            closed("a", "YES", -4.0, 1_000L),
            closed("b", "YES", -4.5, 2_000L),
            closed("c", "YES", -0.2, 3_000L)
        )
        val close = ScalpTicker.closeMs(ticker)!!
        val chart = com.dirk.kalshiodds.ui.ScalpWindowChartData(ticker, close, close - 400_000L, trades = rows)
        val line = chart.sidePauseLine()
        assertTrue(line, line!!.startsWith("UP paused: window −$"))
        assertTrue(line.contains("8."))
    }

    /** Enter at 35¢, fill, stop out under the 8¢ stop. Returns the next timestamp. */
    private fun loseUp(book: ScalpBook, market: String, close: Long, start: Long): Long {
        var at = start
        book.onQuote(quote(market, close, at, 0.34, 0.35, 101_000.0), true)
        at += 4_000L
        book.onQuote(quote(market, close, at, 0.34, 0.35, 101_000.0), true)
        at += 4_000L
        // Ask is through the entry limit, so a stacked pending clip does not fill
        // the crash and then take profit on the next dip. The open clip stops.
        book.onQuote(quote(market, close, at, 0.20, 0.37, 101_000.0), true)
        at += 4_000L
        return at
    }

    private fun quote(market: String, close: Long, now: Long, yesBid: Double, yesAsk: Double, spot: Double) = ScalpRule.Quote(
        ticker = market, nowMs = now, closeMs = close, bookAtMs = now,
        yesBid = yesBid, yesBidSize = 400.0, yesAsk = yesAsk, yesAskSize = 400.0,
        spot = spot, strike = 100_000.0, sigmaPerSec = 3e-5
    )
}
