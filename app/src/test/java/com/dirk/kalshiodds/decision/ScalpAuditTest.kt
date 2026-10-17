package com.dirk.kalshiodds.decision

import java.math.BigDecimal
import java.math.RoundingMode
import org.junit.Assert.*
import org.junit.Test

class ScalpAuditTest {
    private val ticker = "KXBTC15M-26OCT122015-15"
    private val close = ScalpTicker.closeMs(ticker)!!
    private val at = close - 300_000L
    private fun q(ms: Long, bid: Double, side: String = "YES", size: Double = 10.0) = ScalpRule.Quote(
        ticker, ms, close, ms, if (side == "YES") bid else 1 - (bid + .01), size,
        if (side == "YES") bid + .01 else 1 - bid, size, null, null, null)
    private fun held(variant: String, side: String = "YES", id: String = "held") = ScalpTrade(
        id, ticker, side, ScalpState.OPEN, at - 2000, .50, .5, contracts = 3,
        entryPrice = .50, entryFeeUsd = .06, entryAtMs = at - 1000,
        note = "v2peakrule=1 · v2stopPct=20 · v2trail=.03", ruleVersion = "v|$variant|P")
    private fun tick(b: ScalpBook, q: ScalpRule.Quote) {
        b.onQuote(q, false); b.onForecastQuote(q, 10.0, 10.0, false); b.onShockFavQuote(q, false)
    }
    private fun row(b: ScalpBook) = b.allTrades().single { it.id == "held" }

    @Test fun onePercentUsesFillNotOneCentOrBankrollAndDoesNotRoundThreshold() {
        assertEquals(.5445, ScalpRisk.stopPrice(.55, 1.0), 1e-12)
        assertEquals(.396, ScalpRisk.stopPrice(.40, 1.0), 1e-12)
        assertEquals(.0099, ScalpRisk.stopPrice(.01, 1.0), 1e-12)
        assertFalse(ScalpRisk.hit(.55, .544501, 1.0))
        assertTrue(ScalpRisk.hit(.55, .5445, 1.0))
        assertTrue(ScalpRisk.hit(.55, .54, 1.0)) // spread counts as a price loss
        assertFalse(ScalpRisk.hit(.55, Double.NaN, 1.0))
        assertFalse(ScalpRisk.hit(.55, null, 1.0))
    }

    @Test fun everyModelStopsBothSidesWithMissingSpotAndDoesNotReuseDepthOrCancelOnRebound() {
        for (model in ScalpModels.ALL) for (side in listOf("YES", "NO")) {
            fun variantOf(m: ScalpModels.Model): String {
                if (ScalpModels.isV2(m)) return if (ScalpV2.isExtra(m.code)) m.code else "v2-" + variantOf(ScalpModels.originalOf(m)!!)
                if (ShockFav.isShockFav(m.code)) return m.code
                if (m == ScalpModels.Model.FORECAST_RIDE) return ForecastScalp.variantId(true, 600)
                if (m == ScalpModels.Model.FORECAST_FADE) return ForecastScalp.variantId(false, 600)
                val params = ScalpParams.STRATEGY_SEEDS.values + ScalpParams.HOLD60_SIBLINGS +
                    ScalpParams.QUEUED_COPIES + ScalpModels.MAKER_DIP_PARAMS
                return params.first { ScalpModels.modelOf(it) == m }.id
            }
            val variant = variantOf(model)
            assertEquals(model, ScalpModels.modelOf(held(variant, side)))
            val backing = InMemoryScalpPersistence().also { it.seed(listOf(held(variant, side))) }
            val b = ScalpBook(backing, variants = emptyList(), liveExtras = true)
            tick(b, q(at, .499, side))
            assertEquals("${model.code} $side above stop", ScalpState.OPEN, row(b).state)
            tick(b, q(at + 1000, .495, side, 2.0))
            assertEquals("${model.code} $side trigger", ScalpState.PENDING_EXIT, row(b).state)
            assertEquals(0, row(b).soldContracts)
            tick(b, q(at + 2000, .494, side, 2.0))
            assertEquals("${model.code} $side partial", 2, row(b).soldContracts)
            tick(b, q(at + 2100, .494, side, 2.0).copy(bookAtMs = at + 2000))
            assertEquals(2, row(b).soldContracts)
            b.flushWrites()
            val restart = ScalpBook(backing, variants = emptyList(), liveExtras = true,
                controls = MemoryScalpControls(ScalpControls(enabled = false, stopLossPercent = 80)))
            tick(restart, q(at + 3000, .60, side, 2.0)) // latched stop survives rebound, pause, higher cap and restart
            val closed = row(restart)
            assertEquals("${model.code} $side closed", ScalpState.CLOSED, closed.state)
            assertEquals(3, closed.soldContracts)
            assertEquals(1.588, closed.proceedsUsd, 1e-10)
            assertEquals(.06, closed.exitFeeUsd, 1e-10)
            assertEquals(-.032, closed.netUsd!!, 1e-10)
            assertEquals(2, ScalpFills.sales(closed).size)
        }
    }

    @Test fun stopsWaitOnStaleOrGappedBooksAndDoNotSellOnTheEntrySnapshot() {
        for (gap in listOf(false, true)) {
            val t = held("v2-sf41").copy(entryAtMs = at)
            val store = InMemoryScalpPersistence().also { it.seed(listOf(t)) }
            val b = ScalpBook(store, variants = emptyList(), liveExtras = true)
            tick(b, q(at, .49))
            assertEquals(ScalpState.OPEN, row(b).state)
            tick(b, q(at + 5000, .49).copy(bookAtMs = if (gap) at + 5000 else at, bookGap = gap))
            assertEquals(ScalpState.OPEN, row(b).state)
            tick(b, q(at + 6000, .49))
            assertEquals(ScalpState.PENDING_EXIT, row(b).state)
        }
    }

    @Test fun shockOrdersCannotBeFilledByTheGenericStrategyEngine() {
        val pending = held("sf28").copy(state = ScalpState.PENDING_ENTRY, contracts = 0, entryPrice = null,
            entryAtMs = null, signalAtMs = at, signalAsk = .50, restingLimit = .49)
        val b = ScalpBook(InMemoryScalpPersistence().also { it.seed(listOf(pending)) }, variants = emptyList(), liveExtras = true)
        b.onQuote(q(at + 3000, .49).copy(yesAsk = .50), false)
        assertEquals(ScalpState.PENDING_ENTRY, row(b).state)
        b.onShockFavQuote(q(at + 3000, .47, size = 2.0).copy(yesAsk = .48), false)
        assertEquals(ScalpState.OPEN, row(b).state)
        assertEquals(2, row(b).contracts)
        assertEquals(.49, row(b).entryPrice!!, 1e-10)
    }

    @Test fun forecastOrdersRequireFreshLaterSnapshotAndPartialExitsConsumeDepthOnce() {
        val variant = ForecastScalp.variantId(true, 600)
        val p = held(variant).copy(state = ScalpState.PENDING_ENTRY, entryPrice = null, entryAtMs = null,
            contracts = 0, signalAtMs = at, restingLimit = .50)
        val b = ScalpBook(InMemoryScalpPersistence().also { it.seed(listOf(p)) }, variants = emptyList(), liveExtras = true)
        b.onForecastQuote(q(at, .49).copy(yesAsk = .50), 10.0, 10.0, false)
        assertEquals(ScalpState.PENDING_ENTRY, row(b).state)
        b.onForecastQuote(q(at + 1000, .49).copy(yesAsk = .50, bookGap = true), 10.0, 10.0, false)
        assertEquals(ScalpState.PENDING_ENTRY, row(b).state)
        b.onForecastQuote(q(at + 1500, .49).copy(yesAsk = .50), 10.0, 10.0, false)
        assertEquals(ScalpState.OPEN, row(b).state)
    }

    @Test fun feeRoundingMatchesIndependentDecimalCalculationAndIsSymmetric() {
        for (n in listOf(1, 2, 3, 10, 100, 1000)) for (cents in 1..99) {
            val p = BigDecimal(cents).movePointLeft(2)
            val expected = BigDecimal("0.07").multiply(BigDecimal(n)).multiply(p)
                .multiply(BigDecimal.ONE.subtract(p)).setScale(2, RoundingMode.CEILING).toDouble()
            assertEquals("$n @ $cents", expected, ScalpRule.orderFee(n, cents / 100.0), 1e-10)
            assertEquals(expected, ScalpRule.orderFee(n, (100 - cents) / 100.0), 1e-10)
        }
        assertEquals(.5, ScalpRule.normCdf(0.0), 1e-7)
        for (z in listOf(.1, .5, 1.0, 2.0, 4.0)) assertEquals(1.0, ScalpRule.normCdf(z) + ScalpRule.normCdf(-z), 1e-9)
    }

    @Test fun forecastFeeGateUsesClipRoundingAndReachableExitPrices() {
        assertTrue(ForecastScalp.wantsEntry(.5, .01, .50, .055, 300.0, .005))
        assertFalse(ForecastScalp.wantsEntry(.5, .01, .50, .045, 300.0, .005))
        assertFalse(ForecastScalp.wantsEntry(.5, .01, .50, Double.NaN, 300.0))
        assertFalse(ForecastScalp.wantsEntry(.5, .01, .50, 10.0, 300.0, .51)) // payout capped at $1
    }
    @Test fun riskStateAndLiquidityWatermarkSurviveTruncatedNoteSync() {
        val t = held("v2-sf41").copy(note = "context · riskStopPct=1.0 · riskExit=1 · scalpSellBook=1000 · o50open=.5")
        val execution = ScalpFills.executionNotes(t.note)
        val restored = t.copy(note = ScalpFills.restoreNote("truncated context", execution))
        assertTrue(ScalpRisk.managedExit(restored))
        assertEquals(1.0, ScalpRisk.percent(restored, ScalpControls(stopLossPercent = 80)), 0.0)
        assertEquals(1000L, ScalpFills.lastSellBook(restored))
        assertFalse(ScalpFills.canSell(restored, 1000L))
    }

    @Test fun stopPolicyMigrationResetsOnlyRiskThenPreservesLaterUserChoice() {
        val old = ScalpControls(stopLossPercent = 20, contracts = 7, disabledStrategies = setOf("v2-sf41"),
            strategySettings = mapOf("v2-sf42" to ScalpStrategySettings(stopLossPercent = 30, trailCents = 5)))
        val upgraded = old.migrateStopPolicy()
        assertEquals(1, upgraded.stopLossPercent)
        assertEquals(7, upgraded.contracts)
        assertEquals(old.disabledStrategies, upgraded.disabledStrategies)
        assertNull(upgraded.settings("v2-sf42").stopLossPercent)
        assertEquals(5, upgraded.settings("v2-sf42").trailCents)
        assertEquals(8, upgraded.copy(stopLossPercent = 8).migrateStopPolicy().stopLossPercent)
    }
}
