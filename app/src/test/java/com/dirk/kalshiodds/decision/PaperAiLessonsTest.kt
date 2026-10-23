package com.dirk.kalshiodds.decision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.0.37 paper lessons from the 22:30 BTC window.
 * DOWN at 69–78¢ can enter when spot is below the strike. A crashing UP dip does not.
 * The 55¢ constants stay put.
 */
class PaperAiLessonsTest {
    private val ticker = "KXBTC15M-26OCT222330-30"

    private fun downQuote(askNo: Double = 0.72, spot: Double = 99_950.0, tauS: Double = 600.0, fairSigma: Double = 1.2e-5): ScalpRule.Quote {
        val now = 1_700_000_000_000L
        val yesBid = 1.0 - askNo
        return ScalpRule.Quote(
            ticker = ticker,
            nowMs = now,
            closeMs = now + (tauS * 1000.0).toLong(),
            bookAtMs = now,
            yesBid = yesBid,
            yesBidSize = 80.0,
            yesAsk = yesBid + 0.01,
            yesAskSize = 80.0,
            spot = spot,
            strike = 100_000.0,
            sigmaPerSec = fairSigma
        )
    }

    @Test fun ceilingConstantsStayAtFiftyFive() {
        assertEquals(0.55, ScalpRule.ENTRY_HI, 1e-9)
        assertEquals(0.55, ScalpV2.MAX_ASK, 1e-9)
        assertTrue(ScalpV2.entryOk(0.55))
        assertFalse(ScalpV2.entryOk(0.56))
        assertFalse(ScalpRule.buyPriceOk(0.72))
    }

    @Test fun downMomentumAboveFiftyFiveIsAllowed() {
        val q = downQuote()
        val fairNo = q.fair("NO")!!
        assertTrue(fairNo >= 0.82)
        assertTrue(FairGapTune.lessonAllowed(q, "NO", 0.72, fairNo, 10, moveBp = -40.0))
        assertTrue(FairGapTune.askAllowed("sf04", 0.72, false, q, "NO", fairNo, 10, -40.0))
        assertFalse(FairGapTune.lessonAllowed(q, "YES", q.yesAsk!!, q.fair("YES")!!, 10, moveBp = -40.0))
        val (sig, why) = ScalpRule.entrySignal(q, ScalpParams.CLASSIC.copy(minGap = 0.04, tauMinS = 60.0, tauMaxS = 870.0), listOf("YES", "NO"))
        assertEquals(why, "NO", sig?.side)
    }

    @Test fun stackingUnderOneHundredFiftyIsBlocked() {
        val q = downQuote(askNo = 0.70)
        val open = ScalpTrade(
            id = "open", ticker = ticker, side = "NO", state = ScalpState.OPEN,
            signalAtMs = q.nowMs - 5_000L, signalAsk = 0.70, fairAtSignal = 0.90,
            contracts = 10, entryPrice = 0.70, entryAtMs = q.nowMs - 4_000L,
            ruleVersion = "${ScalpRule.VERSION}|sf04|P"
        )
        assertTrue(FairGapTune.stackingBlocked("sf04", q, "NO", 0.74, listOf(open)))
        assertFalse(FairGapTune.lessonAllowed(q, "NO", 0.74, 0.90, 10, moveBp = -20.0, variantId = "sf04", open = listOf(open)))
        assertEquals(FairGapTune.STACK_GUARD, "lesson guard: no expensive same-side stacking near strike")
    }

    @Test fun lateExpensiveStackingIsBlockedAcrossDifferentStrategies() {
        val q = downQuote(askNo = 0.81, spot = 99_960.0, tauS = 240.0)
        val open = ScalpTrade(
            id = "late", ticker = ticker, side = "NO", state = ScalpState.OPEN,
            signalAtMs = q.nowMs - 5_000L, signalAsk = 0.81, fairAtSignal = 0.91,
            contracts = 10, entryPrice = 0.81, entryAtMs = q.nowMs - 4_000L,
            ruleVersion = "${ScalpRule.VERSION}|sf04|P"
        )
        assertTrue(FairGapTune.lessonAllowed(q, "NO", 0.81, 0.94, 10, variantId = "sf07"))
        assertTrue(FairGapTune.stackingBlocked("sf07", q, "NO", 0.81, listOf(open)))
        assertFalse(FairGapTune.lessonAllowed(q, "NO", 0.81, 0.94, 10, variantId = "sf07", open = listOf(open)))
        assertFalse(FairGapTune.stackingBlocked("sf07", q.copy(spot = 99_800.0), "NO", 0.81, listOf(open)))
        assertFalse(FairGapTune.stackingBlocked("sf07", q, "YES", 0.81, listOf(open)))
        assertFalse(FairGapTune.stackingBlocked("sf07", q, "NO", 0.79, listOf(open)))
        assertFalse(FairGapTune.stackingBlocked("sf07", q, "NO", 0.81, listOf(open.copy(state = ScalpState.CLOSED))))
    }

    @Test fun hftAlsoSeesTheExistingKeptStrategyExposure() {
        val q = downQuote(askNo = 0.81, spot = 99_960.0, tauS = 40.0, fairSigma = 1e-6)
        val first = HftBook().onQuote(q, 20_000.0) { "first" }
        assertTrue(first.toString(), first.any { it.variantId == "ts-arb" && it.side == "NO" })
        val existing = ScalpTrade(
            id = "kept", ticker = ticker, side = "NO", state = ScalpState.OPEN,
            signalAtMs = q.nowMs - 5_000L, signalAsk = 0.81, fairAtSignal = 0.91,
            contracts = 10, entryPrice = 0.81, entryAtMs = q.nowMs - 4_000L,
            ruleVersion = "${ScalpRule.VERSION}|sf04|P"
        )
        val blocked = HftBook().onQuote(q, 20_000.0, listOf(existing)) { "blocked" }
        assertTrue(blocked.toString(), blocked.none { it.side == "NO" && it.state == ScalpState.OPEN })
    }

    @Test fun overwhelmingBufferAndLateDiscount() {
        val big = downQuote(askNo = 0.83, spot = 99_800.0, tauS = 200.0)
        assertTrue(FairGapTune.lessonAllowed(big, "NO", 0.83, 0.97, 10))
        val late = downQuote(askNo = 0.70, spot = 99_980.0, tauS = 60.0)
        assertTrue(FairGapTune.signedBuffer("NO", 99_980.0, 100_000.0) >= 13.0)
        assertTrue(FairGapTune.lessonAllowed(late, "NO", 0.70, 0.84, 10, moveBp = 5.0))
    }

    @Test fun fallingKnifeBlocksTheUpDip() {
        val q = downQuote(askNo = 0.78, spot = 99_950.0)
        val yesAsk = q.yesAsk!!
        val yesFair = q.fair("YES")!!
        assertTrue(yesAsk < 0.55)
        assertTrue(FairGapTune.fallingKnife("YES", q.spot, q.strike, yesFair, yesAsk))
        assertFalse(FairGapTune.fallingKnife("NO", q.spot, q.strike, q.fair("NO"), q.ask("NO")!!))
        val book = ScalpBook(liveExtras = true, bankrollUsd = { 20_000.0 })
        val start = q.nowMs - 300_000L
        for (i in 0..40) {
            val at = start + i * 8_000L
            book.onShockFavQuote(q.copy(nowMs = at, bookAtMs = at, closeMs = q.closeMs, spot = 100_200.0, yesBid = 0.48, yesAsk = 0.50), false)
        }
        val dip = q.copy(nowMs = q.nowMs, bookAtMs = q.nowMs)
        book.onShockFavQuote(dip, true)
        assertTrue(book.snapshot().none { it.variantId == "sf41" && it.side == "YES" && it.state != ScalpState.NO_FILL })
        assertTrue(book.snapshot().none { it.variantId == "sf42" && it.side == "YES" && it.state != ScalpState.NO_FILL })
    }

    @Test fun downShockFillsAboveTheCeiling() {
        val book = ScalpBook(liveExtras = true, bankrollUsd = { 20_000.0 })
        val tau = 600.0
        val close = 1_700_000_600_000L
        val now = close - (tau * 1000.0).toLong()
        book.tickSpot("BTC", 100_000.0, now - 5_000L)
        book.tickSpot("BTC", 99_900.0, now)
        val q = ScalpRule.Quote(
            ticker, now, close, now,
            yesBid = 0.27, yesBidSize = 100.0, yesAsk = 0.28, yesAskSize = 100.0,
            spot = 99_900.0, strike = 100_000.0, sigmaPerSec = 2.654e-5
        )
        assertTrue(q.fair("NO")!! >= 0.82)
        book.onShockFavQuote(q, true)
        val pending = book.snapshot().filter { it.side == "NO" && (it.state == ScalpState.PENDING_ENTRY || it.state == ScalpState.OPEN) }
        assertTrue(pending.toString(), pending.isNotEmpty())
        val later = q.copy(nowMs = now + 3_000L, bookAtMs = now + 3_000L)
        book.tickSpot("BTC", 99_950.0, later.nowMs)
        book.onShockFavQuote(later, false)
        val filled = book.snapshot().filter { it.side == "NO" && it.state == ScalpState.OPEN }
        assertTrue(filled.toString(), filled.isNotEmpty())
        assertTrue(filled.first().entryPrice!! in 0.69..0.78)
        assertNull(filled.first().note.let { if (it.contains("55¢")) it else null })
    }
}
