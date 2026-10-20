package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.latefav.LateFavoriteTotals
import com.dirk.kalshiodds.signal.trade.BetCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The big "AI SAYS UP / DOWN / WAIT" banner at the top of Home. */
class AiBannerTest {
    private val now = HomeFixtures.NOW_MS
    private val record = LateFavoriteTotals(wins = 12, losses = 20, pnlUsd = 8.4)

    @Test
    fun aLateComebackSaysUpWithItsNumbers() {
        val m = HomeFixtures.market("KXBTC15M-26OCT201230-30", "Bitcoin", yesAsk = 0.32, aiYes = 50.0, predicted = "YES", closeMs = now + 220_000L)
            .copy(comebackSide = "YES", comebackAsk = 0.32)
        val call = BetCall.decide(m, SignalSettings(), now)
        val b = AiBanner.of(call, m, now, comebackRecord = record)
        assertEquals(AiBanner.UP, b.title)
        assertEquals(BetCall.Headline.BET_UP, b.headline)
        assertEquals(AiBanner.COMEBACK, b.rule)
        assertTrue(b.reason, b.reason.startsWith("The underdog at 32¢ is climbing"))
        val f = b.figures.associate { it.label to it.value }
        assertEquals("32¢", f["Buy at"])
        assertTrue(f.getValue("\$5 buys").endsWith("contracts"))
        assertTrue(f.getValue("If it wins").startsWith("+$"))
        assertEquals("3:40 left", f["Time left"])
        assertEquals("$67,240 · $240 above", f["Bitcoin"])
        assertEquals("12 won · 20 lost · +$8.40", f["Late comeback record"])
        assertEquals("YES", b.side)
        assertTrue(b.footer.contains("Approve"))
    }

    @Test
    fun aClearLeadDownSaysDown() {
        val m = HomeFixtures.market("KXBTC15M-26OCT201230-30", "Bitcoin", yesAsk = 0.13, aiYes = 50.0, predicted = "NO")
            .copy(clearLeadSide = "NO", clearLeadAsk = 0.88)
        val call = BetCall.decide(m, SignalSettings(), now)
        val b = AiBanner.of(call, m, now)
        assertEquals(AiBanner.DOWN, b.title)
        assertEquals(AiBanner.CLEAR_LEAD, b.rule)
        assertEquals("NO", b.side)
        assertTrue(b.footer.startsWith(AiBanner.CLEAR_LEAD_HISTORY))
    }

    @Test
    fun noRuleOnSaysWaitWithTheLean() {
        val m = HomeFixtures.market("KXBTC15M-26OCT201230-30", "Bitcoin", yesAsk = 0.63, aiYes = 50.0, predicted = "YES")
        val b = AiBanner.of(BetCall.decide(m, SignalSettings(), now), m, now, comebackRecord = LateFavoriteTotals())
        assertEquals(AiBanner.WAIT, b.title)
        assertEquals(AiBanner.WATCHING, b.reason)
        val f = b.figures.associate { it.label to it.value }
        assertEquals("UP 62%", f["Kalshi leans"])
        assertEquals("no bets settled yet", f["Late comeback record"])
        assertNull(b.side)
        assertEquals(AiBanner.WAIT, AiBanner.of(null, null, now).title)
    }

    @Test
    fun bitcoinBelowTheLine() {
        val m = HomeFixtures.market("KXBTC15M-T", "Bitcoin", yesAsk = 0.40, aiYes = 50.0, predicted = "NO", spotUsd = 66_910.4)
        assertEquals("$66,910 · $90 below", AiBanner.bitcoinLine(m))
        assertEquals("DOWN 61%", AiBanner.leanLine(m))
    }
}
