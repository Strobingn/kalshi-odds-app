package com.dirk.kalshiodds.signal

import com.dirk.kalshiodds.data.local.history.SettingsRestore
import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.domain.withSignalScore
import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.engine.ScoringEngine
import com.dirk.kalshiodds.signal.external.AssetSpotFeatures
import com.dirk.kalshiodds.signal.external.ExternalSnapshot
import com.dirk.kalshiodds.signal.ml.HeavyMlGuard
import com.dirk.kalshiodds.signal.model.MarketTick
import com.dirk.kalshiodds.signal.model.TickSource
import com.dirk.kalshiodds.signal.trade.BetCall
import com.dirk.kalshiodds.signal.trade.TicketBuilder
import com.dirk.kalshiodds.ui.HomeCopy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Entry filter wired through the engine (alerts), the market model, the
 * BetCall headline, and auto tickets (docs/ml-review-2026-09-27.md #7).
 */
class EntryFilterScoringTest {

    @Before
    fun setUp() {
        HeavyMlGuard.reset()
    }

    @After
    fun tearDown() {
        HeavyMlGuard.reset()
    }

    private val now = 1_000_000L

    @Test
    fun earlyWindowTickIsNotAlertedAndCarriesTheEntryReason() {
        val engine = engineWithSpot(lastPrice = 100_241.0, ret1m = 0.004)
        val tick = tick(close = now + 820_000L, yesBid = 0.88, yesAsk = 0.92)
        val score = engine.score(tick, settings(), now)
        assertNotNull(score)
        assertFalse(score!!.passedFilter)
        assertEquals("too early: 13:40 left (wait until 12:00)", score.entryBlockReason)
        assertEquals(score.entryBlockReason, score.skipReason)
        assertTrue(score.reason.contains("filtered"))
        assertNull(engine.maybeAlert(tick, settings(), now))

        val ui = market().withSignalScore(score, thresholdPp = 5.0)
        assertFalse(ui.passedFilter)
        assertEquals("too early: 13:40 left (wait until 12:00)", ui.entryBlockReason)
        assertEquals("Filtered — too early: 13:40 left (wait until 12:00)", ui.stance)
    }

    @Test
    fun sameTickAlertsOnceTheFilterIsOff() {
        val engine = engineWithSpot(lastPrice = 100_241.0, ret1m = 0.004)
        val tick = tick(close = now + 820_000L, yesBid = 0.88, yesAsk = 0.92)
        val off = settings().copy(entryFilterEnabled = false)
        val score = engine.score(tick, off, now)
        assertNotNull(score)
        assertNull(score!!.entryBlockReason)
        assertTrue(score.passedFilter)
        assertNotNull("control: only the entry filter blocked the alert", engine.maybeAlert(tick, off, now))
    }

    @Test
    fun twelveMinutesLeftIsAllowed() {
        val engine = engineWithSpot(lastPrice = 100_241.0, ret1m = 0.004)
        val score = engine.score(tick(close = now + 720_000L, yesBid = 0.88, yesAsk = 0.92), settings(), now)
        assertNotNull(score)
        assertNull(score!!.entryBlockReason)
        assertTrue(score.passedFilter)
    }

    @Test
    fun nearStrikeMidWindowIsFiltered() {
        val engine = engineWithSpot(lastPrice = 100_010.0, ret1m = 0.0)
        val tick = tick(close = now + 600_000L, yesBid = 0.48, yesAsk = 0.52)
        val score = engine.score(tick, settings(), now)
        assertNotNull(score)
        assertFalse(score!!.passedFilter)
        assertEquals("near strike: 1.0bp < 5bp", score.entryBlockReason)
        assertNull(engine.maybeAlert(tick, settings(), now))

        val offEngine = engineWithSpot(lastPrice = 100_010.0, ret1m = 0.0)
        val noBp = offEngine.score(tick, settings().copy(entryMinStrikeDistanceBp = 0.0), now)
        assertNotNull(noBp)
        assertNull(noBp!!.entryBlockReason)
    }

    @Test
    fun missingSpotDoesNotBlockMidWindow() {
        val engine = ScoringEngine(idFactory = { "entry-nospot" })
        val score = engine.score(tick(close = now + 600_000L, yesBid = 0.48, yesAsk = 0.52), settings(), now)
        assertNotNull(score)
        assertNull(score!!.entryBlockReason)
        assertTrue(score.passedFilter)
    }

    @Test
    fun betCallShowsTheEntryReasonInsteadOfABet() {
        val open = betMarket()
        val ctx = TicketBuilder.Context(settings = SignalSettings(), alertsPaused = false)
        assertEquals("control: this market is a BET UP", BetCall.Headline.BET_UP, BetCall.decide(open, ctx).headline)

        val reason = "too early: 13:40 left (wait until 12:00)"
        val blocked = open.copy(entryBlockReason = reason)
        val decision = BetCall.decide(blocked, ctx)
        assertEquals(BetCall.Headline.NO_BET, decision.headline)
        assertFalse(decision.isActionable)
        assertEquals(reason, decision.noBetReason)
        val line = HomeCopy.thisWindowHeadline(decision, blocked, System.currentTimeMillis())
        assertTrue(line, line.contains(reason))

        val off = TicketBuilder.Context(settings = SignalSettings(entryFilterEnabled = false), alertsPaused = false)
        assertEquals(BetCall.Headline.BET_UP, BetCall.decide(blocked, off).headline)
    }

    @Test
    fun autoTicketsAreBlockedEvenWhenTicketsIgnoreGates() {
        val open = betMarket()
        val ctx = TicketBuilder.Context(
            settings = SignalSettings(ticketRespectGates = false),
            alertsPaused = false
        )
        assertTrue("control: long-shot ticket without the block", TicketBuilder.proposeAll(listOf(open), ctx).isNotEmpty())

        val blocked = open.copy(entryBlockReason = "near strike: 2.1bp < 5bp")
        assertTrue(TicketBuilder.proposeAll(listOf(blocked), ctx).isEmpty())
        assertNull(TicketBuilder.propose(blocked, ctx))
        assertNull(TicketBuilder.proposeHunter(blocked, ctx))
        assertNull(TicketBuilder.proposeHunterValue(blocked, ctx))
        assertEquals("near strike: 2.1bp < 5bp", TicketBuilder.entryBlockReason(blocked, ctx.settings))

        // Buy anyway is the user's call.
        val manual = TicketBuilder.proposeManual(blocked, "YES", ctx)
        assertNotNull(manual)
        assertTrue(manual!!.canApprove)

        val off = ctx.copy(settings = ctx.settings.copy(entryFilterEnabled = false))
        assertNull(TicketBuilder.entryBlockReason(blocked, off.settings))
        assertTrue(TicketBuilder.proposeAll(listOf(blocked), off).isNotEmpty())
    }

    @Test
    fun settingsRestoreRoundTripsEntryFields() {
        val s = SignalSettings(
            entryFilterEnabled = false,
            entryMinElapsedMinutes = 5,
            entryMinStrikeDistanceBp = 3.0,
            entryNearStrikeOverridePp = 12.0
        )
        val parsed = SettingsRestore.parse(SettingsRestore.snapshot(s))
        assertEquals(false, parsed.entryFilterEnabled)
        assertEquals(5, parsed.entryMinElapsedMinutes)
        assertEquals(3.0, parsed.entryMinStrikeDistanceBp!!, 1e-9)
        assertEquals(12.0, parsed.entryNearStrikeOverridePp!!, 1e-9)
        val legacy = SettingsRestore.parse("""{"ticketStakeUsd":5.0}""")
        assertNull(legacy.entryFilterEnabled)
        assertNull(legacy.entryMinElapsedMinutes)
    }

    private fun settings() = SignalSettings(
        watchBtc = true,
        edgeThresholdPp = 0.01,
        debounceMs = 10_000L,
        minConfidence = 0.0,
        minLiquidity = 0.0,
        maxSpreadCents = 50.0,
        hideWeakOpportunities = false,
        heavyMlEnabled = false,
        extendedAiEnabled = false
    )

    private fun engineWithSpot(lastPrice: Double, ret1m: Double): ScoringEngine {
        val engine = ScoringEngine(idFactory = { "entry" })
        engine.external = ExternalSnapshot(
            btc = AssetSpotFeatures(
                asset = "BTC",
                lastPrice = lastPrice,
                spotReturn1m = ret1m,
                source = "test",
                fetchedAtMs = 1L
            )
        )
        return engine
    }

    private fun tick(close: Long, yesBid: Double, yesAsk: Double) = MarketTick(
        ticker = "KXBTC15M-ENTRY",
        series = "KXBTC15M",
        yesBid = yesBid,
        yesAsk = yesAsk,
        lastPrice = (yesBid + yesAsk) / 2.0,
        volume = 20_000.0,
        openInterest = 2_000.0,
        closeTimeEpochMs = close,
        source = TickSource.REST,
        receiveElapsedNanos = 1L,
        floorStrike = 100_000.0
    )

    private fun market() = MarketUiModel(
        ticker = "KXBTC15M-ENTRY",
        title = "Bitcoin price up?",
        subtitle = "Above 100000",
        floorStrike = 100_000.0,
        yesBid = 0.88,
        yesAsk = 0.92,
        noBid = 0.08,
        noAsk = 0.12,
        lastPrice = 0.90,
        yesProbabilityPercent = 90.0,
        noProbabilityPercent = 10.0,
        volume = 20_000.0,
        volume24h = 20_000.0,
        openInterest = 2_000.0,
        liquidityDollars = 5_000.0,
        closeTimeLocal = null,
        closeTimeEpochMs = now + 820_000L,
        status = "open",
        seriesLabel = "Bitcoin"
    )

    /** Same shape as BetCallAgreementTest's BET UP sample (20¢ UP, AI 80%). */
    private fun betMarket() = MarketUiModel(
        ticker = "KXBTC15M-ENTRYBET",
        title = "Bitcoin price up?",
        subtitle = null,
        floorStrike = 100_000.0,
        yesBid = 0.19,
        yesAsk = 0.20,
        noBid = 0.79,
        noAsk = 0.80,
        lastPrice = 0.20,
        yesProbabilityPercent = 20.0,
        noProbabilityPercent = 80.0,
        aiYesPercent = 80.0,
        aiNoPercent = 20.0,
        volume = 2000.0,
        volume24h = 2000.0,
        openInterest = 200.0,
        liquidityDollars = 8000.0,
        closeTimeLocal = null,
        closeTimeEpochMs = System.currentTimeMillis() + 600_000L,
        status = "active",
        seriesLabel = "Bitcoin",
        passedFilter = true,
        predictedSide = "YES",
        primaryHeroSide = "YES"
    )
}
