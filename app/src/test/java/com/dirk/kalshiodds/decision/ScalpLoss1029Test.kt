package com.dirk.kalshiodds.decision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.0.29 paper tape: green size walked past the variant clip, v2 dip notes
 * still bought under the floor, and the tuner loosened on a short sample.
 */
class ScalpLoss1029Test {
    private val tk = "KXBTC15M-26OCT122015-15"
    private val close = 1_791_850_500_000L

    @Test fun sizeAboveTheClipCannotFillAndGreenSizeStopsAtTheVariantClip() {
        val floor = ScalpFloors.cents("sf07-v2") / 100.0
        val blocked = ScalpParamSanity.entryBlock("NO", 0.33, 200, floor, 20_000.0, 10)
        assertNotNull(blocked)
        assertTrue(blocked!!.contains("clip 10"))
        assertNull(ScalpParamSanity.entryBlock("NO", 0.33, 10, floor, 20_000.0, 10))
        assertEquals(10, ScalpParamSanity.builtinClip("sf07-v2"))
        assertEquals(10, ScalpParamSanity.ownClip("sf07-v2", null))
        assertEquals(16, ScalpParamSanity.ownClip("sf07-v2", 16))
        val boosted = ScalpControls(greenContracts = 200, algo = mapOf("sf07-v2" to mapOf("contracts" to 200.0)))
        assertEquals(10, StrategyAlgo.clip(boosted, "sf07-v2", 10))
        val manual = ScalpControls(strategySettings = mapOf("sf07-v2" to ScalpStrategySettings(contracts = 16)))
        assertEquals(16, StrategyAlgo.clip(manual, "sf07-v2", 10))

        val store = InMemoryScalpPersistence()
        store.seed((1..20).map { i ->
            ScalpTrade(
                id = "c$i", ticker = tk, side = "NO", state = ScalpState.CLOSED,
                signalAtMs = i * 1_000L, signalAsk = 0.33, fairAtSignal = 0.40, contracts = 10, entryPrice = 0.33,
                closedAtMs = i * 1_000L + 50, netUsd = 1.0,
                ruleVersion = "${ScalpRule.VERSION}|sf07-v2|P"
            )
        })
        val controls = MemoryScalpControls(ScalpControls(greenContracts = 200))
        val book = ScalpBook(store, controls = controls, bankrollUsd = { 20_000.0 })
        assertTrue(book.isGreen("sf07-v2"))
        val signal = close - 400_000L
        book.importRestored(listOf(ScalpTrade(
            id = "pend", ticker = tk, side = "NO", state = ScalpState.PENDING_ENTRY,
            signalAtMs = signal, signalAsk = 0.33, fairAtSignal = 0.38, contracts = 10,
            note = "sf07-v2 Shock hold to close",
            ruleVersion = "${ScalpRule.VERSION}|sf07-v2|P"
        )))
        val quote = ScalpRule.Quote(
            tk, signal + 2_500L, close, signal + 2_500L,
            yesBid = 0.67, yesBidSize = 500.0, yesAsk = 0.68, yesAskSize = 500.0,
            spot = 81_531.91, strike = 81_484.70, sigmaPerSec = 3e-5
        )
        book.onShockFavQuote(quote, true)
        val filled = book.allTrades().first { it.id == "pend" }
        assertEquals(ScalpState.OPEN, filled.state)
        assertEquals(10, filled.contracts)
        assertTrue(filled.note.contains("green size"))
        assertTrue(filled.note.contains("capped at clip 10"))
        assertFalse(filled.note.contains("200/200"))
        assertTrue(book.gateSnapshot().any { it.code == "sf07-v2" && it.reason.contains("clip 10") })
    }

    @Test fun v2DipBandUsesTheFloorAndTheParentCeiling() {
        val ids = listOf(
            KeptScalpers.V2_DIP_ID,
            "v2-dip-g05-t08-s06-d10-w180-v1",
            "v2-dip-g05-t08-s06-d10-w180-v2",
            "v2-dip-g05-t08-s06-d10-w180-v3",
            KeptScalpers.V2_FAIR_ID,
            "v2-g10-t12-s08-d15-w300-mk-v1",
            "v2-g10-t12-s08-d15-w300-mk-v2",
            "v2-g10-t12-s08-d15-w300-mk-v3"
        )
        ids.forEach { code ->
            val saved = ScalpParamSanity.settings(code, ScalpStrategySettings(minEntryCents = 1, maxEntryCents = 12))
            assertEquals(code, 10, saved.minEntryCents)
            assertEquals(code, 55, saved.maxEntryCents)
            val band = ScalpParamSanity.v2Band(code, 0.01, 0.12)
            assertEquals(code, 0.10, band.first, 1e-9)
            assertEquals(code, 0.55, band.second, 1e-9)
            val kept = ScalpParamSanity.settings(code, ScalpStrategySettings(minEntryCents = 18, maxEntryCents = 40))
            assertEquals(code, 18, kept.minEntryCents)
            assertEquals(code, 40, kept.maxEntryCents)
        }
        val loaded = ScalpControls(strategySettings = mapOf(
            "v2-dip-g05-t08-s06-d10-w180-v3" to ScalpStrategySettings(minEntryCents = 1, maxEntryCents = 14)
        )).normalized()
        val row = loaded.settings("v2-dip-g05-t08-s06-d10-w180-v3")
        assertEquals(10, row.minEntryCents)
        assertEquals(55, row.maxEntryCents)
    }

    @Test fun v2DipFillRejectsAPennyAskAndCapsTheClip() {
        val code = "v2-dip-g05-t08-s06-d10-w180-v3"
        val book = ScalpBook(bankrollUsd = { 20_000.0 })
        val signal = close - 300_000L
        book.importRestored(listOf(ScalpTrade(
            id = "v2", ticker = tk, side = "YES", state = ScalpState.PENDING_ENTRY,
            signalAtMs = signal, signalAsk = 0.12, fairAtSignal = 0.20, contracts = 200,
            note = "v2 variant of v2-dip-g05-t08-s06-d10-w180 · v2ask=0.1200 · v2trail=0.0300 · v2clip=200 · v2peakrule=1 · v2stopPct=20 · v2min=0.01 · v2until=14 · v2sidecap=20 · v2cadence=0",
            ruleVersion = "${ScalpRule.VERSION}|$code|P"
        )))
        fun q(at: Long, ask: Double) = ScalpRule.Quote(
            tk, at, close, at, yesBid = ask - 0.01, yesBidSize = 500.0, yesAsk = ask, yesAskSize = 500.0,
            spot = 81_500.0, strike = 81_500.0, sigmaPerSec = 3e-5
        )
        book.onQuote(q(signal + 3_000L, 0.06), true)
        val skipped = book.allTrades().first { it.id == "v2" }
        assertEquals(ScalpState.PENDING_ENTRY, skipped.state)
        val gate = book.gateLine(code)
        assertNotNull(gate)
        assertTrue(gate!!.contains("floor"))
        book.onQuote(q(signal + 4_000L, 0.30), true)
        val filled = book.allTrades().first { it.id == "v2" }
        assertEquals(ScalpState.OPEN, filled.state)
        assertEquals(20, filled.contracts)
        assertEquals(0.30, filled.entryPrice!!, 1e-9)
    }

    @Test fun trailExitMustClearFeesAndTheStopStillFires() {
        val entry = 0.12
        val scratch = 0.11
        assertTrue(ScalpRule.netAfterFees(entry, scratch, 20) < 0.0)
        assertNull(ScalpV2.peakExit(entry, 20.0, 0.14, scratch, 0.03, 20))
        val banked = 0.20
        assertTrue(ScalpRule.netAfterFees(entry, banked, 20) > 0.0)
        assertTrue(ScalpV2.peakExit(entry, 20.0, 0.24, banked, 0.03, 20)!!.contains("trail"))
        assertTrue(ScalpV2.peakExit(entry, 20.0, 0.14, 0.09, 0.03, 20)!!.contains("stop"))
    }

    @Test fun tunerLoosensOnlyAProvenWinnerAndTightensALoser() {
        val code = KeptScalpers.V2_DIP_ID
        val shortWin = PaperTape(code, 9, 9, 9.0, 1.0, List(9) { 1.0 }, emptyList())
        assertTrue(PaperSettingTuner.propose(ScalpControls(), listOf(shortWin)).isEmpty())
        val winner = PaperTape(code, 12, 8, 4.0, 8.0 / 12.0, List(12) { 0.4 }, emptyList())
        val up = PaperSettingTuner.propose(ScalpControls(), listOf(winner))
        assertTrue(up.isNotEmpty())
        assertTrue(up.none { it.setting == "bench" || it.setting == "enabled" })
        val clipUp = up.first { it.setting == "contracts" }
        val clipBefore = clipUp.oldValue.toIntOrNull() ?: ScalpParamSanity.builtinClip(code)
        assertTrue(clipUp.newValue.toInt() > clipBefore)
        up.filter { it.setting == "minEntryCents" }.forEach {
            assertTrue(it.newValue.toInt() >= ScalpFloors.cents(code))
        }
        val blocked = PaperSettingTuner.propose(ScalpControls(), listOf(winner)) { true }
        assertTrue(blocked.isEmpty())

        val loserNets = List(7) { 0.20 } + List(3) { -1.0 }
        val loser = PaperTape(code, 10, 7, loserNets.sum(), 0.7, loserNets, emptyList())
        assertTrue(loser.netUsd < 0.0)
        val down = PaperSettingTuner.propose(ScalpControls(), listOf(loser))
        assertTrue(down.isNotEmpty())
        assertTrue(down.none { it.setting == "bench" || it.setting == "enabled" })
        val clipDown = down.first { it.setting == "contracts" }
        val clipWas = clipDown.oldValue.toIntOrNull() ?: ScalpParamSanity.builtinClip(code)
        assertTrue(clipDown.newValue.toInt() < clipWas)
        down.filter { it.setting == "maxEntryCents" && it.oldValue != "default" }.forEach {
            assertTrue(it.newValue.toInt() <= it.oldValue.toInt())
        }
        down.filter { it.setting == "minEntryCents" && it.oldValue != "default" }.forEach {
            assertTrue(it.newValue.toInt() >= it.oldValue.toInt())
            assertTrue(it.newValue.toInt() >= ScalpFloors.cents(code))
        }
    }

    @Test fun aiLearnsDoesNotLoosenOneWinningFill() {
        val code = KeptScalpers.V2_DIP_ID
        val start = ScalpStrategySettings(minEntryCents = 12, maxEntryCents = 40, contracts = 10)
        val one = Research.learn(code, start, 2.0, 55)
        assertEquals(10, one.contracts)
        assertEquals(40, one.maxEntryCents)
        assertEquals(12, one.minEntryCents)
        assertTrue(one.aiNote.contains("held"))
        val lost = Research.learn(code, start, -2.0, 55)
        assertTrue((lost.contracts ?: 10) < 10)
        assertTrue((lost.maxEntryCents ?: 99) <= 40)
        assertTrue(lost.minEntryCents >= ScalpFloors.cents(code))
        val collapsed = Research.learn(code, ScalpStrategySettings(minEntryCents = 1, maxEntryCents = 12, contracts = 20), -1.0, 55)
        assertTrue(collapsed.minEntryCents >= 10)
        assertTrue((collapsed.maxEntryCents ?: 0) >= ScalpFloors.minCeilingCents(code))
    }
}
