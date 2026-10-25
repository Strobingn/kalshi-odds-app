package com.dirk.kalshiodds.decision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.floor

/** Paper fleet registry and the shared profitability gate. No live orders. */
class TwentyScalperFleetTest {
    private val ids = listOf(
        "s01-brti-bound",
        "s02-spike-fade",
        "s03-vwap-slope",
        "s04-deci-tail",
        "s05-student-tail",
        "s06-diurnal-theta",
        "s07-rv-iv-disp",
        "s08-corr-accel",
        "s09-as-mm",
        "s10-lob-ladder",
        "s11-obi-micro",
        "s12-vpin-flip",
        "s13-no-surplus",
        "s14-brti-nowcast",
        "s15-poly-arb",
        "s16-perp-delta",
        "s17-bracket-1h",
        "s18-macro-rev",
        "s19-gamma-pin",
        "s20-timesfm-gate"
    )

    @Test fun registryIsTwentyIdsAndLeavesTheWinnersAlone() {
        assertEquals(ids, TwentyScalperFleet.IDS)
        assertEquals(20, TwentyScalperFleet.ALL.size)
        assertEquals(ids.toSet().size, 20)
        assertEquals(ids, KeptScalpers.FLEET.map { it.id })
        assertTrue(TwentyScalperFleet.IDS.none { it in KeptScalpers.IDS })
        assertEquals(KeptScalpers.ALL.map { it.id }, ScalpModels.WINNERS.map { it.code })
        assertEquals(4, TwentyScalperFleet.ALL.count { it.category == TwentyScalperFleet.Category.TERMINAL_BRTI })
        assertEquals(4, TwentyScalperFleet.ALL.count { it.category == TwentyScalperFleet.Category.NON_GAUSSIAN })
        assertEquals(5, TwentyScalperFleet.ALL.count { it.category == TwentyScalperFleet.Category.LOB_MM })
        assertEquals(4, TwentyScalperFleet.ALL.count { it.category == TwentyScalperFleet.Category.CROSS_VENUE })
        assertEquals(3, TwentyScalperFleet.ALL.count { it.category == TwentyScalperFleet.Category.EVENT_HYBRID })
        assertEquals("s05 Student-t tail", ScalpModels.labelFor("s05-student-tail"))
        assertFalse(ScalpModels.isRemoved("s05-student-tail"))
        assertFalse(FairGapTune.entryGated("s01-brti-bound"))
    }

    @Test fun multiplexCoversBtcEthSolAndLeavesOneHourToTheBracket() {
        assertEquals("KXBTC15M", TwentyScalperFleet.seriesOf("kxbtc15m-26OCT181200-00"))
        assertEquals("KXETH15M", TwentyScalperFleet.seriesOf("KXETH15M-26OCT181200-00"))
        assertEquals("KXSOL15M", TwentyScalperFleet.seriesOf("KXSOL15M-26OCT181200-00"))
        assertEquals("BRACKET_1H", TwentyScalperFleet.seriesOf("KXBTC1H-26OCT181200-00"))
        assertTrue(TwentyScalperFleet.listedOn("s05-student-tail", "KXETH15M-X"))
        assertTrue(TwentyScalperFleet.listedOn("s11-obi-micro", "KXSOL15M-X"))
        assertFalse(TwentyScalperFleet.listedOn("s17-bracket-1h", "KXBTC15M-X"))
        assertTrue(TwentyScalperFleet.listedOn("s17-bracket-1h", "KXBTC1H-X"))
        val eth = TwentyScalperFleet.SignalCtx(ticker = "KXETH15M-X", fairYes = 0.90, yesAsk = 0.40, yesBid = 0.39)
        assertEquals("YES", TwentyScalperFleet.signal("s05-student-tail", eth)?.side)
    }

    @Test fun quadraticFeeBookWalkAndQuarterKelly() {
        assertEquals(0.02, TwentyScalperFleet.quadraticFeeUsd(1, 0.50), 0.0)
        assertEquals(0.18, TwentyScalperFleet.quadraticFeeUsd(10, 0.50), 0.0)
        assertEquals(0.0, TwentyScalperFleet.quadraticFeeUsd(0, 0.50), 0.0)
        assertEquals(0.37, TwentyScalperFleet.impliedAskFromBestNo(0.63)!!, 1e-12)
        assertNull(TwentyScalperFleet.impliedAskFromBestNo(0.0))
        val walk = TwentyScalperFleet.bookWalk(
            listOf(TwentyScalperFleet.Level(0.40, 10.0), TwentyScalperFleet.Level(0.42, 10.0)),
            15
        )!!
        val avg = (0.40 * 10 + 0.42 * 5) / 15.0
        assertEquals(15, walk.filled)
        assertEquals(avg, walk.avg, 1e-12)
        assertEquals(avg - 0.40, walk.slippage, 1e-12)
        val p = 0.60
        val cost = 0.50
        val f = (p - cost) / (1.0 - cost)
        val raw = floor(1_000.0 * TwentyScalperFleet.QUARTER * f / cost).toInt()
        assertEquals(raw.coerceAtMost(TwentyScalperFleet.MAX_CLIP), TwentyScalperFleet.quarterKellyContracts(p, cost, 1_000.0))
        assertEquals(0, TwentyScalperFleet.quarterKellyContracts(0.40, 0.50, 1_000.0))
    }

    @Test fun gateFailsAStaleBookAndAThinEdge() {
        val ladder = listOf(TwentyScalperFleet.Level(0.40, 50.0))
        val stale = TwentyScalperFleet.evaluate("YES", 0.70, ladder, 250L, 10_000.0)
        assertFalse(stale.pass)
        assertEquals("stale book", stale.reason)
        assertTrue(TwentyScalperFleet.bookStale(250L))
        assertFalse(TwentyScalperFleet.bookStale(249L))
        val ok = TwentyScalperFleet.evaluate("YES", 0.70, ladder, 249L, 10_000.0)
        assertTrue(ok.reason, ok.pass)
        assertTrue(ok.netEv >= TwentyScalperFleet.THETA_MIN)
        assertTrue(ok.contracts in 1..TwentyScalperFleet.MAX_CLIP)
        assertTrue(ok.slippage >= -1e-12)
        val thin = TwentyScalperFleet.evaluate("YES", 0.41, ladder, 0L, 10_000.0)
        assertFalse(thin.pass)
        assertTrue(thin.netEv < TwentyScalperFleet.THETA_MIN || thin.reason != "pass")
    }

    @Test fun routerDropsTheCrossingSideAndStubsStayQuiet() {
        val yes = TwentyScalperFleet.InternalRouter.Intent("s05-student-tail", "KXBTC15M-X", "YES", 0.55, 4, 0.08)
        val no = TwentyScalperFleet.InternalRouter.Intent("s02-spike-fade", "KXBTC15M-X", "NO", 0.50, 4, 0.02)
        val kept = TwentyScalperFleet.InternalRouter.admit(listOf(yes, no))
        assertEquals(listOf("s05-student-tail"), kept.map { it.id })
        val both = TwentyScalperFleet.InternalRouter.admit(
            listOf(yes.copy(price = 0.30, netEv = 0.05), no.copy(price = 0.30, netEv = 0.04))
        )
        assertEquals(2, both.size)
        val ctx = TwentyScalperFleet.SignalCtx()
        assertNull(TwentyScalperFleet.signal("s15-poly-arb", ctx))
        assertNull(TwentyScalperFleet.signal("s16-perp-delta", ctx))
        assertNull(TwentyScalperFleet.signal("s18-macro-rev", ctx))
        assertNull(TwentyScalperFleet.signal("s20-timesfm-gate", ctx))
        assertNull(TwentyScalperFleet.signal("s14-brti-nowcast", ctx.copy(cfFairYes = null)))
        assertNull(TwentyScalperFleet.signal("s17-bracket-1h", ctx.copy(ticker = "KXBTC15M-X")))
        assertEquals("YES", TwentyScalperFleet.signal("s14-brti-nowcast", ctx.copy(cfFairYes = 0.80, fairYes = 0.55))?.side)
        assertEquals(
            "NO",
            TwentyScalperFleet.signal("s15-poly-arb", ctx.copy(polyYesAsk = 0.20, yesBid = 0.70, yesAsk = 0.72))?.side
        )
        assertEquals("YES", TwentyScalperFleet.signal("s17-bracket-1h", ctx.copy(ticker = "KXBTC1H-X", bracket1h = true, fairYes = 0.80))?.side)
        assertEquals("NO", TwentyScalperFleet.signal("s18-macro-rev", ctx.copy(macroZ = 2.0))?.side)
        assertEquals("YES", TwentyScalperFleet.signal("s20-timesfm-gate", ctx.copy(timesFmYes = 0.80, fairYes = 0.55))?.side)
        assertNull(TwentyScalperFleet.signal("s16-perp-delta", ctx.copy(perpBasis = 0.0)))
        assertEquals("YES", TwentyScalperFleet.signal("s16-perp-delta", ctx.copy(perpBasis = 0.01))?.side)
    }

    @Test fun paperBookOpensOnAFreshBtcBookAndSkipsAStaleOne() {
        val now = 1_700_000_000_000L
        val sig = HftMathEngines.perSecVol(0.0075)
        fun quote(age: Long) = ScalpRule.Quote(
            ticker = "KXBTC15M-26OCT181200-00",
            nowMs = now,
            closeMs = now + 400_000L,
            bookAtMs = now - age,
            yesBid = 0.39,
            yesBidSize = 100.0,
            yesAsk = 0.40,
            yesAskSize = 100.0,
            spot = 101_000.0,
            strike = 100_000.0,
            sigmaPerSec = sig
        )
        val book = ScalpBook(liveExtras = true, bankrollUsd = { 20_000.0 })
        val fresh = book.onQuote(quote(0L), true).filter { TwentyScalperFleet.isFleet(it.variantId) }
        assertTrue(fresh.toString(), fresh.isNotEmpty())
        assertTrue(fresh.all { it.state == ScalpState.OPEN && it.note.contains("no live order") })
        assertTrue(fresh.all { it.contracts >= PaperClip.MIN })
        val staleBook = ScalpBook(liveExtras = true, bankrollUsd = { 20_000.0 })
        val stale = staleBook.onQuote(quote(250L), true).filter { TwentyScalperFleet.isFleet(it.variantId) }
        assertTrue(stale.isEmpty())
    }
}
