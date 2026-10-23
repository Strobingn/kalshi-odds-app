package com.dirk.kalshiodds.signal.scalper.micro

import com.dirk.kalshiodds.signal.scalper.ScalpStrategy
import com.dirk.kalshiodds.signal.scalper.fleet.FleetExternal
import com.dirk.kalshiodds.signal.scalper.fleet.FleetMath
import com.dirk.kalshiodds.signal.scalper.fleet.FleetOrder
import com.dirk.kalshiodds.signal.scalper.fleet.FleetView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln
import kotlin.math.sqrt

/** The four micro scalpers fire in their own situation, pay for the fee, and stay quiet otherwise. */
class MicroScalpersTest {
    private val k = 100_000.0
    private val sigma = 2e-5

    private fun view(
        s: Int = 400, spot: Double = k, yesBid: Double = 0.49, yesAsk: Double = 0.51,
        index1s: DoubleArray = DoubleArray(600) { k }, yesMids: DoubleArray = DoubleArray(120) { 0.50 },
        yesLevels: List<Pair<Double, Double>> = emptyList(), noLevels: List<Pair<Double, Double>> = emptyList(),
        yesBidQty: Double = 100.0, yesAskQty: Double = 100.0, nowMs: Long = 1_800_000_000_000L
    ) = FleetView(
        ticker = "KXBTC15M-TEST", nowMs = nowMs, s = s, tte = 900.0 - s, strike = k, spot = spot, sigma1sLog = sigma,
        index1s = index1s, finalPrints = DoubleArray(0),
        yesBid = yesBid, yesAsk = yesAsk, noBid = 1 - yesAsk, noAsk = 1 - yesBid,
        yesBidQty = yesBidQty, yesAskQty = yesAskQty, noBidQty = 100.0, noAskQty = 100.0,
        yesLevels = yesLevels, noLevels = noLevels, yesMids = yesMids, tradeGap = 1.0, fairYes = null,
        vpin = null, recentYesShare = null, behavioural = { null }, inventory = { 0.0 }, restingAt = { _, _ -> emptySet() },
        ext = FleetExternal()
    )

    /** Spot [z] standard deviations (of the remaining settlement variance) above the strike. */
    private fun spotAt(z: Double, tte: Double) = k * kotlin.math.exp(z * sigma * sqrt(maxOf(20.0, tte - 40.0)))

    private fun assertPaysFee(orders: List<FleetOrder>) {
        for (o in orders) {
            val fee = if (o.taker) FleetMath.takerFeePer(o.contracts, o.price) else 0.0
            assertTrue("${o.note}: ${o.pModel} vs ${o.price} + $fee", o.pModel - o.price - fee >= 0.0099)
            assertTrue(o.contracts > 0)
        }
    }

    @Test
    fun mathObiOfiVwapFair() {
        val yes = listOf(0.50 to 300.0, 0.49 to 200.0, 0.48 to 100.0, 0.47 to 100.0, 0.46 to 100.0)
        val no = listOf(0.48 to 100.0, 0.47 to 50.0)
        assertTrue(MicroMath.obi5(yes, no)!! > 0.5)
        // Size added on the YES bids, none on the NO bids: positive flow.
        val more = yes.map { it.first to it.second + 100.0 }
        assertTrue(MicroMath.ofi5(yes, no, more, no)!! > 0.0)
        // A NO bid stepping up a cent counts its whole size for NO: negative flow.
        assertTrue(MicroMath.ofi5(yes, no, yes, listOf(0.49 to 400.0, 0.48 to 100.0))!! < 0.0)
        assertNull(MicroMath.ofi5(emptyList(), no, yes, no))
        assertEquals(0.52, MicroMath.vwap(listOf(Triple(0L, 0.50, 100.0), Triple(1L, 0.54, 100.0)))!!, 1e-12)
        assertEquals(0.5, MicroMath.fairT(k, k, sigma, 300.0)!!, 1e-9)
        assertTrue(MicroMath.fairT(spotAt(2.0, 300.0), k, sigma, 300.0)!! > 0.9)
        assertEquals(1.0, MicroMath.indexZ(DoubleArray(20) { if (it == 19) k * kotlin.math.exp(sigma * sqrt(10.0)) else k }, 10, sigma)!!, 1e-6)
    }

    @Test
    fun obiMakerRestsOnTheHeavySideWhenTheFairBacksIt() {
        val sp = spotAt(0.3, 500.0)
        val fair = MicroMath.fairT(sp, k, sigma, 500.0)!!
        assertTrue(fair > 0.55)
        val v = view(spot = sp, yesBid = 0.50, yesAsk = 0.53)
        val m = MicroSignals(obi5 = 0.5, fairT = fair)
        val o = MicroScalpers.decide(ScalpStrategy.MICRO_OBI_MAKER, v, m, 0.35, 10)
        assertEquals("YES", o.single().side)
        assertTrue("resting", !o.single().taker)
        assertEquals("1¢ inside a 3¢ spread", 0.51, o.single().price, 1e-9)
        assertTrue(o.single().exitPrice!! > o.single().price + 0.019)
        assertPaysFee(o)
        // Weak imbalance, or a fair that does not back it: nothing.
        assertTrue(MicroScalpers.decide(ScalpStrategy.MICRO_OBI_MAKER, v, m.copy(obi5 = 0.2), 0.35, 10).isEmpty())
        assertTrue(MicroScalpers.decide(ScalpStrategy.MICRO_OBI_MAKER, v, m.copy(fairT = 0.52), 0.35, 10).isEmpty())
    }

    @Test
    fun brtiPulsePaysTheAskWhenTheBookLagsTheIndex() {
        val sp = spotAt(0.6, 500.0)
        val fair = MicroMath.fairT(sp, k, sigma, 500.0)!!
        val v = view(spot = sp, yesBid = 0.50, yesAsk = 0.52)
        val m = MicroSignals(fairT = fair, fairT10 = 0.50, idxZ10 = 1.2, mid10 = 0.50)
        val o = MicroScalpers.decide(ScalpStrategy.MICRO_BRTI_PULSE, v, m, 0.75, 10)
        assertEquals("YES", o.single().side)
        assertTrue(o.single().taker)
        assertTrue(o.single().exitPrice!! >= 0.54 - 1e-9)
        assertPaysFee(o)
        // The book already moved with the index: no lag, nothing.
        val caught = MicroSignals(fairT = fair, fairT10 = 0.50, idxZ10 = 1.2, mid10 = 0.20)
        assertTrue(MicroScalpers.decide(ScalpStrategy.MICRO_BRTI_PULSE, v, caught, 0.75, 10).isEmpty())
        // A small index move: nothing.
        assertTrue(MicroScalpers.decide(ScalpStrategy.MICRO_BRTI_PULSE, v, m.copy(idxZ10 = 0.4), 0.75, 10).isEmpty())
    }

    @Test
    fun vwapReversionRestsOnTheCheapSideWhileTheIndexIsCalm() {
        val v = view(yesBid = 0.44, yesAsk = 0.46)
        val m = MicroSignals(vwap30 = 0.50, vwapContracts = 600.0, idxZ30 = 0.1, fairT = 0.50)
        val o = MicroScalpers.decide(ScalpStrategy.MICRO_VWAP_REV, v, m, 0.03, 10)
        assertEquals("YES", o.single().side)
        assertEquals(0.44, o.single().price, 1e-9)
        assertEquals("sells at the VWAP", 0.50, o.single().exitPrice!!, 1e-9)
        assertPaysFee(o)
        // The index is moving: the book is right to move too.
        assertTrue(MicroScalpers.decide(ScalpStrategy.MICRO_VWAP_REV, v, m.copy(idxZ30 = 1.5), 0.03, 10).isEmpty())
        // Too few contracts behind the VWAP.
        assertTrue(MicroScalpers.decide(ScalpStrategy.MICRO_VWAP_REV, v, m.copy(vwapContracts = 50.0), 0.03, 10).isEmpty())
    }

    @Test
    fun thetaFavouriteBuysTheLeaderBetween90And240SecondsLeft() {
        val s = 900 - 150
        val sp = spotAt(1.6, 150.0)
        val fair = MicroMath.fairT(sp, k, sigma, 150.0)!!
        assertTrue(fair > 0.85)
        val v = view(s = s, spot = sp, yesBid = 0.80, yesAsk = 0.82)
        val o = MicroScalpers.decide(ScalpStrategy.MICRO_THETA_FAV, v, MicroSignals(fairT = fair), 0.80, 10)
        assertEquals("YES", o.single().side)
        assertTrue(o.single().taker)
        assertNull("holds to the settlement", o.single().exitPrice)
        assertPaysFee(o)
        // Outside its window.
        assertTrue(MicroScalpers.decide(ScalpStrategy.MICRO_THETA_FAV, view(s = 500, spot = sp, yesBid = 0.80, yesAsk = 0.82), MicroSignals(fairT = fair), 0.80, 10).isEmpty())
        // Priced right: no edge either way.
        assertTrue(MicroScalpers.decide(ScalpStrategy.MICRO_THETA_FAV, view(s = s, spot = sp, yesBid = fair - 0.01, yesAsk = fair + 0.005), MicroSignals(fairT = fair), 0.80, 10).isEmpty())
    }

    @Test
    fun tapeRecordsOneChartPointASecond() {
        val tape = MicroTape()
        val lv = listOf(0.50 to 100.0)
        val v = view(yesLevels = lv, noLevels = lv)
        tape.onTrade(v.ticker, 0.50, 10.0, v.nowMs - 1000)
        val a = tape.signals(v)
        assertNotNull(a.vwap30)
        tape.signals(v)
        tape.signals(view(s = 401, yesLevels = lv, noLevels = lv, nowMs = v.nowMs + 1000))
        val live = tape.live()!!
        assertEquals(listOf(400, 401), live.points.map { it.s })
        tape.forget(v.ticker)
        assertNull(tape.live())
    }

    @Test
    fun everyMicroScalperIsActiveWithItsOwnTriggerAndFamily() {
        val micro = ScalpStrategy.values().filter { it.micro }
        assertEquals(4, micro.size)
        assertTrue(micro.all { it.viewBased && !it.fleet })
        // The three that lost in the replay of the recorded days are retired, and still run in learning mode.
        assertEquals(listOf(ScalpStrategy.MICRO_OBI_MAKER), micro.filter { !ScalpStrategy.isRetiredBase(it) })
        val was = ScalpStrategy.learningMode
        try {
            ScalpStrategy.learningMode = true
            assertTrue(micro.all { it in ScalpStrategy.ACTIVE })
        } finally {
            ScalpStrategy.learningMode = was
        }
        assertTrue(micro.all { com.dirk.kalshiodds.signal.scalper.ScalpTriggers.of(it) != null })
        assertTrue("the scalps are sold before the close", micro.filter { !it.hold }.all { it.flattenSeconds > 0 })
        assertTrue(ln(1.0) == 0.0)
    }
}
