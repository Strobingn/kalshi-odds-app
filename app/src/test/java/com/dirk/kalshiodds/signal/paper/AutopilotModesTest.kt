package com.dirk.kalshiodds.signal.paper

import com.dirk.kalshiodds.prediction.PredictionLogEntry
import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.trade.LiveOrderSizer
import com.dirk.kalshiodds.ui.HomeFixtures
import com.dirk.kalshiodds.ui.ScorecardCopy
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AutopilotModesTest {
    private val nowMs = HomeFixtures.NOW_MS
    private val settings = SignalSettings(
        paperTradingEnabled = true,
        aiPaperAutopilotEnabled = true,
        paperKellyFraction = 0.5
    )

    @Before
    fun reset() {
        PaperAutopilot.resetSession()
    }

    @Test
    fun shadowRecordsButNeverSendsAndPaperModeFills() {
        val book = PaperBook(idFactory = { "p1" }, nowMs = { nowMs })
        val shadow = ShadowBook(idFactory = { "s1" }, nowMs = { nowMs })
        val market = taggedMarket()
        var last: AutopilotStep.Outcome? = null
        repeat(2) {
            last = AutopilotStep.run(book, shadow, market, settings, AutopilotMode.SHADOW, nowMs,
                0.20, market.noAsk, 100_000, 100_000, null, null, "shadow-1")
        }
        assertTrue("$last", last is AutopilotStep.Outcome.ShadowOnly)
        assertTrue(book.snapshot().fills.isEmpty())
        val ticket = shadow.snapshot().tickets.single()
        assertTrue(ticket.summary().contains("SHADOW — not submitted"))
        assertTrue(ticket.payloadJson.contains("client_order_id"))
        assertFalse(ticket.liveSubmitted)
        listOf("ShadowBook.kt", "ShadowOrderPayload.kt", "AutopilotStep.kt", "PaperAutopilot.kt").forEach { name ->
            val text = source(name)
            assertFalse(name, text.contains("createOrder"))
            assertFalse(name, text.contains("createLimit"))
            assertFalse(name, text.contains("placeOrder"))
        }
    }

    @Test
    fun legacyLiveModeParsesAsPaperAndOnlyPaperAndShadowExist() {
        assertEquals(AutopilotMode.PAPER, AutopilotMode.parse("LIVE"))
        assertEquals(AutopilotMode.PAPER, AutopilotMode.parse(null))
        assertEquals(AutopilotMode.SHADOW, AutopilotMode.parse("shadow"))
        assertEquals(listOf("PAPER", "SHADOW"), AutopilotMode.entries.map { it.name })
        assertEquals(AutopilotMode.PAPER, SignalSettings().autopilotModeEnum())
    }

    @Test
    fun regimeTagsPersistAndScorecardSplits() {
        val clock = java.util.concurrent.atomic.AtomicLong(nowMs)
        val book = PaperBook(idFactory = { "r1" }, nowMs = { clock.get() })
        val market = taggedMarket()
        PaperAutopilot.consider(book, market, settings, clock.get(), yesDepth = 100_000, noDepth = 100_000)
        val fill = PaperAutopilot.consider(book, market, settings, clock.get(), yesDepth = 100_000, noDepth = 100_000)
        assertNotNull(fill)
        assertEquals("chop", fill!!.regimePath)
        assertEquals("underdog", fill.regimeRole)
        assertEquals("mid", fill.regimeVol)
        assertEquals("us", fill.regimeSession)
        assertEquals("far", fill.regimeStrike)
        clock.addAndGet(1_000)
        book.settle(fill.ticker, "no")
        val favorite = PredictionLogEntry(
            ticker = "KXBTC15M-FAV",
            series = "KXBTC15M",
            predictedYes = 0.80,
            predictedNo = 0.20,
            marketMid = 0.80,
            timestampMs = 1L,
            closeTimeMs = 2L,
            outcome = "yes",
            predictedSide = "YES"
        )
        val view = ScorecardCopy.of(listOf(favorite), book.snapshot())
        val lines = view.allLines()
        assertTrue(lines.any { it.contains("Paper bankroll") })
        assertTrue(lines.any { it.contains("Autopilot settled P&L") })
        assertTrue(lines.any { it.contains("Prediction-log favorites") })
        assertTrue(lines.any { it.contains("Autopilot edge fills") })
        assertTrue(lines.any { it.startsWith("Path chop") })
        assertTrue(view.autopilot.credibility.contains("Thin sample"))
        assertTrue(view.autopilot.credibility.contains("No train/test split"))
        assertFalse(view.autopilot.favoriteLogLine.contains("Autopilot edge"))
        assertTrue(view.autopilot.edgeFillLine.contains("P&L"))
        val shadow = ShadowBook(nowMs = { nowMs })
        val missed = shadowOf(
            ticker = "KXBTC15M-MISS",
            depth = 0,
            clientOrderId = "miss"
        )
        assertFalse(missed.depthFill)
        shadow.record(missed)
        shadow.settle("KXBTC15M-MISS", "yes")
        val row = shadow.snapshot().tickets.single()
        assertTrue(row.settled)
        assertNull(row.pnlUsd)
        assertNull(row.won)
        assertEquals(0.0, shadow.snapshot().lifetimeRealizedPnlUsd, 1e-9)
        val filled = shadowOf(
            ticker = "KXBTC15M-HIT",
            depth = 100_000,
            clientOrderId = "hit"
        )
        assertTrue(filled.booked)
        assertTrue(filled.stakeUsd > 0.0)
        shadow.record(filled)
        shadow.settle("KXBTC15M-HIT", "yes")
        val won = shadow.snapshot().tickets.first { it.clientOrderId == "hit" }
        assertEquals(true, won.won)
        assertNotNull(won.pnlUsd)
        assertTrue(won.pnlUsd!! > 0.0)
    }

    private fun shadowOf(ticker: String, depth: Int, clientOrderId: String): ShadowTicket {
        val sized = PaperKellySizer.size(
            winProb = 0.55,
            ask = 0.20,
            bankrollUsd = 1_000.0,
            kellyFraction = 0.5,
            depthContracts = depth.coerceAtLeast(0)
        )
        val use = if (depth <= 0) sized.copy(skip = true, contracts = 0, allInUsd = 0.0) else sized
        return ShadowOrderPayload.fromKelly(
            ticker = ticker,
            side = "YES",
            sized = use,
            depth = depth,
            reason = "edge",
            nowMs = nowMs,
            clientOrderId = clientOrderId
        )
    }


    private fun taggedMarket() = HomeFixtures.market(
        ticker = "KXBTC15M-25SEP181700-50",
        seriesLabel = "Bitcoin",
        yesAsk = 0.20,
        aiYes = 80.0,
        predicted = "YES",
        closeMs = nowMs + 372_000L,
        floorStrike = 67_000.0,
        spotUsd = 67_240.0,
        spotDelta = 240.0
    ).copy(regimeTag = "CHOP", sessionTag = "US", midVolPp = 0.80)

    private fun source(name: String): String = listOf(
        java.io.File("app/src/main/java/com/dirk/kalshiodds/signal/paper/$name"),
        java.io.File("src/main/java/com/dirk/kalshiodds/signal/paper/$name")
    ).first { it.isFile }.readText()
}
