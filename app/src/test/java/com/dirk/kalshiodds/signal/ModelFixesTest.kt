package com.dirk.kalshiodds.signal

import com.dirk.kalshiodds.prediction.PredictionLogEntry
import com.dirk.kalshiodds.signal.engine.DirectionSanity
import com.dirk.kalshiodds.signal.external.AssetSpotFeatures
import com.dirk.kalshiodds.signal.external.SpotBars
import com.dirk.kalshiodds.signal.external.SpotStream
import com.dirk.kalshiodds.signal.fair.DigitalOptionFairValue
import com.dirk.kalshiodds.signal.feedback.Calibrator
import com.dirk.kalshiodds.signal.sizing.NetExpectedValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

/**
 * 2026-09-27 ML review fixes. Numbers marked "Python" come from
 * tools/backtest/pipeline.py so the backtest port and the app agree.
 */
class ModelFixesTest {

    private val closes = (0 until 70).map { i -> 100.0 * (1 + 0.001 * sin(i * 0.7) + 0.0003 * i) }

    // --- σ / spot ----------------------------------------------------------------

    @Test
    fun sigmaEwmaMatchesPython() {
        assertEquals(0.39297791001202576, DigitalOptionFairValue.sigmaFromCloses(closes)!!, 1e-12)
        assertNull(DigitalOptionFairValue.sigmaFromCloses(closes.take(4)))
    }

    @Test
    fun barStdMatchesPython() {
        assertEquals(0.0005106836365757824, SpotBars.barStd(closes.takeLast(17))!!, 1e-15)
    }

    @Test
    fun coinbaseLiveTickerBeatsLaggingCandleAndReturnsUseExactHorizons() {
        val now = 1_790_000_000_000L - (1_790_000_000_000L % 60_000L) + 20_000L // 20 s into a minute
        // Completed bars ending at now−20s, now−80s, … plus the in-progress bar.
        val bars = (0 until 70).map { k ->
            SpotBars.Bar(startMs = now - 20_000L - 60_000L * (k + 1), close = 100.0 + k * 0.1)
        } + SpotBars.Bar(startMs = now - 20_000L, close = 999.0)
        val f = SpotBars.features("BTC", "coinbase", livePrice = 101.0, bars = bars, nowMs = now)!!
        assertEquals(101.0, f.lastPrice!!, 0.0) // ticker, not the lagging/in-progress candle
        // Price 60 s ago = the bar that had closed by now−60 s = the one ending at now−80 s (k=1).
        assertEquals(101.0 / 100.1 - 1.0, f.spotReturn1m!!, 1e-12)
        assertEquals(101.0 / 100.5 - 1.0, f.spotReturn5m!!, 1e-12)
        assertTrue(f.closes.none { it == 999.0 }) // in-progress bar excluded from σ
        assertNotNull(f.sigmaAnnual)
    }

    @Test
    fun streamOverlaysLivePriceAndExactReturns() {
        var now = 1_790_000_000_000L
        val stream = SpotStream(clock = { now }, url = "ws://127.0.0.1:1/never")
        fun tick(px: Double) = stream.ingest("""{"type":"ticker","product_id":"BTC-USD","price":"$px"}""", now)
        tick(100.0)
        now += 240_000L
        tick(100.2)
        now += 60_000L
        tick(100.5)
        val base = AssetSpotFeatures(asset = "BTC", lastPrice = 99.0, spotReturn1m = 0.9, spotReturn5m = 0.9)
        val out = stream.overlay(base, now)
        assertEquals(100.5, out.lastPrice!!, 0.0)
        assertEquals(100.5 / 100.2 - 1.0, out.spotReturn1m!!, 1e-12)
        assertEquals(100.5 / 100.0 - 1.0, out.spotReturn5m!!, 1e-12)
        assertEquals("coinbase-ws", out.source)
        // Stale stream leaves the REST snapshot alone.
        assertEquals(99.0, stream.overlay(base, now + SpotStream.STALE_MS + 1).lastPrice!!, 0.0)
        stream.stop()
    }

    // --- side at the ask -----------------------------------------------------------

    @Test
    fun bestSideUsesRealAsksMatchesPython() {
        // $5 ticket, same as the backtest (the app default is $10).
        val r = NetExpectedValue.bestSide(fairYes = 0.62, yesAsk = 0.55, noAsk = 0.47, mid = 0.54, spreadDollars = 0.02, stakeUsd = 5.0)
        assertEquals("YES", r.side)
        assertEquals(0.052222222222222156, r.netEv, 1e-12)
        val no = NetExpectedValue.atAsk(0.62, "NO", 0.47, 0.54, 0.02, stakeUsd = 5.0)
        assertEquals(-0.10800000000000003, no.netEv, 1e-12)
    }

    @Test
    fun favoritePricedRightHasNoPositiveSideAndKeepsItsLean() {
        // 70% favorite at a 71¢ ask / 31¢ NO ask: neither side clears ask + fee.
        val best = NetExpectedValue.bestSide(fairYes = 0.70, yesAsk = 0.71, noAsk = 0.31, mid = 0.70, spreadDollars = 0.02)
        assertTrue(best.netEv < 0.0)
        val pick = NetExpectedValue.pick(fairYes = 0.70, yesAsk = 0.71, noAsk = 0.31, mid = 0.70, spreadDollars = 0.02)
        assertEquals("YES", pick.side)
        assertTrue(pick.netEv < 0.0)
    }

    @Test
    fun cheapSideWithRealEdgeIsPicked() {
        // Model 45% YES but YES asks 30¢: buy YES even though NO is the favorite.
        val pick = NetExpectedValue.pick(fairYes = 0.45, yesAsk = 0.30, noAsk = 0.72, mid = 0.29, spreadDollars = 0.02)
        assertEquals("YES", pick.side)
        assertTrue(pick.netEv > 0.10)
    }

    // --- blend nudges in log-odds ------------------------------------------------

    @Test
    fun tailScalingMatchesPythonAndKillsFakeLongshotEdge() {
        val s = com.dirk.kalshiodds.signal.engine.ScoringEngine
        assertEquals(58.0, s.tailScaledFairPp(58.0, 0.50), 1e-12) // full size at 50¢
        assertEquals(6.52, s.tailScaledFairPp(13.0, 0.05), 1e-9) // was 13% at a 5¢ market
        assertEquals(0.5398000000000001, s.tailScaledFairPp(2.5, 0.005), 1e-12)
        assertEquals(90.36519999999999, s.tailScaledFairPp(40.0, 0.97), 1e-9)
        // 5¢ market, 6¢ ask: raw 13% showed ~+6pp net edge; scaled it is under 1pp,
        // far below the 5pp alert threshold.
        val raw = NetExpectedValue.atAsk(0.13, "YES", 0.06, 0.05, 0.02)
        val ev = NetExpectedValue.atAsk(s.tailScaledFairPp(13.0, 0.05) / 100.0, "YES", 0.06, 0.05, 0.02)
        assertTrue(raw.netEdgePp > 5.0)
        assertTrue(ev.netEdgePp < 1.0)
    }

    // --- DirectionSanity ---------------------------------------------------------

    @Test
    fun lockUsesDigitalAndNoLongerOnlyPushesUp() {
        // SOL-like: spot 0.2% over strike with 14 min left, σ 75% → Φ(d2) ≈ 70%.
        val digital = DigitalOptionFairValue.pFinishAbove(1002.0, 1000.0, 840.0, 0.75)!! * 100.0
        assertEquals(70.0, digital, 1.5)
        val r = DirectionSanity.apply(
            spotUsd = 1002.0, strikeUsd = 1000.0, spotReturn = 0.001,
            fairPp = 60.0, predictedSide = "YES", digitalPp = digital
        )
        assertTrue(r.applied)
        assertEquals(0.5 * 60.0 + 0.5 * digital, r.fairPp, 1e-9)
        assertTrue("old tanh curve said 87%", r.fairPp < 70.0)
    }

    // --- calibration -------------------------------------------------------------

    private fun entry(raw: Double?, early: Double?, bucket: String, outcome: String) = PredictionLogEntry(
        ticker = "KXBTC15M-T${raw}-$outcome-${early}",
        series = "KXBTC15M",
        predictedYes = 0.99, // final displayed fair — must be ignored by the fit
        predictedNo = 0.01,
        marketMid = 0.5,
        timestampMs = 0L,
        closeTimeMs = 0L,
        outcome = outcome,
        tteBucket = bucket,
        rawFairYes = raw,
        rawFairEarly = early
    )

    @Test
    fun calibratorFitsRawPerBucketAndLeavesColdBucketsAlone() {
        // LATE: raw 0.8 but only half win → temperature > 1 (shrink toward 0.5).
        val entries = (0 until 40).map { i -> entry(raw = 0.8, early = null, bucket = "LATE", outcome = if (i % 2 == 0) "yes" else "no") }
        val state = Calibrator.fitEntries(entries)
        assertTrue(state.byTte["LATE"]!!.ready)
        assertTrue(Calibrator.apply(0.8, state, "LATE") < 0.8)
        // EARLY has no samples → identity; late-window fit is never applied early.
        assertEquals(0.8, Calibrator.apply(0.8, state, "EARLY"), 1e-12)
    }

    @Test
    fun calibratorIgnoresLegacyEntriesWithoutRaw() {
        val legacy = (0 until 50).map { entry(raw = null, early = null, bucket = "LATE", outcome = "no") }
        val state = Calibrator.fitEntries(legacy)
        assertTrue(!state.ready)
        assertEquals(0.7, Calibrator.apply(0.7, state, "LATE"), 1e-12)
    }
}
