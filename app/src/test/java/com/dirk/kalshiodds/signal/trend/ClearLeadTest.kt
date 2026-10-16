package com.dirk.kalshiodds.signal.trend

import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.domain.withSignalScore
import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.engine.ScoringEngine
import com.dirk.kalshiodds.signal.external.AssetSpotFeatures
import com.dirk.kalshiodds.signal.external.ExternalSnapshot
import com.dirk.kalshiodds.signal.latefav.LateFavoriteLedger
import com.dirk.kalshiodds.signal.ml.HeavyMlGuard
import com.dirk.kalshiodds.signal.model.MarketTick
import com.dirk.kalshiodds.signal.model.TickSource
import com.dirk.kalshiodds.signal.trade.BetCall
import com.dirk.kalshiodds.signal.trade.KalshiFee
import com.dirk.kalshiodds.signal.trade.TicketBuilder
import com.dirk.kalshiodds.signal.trade.TicketKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Dirk's clear-lead rule (Kalshi prices only): the pure rule, the engine score, the BET headline,
 * the $5 ticket (no min-profit gate on its side) and the paper record.
 */
class ClearLeadTest {

    @Before
    fun setUp() {
        HeavyMlGuard.reset()
    }

    @After
    fun tearDown() {
        HeavyMlGuard.reset()
    }

    private val strike = 100_000.0
    private val now = 1_000_000L

    // ---- the rule ----

    @Test
    fun callsTheSideKalshiPricesAtEightyToNinetyCents() {
        val up = ClearLeadRule.signal(480L, yesAsk = 0.84, noAsk = 0.17)!!
        assertEquals("YES", up.side)
        assertEquals(0.84, up.ask, 1e-9)
        val down = ClearLeadRule.signal(480L, yesAsk = 0.13, noAsk = 0.88)!!
        assertEquals("NO", down.side)
        assertNull("79c is not a clear lead", ClearLeadRule.signal(480L, 0.79, 0.22))
        assertNotNull("80c is in", ClearLeadRule.signal(480L, 0.80, 0.21))
        assertNotNull("89c is in", ClearLeadRule.signal(480L, 0.89, 0.12))
        assertNull("90c is out", ClearLeadRule.signal(480L, 0.90, 0.11))
        assertNull("a coin flip is out", ClearLeadRule.signal(480L, 0.51, 0.50))
    }

    @Test
    fun onlyBetweenMinuteThreeAndMinuteTen() {
        assertNull("minute 2: 12:01 left", ClearLeadRule.signal(721L, 0.84, 0.17))
        assertNotNull("minute 3: 12:00 left", ClearLeadRule.signal(720L, 0.84, 0.17))
        assertNotNull("minute 10: 5:00 left", ClearLeadRule.signal(300L, 0.84, 0.17))
        assertNull("4:59 left", ClearLeadRule.signal(299L, 0.84, 0.17))
        assertNull(ClearLeadRule.signal(null, 0.84, 0.17))
        assertNull(ClearLeadRule.signal(480L, null, null))
        assertNull(ClearLeadRule.signal(480L, Double.NaN, 0.17))
    }

    @Test
    fun reasonReadsInPlainWords() {
        assertEquals("Kalshi prices UP at 84¢ with 7:10 left", ClearLeadRule.signal(430L, 0.84, 0.17)!!.reason)
        assertEquals("Kalshi prices DOWN at 88¢ with 5:05 left", ClearLeadRule.signal(305L, 0.13, 0.88)!!.reason)
    }

    @Test
    fun paperBetIsFiveDollarsAllInAtTheAskOncePerWindow() {
        val inputs = ClearLeadRule.Inputs(
            ticker = "KXBTC15M-26OCT091015-15",
            nowMs = now,
            tteSeconds = 480L,
            yesAsk = 0.80,
            noAsk = 0.21
        )
        val d = ClearLeadRule.evaluate(inputs, alreadyEntered = false)
        assertNotNull(d)
        assertEquals("YES", d!!.side)
        assertEquals(0.80, d.ask, 1e-9)
        // 6 × 80¢ = $4.80, fee = ceil_cent(0.07 × 6 × 0.8 × 0.2) = $0.07 → $4.87; 7 contracts would be $5.68.
        assertEquals(6, d.sized.contracts)
        assertEquals(KalshiFee.totalCost(6, 0.80), d.sized.costUsd, 1e-9)
        assertEquals(4.87, d.sized.costUsd, 1e-9)
        assertNull("one entry per window", ClearLeadRule.evaluate(inputs, alreadyEntered = true))
        assertNull("no ask in the band, no bet", ClearLeadRule.evaluate(inputs.copy(yesAsk = null), false))
        assertNull(
            "ETH / SOL windows are not traded",
            ClearLeadRule.evaluate(inputs.copy(ticker = "KXETH15M-26OCT091015-15"), false)
        )
    }

    @Test
    fun ledgerRecordsOncePerWindowAndSettles() {
        val ledger = LateFavoriteLedger()
        val signal = ClearLeadRule.Signal("YES", 0.80, 480L)
        val d = ClearLeadRule.decide("KXBTC15M-26OCT091015-15", now, signal, yesAsk = 0.80, noAsk = 0.21)!!
        assertNotNull(ledger.record(d))
        assertNull("second call in the same window is ignored", ledger.record(d))
        ledger.settle("KXBTC15M-26OCT091015-15", "yes")
        val t = ledger.snapshot().totals
        assertEquals(1, t.wins)
        assertEquals(6.0 - 4.87, t.pnlUsd, 1e-9)
        val copy = ClearLeadSummary.of(ledger.snapshot())
        assertEquals(ClearLeadSummary.TITLE, copy.title)
        assertTrue(copy.recordLine, copy.recordLine.contains("1-0"))
        assertTrue(copy.recordLine, copy.recordLine.contains("/ ${ClearLeadRule.TARGET_SETTLED} settled"))
        assertTrue(copy.recordLine, copy.recordLine.contains("80¢ ask"))
        assertTrue(copy.worseLine, copy.worseLine.startsWith("Worse fill (+1¢)"))
    }

    // ---- the engine ----

    @Test
    fun engineReadsTheCallFromKalshiQuotesWithNoBitcoinFeed() {
        // A spot far from the strike must not matter: the call comes from Kalshi's prices.
        val engine = engineWithSpot(lastPrice = strike * 1.0300)
        val score = engine.score(tick(close = now + 480_000L, yesBid = 0.83, yesAsk = 0.84), SignalSettings(), now)
        assertNotNull(score)
        assertEquals("YES", score!!.clearLeadSide)
        assertEquals(0.84, score.clearLeadAsk!!, 1e-9)
        val ui = market(yesAsk = 0.84, noAsk = 0.17).withSignalScore(score, thresholdPp = 5.0)
        assertEquals("YES", ui.clearLeadSide)
        assertEquals(0.84, ui.clearLeadAsk!!, 1e-9)
    }

    @Test
    fun engineDoesNotCallOutsideTheBands() {
        val e = engineWithSpot(lastPrice = strike * 1.0015)
        assertNull(e.score(tick(now + 480_000L, 0.60, 0.61), SignalSettings(), now)!!.clearLeadSide)
        assertNull(e.score(tick(now + 800_000L, 0.83, 0.84), SignalSettings(), now)!!.clearLeadSide)
        assertNull(e.score(tick(now + 120_000L, 0.95, 0.96), SignalSettings(), now)!!.clearLeadSide)
    }

    // ---- headline and ticket ----

    @Test
    fun headlineIsBetUpWithAnApprovableEightyCentTicket() {
        val m = market(yesAsk = 0.80, noAsk = 0.21, lead = "YES")
        val ctx = TicketBuilder.Context(settings = SignalSettings(), alertsPaused = false, nowMs = now)
        val call = BetCall.decide(m, ctx)
        assertEquals(BetCall.Headline.BET_UP, call.headline)
        assertTrue(call.isActionable)
        val t = call.ticket!!
        assertEquals("YES", t.side)
        assertTrue(t.clearLead)
        assertEquals(TicketKind.MANUAL, t.kind)
        assertEquals(0.80, t.limitPrice, 1e-9)
        assertEquals(6, t.contracts)
        assertEquals(4.87, t.allInUsd!!, 1e-9)
        assertTrue("never above the $5 all-in cap", t.allInUsd!! <= 5.0 + 1e-9)
        assertNull("the $10 min-profit gate does not block the rule's side", t.blockedReason)
        assertFalse(t.belowMinProfit)
        val note = t.gateNote!!
        assertTrue(note, note.startsWith("Clear lead · Kalshi prices UP at 80¢ with 8:00 left"))
        assertTrue(note, note.endsWith("Approve still required"))
    }

    @Test
    fun headlineIsBetDownWhenKalshiFavoursDown() {
        val m = market(yesAsk = 0.20, noAsk = 0.81, lead = "NO")
        val call = BetCall.decide(m, TicketBuilder.Context(SignalSettings(), alertsPaused = false, nowMs = now))
        assertEquals(BetCall.Headline.BET_DOWN, call.headline)
        assertEquals("NO", call.ticket!!.side)
        assertTrue(call.ticket!!.clearLead)
        assertTrue(call.ticket!!.gateNote, call.ticket!!.gateNote!!.contains("Kalshi prices DOWN at 81¢"))
    }

    @Test
    fun theBuyButtonBuildsTheSameTicketAndTheOtherSideKeepsItsGate() {
        val m = market(yesAsk = 0.80, noAsk = 0.21, lead = "YES")
        val ctx = TicketBuilder.Context(SignalSettings(), alertsPaused = false, nowMs = now)
        val buy = TicketBuilder.proposeManual(m, "YES", ctx)!!
        assertTrue(buy.clearLead)
        assertTrue(buy.canApprove)
        // No clear lead on this market: the same 80¢ buy is still blocked by the $10 minimum.
        val plain = TicketBuilder.proposeManual(market(yesAsk = 0.80, noAsk = 0.21), "YES", ctx)!!
        assertFalse(plain.clearLead)
        assertFalse(plain.canApprove)
        assertTrue(plain.blockedReason!!.contains("below the $10 minimum"))
        // The other side is an ordinary manual buy.
        assertFalse(TicketBuilder.proposeManual(m, "NO", ctx)!!.clearLead)
    }

    @Test
    fun sitOutDoesNotSilenceTheRuleButTheSettingDoes() {
        val m = market(yesAsk = 0.80, noAsk = 0.21, lead = "YES")
        val sittingOut = SignalSettings(autoTuneEnabled = true, autoTuneManualOverride = false, sitOut = true)
        assertTrue(sittingOut.isSittingOut())
        val call = BetCall.decide(m, TicketBuilder.Context(sittingOut, alertsPaused = false, nowMs = now))
        assertEquals(BetCall.Headline.BET_UP, call.headline)

        val off = SignalSettings(clearLeadEnabled = false)
        val quiet = BetCall.decide(m, TicketBuilder.Context(off, alertsPaused = false, nowMs = now))
        assertEquals(BetCall.Headline.NO_BET, quiet.headline)
        assertNull(TicketBuilder.proposeClearLead(m, TicketBuilder.Context(off, alertsPaused = false, nowMs = now)))

        val noTickets = SignalSettings(ticketsEnabled = false)
        assertEquals(
            BetCall.Headline.NO_BET,
            BetCall.decide(m, TicketBuilder.Context(noTickets, alertsPaused = false, nowMs = now)).headline
        )
    }

    @Test
    fun closedWindowOrNoSellersIsNotACall() {
        val ctx = TicketBuilder.Context(SignalSettings(), alertsPaused = false, nowMs = now)
        val closed = market(yesAsk = 0.80, noAsk = 0.21, lead = "YES").copy(closeTimeEpochMs = now - 1_000L)
        assertNull(TicketBuilder.proposeClearLead(closed, ctx))
        assertNull(BetCall.clearLeadCall(closed, ctx))
        val noSellers = market(yesAsk = 0.80, noAsk = 0.21, lead = "YES").copy(yesAsk = null, noBid = null)
        assertNull(TicketBuilder.proposeClearLead(noSellers, ctx))
    }

    // ---- helpers ----

    private fun engineWithSpot(lastPrice: Double): ScoringEngine {
        val engine = ScoringEngine(idFactory = { "lead" })
        engine.external = ExternalSnapshot(
            btc = AssetSpotFeatures(
                asset = "BTC",
                lastPrice = lastPrice,
                spotReturn1m = 0.0002,
                source = "test",
                fetchedAtMs = 1L
            )
        )
        return engine
    }

    private fun tick(close: Long, yesBid: Double, yesAsk: Double) = MarketTick(
        ticker = "KXBTC15M-LEAD",
        series = "KXBTC15M",
        yesBid = yesBid,
        yesAsk = yesAsk,
        lastPrice = (yesBid + yesAsk) / 2.0,
        volume = 20_000.0,
        openInterest = 2_000.0,
        closeTimeEpochMs = close,
        source = TickSource.REST,
        receiveElapsedNanos = 1L,
        floorStrike = strike
    )

    private fun market(yesAsk: Double, noAsk: Double, lead: String? = null) = MarketUiModel(
        ticker = "KXBTC15M-LEAD",
        title = "Bitcoin price up?",
        subtitle = null,
        floorStrike = strike,
        yesBid = (1.0 - noAsk),
        yesAsk = yesAsk,
        noBid = (1.0 - yesAsk),
        noAsk = noAsk,
        lastPrice = yesAsk,
        yesProbabilityPercent = yesAsk * 100.0,
        noProbabilityPercent = noAsk * 100.0,
        volume = 20_000.0,
        volume24h = 20_000.0,
        openInterest = 2_000.0,
        liquidityDollars = 5_000.0,
        closeTimeLocal = null,
        closeTimeEpochMs = now + 480_000L,
        status = "active",
        seriesLabel = "Bitcoin",
        clearLeadSide = lead,
        clearLeadAsk = lead?.let { if (it == "NO") noAsk else yesAsk }
    )
}
