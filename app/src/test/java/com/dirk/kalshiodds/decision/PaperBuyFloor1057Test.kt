package com.dirk.kalshiodds.decision

import com.dirk.kalshiodds.ui.ScalpWindowChartData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * 1.0.57 paper rules: 15¢ floor on every scalper, no auto-pause, no buy of the
 * under-50¢ side in the last 120s, and the Final 60s favourite.
 */
class PaperBuyFloor1057Test {
    private val ticker = "KXBTC15M-26OCT181315-00"

    @Test fun nothingBuysUnderFifteenCents() {
        assertEquals("ask under 15¢", ScalpFloors.buyRefused(0.12, 400.0))
        assertNull(ScalpFloors.buyRefused(0.15, 400.0))
        assertFalse(FairGapTune.askAllowed("sf16", 0.12, favLeg = true, maxCents = 97))
        assertTrue(FairGapTune.askAllowed("sf16", 0.90, favLeg = true, maxCents = 97))

        val now = 1_700_000_000_000L
        val sig = HftMathEngines.perSecVol(0.0075)
        val cheap = ScalpRule.Quote(
            ticker = ticker, nowMs = now, closeMs = now + 400_000L, bookAtMs = now,
            yesBid = 0.11, yesBidSize = 200.0, yesAsk = 0.12, yesAskSize = 200.0,
            spot = 101_000.0, strike = 100_000.0, sigmaPerSec = sig
        )
        var fleetIds = 0
        val fleet = TwentyScalperFleet.FleetBook().onQuote(cheap, 20_000.0) { "f${fleetIds++}" }
        assertTrue(fleet.joinToString { "${it.variantId}@${it.entryPrice}" }, fleet.none { (it.entryPrice ?: 0.0) < 0.15 - 1e-9 })

        val dos = HftBook()
        val low = cheap.copy(spot = 90_000.0, yesBid = 0.08, yesAsk = 0.12)
        dos.onQuote(low, 20_000.0) { "d1" }
        val crossed = low.copy(nowMs = now + 1_000L, bookAtMs = now + 1_000L, yesAsk = 0.06, yesBid = 0.04)
        val dosFills = dos.onQuote(crossed, 20_000.0) { "d2" }
        assertTrue(dosFills.joinToString { "${it.variantId}@${it.entryPrice}" }, dosFills.none { (it.entryPrice ?: 1.0) < 0.15 - 1e-9 })

        val book = ScalpBook(bankrollUsd = { 100_000.0 })
        val close = ScalpTicker.closeMs(ticker)!!
        val primaryAt = close - 800_000L
        book.onQuote(quote(primaryAt, 0.11, 0.12, 101_000.0, close), true)
        book.onQuote(quote(primaryAt + 4_000L, 0.11, 0.12, 101_000.0, close), true)
        assertTrue(book.snapshot().none { it.variantId == KeptScalpers.FAIR_ID && it.state != ScalpState.NO_FILL })

        val favBook = ScalpBook(bankrollUsd = { 100_000.0 })
        val favAt = Instant.parse("2026-11-04T03:30:00Z").toEpochMilli()
        val fav = favQuote(favAt, 0.10, 0.12)
        favBook.onShockFavQuote(fav, true)
        favBook.onShockFavQuote(fav.copy(nowMs = favAt + 500L, bookAtMs = favAt + 500L), true)
        val favOpen = favBook.snapshot().filter { ShockFav.isFavLeg(it.variantId) && it.state != ScalpState.NO_FILL }
        assertTrue(favOpen.joinToString { "${it.variantId}@${it.signalAsk}" }, favOpen.none { it.signalAsk < 0.15 - 1e-9 })
    }

    @Test fun lastTwoMinutesDoNotBuyTheUnderdog() {
        assertEquals("last 120s: no buy under 50¢", ScalpFloors.buyRefused(0.40, 100.0))
        assertNull(ScalpFloors.buyRefused(0.50, 100.0))
        assertNull(ScalpFloors.buyRefused(0.40, 121.0))
        val why = LateWindowScalper.blockReason("BTC", setOf("BTC"), 100.0, 0.40, 0.20, true, "ok")
        assertTrue(why, why!!.contains("50"))
        assertNull(LateWindowScalper.blockReason("BTC", setOf("BTC"), 100.0, 0.55, 0.20, true, "ok"))
        assertFalse(FairGapTune.askAllowed("sf62", 0.40, favLeg = true, q = favQuote(1_700_000_040_000L, 0.39, 0.40).copy(closeMs = 1_700_000_040_000L + 100_000L)))

        val book = ScalpBook(liveExtras = true, bankrollUsd = { 100_000.0 })
        val close = ScalpTicker.closeMs(ticker)!!
        val at = close - 100_000L
        book.onQuote(quote(at, 0.39, 0.40, 101_000.0, close), true)
        book.onShockFavQuote(quote(at, 0.39, 0.40, 101_000.0, close), true)
        val bought = book.snapshot().filter { it.state != ScalpState.NO_FILL && it.signalAsk < 0.50 - 1e-9 }
        assertTrue(bought.joinToString { "${it.variantId}@${it.signalAsk}" }, bought.isEmpty())
    }

    @Test fun aLargeWindowLossDoesNotPause() {
        val close = ScalpTicker.closeMs(ticker)!!
        val start = close - 800_000L
        val book = ScalpBook(bankrollUsd = { 100_000.0 })
        book.onQuote(quote(start, 0.34, 0.35, 101_000.0, close), true)
        book.onQuote(quote(start + 4_000L, 0.34, 0.35, 101_000.0, close), true)
        var at = start + 8_000L
        repeat(6) {
            book.onQuote(quote(at, 0.20, 0.36, 100_000.0, close), true)
            at += 4_000L
        }
        val before = book.snapshot().count { it.variantId == KeptScalpers.FAIR_ID && it.side == "YES" && it.state != ScalpState.NO_FILL }
        repeat(4) {
            book.onQuote(quote(at, 0.34, 0.35, 101_000.0, close), true)
            at += 4_000L
        }
        val after = book.snapshot().count { it.variantId == KeptScalpers.FAIR_ID && it.side == "YES" && it.state != ScalpState.NO_FILL }
        assertTrue("clips $before -> $after", after >= before)
        assertTrue(book.gateLine(KeptScalpers.FAIR_ID)?.contains("paused") != true)
    }

    @Test fun final60FavouriteBuysOnceAndHolds() {
        val p = ShockFav.v(ShockFav.FINAL60)
        val close = Instant.parse("2026-11-04T03:30:40Z").toEpochMilli()
        assertTrue(ShockFav.favEntryOk(p, 0.90, 0.85, 40.0, "BTC", close))
        assertFalse(ShockFav.favEntryOk(p, 0.75, 0.85, 40.0, "BTC", close))
        assertFalse(ShockFav.favEntryOk(p, 0.90, 0.70, 40.0, "BTC", close))
        assertFalse(ShockFav.favEntryOk(p, 0.90, 0.99, 40.0, "BTC", close))
        assertFalse(ShockFav.favEntryOk(p, 0.90, 0.85, 90.0, "BTC", close))
        assertFalse(ShockFav.favEntryOk(p, 0.90, 0.12, 40.0, "BTC", close))
        assertTrue(KeptScalpers.entryFires(ShockFav.FINAL60_ID))
        assertEquals(ShockFav.FINAL60_ID, ScalpModels.modelByCode(ShockFav.FINAL60_ID)?.code)
        assertTrue(ScalpModels.stopLabel(ScalpModels.Model.SF62F).contains("settlement"))
        assertTrue(PaperFleetDefaults.buyCeiling(ShockFav.FINAL60_ID) > PaperFleetDefaults.BUY_CEILING)

        val at = Instant.parse("2026-11-04T03:30:00Z").toEpochMilli()
        val book = ScalpBook(bankrollUsd = { 100_000.0 })
        val first = book.onShockFavQuote(favQuote(at, 0.83, 0.85), true).filter { it.variantId == ShockFav.FINAL60_ID }
        assertEquals(1, first.size)
        assertEquals(ScalpState.PENDING_ENTRY, first[0].state)
        assertTrue(first[0].contracts >= PaperClip.MIN)
        val filled = book.onShockFavQuote(favQuote(at + 400L, 0.83, 0.85), true).filter { it.variantId == ShockFav.FINAL60_ID }
        assertEquals(ScalpState.OPEN, filled.single().state)
        assertTrue(filled.single().contracts >= PaperClip.MIN)
        assertEquals(0.85, filled.single().entryPrice!!, 1e-9)
        val again = book.onShockFavQuote(favQuote(at + 800L, 0.83, 0.85), true).filter { it.variantId == ShockFav.FINAL60_ID && it.id != filled.single().id }
        assertTrue(again.isEmpty())
        val rich = book.onShockFavQuote(favQuote(at + 1_200L, 0.98, 0.99), true).filter { it.id == filled.single().id }
        assertTrue(rich.isEmpty() || rich.single().state == ScalpState.OPEN)
        val held = book.snapshot().single { it.id == filled.single().id }
        assertEquals(ScalpState.OPEN, held.state)

        val blocked = ScalpBook(bankrollUsd = { 100_000.0 })
        assertTrue(blocked.onShockFavQuote(favQuote(at, 0.78, 0.70), true).none { it.variantId == ShockFav.FINAL60_ID })
        assertTrue(blocked.onShockFavQuote(favQuote(at, 0.97, 0.99), true).none { it.variantId == ShockFav.FINAL60_ID })
        val late = favQuote(at, 0.83, 0.85).let { it.copy(closeMs = it.nowMs + 90_000L) }
        assertTrue(blocked.onShockFavQuote(late, true).none { it.variantId == ShockFav.FINAL60_ID })

        val hour = Instant.parse("2026-11-04T08:10:00Z").toEpochMilli()
        val avoid = ScalpBook(bankrollUsd = { 100_000.0 }, controls = MemoryScalpControls(ScalpControls(final60AvoidHours = true)))
        assertTrue(ShockFav.final60HourBlocked(hour))
        assertTrue(avoid.onShockFavQuote(favQuote(hour, 0.83, 0.85), true).none { it.variantId == ShockFav.FINAL60_ID })
        val openHour = ScalpBook(bankrollUsd = { 100_000.0 }, controls = MemoryScalpControls(ScalpControls(final60AvoidHours = true)))
        assertTrue(openHour.onShockFavQuote(favQuote(at, 0.83, 0.85), true).any { it.variantId == ShockFav.FINAL60_ID })
    }

    @Test fun fleetAndDosClosesDrawAsSells() {
        val now = 1_700_000_000_000L
        val sig = HftMathEngines.perSecVol(0.0075)
        val openQ = ScalpRule.Quote(
            ticker = ticker, nowMs = now, closeMs = now + 400_000L, bookAtMs = now,
            yesBid = 0.39, yesBidSize = 200.0, yesAsk = 0.40, yesAskSize = 200.0,
            spot = 101_000.0, strike = 100_000.0, sigmaPerSec = sig
        )
        var ids = 0
        val fleet = TwentyScalperFleet.FleetBook()
        val opened = fleet.onQuote(openQ, 20_000.0) { "o${ids++}" }
        assertTrue(opened.isNotEmpty())
        val exitQ = openQ.copy(nowMs = now + 2_000L, bookAtMs = now + 2_000L, yesBid = 0.55, yesAsk = 0.56)
        val closed = fleet.onQuote(exitQ, 20_000.0) { "c${ids++}" }.filter { it.state == ScalpState.CLOSED }
        assertTrue(closed.joinToString { it.note }, closed.isNotEmpty())
        closed.forEach { t ->
            assertTrue(t.note, ScalpFills.sales(t).any { !it.settlement })
            val marks = ScalpWindowChartData(ticker = t.ticker, closeMs = openQ.closeMs, nowMs = exitQ.nowMs, trades = listOf(t)).markers(t.side)
            assertTrue(marks.any { it.kind == ScalpWindowChartData.Kind.SELL })
        }
    }

    private fun quote(now: Long, yesBid: Double, yesAsk: Double, spot: Double, close: Long) = ScalpRule.Quote(
        ticker = ticker, nowMs = now, closeMs = close, bookAtMs = now,
        yesBid = yesBid, yesBidSize = 400.0, yesAsk = yesAsk, yesAskSize = 400.0,
        spot = spot, strike = 100_000.0, sigmaPerSec = 3e-5
    )

    private fun favQuote(now: Long, yesBid: Double, yesAsk: Double) = ScalpRule.Quote(
        ticker = ticker, nowMs = now, closeMs = now + 40_000L, bookAtMs = now,
        yesBid = yesBid, yesBidSize = 400.0, yesAsk = yesAsk, yesAskSize = 400.0,
        spot = 101_000.0, strike = 100_000.0, sigmaPerSec = 3e-5
    )
}
