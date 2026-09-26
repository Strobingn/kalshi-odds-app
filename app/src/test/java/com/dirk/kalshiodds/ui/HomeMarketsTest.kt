package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.data.api.KalshiApi
import com.dirk.kalshiodds.domain.CryptoMarkets
import com.dirk.kalshiodds.domain.FakeClock
import com.dirk.kalshiodds.domain.MarketLifecycle
import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.signal.trade.BetCall
import com.dirk.kalshiodds.signal.trade.TicketBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeMarketsTest {

    @Test
    fun currentWindowIsExactlyOneBtcCard() {
        val settings = HomeFixtures.settings(hasKey = true)
        val markets = listOf(HomeFixtures.noBetEth(), HomeFixtures.actionableBtc(), HomeFixtures.noBetSol())
        val cards = HomeMarkets.coinCards(markets, HomeFixtures.NOW_MS)
        assertEquals(listOf(KalshiApi.SERIES_BTC), cards.map { it.series })
        assertEquals(listOf(KalshiApi.SERIES_BTC), CryptoMarkets.DEFAULT_SERIES)
        assertEquals(listOf(KalshiApi.SERIES_BTC), HomeMarkets.CARD_SERIES)
        assertEquals(1, cards.size)
        assertEquals("KXBTC15M-25SEP181700-50", cards.single().market!!.ticker)
        assertTrue(cards.none { it.series.contains("SOL") || it.series.contains("ETH") })
        val live = cards.mapNotNull { it.market }
        val ctx = TicketBuilder.Context(settings = settings, alertsPaused = false, nowMs = HomeFixtures.NOW_MS)
        val decisions = HomeMarkets.decisions(live, ctx)
        val ranked = HomeMarkets.ranked(live, decisions, settings)
        assertEquals("KXBTC15M-25SEP181700-50", ranked.single().ticker)
        assertTrue(decisions[ranked.first().ticker]!!.isActionable)
        val best = HomeMarkets.best(ranked, decisions)!!
        assertEquals(BetCall.Headline.BET_UP, best.second.headline)
    }

    @Test
    fun allNoBetKeepsTheBtcCard() {
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
        assertEquals(listOf(KalshiApi.SERIES_BTC), HomeMarkets.coinCards(markets, HomeFixtures.NOW_MS).map { it.series })
        assertEquals(1, cards.size)
    }

    @Test
    fun extraHunterMarketIsNeverInjectedIntoTheBtcCard() {
        val hunter = HomeFixtures.market(
            ticker = "KXXRP15M-25SEP181700-10",
            seriesLabel = "XRP",
            yesAsk = 0.10,
            aiYes = 40.0,
            predicted = "NO"
        )
        val cards = HomeMarkets.coinCards(
            listOf(HomeFixtures.actionableBtc(), HomeFixtures.noBetSol(), HomeFixtures.noBetEth(), hunter),
            HomeFixtures.NOW_MS
        )
        assertEquals(1, cards.size)
        assertEquals(KalshiApi.SERIES_BTC, cards.single().series)
        assertTrue(cards.none { it.market?.ticker?.contains("XRP") == true })
        assertTrue(cards.none { it.series.contains("SOL") || it.series.contains("ETH") })
    }

    /**
     * Fake clock + listings: closed-but-still-active BTC + next, a future
     * window listed early, next listing missing for 10s — the single BTC
     * card never becomes SOL/ETH and recovers when the listing returns.
     */
    @Test
    fun fixedBtcCardAcrossRolloverListingsAndEdgeFlips() {
        val t0 = 1_700_000_000_000L
        val clock = FakeClock(t0 + 60_000L)
        val windowMs = MarketLifecycle.WINDOW_MS
        val closes = LongArray(6) { i -> t0 + (i + 1) * windowMs }

        fun ticker(step: Int) = "${KalshiApi.SERIES_BTC}-26SEP25${1200 + step * 15}-45"
        fun coin(step: Int, edgePp: Double, openMs: Long, closeMs: Long): MarketUiModel =
            HomeFixtures.market(
                ticker = ticker(step),
                seriesLabel = "Bitcoin",
                yesAsk = 0.50,
                aiYes = 50.0 + edgePp,
                predicted = if (edgePp >= 0) "YES" else "NO",
                closeMs = closeMs
            ).copy(
                openTimeEpochMs = openMs,
                status = "active",
                edgePp = edgePp,
                importedModelPp = 50.0 + edgePp
            )

        fun assertBtc(cards: List<HomeMarkets.CoinCard>, expected: String?) {
            assertEquals(listOf(KalshiApi.SERIES_BTC), cards.map { it.series })
            assertEquals(1, cards.size)
            assertEquals(expected, cards.single().market?.ticker)
            if (expected == null) assertTrue(cards.single().loading)
            assertTrue(cards.none { it.series.contains("SOL") || it.series.contains("ETH") })
        }

        val w0 = coin(0, 20.0, closes[0] - windowMs, closes[0])
        var listed = mutableListOf(w0)
        assertBtc(HomeMarkets.coinCards(listed, clock.nowMs()), ticker(0))

        val nextBtc = coin(1, 4.0, closes[0], closes[1])
        clock.set(closes[0] + 2_000L)
        listed = mutableListOf(w0.copy(status = "active"), nextBtc)
        assertBtc(HomeMarkets.coinCards(listed, clock.nowMs()), ticker(1))

        val futureBtc = coin(2, 30.0, closes[1], closes[2])
        listed.add(futureBtc)
        assertBtc(HomeMarkets.coinCards(listed, clock.nowMs()), ticker(1))
        assertTrue(HomeMarkets.coinCards(listed, clock.nowMs()).none { it.market?.ticker == futureBtc.ticker })

        clock.set(closes[1] + 6_000L)
        listed = mutableListOf(futureBtc)
        val missing = HomeMarkets.coinCards(listed, clock.nowMs())
        assertBtc(missing, null)
        assertEquals(HomeMarkets.NEXT_WINDOW_LOADING, HomeMarkets.NEXT_WINDOW_LOADING)
        clock.advance(10_000L)
        listed.add(coin(2, 8.0, closes[1], closes[2]))
        assertBtc(HomeMarkets.coinCards(listed, clock.nowMs()), ticker(2))

        val settings = HomeFixtures.settings(hasKey = true)
        for (step in 1..3) {
            clock.set(closes[step - 1] + 4_000L)
            listed = mutableListOf(
                coin(step, 10.0 + step, closes[step] - windowMs, closes[step]),
                coin(step - 1, 50.0, closes[step - 1] - windowMs, closes[step - 1]),
                coin(step + 1, 99.0, closes[step], closes[step + 1])
            )
            val cards = HomeMarkets.coinCards(listed, clock.nowMs())
            assertBtc(cards, ticker(step))
            val live = cards.mapNotNull { it.market }
            val ctx = TicketBuilder.Context(settings = settings, alertsPaused = false, nowMs = clock.nowMs())
            val decisions = HomeMarkets.decisions(live, ctx)
            val ranked = HomeMarkets.ranked(live, decisions, settings)
            assertEquals(ticker(step), HomeMarkets.best(ranked, decisions)!!.first.ticker)
        }
    }
}
