package com.dirk.kalshiodds.signal.scalper.fleet

import com.dirk.kalshiodds.signal.scalper.ScalpStrategy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each of the 20 fleet scalpers: it fires in its own situation, every order clears the gate, and it stays quiet otherwise. */
class FleetTest {

    private val k = 100_000.0
    private val sigma = 2e-5 // index std per √s (log)

    private fun view(
        s: Int = 400,
        spot: Double? = k,
        yesBid: Double? = 0.49, yesAsk: Double? = 0.51,
        finalPrints: DoubleArray = DoubleArray(0),
        index1s: DoubleArray = DoubleArray(600) { k },
        yesMids: DoubleArray = DoubleArray(120) { 0.50 },
        fair: Double? = 0.50,
        yesLevels: List<Pair<Double, Double>> = emptyList(),
        noLevels: List<Pair<Double, Double>> = emptyList(),
        vpin: Double? = null, share: Double? = null,
        behavioural: Double? = null,
        ext: FleetExternal = FleetExternal(),
        tradeGap: Double? = 1.0,
        nowMs: Long = 1_800_000_000_000L,
        yesBidQty: Double? = 100.0, yesAskQty: Double? = 100.0,
        strike: Double? = k
    ) = FleetView(
        ticker = "KXBTC15M-TEST", nowMs = nowMs, s = s, tte = 900.0 - s, strike = strike, spot = spot, sigma1sLog = sigma,
        index1s = index1s, finalPrints = finalPrints,
        yesBid = yesBid, yesAsk = yesAsk, noBid = yesAsk?.let { 1 - it }, noAsk = yesBid?.let { 1 - it },
        yesBidQty = yesBidQty, yesAskQty = yesAskQty, noBidQty = 100.0, noAskQty = 100.0,
        yesLevels = yesLevels, noLevels = noLevels, yesMids = yesMids, tradeGap = tradeGap, fairYes = fair,
        vpin = vpin, recentYesShare = share, behavioural = { behavioural },
        inventory = { 0.0 }, restingAt = { _, _ -> emptySet() }, ext = ext
    )

    @After
    fun reset() {
        Fleet.makersHaltedUntilMs = 0L
    }

    /** Every order the fleet sends clears P − price − fee ≥ θ (θ 1¢; the tail harvester's 0.3¢). */
    private fun assertGated(st: ScalpStrategy, orders: List<FleetOrder>) {
        for (o in orders) {
            val fee = if (o.taker) FleetMath.takerFeePer(o.contracts, o.price) else 0.0
            val theta = when (st) {
                ScalpStrategy.FLEET_TAIL_HARVEST -> 0.003
                ScalpStrategy.FLEET_TIMESFM -> 0.015
                else -> 0.0099
            }
            // Arbitrage pairs gate on the pair's cost, not one leg: checked in their own tests.
            if (st == ScalpStrategy.FLEET_POLY_ARB || st == ScalpStrategy.FLEET_HOURLY_DOMINANCE) continue
            assertTrue("${st.name}: ${o.pModel} vs ${o.price} + $fee", o.pModel - o.price - fee >= theta - 1e-9)
            assertTrue(o.contracts > 0)
        }
    }

    private fun run(st: ScalpStrategy, v: FleetView): List<FleetOrder> = Fleet.decide(st, v).also { assertGated(st, it) }

    @Test
    fun s1LockedIn() {
        // 45 prints banked well above the strike; YES still offered at 80¢.
        val prints = DoubleArray(45) { k + 40 }
        val o = run(ScalpStrategy.FLEET_LOCKED_IN, view(s = 885, finalPrints = prints, yesBid = 0.78, yesAsk = 0.80))
        assertEquals("YES", o.single().side)
        assertTrue(o.single().taker)
        assertTrue(run(ScalpStrategy.FLEET_LOCKED_IN, view(s = 885, finalPrints = DoubleArray(10) { k + 40 }, yesBid = 0.78, yesAsk = 0.80)).isEmpty())
        // Priced right: nothing.
        assertTrue(run(ScalpStrategy.FLEET_LOCKED_IN, view(s = 885, finalPrints = prints, yesBid = 0.995, yesAsk = 0.999)).isEmpty())
    }

    @Test
    fun s2SpikeFade() {
        // 49 prints $30 under the strike, then a 5σ jump of $10 in the 50th: the average still needs $149 more,
        // so the fair price barely moves, but YES ran from 5¢ to 15¢.
        val prints = DoubleArray(50) { if (it == 49) k - 20 else k - 30 }
        val mids = DoubleArray(120) { if (it >= 118) 0.15 else 0.05 }
        val o = run(ScalpStrategy.FLEET_SPIKE_FADE, view(s = 890, finalPrints = prints, yesMids = mids, yesBid = 0.14, yesAsk = 0.16))
        assertEquals("NO", o.single().side)
        assertFalse(o.single().taker)
        assertTrue(run(ScalpStrategy.FLEET_SPIKE_FADE, view(s = 890, finalPrints = DoubleArray(50) { k }, yesMids = mids)).isEmpty())
    }

    @Test
    fun s3VwapSlope() {
        val vw = DoubleArray(60) { k - 30 + it * 1.0 + (if (it % 2 == 0) 0.2 else -0.2) }
        val o = run(ScalpStrategy.FLEET_VWAP_SLOPE, view(s = 830, spot = k + 29.0, yesBid = 0.55, yesAsk = 0.57, ext = FleetExternal(vwap1s = vw)))
        assertEquals("YES", o.single().side)
        val flat = DoubleArray(60) { k + (if (it % 2 == 0) 0.5 else -0.5) }
        assertTrue(run(ScalpStrategy.FLEET_VWAP_SLOPE, view(s = 830, ext = FleetExternal(vwap1s = flat), index1s = flat)).isEmpty())
    }

    @Test
    fun s4TailHarvest() {
        val prints = DoubleArray(50) { k + 200 }
        val o = run(ScalpStrategy.FLEET_TAIL_HARVEST, view(s = 892, finalPrints = prints, yesBid = 0.990, yesAsk = 0.998))
        assertEquals("YES", o.single().side)
        assertTrue(o.single().price in 0.991..0.996)
        assertTrue(run(ScalpStrategy.FLEET_TAIL_HARVEST, view(s = 892, finalPrints = DoubleArray(50) { k + 1 }, yesBid = 0.6, yesAsk = 0.62)).isEmpty())
    }

    @Test
    fun s5FatTail() {
        // Spot 2.5σ above the strike with 5 minutes left; NO offered at a normal's price.
        val tau = 300.0 / 900
        val s15 = sigma * kotlin.math.sqrt(900.0)
        val spot = k * kotlin.math.exp(2.5 * s15 * kotlin.math.sqrt(tau))
        val gauss = FleetMath.normCdf(-2.5)
        // NO is offered near the normal's 0.6¢; the t says 3.2¢.
        val o = run(ScalpStrategy.FLEET_FAT_TAIL, view(s = 600, spot = spot, yesBid = 1 - gauss - 0.002, yesAsk = 1 - gauss + 0.002))
        assertEquals("NO", o.single().side)
        assertTrue(run(ScalpStrategy.FLEET_FAT_TAIL, view(s = 600, spot = k * 1.00001)).isEmpty())
    }

    @Test
    fun s6NightTheta() {
        val spot = k * 1.002
        val o = run(ScalpStrategy.FLEET_NIGHT_THETA, view(s = 600, spot = spot, yesBid = 0.70, yesAsk = 0.72, ext = FleetExternal(hourVol = 0.5 to sigma)))
        assertEquals("YES", o.single().side)
        assertTrue(run(ScalpStrategy.FLEET_NIGHT_THETA, view(s = 600, spot = spot, yesBid = 0.70, yesAsk = 0.72, ext = FleetExternal(hourVol = 0.9 to sigma))).isEmpty())
    }

    @Test
    fun s7RvIvNeedsHistoryThenFires() {
        // A calm index, a market pricing a lot of movement: after a minute of gap history the ITM side is bought.
        val idx = DoubleArray(600) { k * 1.001 * (1 + (if (it % 2 == 0) 1e-6 else -1e-6)) }
        var fired: List<FleetOrder> = emptyList()
        for (i in 0 until 70) {
            val mid = if (i < 65) 0.60 else 0.52
            fired = run(ScalpStrategy.FLEET_RV_IV, view(s = 300 + i, spot = k * 1.001, index1s = idx, yesBid = mid - 0.01, yesAsk = mid + 0.01))
            if (fired.isNotEmpty()) break
        }
        assertEquals("YES", fired.single().side)
    }

    @Test
    fun s8CrossAssetTakesTheLaggingAlt() {
        val n = 40
        val btc = DoubleArray(n) { if (it < n - 5) (if (it % 3 == 0) 0.002 else -0.001) else (if (it % 2 == 0) 0.004 else -0.004) }
        // ETH tracked BTC loosely before, tightly over the last 30 windows... built so ρ rises sharply in the last 5.
        val eth = DoubleArray(n) { i -> if (i < n - 5) (if (i % 5 == 0) 0.003 else -0.0005) * 1.0 else btc[i] * 1.5 }
        val alt = VenueQuote("KXETH15M-X", yesAsk = 0.52, yesBid = 0.50, noAsk = 0.50, noBid = 0.48, strike = 4000.0, closeMs = 0L)
        val ext = FleetExternal(alts = mapOf("ETH" to alt), pairReturns = mapOf("ETH" to (btc to eth)), altSpot = mapOf("ETH" to (4000.0 to 4000.0)))
        val rho = FleetMath.correlation(eth.copyOfRange(n - 30, n), btc.copyOfRange(n - 30, n))!!
        val rho5 = FleetMath.correlation(eth.copyOfRange(n - 35, n - 5), btc.copyOfRange(n - 35, n - 5))!!
        val o = run(ScalpStrategy.FLEET_CROSS_ASSET, view(s = 400, spot = k * 1.004, ext = ext))
        if (rho > 0.85 && rho - rho5 >= 0.25) {
            assertEquals("YES", o.single().side)
            assertFalse(o.single().onBook)
            assertEquals("KXETH15M-X", o.single().offBook.single().market)
        } else {
            assertTrue(o.isEmpty())
        }
        // No correlation jump: nothing.
        val flat = FleetExternal(alts = mapOf("ETH" to alt), pairReturns = mapOf("ETH" to (btc to btc.copyOf())), altSpot = mapOf("ETH" to (4000.0 to 4000.0)))
        assertTrue(run(ScalpStrategy.FLEET_CROSS_ASSET, view(s = 400, spot = k * 1.004, ext = flat)).isEmpty())
    }

    @Test
    fun s9AvellanedaStoikovQuotesBothSides() {
        val mids = DoubleArray(120) { 0.50 + (if (it % 2 == 0) 0.005 else -0.005) }
        val o = run(ScalpStrategy.FLEET_AS_MAKER, view(s = 300, yesMids = mids, yesBid = 0.45, yesAsk = 0.55))
        assertEquals(setOf("YES", "NO"), o.map { it.side }.toSet())
        for (q in o) assertTrue(q.exitPrice!! > q.price)
        Fleet.makersHaltedUntilMs = 1_800_000_000_000L + 10_000
        assertTrue(run(ScalpStrategy.FLEET_AS_MAKER, view(s = 300, yesMids = mids, yesBid = 0.45, yesAsk = 0.55)).isEmpty())
    }

    @Test
    fun s10LadderRungsBelowFair() {
        val mids = DoubleArray(120) { 0.50 + (if (it % 2 == 0) 0.004 else -0.004) }
        val o = run(ScalpStrategy.FLEET_LADDER, view(s = 300, yesMids = mids, yesBid = 0.50, yesAsk = 0.52, fair = 0.51))
        assertTrue("ladder: $o", o.isNotEmpty())
        assertTrue("ladder: $o", o.all { it.price <= 0.51 - 0.01 + 1e-9 || it.side == "NO" })
        assertTrue(o.size <= 14)
        // Volatility jumps in the last minute: no ladder.
        val jumpy = DoubleArray(120) { if (it < 60) 0.50 + (if (it % 2 == 0) 0.001 else -0.001) else 0.50 + (if (it % 2 == 0) 0.02 else -0.02) }
        assertTrue(run(ScalpStrategy.FLEET_LADDER, view(s = 300, yesMids = jumpy)).isEmpty())
    }

    @Test
    fun s11ObiStepsInsideOnTheHeavySide() {
        val o = run(
            ScalpStrategy.FLEET_OBI,
            view(s = 300, yesBid = 0.45, yesAsk = 0.50, yesBidQty = 2000.0, yesAskQty = 50.0,
                yesLevels = listOf(0.45 to 2000.0, 0.44 to 1500.0, 0.43 to 1000.0), noLevels = listOf(0.50 to 50.0, 0.49 to 40.0, 0.48 to 30.0))
        )
        assertEquals("YES", o.single().side)
        assertEquals(0.46, o.single().price, 1e-9)
        assertTrue(run(ScalpStrategy.FLEET_OBI, view(s = 300, yesBid = 0.49, yesAsk = 0.50, yesLevels = listOf(0.49 to 2000.0))).isEmpty())
    }

    @Test
    fun s12VpinHaltsMakersAndFollowsFlow() {
        val mids = DoubleArray(120) { 0.40 + it * 0.002 }
        val v = view(s = 300, yesMids = mids, yesBid = 0.63, yesAsk = 0.64, vpin = 0.8, share = 0.9)
        val o = run(ScalpStrategy.FLEET_VPIN_FLIP, v)
        assertEquals("YES", o.single().side)
        assertTrue(o.single().taker)
        assertTrue(Fleet.makersHaltedUntilMs > v.nowMs)
        assertTrue(run(ScalpStrategy.FLEET_VPIN_FLIP, view(s = 300, vpin = 0.5, share = 0.9)).isEmpty())
    }

    @Test
    fun s13NoBias() {
        val o = run(ScalpStrategy.FLEET_NO_BIAS, view(s = 400, spot = k * 0.999, yesBid = 0.17, yesAsk = 0.19, behavioural = 0.05))
        assertEquals("NO", o.single().side)
        assertTrue(run(ScalpStrategy.FLEET_NO_BIAS, view(s = 400, spot = k * 0.999, yesBid = 0.17, yesAsk = 0.19, behavioural = 0.01)).isEmpty())
        assertTrue(run(ScalpStrategy.FLEET_NO_BIAS, view(s = 400, spot = k * 1.001, yesBid = 0.17, yesAsk = 0.19, behavioural = 0.05)).isEmpty())
    }

    @Test
    fun s14NowcastAcrossTheStrike() {
        val now = 1_800_000_000_000L
        val o = run(ScalpStrategy.FLEET_NOWCAST, view(s = 870, spot = k - 1, yesBid = 0.40, yesAsk = 0.42, nowMs = now, ext = FleetExternal(nowcast = (k + 40) to now)))
        assertEquals("YES", o.single().side)
        // Both on the same side of the strike: nothing.
        assertTrue(run(ScalpStrategy.FLEET_NOWCAST, view(s = 870, spot = k + 5, nowMs = now, ext = FleetExternal(nowcast = (k + 40) to now))).isEmpty())
    }

    @Test
    fun s15PolymarketPair() {
        val now = 1_800_000_000_000L
        val poly = PolyQuote("btc-updown-15m-1", upAsk = 0.75, downAsk = 0.18, upAskSize = 500.0, downAskSize = 500.0, feeRate = 0.07, updatedMs = now)
        val o = run(ScalpStrategy.FLEET_POLY_ARB, view(s = 400, spot = k * 1.003, yesBid = 0.74, yesAsk = 0.76, nowMs = now, ext = FleetExternal(poly = poly)))
        val pair = o.single()
        assertEquals("YES", pair.side)
        val leg = pair.offBook.single()
        assertEquals("NO", leg.side)
        val cost = pair.price + FleetMath.takerFeePer(pair.contracts, pair.price) + leg.price + FleetMath.polymarketFeePer(leg.price, 0.07) + 0.002 + 0.005
        assertTrue(1 - cost > 0.014)
        // Near the strike (oracle basis risk): nothing.
        assertTrue(run(ScalpStrategy.FLEET_POLY_ARB, view(s = 400, spot = k * 1.001, yesBid = 0.74, yesAsk = 0.76, nowMs = now, ext = FleetExternal(poly = poly))).isEmpty())
    }

    @Test
    fun s16PerpHedgeComesWithTheBinary() {
        val now = 1_800_000_000_000L
        val perp = PerpQuote(bid = 9.9990, ask = 10.0010, btcPerContract = 0.0001, takerFeeRate = 0.0012, updatedMs = now)
        val spot = k * 1.0006
        val o = run(ScalpStrategy.FLEET_PERP_HEDGE, view(s = 500, spot = spot, yesBid = 0.55, yesAsk = 0.57, nowMs = now, ext = FleetExternal(perp = perp)))
        val bin = o.single()
        assertEquals("YES", bin.side)
        val hedge = bin.offBook.single()
        assertEquals(OffBookLeg.Venue.PERP, hedge.venue)
        assertEquals("NO", hedge.side) // long the binary → short the perp
    }

    @Test
    fun s17HourlyDominance() {
        val hourly = listOf(VenueQuote("KXBTCD-X-T100050", yesAsk = 0.50, yesBid = 0.48, noAsk = 0.40, noBid = 0.38, strike = k + 50, closeMs = 0L))
        val o = run(ScalpStrategy.FLEET_HOURLY_DOMINANCE, view(s = 400, yesBid = 0.53, yesAsk = 0.55, ext = FleetExternal(hourly = hourly)))
        val pair = o.single()
        assertEquals("YES", pair.side)
        assertEquals("NO", pair.offBook.single().side)
        assertTrue(pair.price + pair.offBook.single().price < 1.0)
        val dear = listOf(hourly[0].copy(noAsk = 0.47))
        assertTrue(run(ScalpStrategy.FLEET_HOURLY_DOMINANCE, view(s = 400, yesBid = 0.53, yesAsk = 0.55, ext = FleetExternal(hourly = dear))).isEmpty())
    }

    @Test
    fun s18MacroReversal() {
        val now = 1_800_000_000_000L
        // 60 s after a print the index jumped $50 (3σ for a minute) and YES ran to 95¢, where it stalled; spread 6¢.
        // 18% of the jump reverting leaves YES about 80% to win: NO is the overshot side.
        val idx = DoubleArray(600) { if (it < 600 - 60) k else k + 50 }
        val mids = DoubleArray(120) { if (it < 100) 0.50 + it * 0.0045 else 0.95 }
        val o = run(
            ScalpStrategy.FLEET_MACRO_REVERT,
            view(s = 300, spot = k + 50, index1s = idx, yesMids = mids, yesBid = 0.92, yesAsk = 0.98, nowMs = now,
                ext = FleetExternal(lastMacro = (now - 60_000L) to "CPI"))
        )
        assertEquals("NO", o.single().side)
        assertTrue(run(ScalpStrategy.FLEET_MACRO_REVERT, view(s = 300, spot = k + 50, index1s = idx, yesMids = mids, yesBid = 0.92, yesAsk = 0.98, nowMs = now)).isEmpty())
    }

    @Test
    fun s19PinQuotesAroundFair() {
        val o = run(ScalpStrategy.FLEET_PIN_GAMMA, view(s = 800, spot = k * 1.0001, yesBid = 0.48, yesAsk = 0.54, fair = 0.51))
        assertEquals(setOf("YES", "NO"), o.map { it.side }.toSet())
        assertTrue(o.all { it.exitPrice!! > it.price })
        assertTrue(run(ScalpStrategy.FLEET_PIN_GAMMA, view(s = 600, spot = k * 1.0001)).isEmpty())
    }

    @Test
    fun s20TimesFmWithRegimeGate() {
        val now = 1_800_000_000_000L
        val q = Array(128) { DoubleArray(9) { j -> k + 60 + 10 * (j - 4) } }
        val good = FleetExternal(forecast = q to now, regime = 0.8 to "test")
        val late = run(ScalpStrategy.FLEET_TIMESFM, view(s = 800, yesBid = 0.55, yesAsk = 0.60, nowMs = now, ext = good))
        assertEquals("YES", late.single().side)
        assertTrue(late.single().taker)
        val early = run(ScalpStrategy.FLEET_TIMESFM, view(s = 400, yesBid = 0.55, yesAsk = 0.60, nowMs = now, ext = good))
        assertTrue(early.all { !it.taker })
        assertTrue(run(ScalpStrategy.FLEET_TIMESFM, view(s = 800, yesBid = 0.55, yesAsk = 0.60, nowMs = now, ext = good.copy(regime = 0.4 to "test"))).isEmpty())
    }

    @Test
    fun everyFleetStrategyHasARule() {
        val fleet = ScalpStrategy.values().filter { it.fleet }
        assertEquals(20, fleet.size)
        assertTrue(fleet.all { it.activeSeconds != null })
    }
}
