package com.dirk.kalshiodds.decision

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * BTC 15m regime. Pure rules, then one paper book pass that sells through ScalpFills.
 * Size stays on the existing clip. No cash-fraction sizer.
 */
class BtcRegimeTest {
    @Test fun regimeBoundaries() {
        assertEquals(BtcRegime.Name.EARLY, BtcRegime.forSecondsLeft(600).name)
        assertEquals(BtcRegime.Name.PRIME, BtcRegime.forSecondsLeft(599).name)
        assertEquals(BtcRegime.Name.PRIME, BtcRegime.forSecondsLeft(599.9).name)
        assertEquals(BtcRegime.Name.PRIME, BtcRegime.forSecondsLeft(300).name)
        assertEquals(BtcRegime.Name.LATE, BtcRegime.forSecondsLeft(299).name)
        assertEquals(BtcRegime.Name.LATE, BtcRegime.forSecondsLeft(60).name)
        assertEquals(BtcRegime.Name.FINAL, BtcRegime.forSecondsLeft(59).name)
        assertTrue(BtcRegime.forSecondsLeft(600).newEntries)
        assertTrue(BtcRegime.forSecondsLeft(600).requiresAlignment)
        assertTrue(BtcRegime.forSecondsLeft(300).newEntries)
        assertTrue(BtcRegime.forSecondsLeft(60).favouriteOnly)
        assertTrue(BtcRegime.forSecondsLeft(60).usesLateStop)
        assertFalse(BtcRegime.forSecondsLeft(59).newEntries)
        assertTrue(BtcRegime.forSecondsLeft(59).flatten)
        assertEquals(BtcRegime.ID, ScalpModels.modelByCode(BtcRegime.ID)?.code)
        assertEquals(BtcRegime.LABEL, ScalpModels.modelByCode(BtcRegime.ID)?.label)
        assertTrue(BtcRegime.tradesTicker("KXBTC15M-26NOV041200-00"))
        assertFalse(BtcRegime.tradesTicker("KXETH15M-26NOV041200-00"))
    }

    @Test fun entryPassAndFail() {
        assertNull(BtcRegime.blockEntry(yes(ask = 0.50)))
        assertEquals("price still falling", BtcRegime.blockEntry(yes(prices = listOf(0.42, 0.41, 0.40))))
        assertEquals("below 15¢ floor", BtcRegime.blockEntry(yes(ask = 0.14)))
        assertNotNull(BtcRegime.blockEntry(yes(seconds = 420, ask = 0.73)))
        assertNull(BtcRegime.blockEntry(yes(seconds = 420, ask = 0.72)))
        assertNotNull(BtcRegime.blockEntry(no(ask = 0.66)))
        assertNull(BtcRegime.blockEntry(no(ask = 0.65)))
        assertEquals("late underdog", BtcRegime.blockEntry(yes(seconds = 120, ask = 0.60, fair = 0.49)))
        assertNull(BtcRegime.blockEntry(yes(seconds = 120, ask = 0.60, fair = 0.62)))
        assertEquals("final minute: no new entries", BtcRegime.blockEntry(yes(seconds = 59, ask = 0.60, fair = 0.80)))
        assertNotNull(BtcRegime.blockEntry(yes(hour = 8, settings = BtcRegime.Settings(avoidUtcHours = true))))
        assertNull(BtcRegime.blockEntry(yes(hour = 8)))
        assertEquals("no averaging down", BtcRegime.blockEntry(yes(openEntry = 0.40, openBid = 0.30)))
        assertEquals("max entries this window", BtcRegime.blockEntry(yes(entries = 2)))
    }

    @Test fun exits() {
        assertEquals(BtcRegime.ExitKind.TAKE_PROFIT, BtcRegime.exitKind(400, 0.40, 0.65))
        assertEquals(BtcRegime.ExitKind.STOP, BtcRegime.exitKind(400, 0.40, 0.28))
        assertNull(BtcRegime.exitKind(120, 0.40, 0.33))
        assertEquals(BtcRegime.ExitKind.STOP, BtcRegime.exitKind(120, 0.40, 0.32))
        assertEquals(BtcRegime.ExitKind.FINAL_CUT, BtcRegime.exitKind(45, 0.40, 0.40))
        assertNull(BtcRegime.exitKind(46, 0.40, 0.40))
        assertTrue(BtcRegime.exitReason(BtcRegime.ExitKind.FINAL_CUT).contains("45"))
    }

    @Test fun spikeDoesNotChaseAndSellsIntoAWinner() {
        val jump = listOf(BtcRegime.Px(0L, 0.40), BtcRegime.Px(5_000L, 0.47))
        val flat = listOf(BtcRegime.Px(0L, 0.40), BtcRegime.Px(5_000L, 0.46))
        assertEquals(BtcRegime.SpikeAction.CLEAR, BtcRegime.spikeAction(flat, 5_000L))
        assertEquals(BtcRegime.SpikeAction.WAIT, BtcRegime.spikeAction(jump, 5_000L))
        assertEquals(BtcRegime.SpikeAction.WAIT, BtcRegime.spikeAction(jump, 5_000L, openEntry = 0.40, bid = 0.50))
        assertEquals(BtcRegime.SpikeAction.SELL, BtcRegime.spikeAction(jump, 5_000L, openEntry = 0.40, bid = 0.65))
        val blocked = yes(path = jump, nowMs = 5_000L)
        assertEquals("spike: no chase", BtcRegime.blockEntry(blocked))
    }

    @Test fun settingsChangeWithoutCodeEditsAndAdviceKeepsFloorAndTarget() {
        val tighter = BtcRegime.Settings(primeYesMax = 70)
        assertNotNull(BtcRegime.blockEntry(yes(ask = 0.72, settings = tighter)))
        assertNull(BtcRegime.blockEntry(yes(ask = 0.72)))
        val clamped = BtcRegime.Settings(floorCents = 10, primeYesMax = 90, takeProfitCents = 20).normalized()
        assertEquals(15, clamped.floorCents)
        assertEquals(75, clamped.primeYesMax)
        assertEquals(20, clamped.takeProfitCents)
        val proposed = BtcRegime.Settings(floorCents = 10, takeProfitCents = 10, primeYesMax = 70, stopCents = 9)
        val kept = BtcRegime.protectDefaults(BtcRegime.Settings(), proposed)
        assertEquals(15, kept.floorCents)
        assertEquals(25, kept.takeProfitCents)
        assertEquals(70, kept.primeYesMax)
        assertEquals(9, kept.stopCents)
        assertTrue(PaperSettingTuner.fieldsFor(BtcRegime.ID).isEmpty())
        val json = Json { ignoreUnknownKeys = true }
        val stored = ScalpControls(btc15 = tighter)
        val back = json.decodeFromString<ScalpControls>(json.encodeToString(stored)).normalized()
        assertEquals(70, back.btc15.primeYesMax)
        assertNotNull(BtcRegime.blockEntry(yes(ask = 0.72, settings = back.btc15)))
    }

    @Test fun paperBookSellsAtTheBid() {
        val book = ScalpBook(bankrollUsd = { 100_000.0 }, idFactory = { "b${seq++}" })
        val base = Instant.parse("2026-11-04T03:00:00Z").toEpochMilli()
        val close = base + 500_000L
        for (i in 0..45) {
            book.onQuote(quote(base + i * 1_000L, close, 0.49, 0.50, 100_000.0 + i * 5.0), true)
        }
        book.onQuote(quote(base + 50_000L, close, 0.80, 0.81, 100_300.0), true)
        val mine = book.snapshot().filter { it.variantId == BtcRegime.ID && it.state != ScalpState.NO_FILL }
        assertTrue(mine.joinToString { "${it.state} ${it.exitReason} ${it.note}" }, mine.isNotEmpty())
        assertTrue(mine.all { it.state == ScalpState.CLOSED })
        assertTrue(mine.all { it.contracts >= PaperClip.MIN })
        assertTrue(mine.all { it.exitReason?.contains("take profit") == true })
        assertTrue(mine.all { it.note.contains("scalpFill=") })
        assertTrue(ScalpModels.stopLabel(ScalpModels.Model.SF62F).contains("settlement"))
    }

    private var seq = 0
    private fun yes(
        seconds: Int = 420,
        ask: Double = 0.50,
        fair: Double = 0.60,
        prices: List<Double> = listOf(0.40, 0.40, 0.40),
        hour: Int = 3,
        entries: Int = 0,
        openEntry: Double? = null,
        openBid: Double? = null,
        path: List<BtcRegime.Px> = emptyList(),
        nowMs: Long = 0L,
        settings: BtcRegime.Settings = BtcRegime.Settings()
    ) = BtcRegime.Entry(
        secondsLeft = seconds, side = "YES", ask = ask, fair = fair, prices = prices,
        velocity = 1.0, spot = 101.0, vwap = 100.0, utcHour = hour, entriesThisWindow = entries,
        openEntry = openEntry, openBid = openBid, askPath = path, nowMs = nowMs, settings = settings
    )

    private fun no(ask: Double) = yes(ask = ask).copy(side = "NO", velocity = -1.0, spot = 99.0, vwap = 100.0)

    private fun quote(now: Long, close: Long, bid: Double, ask: Double, spot: Double) = ScalpRule.Quote(
        ticker = "KXBTC15M-26NOV041200-00", nowMs = now, closeMs = close, bookAtMs = now,
        yesBid = bid, yesBidSize = 200.0, yesAsk = ask, yesAskSize = 200.0,
        spot = spot, strike = 100_000.0, sigmaPerSec = 3e-5
    )
}
