package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.signal.trade.BetCall
import com.dirk.kalshiodds.signal.trade.TicketBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeMarketsTest {

    @Test
    fun currentWindowIsOneCardPerCoinAndActionableSortsFirst() {
        val settings = HomeFixtures.settings(hasKey = true)
        val markets = listOf(HomeFixtures.noBetEth(), HomeFixtures.actionableBtc(), HomeFixtures.noBetSol())
        val cards = HomeMarkets.currentWindowCards(markets, settings, HomeFixtures.NOW_MS)
        assertEquals(3, cards.size)
        val ctx = TicketBuilder.Context(settings = settings, alertsPaused = false, nowMs = HomeFixtures.NOW_MS)
        val decisions = HomeMarkets.decisions(cards, ctx)
        val ranked = HomeMarkets.ranked(cards, decisions, settings)
        assertEquals("KXBTC15M-25SEP181700-50", ranked.first().ticker)
        assertTrue(decisions[ranked.first().ticker]!!.isActionable)
        assertEquals(BetCall.Headline.NO_BET, decisions[ranked[1].ticker]!!.headline)
        val best = HomeMarkets.best(ranked, decisions)!!
        assertEquals(BetCall.Headline.BET_UP, best.second.headline)
    }

    @Test
    fun allNoBetKeepsWindowCards() {
        val settings = HomeFixtures.settings(hasKey = false)
        val markets = listOf(
            HomeFixtures.actionableBtc().copy(yesAsk = 0.63, noAsk = 0.37, yesProbabilityPercent = 63.0, aiYesPercent = 70.0),
            HomeFixtures.noBetEth(),
            HomeFixtures.noBetSol()
        )
        val cards = HomeMarkets.currentWindowCards(markets, settings, HomeFixtures.NOW_MS)
        val ctx = TicketBuilder.Context(settings = settings, alertsPaused = false, nowMs = HomeFixtures.NOW_MS)
        val decisions = HomeMarkets.decisions(cards, ctx)
        assertTrue(decisions.values.all { !it.isActionable })
    }
}
