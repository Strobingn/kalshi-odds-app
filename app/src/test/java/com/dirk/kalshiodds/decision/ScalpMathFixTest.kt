package com.dirk.kalshiodds.decision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/** Regression cases from the Gemini audit, adapted to this branch's forecast and HFT engines. */
class ScalpMathFixTest {
    private val now = 1_700_000_000_000L
    private val ticker = "KXBTC15M-26OCT181200-00"

    @Test fun forecastEntryMustClearTheRoundedRoundTripAndMargin() {
        for (ask in listOf(0.20, 0.40, 0.49)) {
            val cost = ScalpRule.roundTripCost(ask, 0.01, 10)
            assertTrue(cost in 0.033..0.046000001)
            assertFalse(ForecastScalp.wantsEntry(0.45, 0.01, ask, 0.001, 600.0))
            assertFalse(ForecastScalp.wantsEntry(0.45, 0.01, ask, cost + 0.02, 600.0, marginUsd = 0.02))
            assertTrue(ForecastScalp.wantsEntry(0.45, 0.01, ask, cost + 0.0201, 600.0, marginUsd = 0.02))
        }
    }

    @Test fun smallFillsUseTheirOwnPerOrderFeeRounding() {
        assertTrue(ForecastScalp.wantsEntry(0.45, 0.01, 0.45, 0.048, 600.0, marginUsd = 0.0, contracts = 10))
        assertFalse(ForecastScalp.wantsEntry(0.45, 0.01, 0.45, 0.048, 600.0, marginUsd = 0.0, contracts = 1))
        assertFalse(ForecastScalp.wantsEntry(0.45, -0.01, 0.45, 0.20, 600.0))
        assertFalse(ForecastScalp.wantsEntry(0.45, 0.01, 0.45, 0.20, 600.0, contracts = 0))
    }

    @Test fun subCentForecastNoiseDoesNotChurnThePosition() {
        for (move in listOf(0.02, 0.0, -0.001, -0.0099)) {
            assertNull(ForecastScalp.wantsExit(move, 0.45, 5.0, 600, 300.0))
        }
        assertNotNull(ForecastScalp.wantsExit(-0.01, 0.45, 5.0, 600, 300.0))
        assertEquals("forecast flipped hard", ForecastScalp.wantsExit(-0.04, 0.45, 5.0, 600, 300.0))
        assertEquals("exit before close", ForecastScalp.wantsExit(Double.NaN, 0.45, 5.0, 600, 20.0))
    }

    @Test fun leadFeaturesUseSettlementVarianceWithoutChangingTheClockFeature() {
        val spot = 100_100.0
        val strike = 100_000.0
        val sig = 3e-5
        for (tau in listOf(0.5, 30.0, 60.0, 90.0, 450.0, 900.0)) {
            val x = LeadForecaster.features(0.5, 0.5, 0.0, spot, strike, sig, tau) { spot }!!
            val d = (ln(spot / strike) / (sig * sqrt(ScalpRule.effectiveTauSeconds(tau)))).coerceIn(-4.0, 4.0)
            assertEquals(d / 4.0, x[5], 1e-12)
            assertEquals(ScalpRule.normCdf(d) - 0.5, x[4], 1e-12)
            assertEquals(tau.coerceAtLeast(1.0) / 900.0, x[6], 1e-12)
        }
    }

    @Test fun mirroredLeadInputsProduceOppositeForecasts() {
        val strike = 100_000.0
        val move = 0.0002
        val up = LeadForecaster.features(0.52, 0.50, 0.3, strike * exp(move), strike, 3e-5, 90.0) { strike }!!
        val down = LeadForecaster.features(0.48, 0.50, -0.3, strike * exp(-move), strike, 3e-5, 90.0) { strike }!!
        for (h in LeadForecaster.HORIZONS_S) {
            assertEquals(LeadForecaster.predict("BTC", h, up)!!, -LeadForecaster.predict("BTC", h, down)!!, 1e-10)
        }
        assertNull(LeadForecaster.features(0.5, null, 0.0, Double.POSITIVE_INFINITY, strike, 3e-5, 90.0) { strike })
    }

    @Test fun stopFloorCoversEntrySpreadFeeAndOneMoreTick() {
        assertEquals(0.038, ForecastScalp.minimumStopDistance(0.49, 0.01, 10), 1e-12)
        assertEquals(0.03, ForecastScalp.minimumStopDistance(0.20, 0.001, 10), 1e-12)
        val t = trade().copy(note = "filled; fcStopMin=0.0380", entryPrice = 0.49)
        assertEquals(0.038, ForecastScalp.stopDistance(t, 0.02), 1e-12)
        assertEquals(0.08, ForecastScalp.stopDistance(t, 0.08), 1e-12)
        assertTrue(0.49 - 0.02 > 0.49 - ForecastScalp.stopDistance(t, 0.02))
    }

    @Test fun keptForecastUsesItsOwnRiskExitsOnThePaperBook() {
        val controls = MemoryScalpControls(ScalpControls(strategySettings = mapOf(
            KeptScalpers.FADE_ID to ScalpStrategySettings(stopCents = 2)
        )))
        for (side in listOf("YES", "NO")) {
            val book = ScalpBook(controls = controls, bankrollUsd = { 20_000.0 })
            book.importRestored(listOf(trade().copy(side = side, entryPrice = 0.49, note = "fcStopMin=0.0380")))
            fun quote(at: Long, sideBid: Double): ScalpRule.Quote {
                val yesBid = if (side == "YES") sideBid else 1.0 - sideBid - 0.01
                return ScalpRule.Quote(ticker, at, now + 600_000L, at, yesBid, 100.0, yesBid + 0.01, 100.0,
                    100_000.0, 100_000.0, 3e-5)
            }
            book.onForecastQuote(quote(now + 1_000L, 0.47), 100.0, 100.0, false)
            assertEquals(ScalpState.OPEN, book.allTrades().single().state)
            book.onForecastQuote(quote(now + 2_000L, 0.45), 100.0, 100.0, false)
            val stopped = book.allTrades().single()
            assertEquals(ScalpState.CLOSED, stopped.state)
            assertTrue(stopped.exitReason.orEmpty(), stopped.exitReason.orEmpty().startsWith("stop"))
        }
    }

    @Test fun studentTMatchesDistributionsWithKnownClosedForms() {
        assertEquals(1.0 / Math.PI, HftSignals.studentTPdf(0.0, 1.0), 1e-12)
        assertEquals(0.75, HftSignals.studentTCdf(1.0, 1.0), 1e-9)
        assertEquals(0.25, HftSignals.studentTCdf(-1.0, 1.0), 1e-9)
        assertEquals(0.5 + 1.0 / (2.0 * sqrt(3.0)), HftSignals.studentTCdf(1.0, 2.0), 1e-9)
        // A numerical derivative checks the CDF and density against each other, independently of Greeks.
        val h = 1e-4
        for (z in listOf(-2.0, -0.1, 0.1, 2.0)) {
            val slope = (HftSignals.studentTCdf(z + h) - HftSignals.studentTCdf(z - h)) / (2.0 * h)
            assertEquals(HftSignals.studentTPdf(z), slope, 1e-7)
        }
    }

    @Test fun forecastVariantsKeepTheirOwnPredictionAndExitHorizon() {
        val variant = ScalpVariants.ALL.first { it.kind == ScalpVariants.Kind.FADE && it.fadeHorizonS == 300 }
        val book = ScalpBook(bankrollUsd = { 20_000.0 })
        book.importRestored(listOf(trade().copy(side = "NO", signalAsk = 0.15, entryPrice = 0.15,
            ruleVersion = "${ScalpRule.VERSION}|${variant.id}|P")))
        fun quote(at: Long) = ScalpRule.Quote(ticker, at, now + 600_000L, at,
            0.84, 100.0, 0.86, 100.0, 100_000.0, 100_000.0, 3e-5)
        // The 600s model predicts a >1¢ reversal here; this trade's 300s model predicts only noise.
        book.onForecastQuote(quote(now + 1_000L), 100.0, 100.0, false)
        assertEquals(ScalpState.OPEN, book.allTrades().single().state)
        book.onForecastQuote(quote(now + 301_000L), 100.0, 100.0, false)
        assertEquals("horizon reached", book.allTrades().single().exitReason)
    }

    @Test fun deeperBookChangesContributeToOfiAndUnchangedSnapshotsDoNot() {
        val ofi = HftMathEngines.DepthOfi()
        val bids = listOf(0.49 to 10.0, 0.48 to 10.0)
        val asks = listOf(0.51 to 10.0, 0.52 to 10.0)
        assertEquals(0.0, ofi.update(bids, asks), 0.0)
        val changed = listOf(0.49 to 10.0, 0.48 to 20.0)
        assertEquals(exp(-0.5) / 3.0, ofi.update(changed, asks), 1e-12)
        assertEquals(0.0, ofi.update(changed, asks), 0.0)
        assertTrue(ofi.update(changed, asks.take(1)) > 0.0)
    }

    @Test fun aLargeModelDisagreementCannotAuthorizeNegativeExpectedValue() {
        assertNull(HftMathEngines.arbSide(0.5, 0.2, 0.52, 0.50, 0.02, 3.0, 40.0))
        assertNull(HftMathEngines.arbSide(0.5, 0.8, 0.50, 0.52, 0.02, -3.0, 40.0))
    }

    @Test fun hftCannotFillAFractionalDisplayedContract() {
        val book = HftBook()
        val q = ScalpRule.Quote(ticker, now, now + 40_000L, now,
            0.39, 0.4, 0.40, 0.4, 101_000.0, 100_000.0, 3e-5)
        assertTrue(book.onQuote(q, 20_000.0) { "hft" }.isEmpty())
    }

    @Test fun hftExitsStillRunWhenTheSpreadBlocksNewEntries() {
        val book = HftBook()
        var id = 0
        val q = ScalpRule.Quote(ticker, now, now + 450_000L, now,
            0.39, 100.0, 0.40, 100.0, 100_100.0, 100_000.0, 3e-5)
        val opened = book.onQuote(q, 20_000.0) { "hft${id++}" }
        assertTrue(opened.toString(), opened.any { it.variantId == "iv-smile" && it.state == ScalpState.OPEN })
        val wide = q.copy(nowMs = now + 1_000L, bookAtMs = now + 1_000L, yesBid = 0.85, yesAsk = 0.93)
        assertTrue(book.onQuote(wide.copy(bookGap = true), 20_000.0) { "hft${id++}" }.none { it.state == ScalpState.CLOSED })
        val exited = book.onQuote(wide.copy(nowMs = now + 2_000L, bookAtMs = now + 2_000L), 20_000.0) { "hft${id++}" }
        assertTrue(exited.toString(), exited.any { it.variantId == "iv-smile" && it.state == ScalpState.CLOSED })
        assertFalse(exited.any { it.variantId == "iv-smile" && it.state == ScalpState.OPEN })
    }

    private fun trade() = ScalpTrade(
        id = "forecast", ticker = ticker, side = "YES", state = ScalpState.OPEN,
        signalAtMs = now, signalAsk = 0.49, fairAtSignal = 0.60, contracts = 10,
        entryPrice = 0.49, entryFeeUsd = ScalpRule.orderFee(10, 0.49), entryAtMs = now,
        ruleVersion = "${ScalpRule.VERSION}|${KeptScalpers.FADE_ID}|P"
    )
}
