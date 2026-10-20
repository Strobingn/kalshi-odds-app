package com.dirk.kalshiodds.decision

import com.dirk.kalshiodds.llm.AiLiveFeed
import com.dirk.kalshiodds.llm.AiLiveSnapshot
import com.dirk.kalshiodds.llm.ChatPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * 1.0.16: each UP/DOWN asymmetry, checked with a mirrored book. The mirror of an UP result is the DOWN result,
 * and an exact tie is neither side.
 */
class UpDownSymmetryTest {
    private val close = 1_800_000_000_000L

    private fun q(
        now: Long,
        yesBid: Double,
        yesAsk: Double,
        spot: Double,
        strike: Double,
        sigma: Double = 3e-5,
        cf: Double? = null,
        cfAge: Long? = if (cf != null) 100L else null,
        tauS: Double = (close - now) / 1000.0
    ) = ScalpRule.Quote(
        ticker = "KXBTC15M-UPDOWN",
        nowMs = now,
        closeMs = now + (tauS * 1000).toLong(),
        bookAtMs = now,
        yesBid = yesBid,
        yesBidSize = 200.0,
        yesAsk = yesAsk,
        yesAskSize = 200.0,
        spot = spot,
        strike = strike,
        sigmaPerSec = sigma,
        cfSpot = cf,
        cfAgeMs = cfAge
    )

    @Test fun fairUsesThisStrikeAfterTheIndexBasisAndMirrors() {
        val sig = 3e-5
        val tau = 300.0
        val open = 100_000.0
        val index = 99_800.0
        val basis = ShockFav.indexBasis(open, index)
        assertEquals(ln(open / index), basis, 1e-12)
        assertEquals(0.0, ShockFav.indexBasis(open, open), 0.0)

        val spot = 100_400.0
        val at = ShockFav.fairYes(spot, 100_000.0, sig, tau, basis)
        val above = ShockFav.fairYes(spot, 102_000.0, sig, tau, basis)
        assertTrue(at > above + 0.05)
        // The old basis ln(open/strike) cancels K, so two strikes shared one fair. The index basis does not.
        val wrongAt = ShockFav.fairYes(spot, 100_000.0, sig, tau, ln(open / 100_000.0))
        val wrongAbove = ShockFav.fairYes(spot, 102_000.0, sig, tau, ln(open / 102_000.0))
        assertEquals(wrongAt, wrongAbove, 1e-9)
        assertTrue(abs(at - above) > abs(wrongAt - wrongAbove) + 0.05)

        val spotAdj = spot * exp(-basis)
        val mirrorSpot = 100_000.0 * exp(basis)
        val mirrorStrike = spotAdj
        val down = ShockFav.fairYes(mirrorSpot, mirrorStrike, sig, tau, basis)
        assertEquals(1.0, at + down, 1e-9)
        assertTrue(at > 0.5 && down < 0.5)
    }

    @Test fun equalAsksTakeTheLargerExpectedNetAndSkipADoubleTie() {
        assertNull(FavouritePolicy.favouriteSide(0.40, 0.40))
        assertEquals("YES", FavouritePolicy.favouriteSide(0.62, 0.40))
        assertEquals("NO", FavouritePolicy.favouriteSide(0.40, 0.62))

        val no = assess(raw = 0.42, market = 0.42, yesAsk = 0.30, noAsk = 0.30)
        val yes = assess(raw = 0.58, market = 0.58, yesAsk = 0.30, noAsk = 0.30)
        assertEquals("NO", no.chosen?.side)
        assertEquals("YES", yes.chosen?.side)
        assertTrue((no.chosen?.expectedNetPerContract ?: 0.0) > 0.0)
        assertEquals(yes.chosen!!.expectedNetPerContract, no.chosen!!.expectedNetPerContract, 1e-9)

        val tied = assess(raw = 0.50, market = 0.50, yesAsk = 0.40, noAsk = 0.40)
        assertNull(tied.chosen)
    }

    @Test fun lateFavAndResearchTakeTheLargerEdge() {
        val up = listOf("YES" to 0.04, "NO" to 0.11)
        val down = listOf("YES" to 0.11, "NO" to 0.04)
        assertEquals("NO", SidePick.onlyMaxBy(up) { it.second }?.first)
        assertEquals("YES", SidePick.onlyMaxBy(down) { it.second }?.first)
        assertNull(SidePick.onlyMaxBy(listOf("YES" to 0.08, "NO" to 0.08)) { it.second })

        assertEquals("NO", researchHoldSide(0.40))
        assertEquals("YES", researchHoldSide(0.60))
        assertNull(researchHoldSide(0.50))
    }

    @Test fun exactScoreTiesEnterNeitherSide() {
        val now = close - 600_000L
        val tieBid = 0.7
        val tie = ScalpRule.entrySignal(q(now, yesBid = tieBid, yesAsk = 1.0 - tieBid, spot = 100_000.0, strike = 100_000.0))
        assertNull(tie.first)
        assertEquals("exact tie", tie.second)

        val yes = ScalpRule.entrySignal(q(now, 0.34, 0.35, spot = 101_000.0, strike = 100_000.0)).first
        val no = ScalpRule.entrySignal(q(now, 0.65, 0.66, spot = 99_000.0, strike = 100_000.0)).first
        assertEquals("YES", yes?.side)
        assertEquals("NO", no?.side)

        val p = ScalpParams.seedFor("BTC", ScalpStrategy.EXTREME_REVERSION)
        val cheap = 0.1
        val revTie = ScalpRule.strategySignal(q(now, 1.0 - cheap, cheap, spot = 100_000.0, strike = 100_000.0), p, listOf("YES", "NO"), emptyList())
        assertNull(revTie.first)
        assertEquals("exact tie", revTie.second)
        val revYes = ScalpRule.strategySignal(q(now, 0.90, 0.05, spot = 100_200.0, strike = 100_000.0), p, listOf("YES", "NO"), emptyList()).first
        val revNo = ScalpRule.strategySignal(q(now, 0.95, 0.10, spot = 99_800.0, strike = 100_000.0), p, listOf("YES", "NO"), emptyList()).first
        assertEquals("YES", revYes?.side)
        assertEquals("NO", revNo?.side)

        val cfr = ScalpParams.seedFor("BTC", ScalpStrategy.CF_REPRICE)
        val cfNow = close - 400_000L
        val cfBid = 0.6
        val cfTie = ScalpRule.cfRepriceSignal(q(cfNow, cfBid, 1.0 - cfBid, spot = 100_000.0, strike = 100_000.0, cf = 100_000.0), cfr, listOf("YES", "NO"))
        assertNull(cfTie.first)
        assertEquals("exact tie", cfTie.second)
        val cfYes = ScalpRule.cfRepriceSignal(q(cfNow, 0.49, 0.50, spot = 100_000.0, strike = 100_000.0, cf = 100_300.0), cfr, listOf("YES", "NO")).first
        val cfNo = ScalpRule.cfRepriceSignal(q(cfNow, 0.50, 0.51, spot = 100_000.0, strike = 100_000.0, cf = 99_700.0), cfr, listOf("YES", "NO")).first
        assertEquals("YES", cfYes?.side)
        assertEquals("NO", cfNo?.side)

        val ai = listOf("YES" to 0.03, "NO" to 0.03)
        assertNull(SidePick.onlyMaxBy(ai) { it.second })
        assertEquals("NO", SidePick.onlyMaxBy(listOf("YES" to 0.02, "NO" to 0.05)) { it.second }?.first)
        assertEquals("YES", SidePick.onlyMaxBy(listOf("YES" to 0.05, "NO" to 0.02)) { it.second }?.first)
    }

    @Test fun flatForecastIsZeroAndExactZeroDoesNotEnter() {
        for (coin in listOf("BTC", "ETH", "SOL")) {
            for (h in LeadForecaster.HORIZONS_S) {
                if (LeadForecaster.weights(coin, h) == null) continue
                for (tau in listOf(1.0, 30.0, 450.0, 840.0)) {
                    val flat = LeadForecaster.features(0.5, 0.5, 0.0, 100_000.0, 100_000.0, 2e-5, tau) { 100_000.0 }!!
                    assertEquals(0.0, LeadForecaster.predict(coin, h, flat)!!, 0.0)
                }
            }
        }
        assertNull(ForecastScalp.sideOf(0.0))
        assertNull(ForecastScalp.sideOf(Double.NaN))

        val up = DoubleArray(11).also { it[0] = 1.0; it[2] = 0.40; it[6] = 0.20 }
        val down = DoubleArray(11).also { it[0] = 1.0; it[2] = -0.40; it[6] = 0.20 }
        val pu = LeadForecaster.predict("BTC", 600, up)!!
        val pd = LeadForecaster.predict("BTC", 600, down)!!
        assertEquals(pu, -pd, 1e-12)
        assertEquals("YES", ForecastScalp.sideOf(pu))
        assertEquals("NO", ForecastScalp.sideOf(pd))

        val book = ScalpBook(bankrollUsd = { 20_000.0 })
        val now = close - 900_000L
        val flatBook = q(now, 0.49, 0.51, spot = 100_000.0, strike = 100_000.0, sigma = 2e-5, cf = 100_000.0, tauS = 900.0)
        assertTrue(book.onForecastQuote(flatBook, 100.0, 100.0, enabled = true, nowHorizonS = 600).isEmpty())
    }

    @Test fun atmTiesFollowTheOpenThenNeitherSide() {
        val low = "KXBTC15M-Z-LOW"
        val high = "KXBTC15M-A-HIGH"
        fun dir(open: Double?) = AiDirection.current(
            mapOf(low to mark(low, 99_000.0), high to mark(high, 101_000.0)),
            0L,
            open
        )?.ticker
        assertEquals(low, dir(99_200.0))
        assertEquals(high, dir(100_800.0))
        assertNull(dir(100_000.0))
        assertNull(dir(null))
        assertEquals(low, AiDirection.current(mapOf(high to mark(high, 101_000.0), low to mark(low, 99_900.0)), 0L)?.ticker)

        fun chat(open: Double?) = ChatPrompt.atm(listOf(mark(high, 101_000.0), mark(low, 99_000.0)), 100_000.0, open)?.ticker
        assertEquals(low, chat(99_200.0))
        assertEquals(high, chat(100_800.0))
        assertNull(chat(100_000.0))
        assertNull(chat(null))

        fun snap(ticker: String, strike: Double) = AiLiveSnapshot(
            1L, "W", ticker, 900_000L, strike, 100_000.0, 0.5, 0.52, 0.48, 0.50, 0.5
        )
        fun feed(open: Double?) = AiLiveFeed.atm(listOf(snap(high, 101_000.0), snap(low, 99_000.0)), open)?.ticker
        assertEquals(low, feed(99_200.0))
        assertEquals(high, feed(100_800.0))
        assertNull(feed(100_000.0))
        assertNull(feed(null))
    }

    private fun mark(ticker: String, strike: Double) = ScalpRule.Quote(
        ticker, 0L, 900_000L, 0L, 0.49, 10.0, 0.51, 10.0, 100_000.0, strike, 1e-4
    )

    private fun assess(raw: Double, market: Double, yesAsk: Double, noAsk: Double): DecisionPipeline.Assessment {
        val now = 1_800_000_000_000L
        return DecisionPipeline.assess(
            DecisionPipeline.Input(
                ticker = "KXBTC15M-UPDOWN",
                series = "KXBTC15M",
                nowMs = now,
                closeTimeMs = now + 400_000L,
                rawModelYes = raw,
                marketYes = market,
                yesAsk = yesAsk,
                noAsk = noAsk,
                yesBid = yesAsk - 0.01,
                noBid = noAsk - 0.01,
                yesDepth = 500,
                noDepth = 500,
                bookAgeMs = 1_000L,
                spot = 100_000.0,
                strike = 100_000.0,
                volPerSec = 0.0001,
                settlementSource = "Coinbase fallback",
                settlementFresh = true
            )
        )
    }

    /** Crossed 30¢/30¢ book so both research-hold sides clear the 2¢ gate. Returns the side that was queued. */
    private fun researchHoldSide(pTarget: Double): String? {
        val strike = 100_000.0
        val sig = 1e-3
        val tau = 400.0
        val sd = sig * sqrt(tau - 40.0)
        var loZ = -4.0
        var hiZ = 4.0
        var spot = strike
        val now = close - (tau * 1000).toLong()
        if (pTarget == 0.5) {
            spot = strike
        } else repeat(48) {
            val z = (loZ + hiZ) / 2.0
            spot = strike * exp(z * sd)
            val p = Research.fair(quote(now, spot, strike, sig, tau))!!.pYes
            if (p < pTarget) loZ = z else hiZ = z
        }
        val book = ScalpBook(variants = emptyList(), liveExtras = true, bankrollUsd = { 100_000.0 })
        book.onQuote(quote(now, spot, strike, sig, tau), true)
        return book.snapshot().filter { it.variantId == Research.HOLD }.map { it.side }.singleOrNull()
    }

    /** Both asks are the same Double, so a 50¢ fair is an exact edge tie. */
    private fun quote(now: Long, spot: Double, strike: Double, sig: Double, tau: Double): ScalpRule.Quote {
        val bid = 0.7
        return q(now, bid, 1.0 - bid, spot, strike, sigma = sig, cf = spot, tauS = tau)
    }
}
