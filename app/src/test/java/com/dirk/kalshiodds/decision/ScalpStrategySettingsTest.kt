package com.dirk.kalshiodds.decision

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ScalpStrategySettingsTest {
    private val tk = "KXBTC15M-26OCT122015-15"
    private val close = ScalpTicker.closeMs(tk)!!
    private val at = close - 300_000
    private fun source(id: String = "source", ms: Long = at) = ScalpTrade(id, tk, "YES", ScalpState.PENDING_ENTRY, ms, .4, .5, ruleVersion = "v|sf41|P")
    private fun q(ms: Long, bid: Double = .39, ask: Double = .4) = ScalpRule.Quote(tk, ms, close, ms, bid, 100.0, ask, 100.0, 100_000.0, 100_000.0, 3e-5)
    private fun cfg(knobs: ScalpStrategySettings) = MemoryScalpControls(ScalpControls(strategySettings = mapOf("v2-sf41" to knobs),
        disabledStrategies = ScalpModels.V2_MODELS.map { it.code }.filter { it != "v2-sf41" }.toSet()))
    @Test fun perStrategySizingAndRiskAreFrozenAndKeepExitsAfterDisable() {
        val controls = cfg(ScalpStrategySettings(contracts = 3, stopLossPercent = 10, trailCents = 5))
        val book = ScalpBook(variants = emptyList(), liveExtras = true, controls = controls)
        book.importRestored(listOf(source()))
        book.onQuote(q(at + 2000), false)
        val held = book.snapshot().single { it.variantId == "v2-sf41" }
        assertEquals(3, held.contracts)
        assertEquals(.04, ScalpV2.savedStop(held), 1e-9)
        assertEquals(.05, ScalpV2.savedNumber(held.note, "v2trail", 0.0), 1e-9)
        controls.save(controls.state.value.copy(enabled = false, strategySettings = mapOf("v2-sf41" to ScalpStrategySettings(stopLossPercent = 80, trailCents = 20))))
        book.onQuote(q(at + 3000, bid = .36), false)
        assertEquals(ScalpState.PENDING_EXIT, book.snapshot().first { it.id == held.id }.state)
        assertTrue(book.snapshot().first { it.id == held.id }.exitReason!!.contains("10%"))
    }
    @Test fun rangesAndPerModelPlansCannotExceedFiftyOrDisableStops() {
        val knobs = ScalpStrategySettings(maxEntryCents = 90, minEntryCents = 80, stopLossPercent = 0, trailCents = 0, contracts = 0, untilMinute = 15).normalized()
        assertEquals(50, knobs.maxEntryCents); assertEquals(50, knobs.minEntryCents)
        assertEquals(1, knobs.stopLossPercent); assertEquals(1, knobs.trailCents); assertEquals(14, knobs.untilMinute)
        val cfg = ScalpControls(strategySettings = mapOf("v2-sf41" to ScalpStrategySettings(contracts = 3, maxEntryCents = 42)))
        val base = ScalpV2.plan("v2-sf41", emptyList(), at)
        assertEquals(3, cfg.plan(base, "v2-sf41").spec.clip)
        assertEquals(.42, cfg.plan(base, "v2-sf41").spec.maxAsk, 1e-9)
        assertEquals(base.spec.clip, cfg.plan(base, "v2-sf42").spec.clip)
    }
    @Test fun minimumPriceAndEntryCheckCadenceAreExecuted() {
        val controls = cfg(ScalpStrategySettings(minEntryCents = 35, maxEntryCents = 45, cadenceSeconds = 3))
        val book = ScalpBook(variants = emptyList(), liveExtras = true, controls = controls)
        book.importRestored(listOf(source()))
        book.onQuote(q(at + 2000, .29, .30), false)
        assertEquals(ScalpState.PENDING_ENTRY, book.snapshot().first { it.variantId == "v2-sf41" }.state)
        book.onQuote(q(at + 3000), false)
        assertEquals(ScalpState.PENDING_ENTRY, book.snapshot().first { it.variantId == "v2-sf41" }.state)
        book.onQuote(q(at + 5000), false)
        assertEquals(ScalpState.OPEN, book.snapshot().first { it.variantId == "v2-sf41" }.state)
    }
    @Test fun untilMinuteBlocksNewOrdersButDoesNotDisableExits() {
        val controls = cfg(ScalpStrategySettings(untilMinute = 9))
        val book = ScalpBook(variants = emptyList(), liveExtras = true, controls = controls)
        book.importRestored(listOf(source())) // signal is in minute ten
        assertTrue(book.snapshot().none { it.variantId == "v2-sf41" })
        assertTrue(ScalpStrategySettings(untilMinute = 10).timeOk(close - 900_000, close))
        assertFalse(ScalpStrategySettings(untilMinute = 10).timeOk(close - 300_000, close))
    }
    @Test fun learnedCeilingCannotInvertTheUsersPriceRange() {
        val base = ScalpV2.plan("v2-sf41", emptyList(), at)
        val learned = base.copy(spec = base.spec.copy(maxAsk = .48))
        val plan = ScalpStrategySettings(minEntryCents = 50).plan(learned)
        assertEquals(.50, plan.spec.maxAsk, 1e-9)
        assertFalse(ScalpV2.entryOk(.51, plan.spec.maxAsk))
    }
    @Test fun bothLegsOfOneModelShareItsPerSideCapacity() {
        for (cap in listOf(1, 2)) {
            val controls = cfg(ScalpStrategySettings())
            val book = ScalpBook(variants = emptyList(), liveExtras = true, controls = controls)
            val pending = listOf("v2-sf20", "v2-sf20f").mapIndexed { i, code ->
                source("pending-$i").copy(ruleVersion = "v|$code|P", note = "v2sidecap=$cap · v2peakrule=1 · v2stopPct=20 · v2trail=.03")
            }
            assertTrue(pending.all { ScalpModels.modelOf(it) == ScalpModels.Model.V2_SF20 })
            controls.save(controls.state.value.copy(disabledStrategies = emptySet()))
            book.importRestored(pending)
            book.onQuote(q(at + 2000), false)
            assertEquals(cap, book.snapshot().count { it.state == ScalpState.OPEN })
        }
    }
    @Test fun settingsRoundTripAndOldSettingsKeepDefaults() {
        val json = Json { ignoreUnknownKeys = true }
        val settings = ScalpControls(strategySettings = mapOf("v2-sf41" to ScalpStrategySettings(contracts = 7, maxEntryCents = 45, untilMinute = 8)))
        assertEquals(settings, json.decodeFromString<ScalpControls>(json.encodeToString(settings)))
        assertTrue(json.decodeFromString<ScalpControls>("{\"contracts\":4}").strategySettings.isEmpty())
    }
}
