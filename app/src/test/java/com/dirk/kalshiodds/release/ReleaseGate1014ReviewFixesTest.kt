package com.dirk.kalshiodds.release

import com.dirk.kalshiodds.decision.KeptScalpers
import com.dirk.kalshiodds.decision.InMemoryScalpPersistence
import com.dirk.kalshiodds.decision.InMemoryScalpTuneStore
import com.dirk.kalshiodds.decision.ScalpBook
import com.dirk.kalshiodds.decision.ScalpModels
import com.dirk.kalshiodds.decision.ScalpParams
import com.dirk.kalshiodds.decision.ScalpRule
import com.dirk.kalshiodds.decision.ScalpState
import com.dirk.kalshiodds.decision.ScalpStrategy
import com.dirk.kalshiodds.decision.ScalpTicker
import com.dirk.kalshiodds.decision.ScalpTrade
import com.dirk.kalshiodds.decision.ScalpTuneState
import com.dirk.kalshiodds.decision.ScalpTuner
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.0.14 PR review fixes: BTC-only fair tweaks, frozen shadows reserve no bankroll, tweaks never become the tuner seed. */
class ReleaseGate1014ReviewFixesTest {
    private val btc = "KXBTC15M-26OCT091015-15"
    private val eth = "KXETH15M-26OCT091015-15"
    private val close = ScalpTicker.closeMs(btc)!!
    private val tweakIds = ScalpParams.FAIR_TWEAKS.map { it.id }.toSet()

    private fun q(ticker: String, nowMs: Long, yesBid: Double, yesAsk: Double) =
        ScalpRule.Quote(ticker, nowMs, close, nowMs, yesBid, 500.0, yesAsk, 500.0, 100_000.0, 100_000.0, 3e-5)

    private fun book(bankroll: Double = 1_000.0): ScalpBook {
        val ids = AtomicInteger()
        val pin = ScalpTuneState(paramsByCoin = mapOf("BTC" to ScalpParams.CLASSIC.id))
        return ScalpBook(InMemoryScalpPersistence(), emptyList(), InMemoryScalpTuneStore(pin), bankrollUsd = { bankroll }) { "r${ids.incrementAndGet()}" }
    }

    @Test fun fairTweaksTradeBtcOnly() {
        val b = book()
        b.onQuote(q(btc, close - 700_000L, 0.29, 0.30), true)
        assertTrue(b.allTrades().none { it.variantId in tweakIds })
        val e = book()
        e.onQuote(q(eth, close - 700_000L, 0.29, 0.30), true)
        assertTrue(e.allTrades().none { it.variantId in tweakIds || it.variantId == KeptScalpers.FAIR_ID })
    }

    @Test fun frozenShadowsDoNotReservePaperBankroll() {
        // $5 covers one clip. With Fair-gap and Fair-gap hold frozen, their shadows must not use it up: fair-t1 still trades.
        val b = book(bankroll = 5.0)
        b.benched = setOf(ScalpModels.Model.FAIR_GAP.code, ScalpModels.Model.FAIR_HOLD.code)
        b.onQuote(q(btc, close - 600_000L, 0.34, 0.35), true)
        assertTrue(b.snapshot().none { it.variantId in tweakIds || ScalpModels.modelOf(it) == ScalpModels.Model.FAIR_GAP })
    }

    @Test fun tunerNeverAdoptsATweakAsTheSeed() {
        // A tweak that crushes the seed in every window is still not a tuner candidate.
        val trades = (0 until 20).flatMap { w ->
            val tk = "KXBTC15M-26OCT0${1 + w / 4}${10 + (w % 4)}15-15"
            val at = (ScalpTicker.closeMs(tk) ?: (close + w * 900_000L)) - 600_000L
            fun tr(vid: String, net: Double) = ScalpTrade("$vid-$w", tk, "YES", ScalpState.CLOSED, at, 0.35, 0.50,
                entryPrice = 0.35, contracts = 10, netUsd = net, closedAtMs = at + 60_000L, ruleVersion = "${ScalpRule.VERSION}|$vid|P")
            (0 until 3).flatMap { k -> listOf(tr(ScalpParams.FAIR_TWEAKS[0].id, 5.0).copy(id = "a$w-$k"), tr(ScalpParams.CLASSIC.id, -1.0).copy(id = "b$w-$k")) }
        }
        val st = ScalpTuner.tune(trades.filter { it.strategy == ScalpStrategy.FAIR_GAP }, ScalpTuneState(paramsByCoin = mapOf("BTC" to ScalpParams.CLASSIC.id)), 0L)
        assertFalse(st.paramsByCoin.values.any { it in tweakIds })
        // Harness check: a real grid variant with the same record is adopted, so the tweak was excluded on purpose.
        val grid = ScalpParams.GRID.first { it.id != ScalpParams.CLASSIC.id }
        val gridTrades = trades.map { if (it.variantId in tweakIds) it.copy(ruleVersion = "${ScalpRule.VERSION}|${grid.id}|P") else it }
        assertEquals(grid.id, ScalpTuner.tune(gridTrades, ScalpTuneState(paramsByCoin = mapOf("BTC" to ScalpParams.CLASSIC.id)), 0L).paramsByCoin["BTC"])
    }
}
