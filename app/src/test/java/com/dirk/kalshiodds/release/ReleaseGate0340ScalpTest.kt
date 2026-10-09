package com.dirk.kalshiodds.release

import com.dirk.kalshiodds.decision.InMemoryScalpPersistence
import com.dirk.kalshiodds.decision.InMemoryScalpTuneStore
import com.dirk.kalshiodds.decision.ScalpBook
import com.dirk.kalshiodds.decision.ScalpBreakdown
import com.dirk.kalshiodds.decision.ScalpParams
import com.dirk.kalshiodds.decision.ScalpRule
import com.dirk.kalshiodds.decision.ScalpState
import com.dirk.kalshiodds.decision.ScalpTicker
import com.dirk.kalshiodds.decision.ScalpTrade
import com.dirk.kalshiodds.decision.ScalpTuneState
import com.dirk.kalshiodds.decision.ScalpTuner
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.3.40 scalp: multi round trips, explicit target/stop, 60 s hard exit, cost-aware entry, tuner, scorecard. */
class ReleaseGate0340ScalpTest {
    private val ticker = "KXBTC15M-26OCT091015-15"
    private val close = ScalpTicker.closeMs(ticker)!!

    private fun q(nowMs: Long, yesBid: Double, yesAsk: Double, bidSize: Double = 500.0, askSize: Double = 500.0, spot: Double = 100_000.0) =
        ScalpRule.Quote(ticker, nowMs, close, nowMs, yesBid, bidSize, yesAsk, askSize, spot, 100_000.0, 3e-5)

    /** Primary-only book (no shadow variants) so the assertions are about one param set. */
    private fun book(variants: List<ScalpParams> = emptyList()): ScalpBook {
        val ids = AtomicInteger()
        return ScalpBook(InMemoryScalpPersistence(), variants, InMemoryScalpTuneStore()) { "s${ids.incrementAndGet()}" }
    }

    @Test
    fun tickerCloseTimeParsesInEasternTime() {
        // 26OCT091015 = 2026-10-09 10:15 ET = 14:15 UTC.
        assertEquals(java.time.Instant.parse("2026-10-09T14:15:00Z").toEpochMilli(), close)
        assertEquals(10, ScalpTicker.hourEt(close))
    }

    @Test
    fun entryNeedsExpectedMoveAboveSpreadPlusBothFees() {
        val p = ScalpParams.DEFAULT.copy(minGap = 0.0)
        // fair 50¢, ask 48¢: move 2¢ < 1¢ spread + ~1.8¢ + ~1.8¢ fees → no entry even with gap threshold 0.
        val (sig, why) = ScalpRule.entrySignal(q(close - 600_000L, 0.47, 0.48), p)
        assertNull(sig)
        assertTrue(why, why.contains("both fees"))
        assertNotNull(ScalpRule.entrySignal(q(close - 600_000L, 0.34, 0.35), p).first)
    }

    @Test
    fun hardExitAtSixtySecondsLeft() {
        assertEquals(ScalpRule.ExitReason.TIME_STOP, ScalpRule.exitSignal("YES", 0.35, q(close - 60_000L, 0.36, 0.37)))
        assertEquals(60.0, ScalpRule.TIME_STOP_S, 0.0)
        // No entries in the last minute either.
        assertNull(ScalpRule.entrySignal(q(close - 59_000L, 0.34, 0.35)).first)
    }

    @Test
    fun explicitProfitTargetAndStop() {
        val p = ScalpParams.DEFAULT // target +8¢, stop −6¢
        assertEquals(ScalpRule.ExitReason.PROFIT_TARGET, ScalpRule.exitSignal("YES", 0.35, q(close - 400_000L, 0.43, 0.44, spot = 99_990.0), p))
        assertEquals(ScalpRule.ExitReason.STOP, ScalpRule.exitSignal("YES", 0.35, q(close - 400_000L, 0.29, 0.30), p))
        assertNull(ScalpRule.exitSignal("YES", 0.35, q(close - 400_000L, 0.36, 0.37, spot = 100_050.0), p))
    }

    @Test
    fun reentersAfterExitWithCooldownAndOneOpenPerSide() {
        val b = book()
        var t = close - 700_000L
        b.onQuote(q(t, 0.34, 0.35), true)                 // signal YES
        b.onQuote(q(t + 3_000L, 0.34, 0.35), true)        // fill @35
        assertEquals(1, b.snapshot().size)                // still one: YES side already open
        b.onQuote(q(t + 6_000L, 0.44, 0.45), true)        // target hit → pending exit
        b.onQuote(q(t + 9_000L, 0.44, 0.45), true)        // sold @44
        val first = b.snapshot().single()
        assertEquals(ScalpState.CLOSED, first.state)
        assertTrue(first.netUsd!! > 0.0)
        // Cooldown: a fresh dip 10 s later does not re-enter …
        b.onQuote(q(t + 19_000L, 0.34, 0.35), true)
        assertEquals(1, b.snapshot().size)
        // … but after 30 s it does: second round trip in the same window.
        b.onQuote(q(t + 40_000L, 0.34, 0.35), true)
        assertEquals(2, b.snapshot().size)
        val per = ScalpBreakdown.roundTripsPerWindow(b.snapshot().map { if (it.state == ScalpState.PENDING_ENTRY) it.copy(state = ScalpState.CLOSED, netUsd = 0.0, contracts = 10) else it })
        assertEquals(2.0, per.avg, 1e-9)
    }

    @Test
    fun shadowVariantsNeverShowAsPrimaryScalps() {
        val ids = AtomicInteger()
        val b = ScalpBook(InMemoryScalpPersistence(), ScalpParams.GRID, InMemoryScalpTuneStore()) { "v${ids.incrementAndGet()}" }
        b.onQuote(q(close - 700_000L, 0.34, 0.35), true)
        assertTrue(b.allTrades().size > 1)
        assertEquals(1, b.snapshot().size)
        assertTrue(b.snapshot().all { it.isPrimary && it.variantId == ScalpParams.DEFAULT.id })
        assertEquals(8, ScalpParams.GRID.size)
        assertTrue(ScalpParams.DEFAULT in ScalpParams.GRID)
    }

    // ---- tuner ----
    private fun closed(coin: String, window: Int, variant: ScalpParams, centsPerContract: Double, primary: Boolean, i: Int): ScalpTrade {
        val minute = 15 * window
        val hh = 10 + minute / 60
        val mm = minute % 60
        val tk = String.format("KX%s15M-26OCT%02d%02d%02d-15", coin, 1 + hh / 24, hh % 24, mm)
        return ScalpTrade(
            id = "$coin-$window-${variant.id}-$i", ticker = tk, side = "YES", state = ScalpState.CLOSED,
            signalAtMs = ScalpTicker.closeMs(tk)!! - 600_000L, signalAsk = 0.4, fairAtSignal = 0.55, contracts = 10,
            entryPrice = 0.4, entryAtMs = ScalpTicker.closeMs(tk)!! - 597_000L, closedAtMs = ScalpTicker.closeMs(tk)!! - 500_000L,
            netUsd = centsPerContract / 100.0 * 10, ruleVersion = "${ScalpRule.VERSION}|${variant.id}|${if (primary) "P" else "S"}"
        )
    }

    private fun dataset(curFit: Double, curOos: Double, altFit: Double, altOos: Double): List<ScalpTrade> {
        val cur = ScalpParams.DEFAULT
        val alt = ScalpParams.GRID.first { it != cur }
        val out = ArrayList<ScalpTrade>()
        for (w in 0 until 20) {
            val early = w < 12
            repeat(4) { i ->
                out += closed("BTC", w, cur, if (early) curFit else curOos, true, i)
                out += closed("BTC", w, alt, if (early) altFit else altOos, false, i)
            }
        }
        return out
    }

    @Test
    fun tunerAdoptsOnlyWhenBetterOnLaterUnseenBlock() {
        val alt = ScalpParams.GRID.first { it != ScalpParams.DEFAULT }
        // Alt fits better AND wins out of sample → adopted, version bumps.
        val adopted = ScalpTuner.tune(dataset(-1.0, -0.5, 2.0, 1.0), ScalpTuneState(), 1L)
        assertEquals(alt.id, adopted.paramsFor("BTC").id)
        assertEquals(1, adopted.version)
        assertEquals(1.0, adopted.oosCentsByCoin["BTC"]!!, 1e-9)
        assertTrue(adopted.trials > 0)
        // Alt fits better but LOSES on the later block → current params kept, version unchanged.
        val kept = ScalpTuner.tune(dataset(-1.0, 0.5, 2.0, 0.2), ScalpTuneState(), 1L)
        assertEquals(ScalpParams.DEFAULT.id, kept.paramsFor("BTC").id)
        assertEquals(0, kept.version)
        assertEquals(0.5, kept.oosCentsByCoin["BTC"]!!, 1e-9)
        // Deterministic.
        assertEquals(adopted, ScalpTuner.tune(dataset(-1.0, -0.5, 2.0, 1.0), ScalpTuneState(), 1L))
        // Other coins untouched with no data.
        assertEquals(ScalpParams.DEFAULT.id, adopted.paramsFor("ETH").id)
    }

    // ---- scorecard ----
    @Test
    fun scorecardBreaksDownByCoinHourAndTimeLeftAfterBothFees() {
        val v = ScalpParams.DEFAULT
        val trades = listOf(
            closed("BTC", 0, v, 3.0, true, 0),
            closed("BTC", 0, v, -2.0, true, 1),
            closed("ETH", 1, v, 0.0, true, 0), // breakeven after both fees is NOT a win
            closed("SOL", 5, v, 1.0, true, 0)
        )
        val coin = ScalpBreakdown.byCoin(trades).associateBy { it.key }
        assertEquals(2, coin["BTC"]!!.n)
        assertEquals(1, coin["BTC"]!!.wins)
        assertEquals(0, coin["ETH"]!!.wins)
        assertEquals(0.10, coin["BTC"]!!.netUsd, 1e-9)
        val hours = ScalpBreakdown.byHourEt(trades).map { it.key }
        assertTrue(hours.contains("10 ET") && hours.contains("11 ET"))
        assertEquals(listOf("5–10 min left"), ScalpBreakdown.byTimeLeft(trades).map { it.key }.distinct())
        val pw = ScalpBreakdown.roundTripsPerWindow(trades)
        assertEquals(2, pw.max)
        assertTrue(ScalpBreakdown.lines(trades).any { it.startsWith("Round trips per window") })
    }

    @Test
    fun scalpNeverPlacesLiveOrders() {
        val src = listOf(
            java.io.File("app/src/main/java/com/dirk/kalshiodds/decision/Scalp.kt"),
            java.io.File("src/main/java/com/dirk/kalshiodds/decision/Scalp.kt")
        ).first { it.isFile }.readText()
        assertFalse(src.contains("createLimit") || src.contains("tradeClient"))
    }
}
