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
    fun shadowNeverCallsPlaceOrder() {
        val calls = AtomicInteger(0)
        val book = PaperBook(idFactory = { "p1" }, nowMs = { nowMs })
        val shadow = ShadowBook(idFactory = { "s1" }, nowMs = { nowMs })
        val market = taggedMarket()
        repeat(2) {
            val tick = PaperAutopilot.tick(
                book, market, settings, nowMs,
                yesDepth = 100_000, noDepth = 100_000, bookPaper = false
            )
            if (!tick.decision.ok) return@repeat
            val picked = tick.decision.side!!
            val draft = ShadowOrderPayload.draft(
                ticker = market.ticker,
                side = picked.side,
                ask = picked.ask,
                depth = 100_000,
                reason = "edge",
                nowMs = nowMs,
                clientOrderId = "shadow-1",
                capUsd = 10.0,
                regimeKey = "test"
            )
            shadow.record(draft)
            val decision = AutopilotDispatch.decide(
                request(
                    mode = AutopilotMode.SHADOW,
                    armed = true,
                    paperFilled = false,
                    paperSide = picked.side,
                    paperPrice = picked.ask,
                    shadowSide = draft.side,
                    shadowPrice = draft.limitPrice,
                    depthFill = draft.depthFill,
                    allIn = draft.stakeUsd
                )
            )
            AutopilotDispatch.run(decision) { calls.incrementAndGet() }
            assertFalse(decision.shouldPlace)
            assertTrue(decision.reason.contains("SHADOW"))
        }
        assertEquals(0, calls.get())
        assertTrue(book.snapshot().fills.isEmpty())
        assertEquals(1, shadow.snapshot().tickets.size)
        val ticket = shadow.snapshot().tickets.single()
        assertTrue(ticket.summary().contains("SHADOW — not submitted"))
        assertTrue(ticket.payloadJson.contains("client_order_id"))
        assertTrue(ticket.payloadJson.contains("\"action\":\"buy\"") || ticket.payloadJson.contains("\"action\": \"buy\""))
        assertFalse(ticket.liveSubmitted)
        listOf(
            "ShadowBook.kt",
            "ShadowOrderPayload.kt",
            "AutopilotDispatch.kt",
            "PaperAutopilot.kt"
        ).forEach { name ->
            val text = source(name)
            assertFalse(name, text.contains("createOrder"))
            assertFalse(name, text.contains("createLimit"))
            assertFalse(name, text.contains("placeOrder"))
        }
    }

    @Test
    fun liveRespectsTenDollarsAndDailyCap() {
        val wide = ShadowOrderPayload.draft(
            ticker = "KXBTC15M-CAP",
            side = "YES",
            ask = 0.20,
            depth = 100_000,
            reason = "edge",
            nowMs = nowMs,
            clientOrderId = "cap-1",
            capUsd = 80.0
        )
        assertTrue(wide.stakeUsd <= LiveOrderSizer.LIVE_ALL_IN_CAP_USD + 1e-6)
        assertTrue(wide.stakeUsd > 0.0)
        val tight = ShadowOrderPayload.draft(
            ticker = "KXBTC15M-CAP",
            side = "YES",
            ask = 0.20,
            depth = 100_000,
            reason = "edge",
            nowMs = nowMs,
            clientOrderId = "cap-2",
            capUsd = 6.0
        )
        assertTrue(tight.stakeUsd <= 6.0 + 1e-6)
        val overTicket = live(
            allIn = 10.01,
            spent = 0.0,
            cap = 50.0
        )
        assertFalse(overTicket.shouldPlace)
        assertTrue(overTicket.reason.contains("10") || overTicket.reason.contains("cap"))
        val underDaily = live(allIn = 10.0, spent = 40.0, cap = 50.0)
        assertTrue(underDaily.shouldPlace)
        val overDaily = live(allIn = 10.0, spent = 45.0, cap = 50.0)
        assertFalse(overDaily.shouldPlace)
        assertTrue(overDaily.reason.contains("Daily"))
        val book = ShadowBook()
        assertTrue(book.claimLive("a", "2026-10-05", 10.0, 50.0))
        assertTrue(book.claimLive("b", "2026-10-05", 10.0, 50.0))
        assertTrue(book.claimLive("c", "2026-10-05", 10.0, 50.0))
        assertTrue(book.claimLive("d", "2026-10-05", 10.0, 50.0))
        assertTrue(book.claimLive("e", "2026-10-05", 10.0, 50.0))
        assertFalse(book.claimLive("f", "2026-10-05", 10.0, 50.0))
        assertEquals(50.0, book.snapshot().spentOn("2026-10-05"), 1e-6)
        assertFalse(book.claimLive("a", "2026-10-05", 10.0, 200.0))
    }

    @Test
    fun modeSwitchAndArming() {
        val paper = AutopilotDispatch.decide(request(AutopilotMode.PAPER, armed = true))
        assertTrue(paper.bookPaper)
        assertFalse(paper.recordShadow)
        assertFalse(paper.shouldPlace)
        val shadow = AutopilotDispatch.decide(request(AutopilotMode.SHADOW, armed = true))
        assertFalse(shadow.bookPaper)
        assertTrue(shadow.recordShadow)
        assertFalse(shadow.shouldPlace)
        val liveOff = AutopilotDispatch.decide(request(AutopilotMode.LIVE, armed = false))
        assertTrue(liveOff.bookPaper)
        assertTrue(liveOff.recordShadow)
        assertFalse(liveOff.shouldPlace)
        assertTrue(liveOff.reason.contains("REAL MONEY"))
        val disagree = AutopilotDispatch.decide(
            request(AutopilotMode.LIVE, armed = true).copy(shadowSide = "NO")
        )
        assertFalse(disagree.shouldPlace)
        val agree = AutopilotDispatch.decide(request(AutopilotMode.LIVE, armed = true))
        assertTrue(agree.shouldPlace)
        val missingKey = AutopilotDispatch.decide(request(AutopilotMode.LIVE, armed = true, credentials = false))
        assertFalse(missingKey.shouldPlace)
        assertTrue(missingKey.reason.contains("key"))
        val stopped = AutopilotDispatch.decide(request(AutopilotMode.LIVE, armed = true, failClosed = true))
        assertFalse(stopped.shouldPlace)
        assertTrue(stopped.reason.contains("retry") || stopped.reason.contains("stopped"))
        val session = LiveAutopilotSession()
        assertFalse(session.confirmRealMoney())
        assertFalse(session.armed)
        session.tapApprove()
        assertTrue(session.confirmRealMoney())
        assertTrue(session.armed)
        session.disarm()
        assertFalse(session.armed)
        assertFalse(session.approveTapped)
        assertEquals(AutopilotMode.PAPER, SignalSettings().autopilotModeEnum())
        assertEquals(50.0, SignalSettings().liveAutopilotDailyCapUsd, 1e-9)
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
        val missed = ShadowOrderPayload.draft(
            ticker = "KXBTC15M-MISS",
            side = "YES",
            ask = 0.20,
            depth = 0,
            reason = "thin book",
            nowMs = nowMs,
            clientOrderId = "miss",
            capUsd = 10.0
        )
        assertFalse(missed.depthFill)
        shadow.record(missed)
        shadow.settle("KXBTC15M-MISS", "yes")
        val row = shadow.snapshot().tickets.single()
        assertTrue(row.settled)
        assertNull(row.pnlUsd)
        assertNull(row.won)
        assertEquals(0.0, shadow.snapshot().lifetimeRealizedPnlUsd, 1e-9)
        val filled = ShadowOrderPayload.draft(
            ticker = "KXBTC15M-HIT",
            side = "YES",
            ask = 0.20,
            depth = 100_000,
            reason = "edge",
            nowMs = nowMs,
            clientOrderId = "hit",
            capUsd = 10.0,
            cashUsd = shadow.snapshot().cashUsd
        )
        assertTrue(filled.booked)
        assertTrue(filled.stakeUsd <= SignalConstants.LIVE_ALL_IN_CAP_USD + 1e-6)
        shadow.record(filled)
        shadow.settle("KXBTC15M-HIT", "yes")
        val won = shadow.snapshot().tickets.first { it.clientOrderId == "hit" }
        assertEquals(true, won.won)
        assertNotNull(won.pnlUsd)
        assertTrue(won.pnlUsd!! > 0.0)
    }

    private fun live(allIn: Double, spent: Double, cap: Double) = AutopilotDispatch.decide(
        request(AutopilotMode.LIVE, armed = true, allIn = allIn, spent = spent, cap = cap)
    )

    private fun request(
        mode: AutopilotMode,
        armed: Boolean,
        paperFilled: Boolean = true,
        paperSide: String? = "YES",
        paperPrice: Double? = 0.20,
        shadowSide: String? = "YES",
        shadowPrice: Double? = 0.20,
        depthFill: Boolean = true,
        allIn: Double = 8.0,
        spent: Double = 0.0,
        cap: Double = 50.0,
        attempted: Boolean = false,
        credentials: Boolean = true,
        failClosed: Boolean = false,
        decisionOk: Boolean = true
    ) = AutopilotDispatch.Request(
        mode = mode,
        masterOn = true,
        decisionOk = decisionOk,
        paperFilled = paperFilled,
        armed = armed,
        credentialsOk = credentials,
        failClosed = failClosed,
        paperSide = paperSide,
        paperPrice = paperPrice,
        shadowSide = shadowSide,
        shadowPrice = shadowPrice,
        shadowDepthFill = depthFill,
        shadowAllInUsd = allIn,
        spentTodayUsd = spent,
        dailyCapUsd = cap,
        alreadyAttempted = attempted
    )

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
