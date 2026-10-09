package com.dirk.kalshiodds.signal

import com.dirk.kalshiodds.signal.config.DefaultSignalConfig
import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.engine.RegimeTag
import com.dirk.kalshiodds.signal.engine.TteRegime
import com.dirk.kalshiodds.signal.ml.AnomalyDetector
import com.dirk.kalshiodds.signal.ml.BayesianMmShadow
import com.dirk.kalshiodds.signal.ml.ConformalSets
import com.dirk.kalshiodds.signal.ml.EnsembleStack
import com.dirk.kalshiodds.signal.ml.ExtendedAiRuntime
import com.dirk.kalshiodds.signal.ml.MetaLabeler
import com.dirk.kalshiodds.signal.ml.NewsPulse
import com.dirk.kalshiodds.signal.ml.NewsPulseCache
import com.dirk.kalshiodds.signal.ml.PathSimulator
import com.dirk.kalshiodds.signal.ml.RegimeClassifier
import com.dirk.kalshiodds.signal.ml.RivalFlowCluster
import com.dirk.kalshiodds.signal.ml.RlSizer
import com.dirk.kalshiodds.signal.ml.SessionTag
import com.dirk.kalshiodds.signal.ml.SurvivalModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class RegimeClassifierTest {
    @Test
    fun weekendAndSessionBuckets() {
        val utc = TimeZone.getTimeZone("UTC")
        val sat = cal(2026, Calendar.SEPTEMBER, 26, 12, utc)
        assertEquals(SessionTag.WEEKEND, RegimeClassifier.sessionOf(sat))
        val asia = cal(2026, Calendar.SEPTEMBER, 23, 3, utc)
        assertEquals(SessionTag.ASIA, RegimeClassifier.sessionOf(asia))
        val eu = cal(2026, Calendar.SEPTEMBER, 23, 10, utc)
        assertEquals(SessionTag.EUROPE, RegimeClassifier.sessionOf(eu))
        val us = cal(2026, Calendar.SEPTEMBER, 23, 16, utc)
        assertEquals(SessionTag.US, RegimeClassifier.sessionOf(us))
    }

    @Test
    fun newsShockAndStackReweight() {
        val now = cal(2026, Calendar.SEPTEMBER, 23, 16, TimeZone.getTimeZone("UTC"))
        val shock = RegimeClassifier.classify(
            micro = RegimeTag.QUIET,
            vol = 0.4,
            velocityPerSec = 0.0,
            netPp = 1.0,
            spotAbs = 0.05,
            nowMs = now
        )
        assertTrue(shock.scores.values.sum() in 0.99..1.01)
        assertTrue(shock.scores["news"]!! > 0.20)
        assertTrue(shock.newsShock)
        val base = EnsembleStack.Weights(mlp = 0.55, cnn = 0.20, lstm = 0.10, gbm = 0.15)
        val scaled = RegimeClassifier.scaleStack(base, shock)
        assertEquals(1.0, scaled.mlp + scaled.cnn + scaled.lstm + scaled.gbm, 1e-6)
        assertTrue(scaled.cnn < base.cnn)
        assertTrue(scaled.sampleCount == base.sampleCount)
    }

    private fun cal(y: Int, month: Int, day: Int, hour: Int, zone: TimeZone): Long {
        val c = Calendar.getInstance(zone)
        c.set(y, month, day, hour, 0, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }
}

class AnomalyDetectorTest {
    @Test
    fun offIsIdle() {
        val r = AnomalyDetector.evaluate(0.9, 0.9, 1.0, 40.0, 0.2, 8.0, enabled = false)
        assertFalse(r.block)
        assertEquals("anomaly off", r.note)
    }

    @Test
    fun cancelStormAndFakeDepthBlock() {
        val r = AnomalyDetector.evaluate(
            cancelSpike = 0.95,
            quotePull = 0.80,
            depthNear = 1.0,
            depthFar = 20.0,
            spread = 0.15,
            flickerPerSec = 6.0,
            enabled = true
        )
        assertTrue(r.kinds.contains("cancel-storm"))
        assertTrue(r.kinds.contains("fake-depth"))
        assertTrue(r.kinds.contains("quote-stuffing"))
        assertTrue(r.block)
        assertTrue(r.score >= 0.72)
    }

    @Test
    fun cleanBookDoesNotBlock() {
        val r = AnomalyDetector.evaluate(0.1, 0.1, 40.0, 50.0, 0.02, 0.2, enabled = true)
        assertFalse(r.block)
        assertEquals("book clean", r.note)
    }
}

class SurvivalModelTest {
    @Test
    fun lateWindowTrustsMid() {
        val late = SurvivalModel.predict(0.82, 0.05, 0.0, 0.0, 0.0, 0.0)
        val early = SurvivalModel.predict(0.82, 0.95, -0.20, -0.6, -0.02, -0.02)
        assertTrue(late.pYes > 0.70)
        assertTrue(late.pYes > early.pYes)
        assertTrue(late.hazard >= 0.0)
    }
}

class ConformalSetsTest {
    @Test
    fun coldNeverAmbiguous() {
        val r = ConformalSets.predict(0.51, ConformalSets.State())
        assertFalse(r.ready)
        assertFalse(r.ambiguous)
        assertEquals(setOf("YES"), r.set)
    }

    @Test
    fun noisyBagIsAmbiguousAroundHalf() {
        val pairs = (0 until 20).map { 0.51 to (it % 2 == 0) }
        val state = ConformalSets.fit(pairs)
        assertTrue(state.ready)
        val r = ConformalSets.predict(0.51, state)
        assertTrue(r.ready)
        assertTrue(r.ambiguous)
        assertEquals(setOf("NO", "YES"), r.set)
    }

    @Test
    fun quantileIsSplitConformalIndex() {
        val s = (1..20).map { it / 40.0 }
        val q = ConformalSets.quantile(s, 0.10)
        assertTrue(q in 0.02..0.50)
    }
}

class MetaLabelerTest {
    @Test
    fun coldAlwaysTakes() {
        val m = MetaLabeler()
        val r = m.predict(0.4, 0.30, 0.25, 0.12, 0.80)
        assertTrue(r.take)
        assertFalse(r.ready)
    }

    @Test
    fun learnsToSkipMisses() {
        val m = MetaLabeler()
        repeat(12) {
            m.update(0.4, 0.30, 0.25, 0.12, 0.80, primaryHit = false)
        }
        val r = m.predict(0.4, 0.30, 0.25, 0.12, 0.80)
        assertTrue(r.ready)
        assertFalse(r.take)
    }
}

class PathSimulatorTest {
    @Test
    fun probabilitiesAreInUnitInterval() {
        val r = PathSimulator.simulate(
            mid = 0.70,
            fairYes = 0.80,
            tteSeconds = 300,
            vol = 0.03,
            velocityPerSec = 0.0,
            regime = RegimeTag.QUIET,
            seed = 17
        )
        assertEquals(16, r.paths)
        assertTrue(r.pSurvive in 0.0..1.0)
        assertTrue(r.pYesExpiry in 0.0..1.0)
        assertTrue(r.pYesExpiry > 0.5)
    }
}

class RlSizerTest {
    @Test
    fun coldAdviceIsConfiguredStakeAndClipped() {
        val rl = RlSizer()
        val a = rl.suggest(8.0, 0.70, 0.05, 0.5, 5.0)
        assertFalse(a.ready)
        assertTrue(a.stakeUsd in 1.0..10.0)
        assertEquals(10.0, RlSizer.clipStake(100.0), 1e-9)
        assertEquals(1.0, RlSizer.clipStake(0.10), 1e-9)
        assertTrue(RlSizer.rewardFromSettlement(true, 10.0) > 0.0)
        assertTrue(RlSizer.rewardFromSettlement(false, 10.0) < 0.0)
    }

    @Test
    fun reinforceMovesPolicy() {
        val rl = RlSizer()
        repeat(10) {
            rl.update(10.0, 0.80, 0.04, 0.4, actionIndex = 3, reward = 1.5)
        }
        assertTrue(rl.sampleCount >= 8)
        val a = rl.suggest(10.0, 0.80, 0.04, 0.4, 5.0)
        assertTrue(a.ready)
        assertTrue(a.stakeUsd <= 25.0)
    }
}

class NewsPulseTest {
    @Test
    fun lexiconSplitsBullAndBear() {
        val snap = NewsPulse.scoreHeadlines(
            listOf("Bitcoin ETF approval rally", "Solana hack crash dump"),
            nowMs = 1L
        )
        assertTrue(snap.btc > 0.0)
        assertTrue(snap.sol < 0.0)
        assertEquals(2, snap.headlineCount)
        assertTrue(NewsPulse.tiltPp(50.0, 0.5) > 50.0)
    }

    @Test
    fun rssParserDropsChannelTitle() {
        val xml = """
            <rss><channel>
              <title>CoinDesk</title>
              <item><title><![CDATA[Bitcoin ETF inflow]]></title></item>
              <item><title>Ethereum rally</title></item>
            </channel></rss>
        """.trimIndent()
        val titles = NewsPulseCache.parseTitles(xml)
        assertEquals(listOf("Bitcoin ETF inflow", "Ethereum rally"), titles)
    }
}

class RivalFlowAndMmTest {
    @Test
    fun smartLikeFlowBoosts() {
        val c = RivalFlowCluster()
        var last = c.observe(0.40, 0.50, 0.60)
        repeat(4) { last = c.observe(0.40, 0.50, 0.60) }
        assertTrue(last.boost >= 0.0)
        assertNotNull(last.cluster)
    }

    @Test
    fun mmShadowMovesWithInventory() {
        val mm = BayesianMmShadow()
        mm.update("T", 0.50, 0.80, 0.0, 0.90)
        val b = mm.update("T", 0.50, 0.80, 0.0, 0.90)
        assertTrue(b.shadowYes < 0.50)
        assertTrue(b.inventory > 0.0)
    }
}

class ExtendedAiRuntimeTest {
    @Test
    fun masterOffIsIdle() {
        val rt = ExtendedAiRuntime()
        val out = rt.evaluate(sampleInput(), SignalSettings(extendedAiEnabled = false), EnsembleStack.identity())
        assertEquals("extended AI off", out.note)
        assertEquals(null, out.fairBlendYes)
        assertEquals(null, out.blockReason)
    }

    @Test
    fun votesOnlyAfterEnoughTicks() {
        val rt = ExtendedAiRuntime()
        val on = SignalSettings(extendedAiEnabled = true)
        val cold = rt.evaluate(
            sampleInput(nTicks = 2),
            on,
            EnsembleStack.identity()
        )
        assertEquals(null, cold.fairBlendYes)
        val warm = rt.evaluate(
            sampleInput(nTicks = 10),
            on,
            EnsembleStack.identity()
        )
        assertNotNull(warm.survival)
        assertNotNull(warm.path)
        assertNotNull(warm.fairBlendYes)
        assertTrue(warm.note.contains("surv") || warm.note.contains("MC"))
    }

    private fun sampleInput(nTicks: Int = 10) = ExtendedAiRuntime.Input(
        ticker = "KXBTC15M-T",
        series = "KXBTC15M",
        mid = 0.45,
        fairYes = 0.58,
        edgePp = 8.0,
        confidence = 0.62,
        uncertainty = 0.06,
        tteFrac = 0.6,
        tte = TteRegime.EARLY,
        tteSeconds = 400L,
        vol = 0.03,
        momentum = 0.04,
        velocityPerSec = 0.004,
        imbalance = 0.2,
        aggressor = 0.3,
        sizeNorm = 0.4,
        midFollow = 0.3,
        spread = 0.02,
        depthNear = 40.0,
        depthFar = 60.0,
        depthQuality = 0.5,
        cancelSpike = 0.1,
        quotePull = 0.1,
        bestBid = 0.44,
        bestAsk = 0.46,
        spot = 0.001,
        micro = RegimeTag.TREND,
        nowMs = 1_725_000_000_000L,
        nTicks = nTicks,
        configuredStake = 5.0
    )
}

class DefaultExtendedConfigTest {
    @Test
    fun parsesExtendedFlags() {
        val cfg = DefaultSignalConfig.parse(
            """{"extendedAiEnabled":false,"pathSimEnabled":false,"rlSizerEnabled":true}"""
        )
        assertFalse(cfg.extendedAiEnabled)
        assertFalse(cfg.pathSimEnabled)
        assertTrue(cfg.rlSizerEnabled)
        assertTrue(cfg.conformalEnabled)
        assertTrue(cfg.metaLabelEnabled)
    }
}
