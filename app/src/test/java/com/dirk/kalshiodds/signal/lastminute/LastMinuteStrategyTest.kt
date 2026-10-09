package com.dirk.kalshiodds.signal.lastminute

import com.dirk.kalshiodds.signal.engine.BookLevelSnapshot
import com.dirk.kalshiodds.signal.trade.BetCall
import com.dirk.kalshiodds.signal.trade.TicketBuilder
import com.dirk.kalshiodds.signal.trade.TicketKind
import com.dirk.kalshiodds.ui.HomeFixtures
import com.dirk.kalshiodds.ui.ScorecardCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LastMinuteStrategyTest {

    @Test
    fun waitingBeforeFinalMinute() {
        val snap = LastMinuteStrategy.evaluate(
            LastMinuteStrategy.Inputs(
                ticker = "KXBTC15M-X",
                tauSec = 200,
                x = 0.0002,
                obsMean = 0.0,
                sigS = 5e-5,
                upAsk = 0.03,
                downAsk = 0.97,
                startsInMs = 140_000
            )
        )
        assertEquals(LastMinutePhase.WAITING, snap.phase)
        assertTrue(LastMinuteCopy.headline(snap).contains("waiting"))
        assertTrue(LastMinuteCopy.headline(snap).contains("2:20") || LastMinuteCopy.headline(snap).contains("2:"))
    }

    @Test
    fun liveBoxWhenNoEdge() {
        val snap = LastMinuteStrategy.evaluate(
            LastMinuteStrategy.Inputs(
                ticker = "KXBTC15M-X",
                tauSec = 45,
                x = 0.0,
                obsMean = 0.0,
                sigS = 5e-4,
                upAsk = 0.50,
                downAsk = 0.50
            )
        )
        assertEquals(LastMinutePhase.LIVE, snap.phase)
        assertNotNull(snap.up)
        assertNotNull(snap.down)
        assertFalse(snap.up!!.qualifies)
        assertFalse(snap.down!!.qualifies)
        assertNull(snap.fired)
    }

    @Test
    fun goldenCheapUpFiresOnce() {
        val input = LastMinuteStrategy.Inputs(
            ticker = "KXBTC15M-FIRE",
            tauSec = 45,
            x = 0.0006,
            obsMean = 0.0004,
            sigS = 5e-05,
            upAsk = 0.03,
            downAsk = 0.97,
            nowMs = 1_700_000_000_000L
        )
        val first = LastMinuteStrategy.evaluate(input)
        assertEquals(LastMinutePhase.FIRED, first.phase)
        assertEquals("YES", first.fired!!.side)
        assertEquals(312, first.fired!!.contracts)
        assertEquals(10.00, first.fired!!.costUsd, 1e-4)
        assertTrue(first.fired!!.evPerDollar >= 0.35)
        assertTrue(LastMinuteCopy.buyLine(first.fired!!).startsWith("BUY UP"))

        val second = LastMinuteStrategy.evaluate(input.copy(alreadyFired = true, tauSec = 30))
        assertEquals(LastMinutePhase.FIRED, second.phase)
        assertNull(second.fired)
    }

    @Test
    fun depthCapReducesSizeAndFlags() {
        val book = BookLevelSnapshot(
            yes = emptyList(),
            no = listOf(0.97 to 40.0)
        )
        val snap = LastMinuteStrategy.evaluate(
            LastMinuteStrategy.Inputs(
                ticker = "KXBTC15M-D",
                tauSec = 45,
                x = 0.0006,
                obsMean = 0.0004,
                sigS = 5e-05,
                upAsk = 0.03,
                downAsk = 0.97,
                book = book
            )
        )
        assertEquals(LastMinutePhase.FIRED, snap.phase)
        assertEquals(40, snap.fired!!.contracts)
        assertTrue(snap.fired!!.depthLimited)
        assertEquals(40, snap.fired!!.depthContracts)
    }

    @Test
    fun noPlayAfterCloseWhenNothingFired() {
        val snap = LastMinuteStrategy.evaluate(
            LastMinuteStrategy.Inputs(
                ticker = "KXBTC15M-C",
                tauSec = 0,
                x = 0.0,
                obsMean = 0.0,
                sigS = 5e-5,
                upAsk = 0.03,
                downAsk = 0.97,
                windowClosed = true
            )
        )
        assertEquals(LastMinutePhase.NO_PLAY, snap.phase)
        assertEquals(LastMinuteCopy.NO_PLAY, LastMinuteCopy.headline(snap))
    }

    @Test
    fun engineOneSignalPerWindow() {
        val engine = LastMinuteEngine(nowMs = { 1_700_000_000_000L + 840_000L })
        engine.replaceMinuteCloses((0 until 40).map { 11.2 + it * 0.00015 })
        engine.noteSpot(BrtiQuote(67_240.0, "BRTI composite (Coinbase)", fallback = false))
        val market = HomeFixtures.actionableBtc().copy(
            floorStrike = 67_000.0,
            spotUsd = 67_240.0,
            closeTimeEpochMs = 1_700_000_000_000L + 900_000L,
            yesAsk = 0.03,
            noAsk = 0.97,
            noBid = 0.97
        )
        val first = engine.tick(market, stakeUsd = 10.0)
        val second = engine.tick(market.copy(yesAsk = 0.02), stakeUsd = 10.0)
        if (first.phase == LastMinutePhase.FIRED) {
            assertEquals(LastMinutePhase.FIRED, second.phase)
            assertEquals(first.fired!!.ask, second.fired!!.ask, 1e-9)
        }
    }

    @Test
    fun betCallUsesLastMinuteNotOldAi() {
        val waiting = HomeFixtures.actionableBtc().copy(
            lastMinute = LastMinuteSnapshot(
                phase = LastMinutePhase.WAITING,
                tauSec = 200,
                startsInMs = 140_000
            )
        )
        val d = BetCall.decide(waiting, HomeFixtures.settings(true), HomeFixtures.NOW_MS)
        assertEquals(BetCall.Headline.NO_BET, d.headline)
        assertTrue(d.noBetReason!!.contains("waiting"))

        val firedSnap = LastMinuteStrategy.evaluate(
            LastMinuteStrategy.Inputs(
                ticker = waiting.ticker,
                tauSec = 45,
                x = 0.0006,
                obsMean = 0.0004,
                sigS = 5e-05,
                upAsk = 0.03,
                downAsk = 0.97,
                nowMs = HomeFixtures.NOW_MS
            )
        )
        val firedMkt = waiting.copy(yesAsk = 0.03, noAsk = 0.97, lastMinute = firedSnap)
        val bet = BetCall.decide(firedMkt, HomeFixtures.settings(true), HomeFixtures.NOW_MS)
        assertEquals(BetCall.Headline.BET_UP, bet.headline)
        assertEquals(TicketKind.LAST_MINUTE, bet.ticket!!.kind)
        assertTrue(bet.ticket!!.canApprove)
        assertEquals(312, bet.ticket!!.contracts)
    }

    @Test
    fun minProfitDoesNotBlockLastMinuteOrManual() {
        val settings = HomeFixtures.settings(true).copy(minProfitIfWinUsd = 20.0, ticketStakeUsd = 10.0)
        val firedSnap = LastMinuteStrategy.evaluate(
            LastMinuteStrategy.Inputs(
                ticker = "KXBTC15M-25SEP181700-50",
                tauSec = 45,
                x = 0.0006,
                obsMean = 0.0004,
                sigS = 5e-05,
                upAsk = 0.62,
                downAsk = 0.38,
                nowMs = HomeFixtures.NOW_MS
            )
        )
        val m = HomeFixtures.actionableBtc().copy(yesAsk = 0.62, noAsk = 0.38, lastMinute = firedSnap)
        val ticket = TicketBuilder.proposeLastMinute(m, TicketBuilder.Context(settings, false, nowMs = HomeFixtures.NOW_MS))
        if (ticket != null) {
            assertFalse(ticket.belowMinProfit)
            assertTrue(ticket.canApprove)
        }
        val manual = TicketBuilder.proposeManual(m, "YES", TicketBuilder.Context(settings, false, nowMs = HomeFixtures.NOW_MS))
        assertNotNull(manual)
        assertFalse(manual!!.belowMinProfit)
        assertTrue(manual.blockedReason == null)
    }

    @Test
    fun tenDollarCapOnConstants() {
        assertEquals(10.0, com.dirk.kalshiodds.signal.config.SignalConstants.LIVE_ALL_IN_CAP_USD, 1e-9)
        assertEquals(10.0, com.dirk.kalshiodds.signal.config.SignalConstants.DEFAULT_TICKET_STAKE_USD, 1e-9)
        assertEquals(10.0, com.dirk.kalshiodds.signal.config.SignalConstants.TICKET_STAKE_HARD_CAP_USD, 1e-9)
        assertEquals(0.0, com.dirk.kalshiodds.signal.config.SignalConstants.DEFAULT_MIN_PROFIT_IF_WIN_USD, 1e-9)
        assertFalse(
            com.dirk.kalshiodds.signal.trade.LiveOrderSizer.belowMinProfit(0.50, 0.0)
        )
    }

    @Test
    fun scorecardLastMinuteSectionIsolated() {
        val pick = LastMinutePick(
            id = "p1",
            ticker = "KXBTC15M-A",
            side = "YES",
            entryAsk = 0.03,
            contracts = 312,
            stakeUsd = 10.0,
            feeUsd = 0.64,
            winChance = 0.998,
            evPerDollar = 30.0,
            depthLimited = false,
            createdAtMs = 1L,
            settled = true,
            outcome = "yes",
            won = true,
            pnlUsd = 302.0
        )
        val section = ScorecardCopy.lastMinuteSection(listOf(pick))
        assertEquals(1, section.wins)
        assertEquals(0, section.losses)
        assertEquals(302.0, section.pnlUsd, 1e-6)
        assertTrue(section.record.contains("1-0"))
        val view = ScorecardCopy.of(emptyList(), 0.0)
        assertTrue(view.allLines().contains(ScorecardCopy.LAST_MINUTE_TITLE))
        assertTrue(view.allLines().contains(ScorecardCopy.LAST_MINUTE_SUBTITLE))
        assertEquals(0, view.ledger.combined.settledCount)
    }

    @Test
    fun storeOnePickPerWindowThenSettle() {
        val store = LastMinuteStore()
        val fired = LastMinuteFired(
            ticker = "KXBTC15M-S",
            side = "YES",
            displaySide = "UP",
            winChance = 0.9,
            ask = 0.03,
            evPerDollar = 2.0,
            contracts = 312,
            costUsd = 10.0,
            feeUsd = 0.64,
            profitIfWinUsd = 302.0,
            depthLimited = false,
            depthContracts = null,
            tauSec = 12,
            x = 0.0006,
            obsMean = 0.0004,
            sigS = 5e-5,
            firedAtMs = 10L
        )
        assertNotNull(store.record(fired))
        assertNull(store.record(fired.copy(firedAtMs = 11L)))
        val settled = store.settle("KXBTC15M-S", "yes")
        assertEquals(1, settled.size)
        assertEquals(true, settled[0].won)
        assertEquals(302.0, settled[0].pnlUsd!!, 1e-6)
    }

    @Test
    fun brtiMedianAndUiStates() {
        val client = BrtiCompositeClient()
        assertEquals(100.0, client.median(listOf(90.0, 100.0, 110.0))!!, 1e-9)
        assertEquals(105.0, client.median(listOf(100.0, 110.0))!!, 1e-9)
        val wait = LastMinuteSnapshot(LastMinutePhase.WAITING, 200, 90_000)
        assertTrue(LastMinuteCopy.headline(wait).contains("1:30"))
        val live = LastMinuteSnapshot(
            phase = LastMinutePhase.LIVE,
            tauSec = 20,
            startsInMs = null,
            pUp = 0.8,
            up = LastMinuteStrategy.quoteSide("YES", 0.8, 0.03, null, null, 10.0),
            down = LastMinuteStrategy.quoteSide("NO", 0.2, 0.97, null, null, 10.0)
        )
        assertTrue(LastMinuteCopy.sideLiveLine(live.up).contains("UP"))
        assertTrue(LastMinuteCopy.sideLiveLine(live.up).contains("3¢") || LastMinuteCopy.sideLiveLine(live.up).contains("ask"))
        assertTrue(LastMinuteCopy.sideLiveLine(live.up).contains(LastMinuteCopy.MODEL_EV_LABEL))
        assertFalse(LastMinuteCopy.sideLiveLine(live.up).contains("EV/$"))
        assertFalse(LastMinuteCopy.sideLiveLine(live.up).contains("wins +"))
        assertEquals("Spot — · none", LastMinuteCopy.spotSourceLine(LastMinuteSnapshot(LastMinutePhase.NO_PLAY, 0, null)))
    }

    @Test
    fun aiDetailsLabelIsBacktestLoss() {
        assertEquals(
            "AI model (backtest: loses after fees)",
            com.dirk.kalshiodds.ui.HomeCardDetails.SECTION
        )
        assertEquals(
            "AI model (backtest: loses after fees)",
            LastMinuteCopy.AI_BACKTEST
        )
    }
}
