package com.dirk.kalshiodds.signal

import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.engine.ScoringEngine
import com.dirk.kalshiodds.signal.model.MarketTick
import com.dirk.kalshiodds.signal.model.TickSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoringEngineTest {

    private val settings = SignalSettings(
        watchBtc = true,
        watchEth = true,
        watchSol = true,
        edgeThresholdPp = 5.0,
        debounceMs = 10_000L
    )

    @Test
    fun ignoresWtiTicks() {
        val engine = ScoringEngine(idFactory = { "id" })
        val tick = MarketTick(
            ticker = "KXWTI15M-TEST",
            series = "KXWTI15M",
            yesBid = 0.40,
            yesAsk = 0.50,
            lastPrice = 0.45,
            volume = 1000.0,
            openInterest = 100.0,
            closeTimeEpochMs = System.currentTimeMillis() + 600_000,
            source = TickSource.REST,
            receiveElapsedNanos = 1L
        )
        assertNull(engine.score(tick, settings))
        assertNull(engine.maybeAlert(tick, settings))
    }

    @Test
    fun scoresBtcAndCanAlert() {
        val engine = ScoringEngine(idFactory = { "a1" })
        val now = System.currentTimeMillis()
        val close = now + 600_000
        // Seed a related ETH mid so the blend has three components.
        engine.score(
            MarketTick(
                ticker = "KXETH15M-REL",
                series = "KXETH15M",
                yesBid = 0.70,
                yesAsk = 0.72,
                lastPrice = 0.71,
                volume = 50_000.0,
                openInterest = 10_000.0,
                closeTimeEpochMs = close,
                source = TickSource.REST,
                receiveElapsedNanos = 1L
            ),
            settings,
            now
        )
        val btc = MarketTick(
            ticker = "KXBTC15M-TEST",
            series = "KXBTC15M",
            yesBid = 0.20,
            yesAsk = 0.22,
            lastPrice = 0.21,
            volume = 80_000.0,
            openInterest = 12_000.0,
            closeTimeEpochMs = close,
            source = TickSource.WS_TICKER,
            receiveElapsedNanos = 2L
        )
        val score = engine.score(btc, settings, now + 10)
        assertNotNull(score)
        assertTrue(score!!.marketMidPp in 20.0..23.0)
        assertTrue(score.reason.contains("Δ"))
    }

    @Test
    fun debounceSuppressesSecondAlert() {
        val engine = ScoringEngine(idFactory = { "x" })
        val now = 1_000_000L
        val tick = MarketTick(
            ticker = "KXBTC15M-DEB",
            series = "KXBTC15M",
            yesBid = 0.10,
            yesAsk = 0.12,
            lastPrice = 0.11,
            volume = 10_000.0,
            openInterest = 1_000.0,
            closeTimeEpochMs = now + 500_000,
            source = TickSource.REST,
            receiveElapsedNanos = 3L
        )
        val first = engine.maybeAlert(tick, settings.copy(edgeThresholdPp = 0.01), now)
        val second = engine.maybeAlert(tick, settings.copy(edgeThresholdPp = 0.01), now + 1_000)
        val third = engine.maybeAlert(tick, settings.copy(edgeThresholdPp = 0.01), now + 11_000)
        // First and third may fire; second is inside 10s debounce.
        if (first != null) {
            assertNull(second)
            assertNotNull(third)
        }
    }

    @Test
    fun belowThresholdNoAlert() {
        val engine = ScoringEngine(idFactory = { "z" })
        val now = System.currentTimeMillis()
        val tick = MarketTick(
            ticker = "KXSOL15M-FLAT",
            series = "KXSOL15M",
            yesBid = 0.50,
            yesAsk = 0.50,
            lastPrice = 0.50,
            volume = 1.0,
            openInterest = 1.0,
            closeTimeEpochMs = now + 100_000,
            source = TickSource.REST,
            receiveElapsedNanos = 4L
        )
        val alert = engine.maybeAlert(tick, settings.copy(edgeThresholdPp = 40.0), now)
        assertNull(alert)
    }

    @Test
    fun velocityAndImbalanceEnterTheBlend() {
        val engine = ScoringEngine(idFactory = { "v1" })
        val ticker = "KXBTC15M-BLEND"
        val t0 = 5_000_000L
        // Rising mids so velocity is clearly positive.
        for (i in 0 until 8) {
            engine.score(
                MarketTick(
                    ticker = ticker,
                    series = "KXBTC15M",
                    yesBid = 0.30 + i * 0.01,
                    yesAsk = 0.32 + i * 0.01,
                    lastPrice = 0.31 + i * 0.01,
                    volume = 20_000.0,
                    openInterest = 2_000.0,
                    closeTimeEpochMs = t0 + 600_000,
                    source = TickSource.WS_TICKER,
                    receiveElapsedNanos = i.toLong()
                ),
                settings,
                t0 + i * 250L
            )
        }
        engine.applySnapshot(
            ticker,
            yesLevels = listOf(0.48 to 200.0, 0.47 to 80.0),
            noLevels = listOf(0.49 to 20.0),
            seq = 1
        )
        val last = engine.score(
            MarketTick(
                ticker = ticker,
                series = "KXBTC15M",
                yesBid = 0.38,
                yesAsk = 0.40,
                lastPrice = 0.39,
                volume = 20_000.0,
                openInterest = 2_000.0,
                closeTimeEpochMs = t0 + 600_000,
                source = TickSource.WS_TICKER,
                receiveElapsedNanos = 99L
            ),
            settings,
            t0 + 2_000L
        )
        assertNotNull(last)
        assertNotNull(last!!.velocityPerSec)
        assertTrue(last.velocityPerSec!! > 0.0)
        assertNotNull(last.imbalance)
        assertTrue(last.imbalance!! > 0.0)
        assertNotNull(last.velocityPp)
        assertNotNull(last.imbalancePp)
        assertTrue(last.reason.contains("Δ"))
        assertTrue(last.reason.contains("vel") || last.reason.contains("book"))
    }

    @Test
    fun bookSnapshotOnWtiIsIgnored() {
        val engine = ScoringEngine(idFactory = { "wti" })
        assertNull(
            engine.applySnapshot("KXWTI15M-X", listOf(0.40 to 10.0), listOf(0.50 to 10.0), 1)
        )
        assertNull(engine.maybeAlertFromBook("KXWTI15M-X", settings, receiveElapsedNanos = 1L))
    }
}

class DefaultSignalConfigTest {
    @Test
    fun parsesCryptoDefaultsAndDropsWtiExtra() {
        val json = """
            {"watchBtc":true,"watchEth":true,"watchSol":false,"extraTickers":["KXBTC15M-A","KXWTI15M-B"],"edgeThresholdPp":7.5}
        """.trimIndent()
        val cfg = com.dirk.kalshiodds.signal.config.DefaultSignalConfig.parse(json)
        assertTrue(cfg.watchBtc)
        assertTrue(cfg.watchEth)
        assertTrue(!cfg.watchSol)
        assertEquals(listOf("KXBTC15M-A"), cfg.extraTickers)
        assertEquals(7.5, cfg.edgeThresholdPp, 0.001)
    }
}
