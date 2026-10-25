package com.dirk.kalshiodds.decision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sqrt

/** 1.0.37: the five paper HFT engines and the live book hook. */
class HftMathEnginesTest {
    @Test fun studentTGreeksMatchTheClosedForm() {
        assertEquals(0.5, HftSignals.studentTCdf(0.0), 1e-9)
        val spot = 100_200.0
        val strike = 100_000.0
        val tau = 450.0
        val sig = HftMathEngines.perSecVol(0.0075)
        val g = HftMathEngines.greeks(spot, strike, sig, tau)
        val z = HftMathEngines.zMoneyness(spot, strike, sig, tau)
        val pdf = HftSignals.studentTPdf(z)
        val adj = sqrt((4.2 - 2.0) / 4.2)
        val sqrtTau = sqrt(tau)
        val delta = pdf * (1.0 / (spot * sig * sqrtTau)) * adj
        val bracket = 1.0 + ((4.2 + 1.0) * z) / (4.2 + z * z) * (1.0 / (sig * sqrtTau)) * adj
        val gamma = -pdf / (spot * spot * sig * sqrtTau) * adj * bracket
        val theta = pdf * z / (2.0 * tau)
        assertEquals(z, g.z, 1e-9)
        assertEquals(delta, g.delta, 1e-12)
        assertEquals(gamma, g.gamma, 1e-18)
        assertEquals(theta, g.theta, 1e-12)
        assertEquals(delta * sig * spot, g.sigmaP, 1e-12)
        assertTrue(g.delta > 0.0)
        assertTrue(g.fair in 0.01..0.99)
        val atm = HftMathEngines.greeks(100_000.0, 100_000.0, sig, tau)
        assertEquals(0.5, atm.fair, 1e-9)
        assertEquals(0.0, atm.theta, 1e-12)
        assertTrue(atm.gamma < 0.0)
        val eps = 1.0
        val up = HftMathEngines.fairProbability(spot + eps, strike, sig, tau)
        val dn = HftMathEngines.fairProbability(spot - eps, strike, sig, tau)
        assertTrue((up - dn) / (2.0 * eps) > 0.0)
        assertEquals(0.99, HftMathEngines.fairProbability(101_000.0, 100_000.0, sig, 0.5), 0.0)
        assertEquals(0.01, HftMathEngines.fairProbability(99_000.0, 100_000.0, sig, 0.5), 0.0)
    }

    @Test fun hjbReservationAndPostOnlyQuotes() {
        val q = HftMathEngines.dosQuote(0.49, 0.51, 100.0, 100.0, 0.50, 0.0, 0.0, 450.0)
        assertEquals(0.50, q.vamp, 1e-12)
        assertEquals(0.50, q.blended, 1e-12)
        assertEquals(0.50, q.reservation, 1e-12)
        assertEquals(0.04, q.halfSpread, 1e-12)
        assertEquals(0.46, q.bid, 1e-12)
        assertEquals(0.54, q.ask, 1e-12)
        assertNull(q.guard)
        assertTrue(q.bid <= kotlin.math.round((0.51 - 0.01) * 100.0) / 100.0 + 1e-9)
        assertTrue(q.ask >= kotlin.math.round((0.49 + 0.01) * 100.0) / 100.0 - 1e-9)
        val skewed = HftMathEngines.dosQuote(0.49, 0.51, 100.0, 100.0, 0.50, 10.0, 0.0, 450.0)
        assertEquals(0.02, skewed.reservation, 1e-12)
        assertEquals(HftMathEngines.GUARD_PIN, HftMathEngines.dosQuote(0.49, 0.51, 10.0, 10.0, 0.5, 0.0, 3.0, 30.0).guard)
        assertEquals(HftMathEngines.GUARD_TOX, HftMathEngines.dosQuote(0.49, 0.51, 10.0, 10.0, 0.5, 0.0, 2.6, 100.0).guard)
    }

    @Test fun fiveLevelOfiAndObizhaevaWangBounce() {
        val e = HftSignals.ofi(0.49, 10.0, 0.51, 10.0, 0.50, 20.0, 0.51, 10.0)
        val one = HftMathEngines.ofiWeighted(listOf(HftMathEngines.OfLevel(e, 0.0, 15.0)))
        assertEquals(e / 30.0, one, 1e-12)
        val levels = (0 until 5).map { HftMathEngines.OfLevel(1.0, 0.0, 1.0) }
        var expect = 0.0
        for (i in 0 until 5) expect += exp(-0.5 * i) * 1.0 / 2.0
        assertEquals(expect, HftMathEngines.ofiWeighted(levels), 1e-12)
        val buf = HftMathEngines.OfiBuffer()
        assertEquals(0.0, buf.update(0.50, 10.0, 0.51, 10.0), 0.0)
        assertEquals(1.0 / 3.0, buf.update(0.50, 20.0, 0.51, 10.0), 1e-12)
        assertEquals(0.0, HftMathEngines.transientBounce(0.02, 1.2, 0.0), 0.0)
        val limit = 0.02 * 1.2
        assertEquals(limit, HftMathEngines.transientBounce(0.02, 1.2, 10_000.0), 1e-6)
        val flat = HftMathEngines.propagator(1.2, 0.02, 0.0, 0.001, 0.0)
        assertTrue(flat.triggered)
        assertEquals(0.0, flat.lambdaPerm, 0.0)
        assertEquals(0.0, flat.bounce, 1e-12)
        val later = HftMathEngines.propagator(1.2, 0.02, 0.0, 0.001, 50.0)
        assertTrue(abs(later.bounce) > 0.01)
        assertFalse(HftMathEngines.propagator(0.2, 0.02, 0.0, 0.001, 10.0).triggered)
    }

    @Test fun settlementStrikeAndOneOverSqrtThree() {
        val alpha = HftMathEngines.settlementAlpha(30.0)
        assertEquals(0.5, alpha, 1e-12)
        assertEquals(104.0, HftMathEngines.kEff(102.0, 100.0, alpha), 1e-9)
        assertEquals(100.5, HftMathEngines.sHat(101.0, 100.0, alpha), 1e-9)
        val sig = HftMathEngines.perSecVol(0.0075)
        val z0 = HftMathEngines.zMoneyness(101_000.0, 100_000.0, sig, 30.0)
        val zt = HftMathEngines.zTerm(101_000.0, 100_000.0, sig, 30.0)
        assertEquals(sqrt(3.0), zt / z0, 1e-9)
        val pTerm = HftSignals.studentTCdf(zt).coerceIn(0.01, 0.99)
        val yes = HftMathEngines.evYes(pTerm, 0.40)
        assertTrue(yes > 0.05)
        assertEquals("TAKER_ARB_BUY_YES", HftMathEngines.arbSide(pTerm, 0.5, 0.40, 0.60, 0.01, 0.0, 40.0))
        assertNull(HftMathEngines.arbSide(pTerm, 0.5, 0.90, 0.90, 0.01, 0.0, 40.0))
        assertNull(HftMathEngines.arbSide(pTerm, 0.5, 0.40, 0.60, 0.01, 0.0, 10.0))
        assertNull(HftMathEngines.arbSide(pTerm, 0.5, 0.40, 0.60, 0.01, 0.0, 120.0))
    }

    @Test fun feeKellyAndFiveGates() {
        val stop = HftMathEngines.makerInTakerStop()
        assertEquals(0.05, stop.winNet, 1e-12)
        assertEquals(0.069, stop.lossNet, 1e-12)
        assertEquals("57.9%", HftMathEngines.percentLabel(stop.pMin))
        assertEquals("50.0%", HftMathEngines.percentLabel(HftMathEngines.makerMaker().pMin))
        val taker = HftMathEngines.takerTaker()
        assertEquals("66.3%", HftMathEngines.percentLabel(taker.pMin))
        val k60 = HftMathEngines.kelly(0.70, stop.winNet, stop.lossNet, stop.collateral, 2_000.0, 60.0)
        val k15 = HftMathEngines.kelly(0.70, stop.winNet, stop.lossNet, stop.collateral, 2_000.0, 15.0)
        assertEquals(1.0, k60.damp, 1e-12)
        assertEquals(0.5, k15.damp, 1e-12)
        assertEquals(k60.rawContracts * 0.5, k15.rawContracts, 1e-9)
        assertEquals(floor(k60.rawContracts).toInt(), k60.contracts)
        assertEquals(floor(k15.rawContracts).toInt(), k15.contracts)
        val pass = HftMathEngines.riskGates(10.0, -10.0, 1000.0, 4, 0.02, 100L, 0.4)
        assertTrue(pass.all { it.pass })
        assertEquals(5, pass.size)
        assertFalse(HftMathEngines.riskGates(26.0, 0.0, 1000.0, 4, 0.02, 100L, 0.2)[0].pass)
        assertFalse(HftMathEngines.riskGates(0.0, -1001.0, 1000.0, 4, 0.02, 100L, 0.2)[1].pass)
        assertFalse(HftMathEngines.riskGates(0.0, 0.0, 1000.0, 0, 0.02, 100L, 0.2)[2].pass)
        assertFalse(HftMathEngines.riskGates(0.0, 0.0, 1000.0, 4, 0.08, 100L, 0.2)[3].pass)
        assertFalse(HftMathEngines.riskGates(0.0, 0.0, 1000.0, 4, 0.02, 100L, 0.90)[4].pass)
        assertFalse(HftMathEngines.riskGates(0.0, 0.0, 1000.0, 4, 0.02, 2_000L, 0.2)[4].pass)
        val slip = HftMathEngines.bookWalkSlippage(listOf(0.40 to 10.0, 0.41 to 10.0), 15.0)
        assertEquals((0.40 * 10 + 0.41 * 5) / 15.0 - 0.40, slip, 1e-12)
        assertTrue(HftMathEngines.syntheticArb(0.40, 0.50, 0.05))
        assertFalse(HftMathEngines.syntheticArb(0.60, 0.50, 0.05))
        assertEquals(6888.03, HftMathEngines.REF_DOS_PNL, 0.0)
        assertEquals(0.99, HftMathEngines.REF_DOS_WIN, 0.0)
        assertEquals(75.86, HftMathEngines.REF_DOS_SHARPE, 0.0)
        assertEquals(-77223.17, HftMathEngines.REF_NAIVE_PNL, 0.0)
        assertTrue(HftMathEngines.referenceDos().contains("6,888.03"))
        assertTrue(HftMathEngines.referenceDos().contains("75.86"))
    }

    @Test fun paperBookRecordsTsArbAndSkipsTheFairGapGate() {
        for (id in HftMathEngines.IDS) {
            assertFalse(FairGapTune.entryGated(id))
            assertEquals(id, ScalpModels.modelByCode(id)?.code)
            assertFalse(ScalpModels.labelFor(id) == KeptScalpers.REMOVED_LABEL)
        }
        assertEquals(KeptScalpers.ALL.map { it.id }, ScalpModels.WINNERS.map { it.code })
        assertTrue(ScalpModels.ALL.size <= KeptScalpers.MAX_SLOTS)
        val now = 1_700_000_000_000L
        val sig = HftMathEngines.perSecVol(0.0075)
        val q = ScalpRule.Quote(
            ticker = "KXBTC15M-26OCT181200-00",
            nowMs = now,
            closeMs = now + 40_000L,
            bookAtMs = now,
            yesBid = 0.39,
            yesBidSize = 100.0,
            yesAsk = 0.40,
            yesAskSize = 100.0,
            spot = 101_000.0,
            strike = 100_000.0,
            sigmaPerSec = sig
        )
        val book = ScalpBook(liveExtras = true, bankrollUsd = { 20_000.0 })
        book.onQuote(q, true)
        val ts = book.snapshot().filter { it.variantId == "ts-arb" }
        assertTrue(ts.toString(), ts.isNotEmpty())
        assertEquals(ScalpState.OPEN, ts.first().state)
        assertTrue(ts.first().contracts >= PaperClip.MIN)
        assertEquals("YES", ts.first().side)
    }

    @Test fun vwapSlopeIsAStandardizedBeta() {
        val t = doubleArrayOf(0.0, 1.0, 2.0, 3.0, 4.0)
        val up = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0)
        assertTrue(HftMathEngines.vwapSlopeZ(t, up) > 2.0)
        val flat = DoubleArray(5) { 3.0 }
        assertEquals(0.0, HftMathEngines.vwapSlopeZ(t, flat), 0.0)
    }
}
