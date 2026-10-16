package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.data.api.KalshiApi
import com.dirk.kalshiodds.domain.CryptoMarkets
import com.dirk.kalshiodds.domain.MarketLifecycle
import com.dirk.kalshiodds.domain.SeriesKind
import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.d3.D3Quote
import com.dirk.kalshiodds.signal.trade.TicketBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.3.39: BTC/ETH/SOL views route to their own tickers; no price leakage between coins. */
class CoinRoutingTest {
    private val now = HomeFixtures.NOW_MS
    private val btc = HomeFixtures.market("KXBTC15M-25SEP181700-00", "Bitcoin", yesAsk = 0.41, aiYes = 50.0, predicted = "YES")
    private val eth = HomeFixtures.market("KXETH15M-25SEP181700-00", "Ethereum", yesAsk = 0.63, aiYes = 50.0, predicted = "YES", floorStrike = 2_600.0, spotUsd = 2_610.0)
    private val sol = HomeFixtures.market("KXSOL15M-25SEP181700-00", "Solana", yesAsk = 0.27, aiYes = 50.0, predicted = "YES", floorStrike = 150.0, spotUsd = 149.0)
    private val all = listOf(btc, eth, sol)

    @Test
    fun seriesInferenceKeepsCoinsApart() {
        assertEquals(KalshiApi.SERIES_BTC, CryptoMarkets.inferSeries(btc.ticker))
        assertEquals(KalshiApi.SERIES_ETH, CryptoMarkets.inferSeries(eth.ticker))
        assertEquals(KalshiApi.SERIES_SOL, CryptoMarkets.inferSeries(sol.ticker))
        assertEquals(KalshiApi.SERIES_ETHD, CryptoMarkets.inferSeries("KXETHD-25SEP1817-T2599.99"))
        assertEquals(KalshiApi.SERIES_SOLD, CryptoMarkets.inferSeries("KXSOLD-25SEP1817-T149.99"))
        assertEquals(SeriesKind.ETH, CryptoMarkets.kindFor("KXETHD-25SEP1817-T2599.99"))
        assertEquals(SeriesKind.SOL, CryptoMarkets.kindFor("KXSOLD-25SEP1817-T149.99"))
        assertTrue(CryptoMarkets.isLiveTicker(eth.ticker))
        assertTrue(CryptoMarkets.isLiveTicker(sol.ticker))
        assertTrue(SignalSettings().isWatchedTicker(eth.ticker))
        assertTrue(SignalSettings().isWatchedTicker(sol.ticker))
    }

    @Test
    fun homeShowsAllThreeCoinsAndSelectorFilters() {
        val cards = HomeMarkets.coinCards(all, now, HomeMarkets.Coin.ALL)
        assertEquals(listOf(KalshiApi.SERIES_BTC, KalshiApi.SERIES_ETH, KalshiApi.SERIES_SOL), cards.map { it.series })
        cards.forEach { assertEquals(it.series, CryptoMarkets.inferSeries(it.market!!.ticker)) }
        val ethOnly = HomeMarkets.coinCards(all, now, HomeMarkets.Coin.ETH)
        assertEquals(1, ethOnly.size)
        assertEquals(eth.ticker, ethOnly.single().market!!.ticker)
        assertEquals(0.63, ethOnly.single().market!!.yesAsk!!, 1e-9)
        assertEquals(sol.ticker, HomeMarkets.coinCards(all, now, HomeMarkets.Coin.SOL).single().market!!.ticker)
        assertEquals(listOf(KalshiApi.SERIES_ETHD), HomeMarkets.dailySeries(HomeMarkets.Coin.ETH))
        assertEquals(3, HomeMarkets.dailySeries(HomeMarkets.Coin.ALL).size)
    }

    @Test
    fun tapOnEthOrSolResolvesToSameCoinNeverBtc() {
        assertEquals(eth.ticker, MarketLifecycle.resolveActionWindow(eth, all, now)!!.ticker)
        assertEquals(sol.ticker, MarketLifecycle.resolveActionWindow(sol, all, now)!!.ticker)
        // ETH listing missing from the snapshot: never falls back to the BTC (or SOL) window.
        val ethMiss = MarketLifecycle.resolveActionWindow(eth.copy(status = "closed"), listOf(btc, sol), now)
        assertNull(ethMiss)
        assertNull(MarketLifecycle.resolveActionWindow(sol.copy(status = "closed"), listOf(btc), now))
        val stillEth = MarketLifecycle.resolveActionWindow(eth, listOf(btc, sol), now)
        assertTrue(stillEth == null || stillEth.ticker == eth.ticker)
        assertEquals(btc.ticker, MarketLifecycle.resolveActionWindow(btc, listOf(btc, eth), now)!!.ticker)
    }

    @Test
    fun manualTicketUsesTheTappedCoinsOwnPrices() {
        val ctx = TicketBuilder.Context(settings = SignalSettings(), alertsPaused = false, nowMs = now)
        val ethTicket = TicketBuilder.proposeManual(MarketLifecycle.resolveActionWindow(eth, all, now)!!, "YES", ctx)
        assertNotNull(ethTicket)
        assertEquals(eth.ticker, ethTicket!!.ticker)
        assertEquals(0.63, ethTicket.limitPrice, 0.03)
        assertTrue(kotlin.math.abs(ethTicket.limitPrice - btc.yesAsk!!) > 0.1)
        val solTicket = TicketBuilder.proposeManual(MarketLifecycle.resolveActionWindow(sol, all, now)!!, "NO", ctx)
        assertEquals(sol.ticker, solTicket!!.ticker)
        assertEquals("NO", solTicket.side)
        assertEquals(sol.noAsk!!, solTicket.limitPrice, 0.03)
        assertTrue(kotlin.math.abs(solTicket.limitPrice - btc.noAsk!!) > 0.1)
    }

    @Test
    fun dailyRowsAndBuyStayOnTheirOwnSeries() {
        fun q(ticker: String, strike: Double, yesAsk: Double) = D3Quote(
            ticker = ticker, eventTicker = ticker.substringBeforeLast("-"), title = ticker, subtitle = null,
            strikeUsd = strike, yesBid = yesAsk - 0.02, yesAsk = yesAsk, noBid = 0.98 - yesAsk, noAsk = 1.0 - yesAsk + 0.02,
            closeTimeEpochMs = now + 3_600_000L, status = "active"
        )
        val btcD = q("KXBTCD-25SEP1817-T66999.99", 67_000.0, 0.52)
        val ethD = q("KXETHD-25SEP1817-T2599.99", 2_600.0, 0.47)
        val leaked = q("KXBTCD-25SEP1817-T67999.99", 68_000.0, 0.50)
        // A BTC quote filed under the ETH key must not render as ETH.
        val bySeries = mapOf(KalshiApi.SERIES_BTCD to listOf(btcD), KalshiApi.SERIES_ETHD to listOf(ethD, leaked))
        val ethRows = HomeMarkets.dailyRows(bySeries, KalshiApi.SERIES_ETHD)
        assertEquals(listOf(ethD.ticker), ethRows.map { it.ticker })
        assertTrue(HomeMarkets.dailyRows(bySeries, KalshiApi.SERIES_SOLD).isEmpty())
        val m = HomeMarkets.dailyMarket(ethD)
        assertEquals(ethD.ticker, m.ticker)
        assertEquals(ethD.yesAsk!!, m.yesAsk!!, 1e-9)
        assertEquals(2_600.0, m.floorStrike!!, 1e-9)
        val ctx = TicketBuilder.Context(settings = SignalSettings(), alertsPaused = false, nowMs = now)
        val ticket = TicketBuilder.proposeManual(m, "YES", ctx)!!
        assertEquals(ethD.ticker, ticket.ticker)
        assertEquals(0.47, ticket.limitPrice, 0.03)
    }
}
