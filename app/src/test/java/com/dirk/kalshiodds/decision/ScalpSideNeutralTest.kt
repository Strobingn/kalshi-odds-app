package com.dirk.kalshiodds.decision

import org.junit.Assert.*
import org.junit.Test

/** 0.3.87: every scalper picks UP or DOWN only from its own signal; a mirrored market buys the mirrored side. */
class ScalpSideNeutralTest {
    private val ticker = "KXBTC15M-26OCT122015-15"
    private val close = ScalpTicker.closeMs(ticker)!!
    private val now = close - 400_000L

    private fun quote(yb: Double, ya: Double, spot: Double, strike: Double) =
        ScalpRule.Quote(ticker, now, close, now, yb, 50.0, ya, 50.0, spot, strike, 0.0002)
    private fun mirror(q: ScalpRule.Quote) = q.copy(yesBid = 1 - q.yesAsk!!, yesAsk = 1 - q.yesBid!!,
        spot = q.strike!! * q.strike / q.spot!!)

    @Test fun symmetricSetupBuysDownAsOftenAsUp() {
        val rnd = java.util.Random(42)
        var up = 0; var down = 0
        repeat(4000) {
            val mid = .30 + rnd.nextInt(41) / 100.0
            val q = quote(mid - .01, mid, 100_000.0 * (1 + (rnd.nextDouble() - .5) * .004), 100_000.0)
            val params = ScalpParams.SEEDS.values.toList() + ScalpParams.STRATEGY_SEEDS.values
            for (p in params.filter { it.strategy == ScalpStrategy.FAIR_GAP || it.strategy == ScalpStrategy.EXTREME_REVERSION }) {
                val a = ScalpRule.strategySignal(q, p, listOf("YES", "NO"), emptyList()).first
                val b = ScalpRule.strategySignal(mirror(q), p, listOf("YES", "NO"), emptyList()).first
                assertEquals("${p.id} mirrored decision", a == null, b == null)
                if (a != null) {
                    assertNotEquals("${p.id} mirrored side", a.side, b!!.side)
                    assertEquals(a.ask, b.ask, 1e-9)
                    if (a.side == "YES") up++ else down++
                    if (b.side == "YES") up++ else down++
                }
            }
        }
        assertTrue("some entries", up > 0)
        assertEquals(up, down)
    }

    @Test fun noAskIsOneMinusYesBidAndFairIsComplement() {
        val q = quote(.40, .42, 100_000.0, 100_000.0)
        assertEquals(.60, q.ask("NO")!!, 1e-12)
        assertEquals(.58, q.bid("NO")!!, 1e-12)
        assertEquals(1.0, q.fair("YES")!! + q.fair("NO")!!, 1e-12)
    }

    @Test fun exactTieWaitsInsteadOfDefaultingToUp() {
        assertNull(ScalpRule.neutralBest(listOf("YES" to .02, "NO" to .02)) { it.second })
        assertEquals("NO", ScalpRule.neutralBest(listOf("YES" to .01, "NO" to .02)) { it.second }!!.first)
    }

    @Test fun forecastIsAntisymmetricSoNoBuiltInUpBias() {
        val rnd = java.util.Random(3)
        for (coin in ScalpParams.COINS) {
            val h = LeadForecaster.configFor(coin).horizonS
            repeat(200) {
                val x = DoubleArray(11) { i -> if (i == 0) 1.0 else (rnd.nextDouble() - .5) }
                val p = LeadForecaster.predict(coin, h, x) ?: return@repeat
                assertEquals(-p, LeadForecaster.predict(coin, h, LeadForecaster.mirror(x))!!, 1e-12)
            }
            val flat = DoubleArray(11).also { it[0] = 1.0; it[6] = .5 }
            LeadForecaster.predict(coin, h, flat)?.let { assertEquals(0.0, it, 1e-12) }
        }
    }
}
