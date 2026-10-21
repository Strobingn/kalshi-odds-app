package com.dirk.kalshiodds.decision

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ETH/SOL forecasts can see 60s of mid, and those coins still do not trade. */
class EthSolForecastHistoryTest {
    @Test fun ethAndSolDoNotTradeUnlessAScalpIsAlreadyOpen() {
        assertTrue(ScalpTradeGate.trades("KXBTC15M-26OCT181530-30", emptySet()))
        assertFalse(ScalpTradeGate.trades("KXETH15M-26OCT181530-30", emptySet()))
        assertFalse(ScalpTradeGate.trades("KXSOL15M-26OCT181530-30", emptySet()))
        assertFalse(ScalpTradeGate.trades("kxeth15m-26OCT181530-30", emptySet()))
        assertTrue(ScalpTradeGate.trades("kxeth15m-26OCT181530-30", setOf("KXETH15M-26OCT181530-30")))
        assertTrue(ScalpTradeGate.trades("KXSOL15M-26OCT181530-30", setOf("KXSOL15M-26OCT181530-30")))
    }

    @Test fun midHistoryFeedsDm60AndDoesNotOpenATrade() {
        val ids = AtomicInteger()
        val book = ScalpBook(
            InMemoryScalpPersistence(),
            emptyList(),
            InMemoryScalpTuneStore(),
            bankrollUsd = { 1_000.0 }
        ) { "r${ids.incrementAndGet()}" }
        val eth = "KXETH15M-26OCT181530-30"
        val sol = "KXSOL15M-26OCT181530-30"
        val close = ScalpTicker.closeMs(eth)!!
        val t0 = close - 180_000L
        book.noteMidHistory(q(eth, t0, 0.40, 0.42))
        book.noteMidHistory(q(eth, t0 + 60_000L, 0.50, 0.52))
        book.noteMidHistory(q(sol, t0, 0.20, 0.22))
        book.noteMidHistory(q(sol, t0 + 60_000L, 0.30, 0.32))
        assertEquals(0.10, book.dm60Snapshot(eth, t0 + 60_000L), 1e-9)
        assertEquals(0.10, book.dm60Snapshot(sol, t0 + 60_000L), 1e-9)
        assertTrue(book.allTrades().isEmpty())
        assertTrue(book.snapshot().isEmpty())
        assertTrue(book.openTickers().isEmpty())
        assertFalse(ScalpTradeGate.trades(eth, book.openTickers()))
        assertFalse(ScalpTradeGate.trades(sol, book.openTickers()))
    }

    @Test fun viewModelKeepsNonBtcOffTheTradeMethods() {
        val src = listOf(
            File("app/src/main/java/com/dirk/kalshiodds/ui/OddsViewModel.kt"),
            File("src/main/java/com/dirk/kalshiodds/ui/OddsViewModel.kt")
        ).first { it.isFile }.readText()
        val body = src.substring(src.indexOf("private fun runScalp"), src.indexOf("private fun liveSnap"))
        assertTrue(body.contains("ScalpTradeGate.trades(market.ticker, openScalps)"))
        assertTrue(body.contains("noteMidHistory"))
        val trade = body.substringAfter("if (tradeThis) {").substringBefore("} else if")
        assertTrue(trade.contains("onQuote("))
        assertTrue(trade.contains("onForecastQuote("))
        assertTrue(trade.contains("onShockFavQuote("))
        assertFalse(trade.contains("noteMidHistory"))
        val arena = listOf(
            File("app/src/main/java/com/dirk/kalshiodds/ui/KashiArenaScreen.kt"),
            File("src/main/java/com/dirk/kalshiodds/ui/KashiArenaScreen.kt")
        ).first { it.isFile }.readText()
        val screen = arena.substring(arena.indexOf("fun KashiArenaContent"), arena.indexOf("fun ArenaSummary"))
        assertFalse(screen.substringBefore("KashiArenaTab.ANALYSIS").contains("rememberScalpChartHistory"))
        assertTrue(screen.substringAfter("KashiArenaTab.ANALYSIS").contains("rememberScalpChartHistory"))
    }

    private fun q(ticker: String, now: Long, bid: Double, ask: Double) = ScalpRule.Quote(
        ticker = ticker,
        nowMs = now,
        closeMs = ScalpTicker.closeMs(ticker)!!,
        bookAtMs = now,
        yesBid = bid,
        yesBidSize = 10.0,
        yesAsk = ask,
        yesAskSize = 10.0,
        spot = 100.0,
        strike = 100.0,
        sigmaPerSec = 1e-4
    )
}
