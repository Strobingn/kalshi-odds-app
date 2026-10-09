package com.dirk.kalshiodds.signal

import com.dirk.kalshiodds.prediction.PredictionLogEntry
import com.dirk.kalshiodds.signal.ml.EnsembleStack
import com.dirk.kalshiodds.signal.ml.GbmBooster
import com.dirk.kalshiodds.signal.ml.MlMath
import com.dirk.kalshiodds.signal.ml.PolicyEval
import com.dirk.kalshiodds.signal.ml.RegimeCalibrator
import com.dirk.kalshiodds.signal.ml.SequenceFeatures
import com.dirk.kalshiodds.signal.ml.SequenceFrame
import com.dirk.kalshiodds.signal.ml.TemporalCnn
import com.dirk.kalshiodds.signal.ml.UncertaintyGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnsembleStackTest {
    @Test
    fun singleMemberIsIdentity() {
        val r = EnsembleStack.blend(
            listOf(EnsembleStack.Member("mlp", 0.71)),
            EnsembleStack.identity()
        )
        assertEquals(listOf("mlp"), r.used)
        assertEquals(0.71, r.pYes, 1e-9)
    }

    @Test
    fun missingMembersDropOutAndRenormalize() {
        val r = EnsembleStack.blend(
            listOf(
                EnsembleStack.Member("mlp", 0.80),
                EnsembleStack.Member("cnn", 0.20, enabled = false),
                EnsembleStack.Member("gbm", 0.20)
            ),
            EnsembleStack.Weights(mlp = 0.55, cnn = 0.20, lstm = 0.10, gbm = 0.15)
        )
        val expect = (0.80 * 0.55 + 0.20 * 0.15) / (0.55 + 0.15)
        assertEquals(expect, r.pYes, 1e-9)
        assertEquals(listOf("mlp", "gbm"), r.used)
    }

    @Test
    fun emptyMembersStayNeutral() {
        val r = EnsembleStack.blend(emptyList(), EnsembleStack.identity())
        assertEquals(0.5, r.pYes, 1e-9)
        assertTrue(r.used.isEmpty())
    }

    @Test
    fun updateMovesWeightTowardAccurateMember() {
        val members = listOf(
            EnsembleStack.Member("mlp", 0.90),
            EnsembleStack.Member("gbm", 0.20)
        )
        var w = EnsembleStack.identity()
        repeat(12) {
            w = EnsembleStack.update(w, members, outcomeYes = false, lr = 0.20)
        }
        assertTrue(w.gbm > EnsembleStack.identity().gbm)
        assertTrue(w.mlp < EnsembleStack.identity().mlp)
        assertEquals(1.0, w.mlp + w.cnn + w.lstm + w.gbm, 1e-6)
    }
}

class UncertaintyGateTest {
    @Test
    fun ensembleVarianceBlocksWhenAboveThreshold() {
        val r = UncertaintyGate.evaluate(
            members = listOf(0.90, 0.20, 0.15),
            threshold = 0.10,
            enabled = true
        )
        assertEquals("ensemble", r.method)
        assertTrue(r.uncertainty > 0.10)
        assertFalse(r.passed)
    }

    @Test
    fun tightEnsemblePasses() {
        val r = UncertaintyGate.evaluate(
            members = listOf(0.61, 0.60, 0.62),
            threshold = 0.12,
            enabled = true
        )
        assertTrue(r.passed)
        assertTrue(r.uncertainty < 0.05)
    }

    @Test
    fun disabledGateAlwaysPasses() {
        val r = UncertaintyGate.evaluate(
            members = listOf(0.99, 0.01),
            threshold = 0.01,
            enabled = false
        )
        assertTrue(r.passed)
    }

    @Test
    fun mcDropoutUsedWhenOnlyOneMember() {
        val r = UncertaintyGate.evaluate(
            members = listOf(0.55),
            threshold = 0.05,
            enabled = true,
            mcSamples = listOf(0.80, 0.20, 0.75)
        )
        assertEquals("mc-dropout", r.method)
        assertTrue(r.uncertainty > 0.05)
        assertFalse(r.passed)
    }

    @Test
    fun singleProxyIsMildAndFinite() {
        val mid = UncertaintyGate.singleModelProxy(0.50)
        val extreme = UncertaintyGate.singleModelProxy(0.95)
        assertTrue(mid > extreme)
        assertTrue(mid in 0.02..0.18)
    }
}

class RegimeCalibratorTest {
    @Test
    fun coldBucketIsIdentity() {
        val state = RegimeCalibrator.fit(
            listOf(RegimeCalibrator.Sample("KXBTC15M", "EARLY", 0.8, true))
        )
        assertFalse(state.bucket("KXBTC15M", "EARLY").ready)
        assertEquals(0.72, RegimeCalibrator.apply(0.72, "KXBTC15M", "EARLY", state), 1e-9)
    }

    @Test
    fun plattPullsOverconfidentSeriesTowardBaseRate() {
        val samples = (0 until 16).map { i ->
            RegimeCalibrator.Sample("KXBTC15M", "EARLY", 0.90, i % 2 == 0)
        }
        val state = RegimeCalibrator.fit(samples)
        assertTrue(state.bucket("KXBTC15M", "EARLY").ready)
        val cal = RegimeCalibrator.apply(0.90, "KXBTC15M", "EARLY", state)
        assertTrue(cal < 0.90)
        assertTrue(cal > 0.35)
    }

    @Test
    fun seriesBucketsDoNotBleed() {
        val samples = (0 until 12).map { i ->
            RegimeCalibrator.Sample("KXBTC15M", "EARLY", 0.85, true)
        } + (0 until 12).map { i ->
            RegimeCalibrator.Sample("KXETH15M", "LATE", 0.85, false)
        }
        val state = RegimeCalibrator.fit(samples)
        val btc = RegimeCalibrator.apply(0.85, "KXBTC15M", "EARLY", state)
        val eth = RegimeCalibrator.apply(0.85, "KXETH15M", "LATE", state)
        assertTrue(btc > eth)
        assertFalse(state.bucket("KXSOL15M", "EARLY").ready)
    }

    @Test
    fun isotonicIsNonDecreasing() {
        val pairs = listOf(0.1 to 0.8, 0.3 to 0.2, 0.5 to 0.4, 0.8 to 0.9)
        val knots = RegimeCalibrator.isotonic(pairs)
        assertTrue(knots.size >= 2)
        for (i in 1 until knots.size) {
            assertTrue(knots[i].second + 1e-9 >= knots[i - 1].second)
        }
        val mid = RegimeCalibrator.applyIsotonic(0.4, knots.map { it.first }, knots.map { it.second })
        assertTrue(mid in 0.02..0.98)
    }
}

class PolicyEvalTest {
    @Test
    fun yesHitAtMidPaysOneMinusPrice() {
        val e = entry(side = "YES", mid = 0.20, outcome = "yes", pYes = 0.70, edge = 8.0, conf = 0.60)
        val t = PolicyEval.simulate(e, stakeUsd = 5.0)
        // contracts = floor(5 / 0.20) = 25; pnl = 25 * 0.80 = 20
        assertEquals(25, t.contracts)
        assertEquals(20.0, t.pnl, 1e-9)
        assertTrue(t.hit)
    }

    @Test
    fun noMissLosesStake() {
        val e = entry(side = "NO", mid = 0.80, outcome = "yes", pYes = 0.20, edge = -10.0, conf = 0.70)
        val t = PolicyEval.simulate(e, stakeUsd = 5.0)
        // NO price = 0.20; contracts = 25; miss → -5
        assertEquals(25, t.contracts)
        assertEquals(-5.0, t.pnl, 1e-9)
        assertFalse(t.hit)
    }

    @Test
    fun scorecardRoiAndBrier() {
        val rows = listOf(
            entry("YES", 0.20, "yes", 0.70, 8.0, 0.60),
            entry("YES", 0.20, "no", 0.70, 8.0, 0.60)
        )
        val card = PolicyEval.evaluate(rows, stakeUsd = 5.0, edgeThresholdPp = 5.0, minConfidence = 0.45)
        assertEquals(2, card.allAlerts.n)
        assertEquals(1, card.allAlerts.hits)
        assertEquals(0.5, card.allAlerts.hitRate!!, 1e-9)
        // +20 and -5 on 5+5 staked → pnl +15 / 10 = 1.5
        assertEquals(1.5, card.allAlerts.roi!!, 1e-9)
        val brier = ((0.70 - 1.0) * (0.70 - 1.0) + (0.70 - 0.0) * (0.70 - 0.0)) / 2.0
        assertEquals(brier, card.allAlerts.brier!!, 1e-9)
    }

    @Test
    fun belowThresholdIsNotAnAlert() {
        val rows = listOf(entry("YES", 0.50, "yes", 0.52, edge = 1.0, conf = 0.90))
        val card = PolicyEval.evaluate(rows, stakeUsd = 5.0, edgeThresholdPp = 5.0)
        assertEquals(0, card.allAlerts.n)
    }

    @Test
    fun wouldAlertFlagWinsOverInference() {
        val forced = entry("YES", 0.50, "yes", 0.51, edge = 0.5, conf = 0.20).copy(wouldAlert = true)
        assertTrue(PolicyEval.wouldAlert(forced, 5.0, 0.45))
    }

    private fun entry(
        side: String,
        mid: Double,
        outcome: String,
        pYes: Double,
        edge: Double,
        conf: Double
    ) = PredictionLogEntry(
        ticker = "KXBTC15M-T",
        series = "KXBTC15M",
        predictedYes = pYes,
        predictedNo = 1.0 - pYes,
        marketMid = mid,
        timestampMs = 1L,
        closeTimeMs = 2L,
        outcome = outcome,
        predictedSide = side,
        edgePp = edge,
        confidence = conf
    )
}

class SequenceAndGbmSmokeTest {
    @Test
    fun resamplePadsToFixedLength() {
        val now = 1_000_000L
        val raw = (0 until 5).map { i ->
            SequenceFrame(0.4f + i * 0.01f, 0.1f, 0.2f, 0.1f, 0f, now - 40_000L + i * 8_000L)
        }
        val t = SequenceFeatures.resample(raw, now)
        assertEquals(SequenceFeatures.FRAMES, t.size)
        assertEquals(SequenceFrame.CHANNELS, t[0].size)
        assertTrue(SequenceFeatures.populatedBins(raw, now) >= 3)
    }

    @Test
    fun cnnRespondsToRisingMids() {
        val now = 2_000_000L
        val rising = Array(30) { t ->
            floatArrayOf(0.30f + t * 0.01f, 0.1f, 0.3f, 0.2f, 0f)
        }
        val flat = Array(30) { floatArrayOf(0.50f, 0.1f, 0f, 0f, 0f) }
        val cnn = TemporalCnn.defaults()
        val r = cnn.encode(rising)
        val f = cnn.encode(flat)
        assertEquals(TemporalCnn.CONV_OUT, r.size)
        assertTrue(r[0] != f[0] || r.sum() != f.sum())
    }

    @Test
    fun defaultGbmMeanRevertsHighMid() {
        val high = GbmBooster.tabular(
            mid = 0.80, volume = 1000.0, tteFrac = 0.5, volatility = 0.02,
            momentum = 0.0, seriesId = 0.0, openInterest = 100.0,
            imbalance = 0.0, aggressor = 0.0, depthQuality = 0.4,
            leadLag = 0.0, spot = 0.0, cnnP = 0.50, microZ = 0.0, spread = 0.02
        )
        val low = high.copyOf().also { it[0] = 0.20f }
        val model = GbmBooster.defaultModel()
        assertTrue(GbmBooster.predictYes(low, model) > GbmBooster.predictYes(high, model))
        assertTrue(MlMath.clip01(0.99) <= 0.98)
    }
}
