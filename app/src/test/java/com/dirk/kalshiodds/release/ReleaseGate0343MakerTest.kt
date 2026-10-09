package com.dirk.kalshiodds.release

import com.dirk.kalshiodds.decision.ScalpBook
import com.dirk.kalshiodds.decision.ScalpParams
import com.dirk.kalshiodds.decision.ScalpRule
import com.dirk.kalshiodds.decision.ScalpState
import com.dirk.kalshiodds.decision.ScalpStrategy
import com.dirk.kalshiodds.domain.KalshiPrice
import com.dirk.kalshiodds.domain.KalshiQuoteDisplay
import com.dirk.kalshiodds.signal.paper.PaperOrderBook
import com.dirk.kalshiodds.signal.paper.PaperSideQuote
import com.dirk.kalshiodds.signal.trade.KalshiFee
import com.dirk.kalshiodds.signal.ws.CfBenchmarks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.3.43 owner research-doc items: sub-cent prices, fee rounding, maker-first scalps, queue logging, CF explainer. */
class ReleaseGate0343MakerTest {
    private val t = "KXBTC15M-26OCT091445-45"
    private var ids = 0

    @Test
    fun subCentPricesParseAndDisplayAtTenthCent() {
        // tapered_deci_cent: 0.1¢ steps below 10¢ / above 90¢ (live KXBTC15M price_ranges, 2026-10-09).
        assertEquals(0.0335, KalshiPrice.parseDollars("0.0335")!!, 1e-12)
        assertEquals(0.993, KalshiPrice.parseDollars("0.9930")!!, 1e-12)
        assertEquals("99.3¢", KalshiQuoteDisplay.formatPriceCents(0.993))
        assertEquals("33.5¢", KalshiQuoteDisplay.formatPriceCents(0.335))
        assertEquals("33¢", KalshiQuoteDisplay.formatPriceCents(0.33))
        assertEquals("99.3¢", com.dirk.kalshiodds.ui.CoinViewCopy.cents(0.993))
        // Paper touch fill keeps the sub-cent limit exactly.
        val b = PaperOrderBook(idFactory = { "o${ids++}" }, nowMs = { 0L })
        val o = b.submit(t, "YES", "BUY", 0.993, 5, null, PaperSideQuote.top(0.993, 10.0, 0.992, 3.0)).order!!
        assertEquals(5, o.filledQty); assertEquals(0.993, o.limitPrice, 0.0)
    }

    @Test
    fun feeRoundingIsOneConfigurableFunction() {
        val before = KalshiFee.balancePrecisionUsd
        try {
            assertEquals(0.01, KalshiFee.balancePrecisionUsd, 0.0) // non-direct member default (docs)
            assertEquals(0.18, KalshiFee.total(10, 0.50), 1e-9)   // 0.175 → $0.18
            KalshiFee.balancePrecisionUsd = KalshiFee.DIRECT_BALANCE_PRECISION_USD
            assertEquals(0.175, KalshiFee.total(10, 0.50), 1e-9)  // centicent for direct members
        } finally { KalshiFee.balancePrecisionUsd = before }
        val line = KalshiFee.expectedFeeLine(10, 0.50)
        assertTrue(line, line.startsWith("Fee ≈ 7% × (1 − 50¢) of $5.00 stake = $0.1750"))
    }

    @Test
    fun makerFeeFromSeriesFeeType() {
        assertEquals(0.0, KalshiFee.makerCoefficientFor("quadratic"), 0.0)
        assertEquals(0.0175, KalshiFee.makerCoefficientFor("quadratic_with_maker_fees"), 1e-12)
        assertEquals(0.035, KalshiFee.makerCoefficientFor("quadratic_with_combo_maker_fees"), 1e-12)
        assertEquals(0.0, KalshiFee.makerFee(10, 0.5, "KXBTC15M"), 0.0)
    }

    @Test
    fun everyStrategyHasAMakerVariant() {
        ScalpStrategy.values().forEach { s -> assertTrue(s.name, ScalpParams.MAKER_VARIANTS.any { it.strategy == s && it.maker }) }
        ScalpParams.MAKER_VARIANTS.forEach { assertTrue(it.id.endsWith("-mk")); assertNotNull(ScalpParams.byId(it.id)) }
    }

    private fun q(at: Long, bid: Double, bidSz: Double, ask: Double, askSz: Double, spot: Double = 100_000.0) = ScalpRule.Quote(
        ticker = t, nowMs = at, closeMs = 1_000_000L + 400_000L, bookAtMs = at,
        yesBid = bid, yesBidSize = bidSz, yesAsk = ask, yesAskSize = askSz,
        spot = spot, strike = 100_000.0, sigmaPerSec = 3e-5, cfSpot = spot, cfAgeMs = 100L
    )

    private val mk = ScalpParams.seedFor("BTC", ScalpStrategy.CF_REPRICE).copy(maker = true)

    private fun posted(): Pair<ScalpBook, String> {
        val book = ScalpBook(variants = listOf(mk), idFactory = { "m${ids++}" })
        book.onQuote(q(1_000_000L, 0.49, 40.0, 0.50, 40.0, spot = 100_300.0), enabled = true)
        val tr = book.allTrades().single { it.variantId == mk.id }
        assertEquals(0.49, tr.signalAsk, 1e-9) // posts at the bid, not the ask
        assertEquals(40.0, tr.queueAhead!!, 0.0)
        assertTrue(tr.note.contains("queue ahead 40"))
        return book to tr.id
    }

    @Test
    fun makerEntryDoesNotFillOnTouchAlone() {
        val (book, id) = posted()
        book.onQuote(q(1_001_000L, 0.49, 40.0, 0.50, 40.0, spot = 100_300.0), enabled = false)
        book.onQuote(q(1_002_000L, 0.49, 30.0, 0.50, 40.0, spot = 100_300.0), enabled = false) // 10 shrink → 5 counted
        assertEquals(ScalpState.PENDING_ENTRY, book.allTrades().first { it.id == id }.state)
    }

    @Test
    fun makerEntryFillsOnTradeThrough() {
        val (book, id) = posted()
        book.onQuote(q(1_001_000L, 0.47, 40.0, 0.48, 40.0, spot = 100_300.0), enabled = false) // our 49¢ level wiped
        val tr = book.allTrades().first { it.id == id }
        assertEquals(ScalpState.OPEN, tr.state); assertEquals(0.49, tr.entryPrice!!, 1e-9); assertEquals(0.0, tr.entryFeeUsd, 0.0)
    }

    @Test
    fun makerEntryFillsOnceQueueAheadConsumed() {
        val (book, id) = posted()
        var size = 40.0; var at = 1_000_000L
        repeat(8) { at += 1_000; size -= 10.0; if (size < 0) size = 0.0
            book.onQuote(q(at, 0.49, size.coerceAtLeast(1.0), 0.50, 40.0, spot = 100_300.0), enabled = false) }
        // 39 shrink × 0.5 = 19.5 < 40 → still waiting (conservative)
        assertEquals(ScalpState.PENDING_ENTRY, book.allTrades().first { it.id == id }.state)
        repeat(3) { at += 1_000
            book.onQuote(q(at, 0.49, 60.0, 0.50, 40.0, spot = 100_300.0), enabled = false)
            book.onQuote(q(at + 500, 0.49, 1.0, 0.50, 40.0, spot = 100_300.0), enabled = false) }
        assertEquals(ScalpState.OPEN, book.allTrades().first { it.id == id }.state)
    }

    @Test
    fun adverseSelectionRecordedAt30And60s() {
        val (book, id) = posted()
        book.onQuote(q(1_001_000L, 0.47, 40.0, 0.48, 40.0, spot = 100_300.0), enabled = false)
        book.onQuote(q(1_031_000L, 0.45, 40.0, 0.46, 40.0, spot = 100_300.0), enabled = false)
        val tr = book.allTrades().first { it.id == id }
        assertEquals(-0.035, tr.adverse30!!, 1e-9)
        assertTrue(tr.note.contains("AS30 -3.5¢"))
        assertNull(tr.adverse60)
        assertTrue(com.dirk.kalshiodds.ui.ScalpData.makerLines(book.allTrades()).any { it.startsWith("CF-reprice maker") && it.contains("AS30 -3.5¢") })
    }

    @Test
    fun manualRestingOrderLogsQueueAheadAtPost() {
        val b = PaperOrderBook(idFactory = { "o${ids++}" }, nowMs = { 0L })
        val sq = PaperSideQuote.fromBook("YES", listOf(0.48 to 25.0, 0.47 to 10.0), listOf(0.50 to 5.0))
        val o = b.submit(t, "YES", "BUY", 0.47, 10, null, sq).order!!
        assertEquals(0, o.filledQty)
        assertEquals(35.0, o.queueAheadAtPost!!, 0.0)
    }

    @Test
    fun cfFinalMinuteAverageExplainer() {
        val tick = CfBenchmarks.Tick(
            indexId = CfBenchmarks.BTC, receivedAtMs = 0L, sourceTimeMs = 0L, value = 100_010.0, localReceivedAtMs = 0L,
            avg60s = CfBenchmarks.Average(100_000.0, 60, 0L, 60_000L),
            finalMinuteAverage = CfBenchmarks.Average(100_005.5, 42, 0L, 60_000L)
        )
        val line = CfBenchmarks.settlementExplainer(tick)!!
        assertTrue(line, line.startsWith("Settles on the 60 s average: 100,005.50 over 42 prints"))
        assertNull(CfBenchmarks.settlementExplainer(tick.copy(finalMinuteAverage = null)))
    }
}
