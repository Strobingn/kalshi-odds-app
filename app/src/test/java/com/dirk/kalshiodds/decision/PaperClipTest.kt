package com.dirk.kalshiodds.decision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Paper entries book at least [PaperClip.MIN]. A 1-lot touch and a saved size of 1 do not. */
class PaperClipTest {
    @Test fun sizedKeepsZeroAndLiftsAnythingPositive() {
        assertEquals(0, PaperClip.sized(0))
        assertEquals(0, PaperClip.sized(-3))
        assertEquals(PaperClip.MIN, PaperClip.sized(1))
        assertEquals(80, PaperClip.sized(80))
    }

    @Test fun takerDoesNotShrinkToAOneLotTouch() {
        assertEquals(PaperClip.MIN, PaperClip.taker(10, 1.0))
        assertEquals(80, PaperClip.taker(80, 1.0))
        assertEquals(0, PaperClip.taker(10, 0.0))
        assertEquals(0, PaperClip.taker(0, 40.0))
        assertEquals(0, PaperClip.taker(10, Double.NaN))
    }

    @Test fun ownClipAndAlgoCannotCollapseUnderTheFloor() {
        assertEquals(PaperClip.MIN, ScalpParamSanity.ownClip("sf07-v2", null))
        assertEquals(PaperClip.MIN, ScalpParamSanity.ownClip("sf42-v1", 1))
        assertEquals(PaperClip.MIN, ScalpParamSanity.ownClip("sf42-v1", 4))
        assertEquals(80, ScalpParamSanity.ownClip("sf42-v1", 80))
        val algoOne = ScalpControls(algo = mapOf("sf07-v2" to mapOf("contracts" to 1.0)))
        assertTrue(StrategyAlgo.clip(algoOne, "sf07-v2", 1) >= PaperClip.MIN)
        val explicit = ScalpControls(strategySettings = mapOf("sf42-v1" to ScalpStrategySettings(contracts = 80)))
        assertEquals(80, StrategyAlgo.clip(explicit, "sf42-v1", 5))
    }

    @Test fun learnerAndTunerStayAtTheFloor() {
        val code = KeptScalpers.V2_DIP_ID
        val lost = Research.learn(code, ScalpStrategySettings(contracts = 10), -2.0, 55)
        assertTrue((lost.contracts ?: 0) >= PaperClip.MIN)
        val shrunk = Research.learn(code, ScalpStrategySettings(contracts = 80), -2.0, 55)
        assertTrue(shrunk.contracts!! >= PaperClip.MIN)
        assertTrue(shrunk.contracts!! < 80)

        val loserNets = List(7) { 0.20 } + List(3) { -1.0 }
        val tape = PaperTape(code, 10, 7, loserNets.sum(), 0.7, loserNets, emptyList())
        val atFloor = PaperSettingTuner.propose(ScalpControls(), listOf(tape))
        assertTrue(atFloor.none { it.setting == "contracts" && it.newValue.toInt() < PaperClip.MIN })
        val high = ScalpControls(strategySettings = mapOf(code to ScalpStrategySettings(contracts = 80)))
        val down = PaperSettingTuner.propose(high, listOf(tape))
        val clip = down.first { it.setting == "contracts" }
        assertTrue(clip.newValue.toInt() >= PaperClip.MIN)
        assertTrue(clip.newValue.toInt() < clip.oldValue.toInt())
    }

    @Test fun modelTwinAdmissionUsesTheFlooredClip() {
        val twin = ModelFilter.twin("sf41-logistic")!!
        val clip = maxOf(twin.clip, PaperClip.MIN)
        val n = PaperClip.sized(ModelFilter.sized(clip, 1.0, clip))
        assertTrue(n >= PaperClip.MIN)
        val floor = ScalpFloors.cents(twin.parentId) / 100.0
        assertNull(ScalpParamSanity.entryBlock("YES", 0.40, n, floor, 20_000.0, clip))
        val staleCap = ScalpParamSanity.entryBlock("YES", 0.40, n, floor, 20_000.0, twin.clip)
        assertNotNull(staleCap)
        assertTrue(staleCap!!.contains("above the clip ${twin.clip}"))

        val ticker = "KXBTC15M-26OCT251315-15"
        val close = ScalpTicker.closeMs(ticker)!!
        val start = close - 600_000L
        val book = ScalpBook(liveExtras = true, bankrollUsd = { 100_000.0 })
        var t = start
        while (t < start + 250_000L) {
            book.onShockFavQuote(quote(ticker, close, t, yesBid = 0.49, yesAsk = 0.50), true)
            t += 1_000L
        }
        while (t <= start + 256_000L) {
            book.onShockFavQuote(quote(ticker, close, t, yesBid = 0.39, yesAsk = 0.40), true)
            t += 1_000L
        }
        val twins = book.snapshot().filter { ModelFilter.isTwin(it.variantId) && it.state != ScalpState.NO_FILL }
        val why = book.gateSnapshot().joinToString(" | ") { "${it.code}: ${it.reason}" }
        assertTrue(why, twins.isNotEmpty())
        assertTrue(twins.joinToString { "${it.variantId}=${it.contracts}" }, twins.all { it.contracts >= PaperClip.MIN })
        assertTrue(why, book.gateSnapshot().none { it.reason.contains("above the clip") })
    }

    @Test fun expandedWalkSkipsWhenTheFullClipExceedsStake() {
        val tk = "KXBTC15M-26OCT122015-15"
        val close = ScalpTicker.closeMs(tk)!!
        val store = InMemoryScalpPersistence()
        store.seed((1..20).map { i ->
            ScalpTrade(
                id = "c$i", ticker = tk, side = "NO", state = ScalpState.CLOSED,
                signalAtMs = i * 1_000L, signalAsk = 0.33, fairAtSignal = 0.40, contracts = 10, entryPrice = 0.33,
                closedAtMs = i * 1_000L + 50, netUsd = 1.0,
                ruleVersion = "${ScalpRule.VERSION}|sf07-v2|P"
            )
        })
        val controls = MemoryScalpControls(ScalpControls(maxStakeUsd = 3.0, greenContracts = PaperClip.MIN))
        val book = ScalpBook(store, controls = controls, bankrollUsd = { 20_000.0 })
        val signal = close - 400_000L
        book.importRestored(listOf(ScalpTrade(
            id = "pend", ticker = tk, side = "NO", state = ScalpState.PENDING_ENTRY,
            signalAtMs = signal, signalAsk = 0.33, fairAtSignal = 0.38, contracts = PaperClip.MIN,
            note = "sf07-v2 Shock hold to close",
            ruleVersion = "${ScalpRule.VERSION}|sf07-v2|P"
        )))
        val q = ScalpRule.Quote(
            tk, signal + 2_500L, close, signal + 2_500L,
            yesBid = 0.67, yesBidSize = 4.0, yesAsk = 0.68, yesAskSize = 4.0,
            spot = 81_531.91, strike = 81_484.70, sigmaPerSec = 3e-5
        )
        book.onShockFavQuote(q, true)
        val row = book.allTrades().first { it.id == "pend" }
        assertEquals(row.note, ScalpState.PENDING_ENTRY, row.state)
    }

    private fun quote(ticker: String, close: Long, now: Long, yesBid: Double, yesAsk: Double) = ScalpRule.Quote(
        ticker = ticker, nowMs = now, closeMs = close, bookAtMs = now,
        yesBid = yesBid, yesBidSize = 100.0, yesAsk = yesAsk, yesAskSize = 100.0,
        spot = 100_000.0, strike = 100_000.0, sigmaPerSec = 3e-5
    )
}
