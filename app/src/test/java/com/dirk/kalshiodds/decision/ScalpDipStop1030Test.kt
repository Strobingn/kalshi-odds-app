package com.dirk.kalshiodds.decision

import com.dirk.kalshiodds.data.supabase.SupabaseSync
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.0.30: v2 dips were stopped 3–4¢ under a 14–20¢ fill, and sf42-v1 filled 8 against clip 5.
 */
class ScalpDipStop1030Test {
    private val tk = "KXBTC15M-26OCT122015-15"
    private val close = 1_791_850_500_000L
    private val dip = "v2-dip-g05-t08-s06-d10-w180-v3"

    @Test fun percentStopIsInsideTheNoiseAndTheAbsoluteStopIsNot() {
        val entry = 0.18
        val n = 10
        val percent = ScalpV2.percentStopPrice(entry, 20.0)
        assertTrue(entry - percent < 0.06)
        val cents = ScalpV2.dipStopCents(dip)
        assertTrue(cents >= ScalpV2.MIN_DIP_STOP_CENTS)
        val dist = ScalpV2.dipStopDistance(entry, n, cents)
        val noise = 0.01 + ScalpRule.feePerContract(n, entry) * 2
        assertTrue(dist + 1e-9 >= cents / 100.0)
        assertTrue(dist + 1e-9 >= noise)
        // A 14¢ bid is 4¢ under an 18¢ fill: the 20% stop, not the 6¢ stop.
        assertTrue(0.14 <= percent)
        assertNull(ScalpV2.dipExit(entry, 0.14, 0.17, ScalpV2.TRAIL, dist, n))
        assertTrue(ScalpV2.dipExit(entry, entry - dist, 0.17, ScalpV2.TRAIL, dist, n)!!.contains("¢ below fill"))
        assertFalse(ScalpV2.dipExit(entry, entry - dist, 0.17, ScalpV2.TRAIL, dist, n)!!.contains("20%"))
    }

    @Test fun aRealisticDipPathIsNotStructurallyNegativeAfterFees() {
        val entry = ScalpV2.DIP_EDGE_ASK
        val n = 10
        val target = ScalpParams.byId(KeptScalpers.V2_DIP_BASE)!!.target
        val gap = ScalpV2.feeClearingDipGap(entry, n)
        assertTrue(gap > 0.05)
        assertFalse(ScalpV2.dipMoveClearsFees(0.02, entry, n))
        assertTrue(ScalpV2.dipMoveClearsFees(gap, entry, n))
        val noise = listOf(0.17, 0.15, 0.14, 0.15, 0.16)
        val held = ScalpV2.replayDip(entry, noise, n, ScalpV2.dipStopCents(KeptScalpers.V2_DIP_ID), target)
        assertNull(held.second)
        val path = noise + listOf(entry + target)
        val (exit, reason) = ScalpV2.replayDip(entry, path, n, ScalpV2.dipStopCents(KeptScalpers.V2_DIP_ID), target)
        assertNotNull(reason)
        assertTrue(reason!!.contains("target") || reason.contains("trail"))
        val net = (exit - entry) * n - ScalpRule.orderFee(n, entry) - ScalpRule.orderFee(n, exit)
        assertTrue("net $net after a ${gap * 100}¢ minimum dip and a ${target * 100}¢ target", net > 0.0)
        val half = entry + gap / 2.0
        assertTrue(ScalpRule.netAfterFees(entry, half, n) > 0.0)
        val stopped = ScalpV2.percentStopPrice(entry, 20.0)
        assertTrue(noise.any { it <= stopped })
    }

    @Test fun anOpenV2DipDoesNotStopOnAFourCentFlicker() {
        val at = close - 300_000L
        val book = ScalpBook(bankrollUsd = { 20_000.0 })
        book.importRestored(listOf(ScalpTrade(
            id = "dip", ticker = tk, side = "YES", state = ScalpState.OPEN,
            signalAtMs = at, signalAsk = 0.18, fairAtSignal = 0.25, contracts = 10,
            entryPrice = 0.18, entryAtMs = at, entryFeeUsd = ScalpRule.orderFee(10, 0.18),
            note = "v2 variant · v2peakrule=1 · v2stopPct=20 · v2trail=0.0300 · v2ask=0.5500 · v2min=0.1000",
            ruleVersion = "${ScalpRule.VERSION}|$dip|P"
        )))
        fun q(ms: Long, bid: Double) = ScalpRule.Quote(
            tk, ms, close, ms, yesBid = bid, yesBidSize = 40.0, yesAsk = bid + 0.01, yesAskSize = 40.0,
            spot = 81_500.0, strike = 81_500.0, sigmaPerSec = 3e-5
        )
        book.onQuote(q(at + 1_000L, 0.14), true)
        val held = book.allTrades().first { it.id == "dip" }
        assertEquals(held.note, ScalpState.OPEN, held.state)
        book.onQuote(q(at + 2_000L, 0.11), true)
        val stopped = book.allTrades().first { it.id == "dip" }
        assertEquals(ScalpState.PENDING_EXIT, stopped.state)
        assertNotNull(stopped.exitReason)
        assertTrue(stopped.exitReason!!.contains("¢ below fill"))
        assertFalse(stopped.exitReason!!.contains("20%"))
    }

    @Test fun sf42v1CannotFillMoreThanItsClip() {
        val code = "sf42-v1"
        assertEquals(5, ScalpVariants.clipOf(code))
        val store = InMemoryScalpPersistence()
        val signal = close - 400_000L
        store.seed(listOf(ScalpTrade(
            id = "wide", ticker = tk, side = "YES", state = ScalpState.PENDING_ENTRY,
            signalAtMs = signal, signalAsk = 0.30, fairAtSignal = 0.40, contracts = 8,
            note = "sf42-v1 seeded above its clip",
            ruleVersion = "${ScalpRule.VERSION}|$code|P"
        )))
        val controls = MemoryScalpControls(ScalpControls(
            strategySettings = mapOf(code to ScalpStrategySettings(contracts = 8))
        ))
        val book = ScalpBook(store, controls = controls, bankrollUsd = { 20_000.0 })
        fun q(ms: Long, size: Double) = ScalpRule.Quote(
            tk, ms, close, ms, yesBid = 0.29, yesBidSize = size, yesAsk = 0.30, yesAskSize = size,
            spot = 81_500.0, strike = 81_480.0, sigmaPerSec = 3e-5
        )
        book.onShockFavQuote(q(signal + 2_500L, 20.0), true)
        val filled = book.allTrades().first { it.id == "wide" }
        assertEquals(filled.note, ScalpState.OPEN, filled.state)
        assertEquals(5, filled.contracts)
        book.onShockFavQuote(q(signal + 4_000L, 20.0), true)
        assertEquals(5, book.allTrades().first { it.id == "wide" }.contracts)
    }

    @Test fun everyScalpPayloadNamesItsClip() {
        val parent = ScalpTrade(
            id = "p", ticker = tk, side = "YES", state = ScalpState.CLOSED,
            signalAtMs = 1L, signalAsk = 0.18, fairAtSignal = 0.25, contracts = 10,
            entryPrice = 0.18, entryAtMs = 1L, closedAtMs = 2L, netUsd = -0.4,
            ruleVersion = "${ScalpRule.VERSION}|${KeptScalpers.V2_DIP_ID}|P"
        )
        assertFalse(parent.algoLabel.contains("clip"))
        val variant = parent.copy(
            id = "v",
            ruleVersion = "${ScalpRule.VERSION}|sf42-v1|P"
        )
        assertTrue(variant.algoLabel.contains("clip"))
        val packed = SupabaseSync(OkHttpClient()).pack(SupabaseSync.LocalBundle(scalps = listOf(parent, variant)))
        val rows = packed.filter { it.kind == "scalp" }.map { JSONObject(it.payload) }
        assertEquals(2, rows.size)
        rows.forEach { row ->
            assertTrue(row.has("clip"))
            assertTrue(row.getInt("clip") >= 1)
            assertTrue(row.getString("algo").contains("clip ${row.getInt("clip")}"))
        }
    }
}
