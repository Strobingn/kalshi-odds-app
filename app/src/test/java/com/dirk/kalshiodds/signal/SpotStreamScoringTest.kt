package com.dirk.kalshiodds.signal

import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.engine.ScoringEngine
import com.dirk.kalshiodds.signal.external.AssetSpotFeatures
import com.dirk.kalshiodds.signal.external.ExternalSnapshot
import com.dirk.kalshiodds.signal.external.SpotStreamBook
import com.dirk.kalshiodds.signal.ml.HeavyMlGuard
import com.dirk.kalshiodds.signal.model.MarketTick
import com.dirk.kalshiodds.signal.model.TickSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ScoringEngine reads streamed spot through SpotMerge: a fresh stream
 * decides spot-vs-strike and σ; a stale one falls back to the REST snapshot.
 */
class SpotStreamScoringTest {

    private val ticker = "KXBTC15M-SPOT"
    private val now = 1_700_000_000_000L
    private val close = now + 600_000L

    @Before
    fun setUp() {
        HeavyMlGuard.reset()
    }

    @After
    fun tearDown() {
        HeavyMlGuard.reset()
    }

    /** REST is 25 s old: below the $100k strike and falling, no σ. */
    private fun engineWithStaleRest(): ScoringEngine {
        val engine = ScoringEngine(idFactory = { "spot" })
        engine.rememberMeta(ticker, close, 20_000.0, 2_000.0, floorStrike = 100_000.0)
        engine.external = ExternalSnapshot(
            btc = AssetSpotFeatures(
                asset = "BTC",
                lastPrice = 99_700.0,
                spotReturn1m = -0.003,
                source = "binance",
                fetchedAtMs = now - 25_000L
            )
        )
        return engine
    }

    /** Stream: 6 minutes of prints climbing through the strike to $100,300. */
    private fun risingStream(): SpotStreamBook {
        val book = SpotStreamBook()
        for (s in 360 downTo 0) {
            val wobble = if (s % 2 == 0) 3.0 else -3.0
            book.onPrint("BTC", 100_300.0 - s * 1.5 + wobble, now - s * 1_000L)
        }
        return book
    }

    @Test
    fun freshStreamSpotDecidesTheSide() {
        val engine = engineWithStaleRest()
        engine.spotStream = risingStream()
        val score = engine.score(tick(), settings(), nowMs = now + 1_000L)
        assertNotNull(score)
        assertEquals(100_303.0, score!!.spotUsd!!, 1e-9)
        assertEquals("YES", score.predictedSide)
        assertTrue(score.directionalLock)
        assertTrue(score.spotVsTargetUsd!! > 250.0)
        assertTrue(score.spotLabel!!, score.spotLabel!!.contains("coinbase-ws"))
        // σ comes from the stream's EWMA, so the digital fair exists even
        // though REST had no realized vol.
        assertNotNull(score.digitalFairPp)
        assertTrue(score.digitalFairPp!! > 50.0)
        assertEquals(100_303.0, engine.book.lastSpot(ticker)!!, 1e-9)
    }

    @Test
    fun staleStreamFallsBackToRest() {
        val engine = engineWithStaleRest()
        engine.spotStream = risingStream()
        val score = engine.score(tick(), settings(), nowMs = now + 6_000L)
        assertNotNull(score)
        assertEquals(99_700.0, score!!.spotUsd!!, 1e-9)
        assertEquals("NO", score.predictedSide)
        assertTrue(score.directionalLock)
        assertNull("REST has no σ", score.digitalFairPp)
    }

    @Test
    fun noStreamIsTheOldBehaviour() {
        val engine = engineWithStaleRest()
        val score = engine.score(tick(), settings(), nowMs = now)
        assertEquals(99_700.0, score!!.spotUsd!!, 1e-9)
        assertEquals("NO", score.predictedSide)
    }

    private fun settings() = SignalSettings(
        edgeThresholdPp = 5.0,
        debounceMs = 10_000L,
        minConfidence = 0.0,
        maxSpreadCents = 50.0,
        hideWeakOpportunities = false,
        heavyMlEnabled = false,
        extendedAiEnabled = false
    )

    private fun tick() = MarketTick(
        ticker = ticker,
        series = "KXBTC15M",
        yesBid = 0.48,
        yesAsk = 0.52,
        lastPrice = 0.50,
        volume = 20_000.0,
        openInterest = 2_000.0,
        closeTimeEpochMs = close,
        source = TickSource.WS_TICKER,
        receiveElapsedNanos = 1L,
        floorStrike = 100_000.0
    )
}
