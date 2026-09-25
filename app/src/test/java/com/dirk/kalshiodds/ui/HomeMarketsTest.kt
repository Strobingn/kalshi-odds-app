package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.data.api.KalshiApi
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
    fun currentWindowIsOneCardPerCoinAndActionableSortsFirst() {
        val settings = HomeFixtures.settings(hasKey = true)
        val markets = listOf(HomeFixtures.noBetEth(), HomeFixtures.actionableBtc(), HomeFixtures.noBetSol())
        val cards = HomeMarkets.coinCards(markets, HomeFixtures.NOW_MS)
        assertEquals(listOf(KalshiApi.SERIES_BTC, KalshiApi.SERIES_SOL, KalshiApi.SERIES_ETH), cards.map { it.series })
        assertEquals(
            listOf("KXBTC15M-25SEP181700-50", "KXSOL15M-25SEP181700-20", "KXETH15M-25SEP181700-40"),
            cards.map { it.market!!.ticker }
        )
        val live = cards.mapNotNull { it.market }
        val ctx = TicketBuilder.Context(settings = settings, alertsPaused = false, nowMs = HomeFixtures.NOW_MS)
        val decisions = HomeMarkets.decisions(live, ctx)
        val ranked = HomeMarkets.ranked(live, decisions, settings)
        assertEquals("KXBTC15M-25SEP181700-50", ranked.first().ticker)
        assertTrue(decisions[ranked.first().ticker]!!.isActionable)
        assertEquals(BetCall.Headline.NO_BET, decisions[ranked[1].ticker]!!.headline)
        val best = HomeMarkets.best(ranked, decisions)!!
        assertEquals(BetCall.Headline.BET_UP, best.second.headline)
        assertEquals(
            listOf(KalshiApi.SERIES_BTC, KalshiApi.SERIES_SOL, KalshiApi.SERIES_ETH),
            HomeMarkets.coinCards(markets, HomeFixtures.NOW_MS).map { it.series }
        )
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
        assertEquals(
            listOf(KalshiApi.SERIES_BTC, KalshiApi.SERIES_SOL, KalshiApi.SERIES_ETH),
            HomeMarkets.coinCards(markets, HomeFixtures.NOW_MS).map { it.series }
        )
    }

    @Test
    fun extraHunterMarketIsNeverInjectedIntoTheThreeCards() {
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
        assertEquals(3, cards.size)
        assertTrue(cards.none { it.market?.ticker?.contains("XRP") == true })
    }

    /**
     * Fake clock + listings: closed-but-still-active BTC + next, a future window
     * listed early, SOL missing for 10s, and edges flipping which coin is best —
     * across three 15m rollovers the cards stay [BTC, SOL, ETH].
     */
    @Test
    fun fixedBtcSolEthCardsAcrossRolloverListingsAndEdgeFlips() {
        val t0 = 1_700_000_000_000L
        val clock = FakeClock(t0 + 60_000L)
        val windowMs = MarketLifecycle.WINDOW_MS
        val closes = LongArray(6) { i -> t0 + (i + 1) * windowMs }

        fun ticker(series: String, step: Int) = "$series-26SEP25${1200 + step * 15}-45"
        fun coin(series: String, label: String, step: Int, edgePp: Double, openMs: Long, closeMs: Long, status: String = "active") =
            HomeFixtures.market(
                ticker = ticker(series, step),
                seriesLabel = label,
                yesAsk = 0.50,
                aiYes = 50.0 + edgePp,
                predicted = if (edgePp >= 0) "YES" else "NO",
                closeMs = closeMs
            ).copy(
                openTimeEpochMs = openMs,
                status = status,
                edgePp = edgePp,
                importedModelPp = 50.0 + edgePp
            )

        fun triple(step: Int, btcEdge: Double, solEdge: Double, ethEdge: Double): List<MarketUiModel> = listOf(
            coin(KalshiApi.SERIES_BTC, "Bitcoin", step, btcEdge, closes[step] - windowMs, closes[step]),
            coin(KalshiApi.SERIES_SOL, "Solana", step, solEdge, closes[step] - windowMs, closes[step]),
            coin(KalshiApi.SERIES_ETH, "Ethereum", step, ethEdge, closes[step] - windowMs, closes[step])
        )

        fun assertSlots(cards: List<HomeMarkets.CoinCard>, btc: String?, sol: String?, eth: String?) {
            assertEquals(listOf(KalshiApi.SERIES_BTC, KalshiApi.SERIES_SOL, KalshiApi.SERIES_ETH), cards.map { it.series })
            assertEquals(btc, cards[0].market?.ticker)
            assertEquals(sol, cards[1].market?.ticker)
            assertEquals(eth, cards[2].market?.ticker)
            if (sol == null) {
                assertTrue(cards[1].loading)
            }
        }

        val w0 = triple(0, btcEdge = 20.0, solEdge = 2.0, ethEdge = 1.0)
        var listed = w0.toMutableList()
        var cards = HomeMarkets.coinCards(listed, clock.nowMs())
        assertSlots(cards, ticker(KalshiApi.SERIES_BTC, 0), ticker(KalshiApi.SERIES_SOL, 0), ticker(KalshiApi.SERIES_ETH, 0))

        // (a) closed-but-still-active BTC plus the next BTC window
        val closedBtc = w0[0].copy(status = "active")
        val nextBtc = coin(KalshiApi.SERIES_BTC, "Bitcoin", 1, 4.0, closes[0], closes[1])
        clock.set(closes[0] + 2_000L)
        listed = (listOf(closedBtc, nextBtc) + w0.drop(1).map {
            it.copy(openTimeEpochMs = closes[0], closeTimeEpochMs = closes[1])
        }).toMutableList()
        cards = HomeMarkets.coinCards(listed, clock.nowMs())
        assertSlots(cards, ticker(KalshiApi.SERIES_BTC, 1), ticker(KalshiApi.SERIES_SOL, 0), ticker(KalshiApi.SERIES_ETH, 0))
        assertTrue(cards[0].market!!.ticker != closedBtc.ticker)

        // (b) future window listed early — must not replace the current ETH card
        val futureEth = coin(
            KalshiApi.SERIES_ETH, "Ethereum", 2, 30.0,
            openMs = closes[1],
            closeMs = closes[2]
        )
        listed.add(futureEth)
        cards = HomeMarkets.coinCards(listed, clock.nowMs())
        assertSlots(cards, ticker(KalshiApi.SERIES_BTC, 1), ticker(KalshiApi.SERIES_SOL, 0), ticker(KalshiApi.SERIES_ETH, 0))
        assertTrue(cards.none { it.market?.ticker == futureEth.ticker })

        // (c) SOL next market missing for 10 s after its close
        clock.set(closes[0] + 6_000L)
        listed = mutableListOf(
            nextBtc,
            coin(KalshiApi.SERIES_ETH, "Ethereum", 1, 1.0, closes[0], closes[1]),
            futureEth
        )
        cards = HomeMarkets.coinCards(listed, clock.nowMs())
        assertSlots(cards, ticker(KalshiApi.SERIES_BTC, 1), null, ticker(KalshiApi.SERIES_ETH, 1))
        assertEquals(HomeMarkets.NEXT_WINDOW_LOADING, HomeMarkets.NEXT_WINDOW_LOADING)
        clock.advance(10_000L)
        val solNext = coin(KalshiApi.SERIES_SOL, "Solana", 1, 8.0, closes[0], closes[1])
        listed.add(solNext)
        cards = HomeMarkets.coinCards(listed, clock.nowMs())
        assertSlots(cards, ticker(KalshiApi.SERIES_BTC, 1), ticker(KalshiApi.SERIES_SOL, 1), ticker(KalshiApi.SERIES_ETH, 1))

        // (d) + three rollovers: flip which coin has the best edge; order never changes
        val settings = HomeFixtures.settings(hasKey = true)
        val edgeSets = listOf(
            Triple(2.0, 25.0, 1.0),
            Triple(1.0, 2.0, 40.0),
            Triple(30.0, 3.0, 4.0)
        )
        for (step in 1..3) {
            clock.set(closes[step - 1] + 4_000L)
            val (btcE, solE, ethE) = edgeSets[step - 1]
            listed = triple(step, btcE, solE, ethE).toMutableList()
            // leftover previous + a far-future BTC
            listed += triple(step - 1, 50.0, 50.0, 50.0)
            listed += coin(KalshiApi.SERIES_BTC, "Bitcoin", step + 1, 99.0, closes[step], closes[step + 1])
            cards = HomeMarkets.coinCards(listed, clock.nowMs())
            assertSlots(
                cards,
                ticker(KalshiApi.SERIES_BTC, step),
                ticker(KalshiApi.SERIES_SOL, step),
                ticker(KalshiApi.SERIES_ETH, step)
            )
            val live = cards.mapNotNull { it.market }
            val ctx = TicketBuilder.Context(settings = settings, alertsPaused = false, nowMs = clock.nowMs())
            val decisions = HomeMarkets.decisions(live, ctx)
            val ranked = HomeMarkets.ranked(live, decisions, settings)
            val bestTicker = HomeMarkets.best(ranked, decisions)!!.first.ticker
            val expectedBest = when (step) {
                1 -> ticker(KalshiApi.SERIES_SOL, step)
                2 -> ticker(KalshiApi.SERIES_ETH, step)
                else -> ticker(KalshiApi.SERIES_BTC, step)
            }
            assertEquals(expectedBest, bestTicker)
            assertEquals(
                listOf(KalshiApi.SERIES_BTC, KalshiApi.SERIES_SOL, KalshiApi.SERIES_ETH),
                cards.map { it.series }
            )
        }
    }
}
