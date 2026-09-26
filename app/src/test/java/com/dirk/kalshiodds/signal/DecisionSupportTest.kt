package com.dirk.kalshiodds.signal

import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.prediction.PredictionLogEntry
import com.dirk.kalshiodds.signal.checklist.PreTradeChecklist
import com.dirk.kalshiodds.signal.config.DefaultSignalConfig
import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.external.AssetSpotFeatures
import com.dirk.kalshiodds.signal.external.SpotFeatureMath
import com.dirk.kalshiodds.signal.feedback.Allowlist
import com.dirk.kalshiodds.signal.feedback.Guardrails
import com.dirk.kalshiodds.signal.feedback.OnlineAdapter
import com.dirk.kalshiodds.signal.sizing.NetExpectedValue
import com.dirk.kalshiodds.signal.sizing.PositionSizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetExpectedValueTest {
    @Test
    fun feeMatchesKalshiStyleFormula() {
        // $5 @ 50¢: C=10, model 0.175, order fee $0.18, amortized $0.018
        assertEquals(0.018, NetExpectedValue.feePerContract(0.50, 0.07), 1e-9)
        assertEquals(0.0, NetExpectedValue.feePerContract(0.50, 0.0), 1e-9)
        // One-contract schedule number is still $0.02
        assertEquals(0.02, com.dirk.kalshiodds.signal.trade.KalshiFee.total(1, 0.50), 1e-9)
    }

    @Test
    fun netEvSubtractsFeeAndHalfSpread() {
        val r = NetExpectedValue.compute(
            fairYes = 0.60,
            mid = 0.50,
            spreadDollars = 0.04,
            feeRate = 0.07,
            preferSide = "YES"
        )
        // paid = 0.50 + 0.02 = 0.52; C = floor(5/0.52) = 9
        // model = 0.07×9×0.52×0.48 = 0.157248 → debit ceil_cent(4.837248) = 4.84
        // order fee $0.16, amortized 0.16/9
        val fee = 0.16 / 9.0
        assertEquals("YES", r.side)
        assertEquals(0.02, r.halfSpread, 1e-9)
        assertEquals(fee, r.feePerContract, 1e-9)
        assertEquals(0.60 - 0.52 - fee, r.netEv, 1e-9)
        assertTrue(r.netEdgePp < r.rawEdgePp)
        assertTrue(r.netEv > 0.0)
        assertTrue(NetExpectedValue.preferNetForFilter(r.netEdgePp, r.rawEdgePp))
    }

    @Test
    fun noSideWhenFairBelowMid() {
        val r = NetExpectedValue.compute(0.40, 0.50, 0.02, 0.07)
        assertEquals("NO", r.side)
    }

    @Test
    fun wideSpreadCanWipeSmallEdge() {
        val r = NetExpectedValue.compute(0.53, 0.50, 0.10, 0.07, "YES")
        assertTrue(r.netEv < 0.0)
    }
}

class PositionSizerTest {
    @Test
    fun fullKellyBinaryFormula() {
        // p=0.60, c=0.50 → (0.10)/0.50 = 0.20
        assertEquals(0.20, PositionSizer.fullKelly(0.60, 0.50), 1e-9)
        assertTrue(PositionSizer.fullKelly(0.40, 0.50) < 0.0)
    }

    @Test
    fun quarterKellyCappedAndAdvisory() {
        val advice = PositionSizer.suggest(
            fairSide = 0.60,
            contractPrice = 0.50,
            bankrollUsd = 1_000.0,
            mode = PositionSizer.Mode.KELLY,
            kellyFraction = 0.25,
            maxFraction = 0.05,
            netEvPositive = true
        )
        // full Kelly 0.20 * quarter = 0.05 → 5% cap → ~$50 / $0.50 ≈ 100 contracts
        assertTrue("expected ~100 contracts, got ${advice.contracts}", advice.contracts in 99..100)
        assertEquals(advice.contracts * 0.50, advice.dollarsAtRisk, 1e-6)
        assertTrue(advice.reason.contains("contracts max"))
    }

    @Test
    fun liquidityCapsSize() {
        val advice = PositionSizer.suggest(
            fairSide = 0.70,
            contractPrice = 0.40,
            bankrollUsd = 10_000.0,
            mode = PositionSizer.Mode.KELLY,
            kellyFraction = 1.0,
            maxFraction = 0.25,
            liquidity = 80.0,
            netEvPositive = true
        )
        // 10% of 80 volume = 8 contracts
        assertEquals(8, advice.contracts)
        assertTrue(advice.reason.contains("liquidity"))
    }

    @Test
    fun zeroWhenNoNetEdge() {
        val advice = PositionSizer.suggest(
            fairSide = 0.60,
            contractPrice = 0.50,
            bankrollUsd = 1_000.0,
            netEvPositive = false
        )
        assertEquals(0, advice.contracts)
        assertTrue(advice.reason.contains("no edge"))
    }

    @Test
    fun fixedFractionUsesSettingNotKelly() {
        val advice = PositionSizer.suggest(
            fairSide = 0.90,
            contractPrice = 0.20,
            bankrollUsd = 1_000.0,
            mode = PositionSizer.Mode.FIXED_FRACTION,
            fixedFraction = 0.02,
            maxFraction = 0.05,
            netEvPositive = true
        )
        // 2% of 1000 = $20 / 0.20 = 100
        assertEquals(100, advice.contracts)
    }
}

class AllowlistTest {
    @Test
    fun coldStartNeverMutes() {
        val state = Allowlist.evaluate(emptyList(), floor = 0.40, minSamples = 8)
        assertFalse(state.ready)
        assertTrue(state.mutedSeries.isEmpty())
        assertFalse(state.isMuted("KXBTC15M", "TREND", "EARLY"))
    }

    @Test
    fun mutesSeriesBelowFloorWithEnoughSamples() {
        val now = 2_000_000_000_000L
        val rows = (0 until 10).map { i ->
            settled(
                ticker = "KXBTC15M-$i",
                series = "KXBTC15M",
                hit = i < 2,
                ts = now,
                regime = "TREND",
                tte = "EARLY"
            )
        }
        val state = Allowlist.evaluate(rows, nowMs = now, floor = 0.40, minSamples = 8)
        assertTrue(state.isSeriesMuted("KXBTC15M"))
        assertTrue(state.muteReason("KXBTC15M", "TREND", "EARLY")!!.contains("series"))
    }

    @Test
    fun doesNotMuteAboveFloor() {
        val now = 2_000_000_000_000L
        val rows = (0 until 10).map { i ->
            settled("KXETH15M-$i", "KXETH15M", hit = i < 8, ts = now, regime = "QUIET", tte = "LATE")
        }
        val state = Allowlist.evaluate(rows, nowMs = now, floor = 0.40, minSamples = 8)
        assertFalse(state.isSeriesMuted("KXETH15M"))
        assertFalse(state.isRegimeMuted("QUIET"))
    }

    @Test
    fun mutesWeakRegimeIndependently() {
        val now = 2_000_000_000_000L
        val chop = (0 until 8).map { i ->
            settled("KXBTC15M-c$i", "KXBTC15M", hit = false, ts = now, regime = "CHOP", tte = "EARLY")
        }
        val trend = (0 until 8).map { i ->
            settled("KXETH15M-t$i", "KXETH15M", hit = true, ts = now, regime = "TREND", tte = "EARLY")
        }
        val state = Allowlist.evaluate(chop + trend, nowMs = now, floor = 0.40, minSamples = 8)
        assertTrue(state.isRegimeMuted("CHOP"))
        assertFalse(state.isRegimeMuted("TREND"))
    }
}

class GuardrailsTest {
    @Test
    fun pausesAfterNWrongInARow() {
        val now = 1_000L
        var state = Guardrails.identity()
        val t = Guardrails.Thresholds(streakN = 3, drawdownUsd = 1_000.0)
        val rows = (1..3).map { i ->
            settled("T$i", "KXBTC15M", hit = false, ts = now + i, mid = 0.50)
        }
        state = Guardrails.update(state, rows, t, now + 10)
        assertTrue(state.paused)
        assertTrue(state.pauseReason!!.contains("streak guard"))
        assertEquals(3, state.consecutiveWrong)
    }

    @Test
    fun hitResetsStreak() {
        val t = Guardrails.Thresholds(streakN = 4, drawdownUsd = 1_000.0)
        val rows = listOf(
            settled("A", "KXBTC15M", hit = false, ts = 1, mid = 0.50),
            settled("B", "KXBTC15M", hit = false, ts = 2, mid = 0.50),
            settled("C", "KXBTC15M", hit = true, ts = 3, mid = 0.50)
        )
        val state = Guardrails.update(Guardrails.identity(), rows, t, 10)
        assertFalse(state.paused)
        assertEquals(0, state.consecutiveWrong)
    }

    @Test
    fun drawdownPauseUsesOneContractProxy() {
        val t = Guardrails.Thresholds(streakN = 99, drawdownUsd = 0.40)
        // Lose a YES bet at mid 0.50 → −0.50 each. Two losses = −1.00 drawdown.
        val rows = listOf(
            settled("A", "KXBTC15M", hit = false, ts = 1, mid = 0.50, side = "YES"),
            settled("B", "KXBTC15M", hit = false, ts = 2, mid = 0.50, side = "YES")
        )
        val state = Guardrails.update(Guardrails.identity(), rows, t, 10)
        assertTrue(state.drawdown >= 0.40)
        assertTrue(state.paused)
        assertTrue(state.pauseReason!!.contains("drawdown"))
    }

    @Test
    fun resumeClearsPause() {
        val paused = Guardrails.State(paused = true, pauseReason = "alerts paused — streak guard", consecutiveWrong = 5)
        val next = Guardrails.resume(paused, nowMs = 5)
        assertFalse(next.paused)
        assertEquals(0, next.consecutiveWrong)
    }

    @Test
    fun newSessionResumesWhenEnabled() {
        val paused = Guardrails.State(paused = true, pauseReason = "x", sessionId = 1, consecutiveWrong = 4)
        val next = Guardrails.onNewSession(
            paused,
            Guardrails.Thresholds(resumeOnNewSession = true),
            sessionId = 2,
            nowMs = 9
        )
        assertFalse(next.paused)
        assertEquals(2, next.sessionId)
    }

    @Test
    fun oneContractPnlSigns() {
        val winYes = settled("W", "KXBTC15M", hit = true, ts = 1, mid = 0.40, side = "YES")
        assertEquals(0.60, Guardrails.oneContractPnl(winYes), 1e-9)
        val loseYes = settled("L", "KXBTC15M", hit = false, ts = 1, mid = 0.40, side = "YES")
        assertEquals(-0.40, Guardrails.oneContractPnl(loseYes), 1e-9)
    }
}

class OnlineAdapterTest {
    @Test
    fun coldStartIsIdentity() {
        val state = OnlineAdapter.identity()
        assertFalse(state.ready)
        assertEquals(0.70, OnlineAdapter.apply(0.70, state), 1e-9)
        assertEquals(1.0, state.weight("ai"), 1e-9)
    }

    @Test
    fun agreeingFeatureWeightRises() {
        val now = 10_000L
        val rows = (1..SignalConstants.MIN_ADAPTER_SAMPLES).map { i ->
            settled(
                ticker = "KXBTC15M-$i",
                series = "KXBTC15M",
                hit = true,
                ts = now + i,
                mid = 0.50,
                side = "YES",
                pYes = 0.62,
                features = mapOf("ai" to 8.0, "velocity" to -3.0)
            )
        }
        val state = OnlineAdapter.update(OnlineAdapter.identity(), rows, nowMs = now + 100)
        assertTrue(state.ready)
        assertTrue(state.weight("ai") > 1.0)
        assertTrue(state.weight("velocity") < 1.0)
        assertEquals(rows.size, state.sampleCount)
    }

    @Test
    fun overconfidentSlopeDampsProbability() {
        val now = 20_000L
        val rows = (1..12).map { i ->
            settled(
                ticker = "KXETH15M-$i",
                series = "KXETH15M",
                hit = i % 2 == 0,
                ts = now + i,
                mid = 0.50,
                side = "YES",
                pYes = 0.92,
                features = mapOf("ai" to 4.0)
            )
        }
        val state = OnlineAdapter.update(OnlineAdapter.identity(), rows, nowMs = now + 50)
        assertTrue(state.ready)
        val cal = OnlineAdapter.apply(0.92, state)
        assertTrue(cal < 0.92)
        assertTrue(cal > 0.20)
    }

    @Test
    fun watermarkSkipsAlreadyProcessed() {
        val first = listOf(
            settled("A", "KXBTC15M", true, 5, features = mapOf("ai" to 2.0), pYes = 0.60)
        )
        val s1 = OnlineAdapter.update(OnlineAdapter.identity(), first, nowMs = 6)
        val s2 = OnlineAdapter.update(s1, first, nowMs = 7)
        assertEquals(s1.sampleCount, s2.sampleCount)
        assertEquals(s1.weight("ai"), s2.weight("ai"), 1e-12)
    }
}

class SpotFeatureMathTest {
    @Test
    fun positiveSpotNudgeRaisesFair() {
        val feat = AssetSpotFeatures(
            asset = "BTC",
            spotReturn5m = 0.006,
            fundingRate = 0.0,
            realizedVol15m = 0.001,
            source = "test"
        )
        val adj = SpotFeatureMath.adjustPp(50.0, feat)
        assertNotNull(adj)
        assertTrue(adj!! > 50.0)
        assertTrue(SpotFeatureMath.label(feat)!!.contains("spot"))
    }

    @Test
    fun missingFeaturesReturnNull() {
        assertEquals(null, SpotFeatureMath.adjustPp(50.0, null))
        assertEquals(null, SpotFeatureMath.adjustPp(50.0, AssetSpotFeatures("BTC")))
    }
}

class ChecklistTest {
    @Test
    fun copyTextIncludesAdvisoryFields() {
        val market = MarketUiModel(
            ticker = "KXBTC15M-X",
            title = "BTC",
            subtitle = null,
            floorStrike = null,
            yesBid = 0.40,
            yesAsk = 0.44,
            noBid = 0.56,
            noAsk = 0.60,
            lastPrice = 0.42,
            yesProbabilityPercent = 42.0,
            noProbabilityPercent = 58.0,
            volume = 1000.0,
            volume24h = 1000.0,
            openInterest = 200.0,
            liquidityDollars = 50.0,
            closeTimeLocal = null,
            closeTimeEpochMs = null,
            status = "open",
            seriesLabel = "Bitcoin",
            edgePp = 8.0,
            stance = "Lean YES vs market",
            predictedSide = "YES",
            netEdgePp = 5.5,
            netEvDollars = 0.055,
            suggestedContracts = 12,
            aiConfidence = 0.61,
            regimeTag = "Trend",
            tteRegimeLabel = "Early window",
            passedFilter = true
        )
        val text = PreTradeChecklist.copyText(market)
        assertTrue(text.contains("advisory", ignoreCase = true))
        assertTrue(text.contains("12 contracts max"))
        assertTrue(text.contains("Likely side"))
        assertTrue(text.contains("Value side"))
        assertTrue(text.contains("Net EV"))
        assertTrue(text.contains("cleared"))
        val items = PreTradeChecklist.items(market)
        assertEquals(15, items.size)
        assertEquals("Likely side", items[0].label)
        assertEquals("—", items[0].value)
        assertEquals("Value side", items[1].label)
        assertEquals("Uncertainty", items[5].label)
        assertEquals("Survival P(YES)", items[11].label)
        assertEquals("RL stake", items[14].label)
        assertEquals("—", items[14].value)
    }

    @Test
    fun checklistUsesDashNotBlankWhenDataMissing() {
        val market = MarketUiModel(
            ticker = "KXBTC15M-EMPTY",
            title = "BTC",
            subtitle = null,
            floorStrike = null,
            yesBid = 0.40,
            yesAsk = 0.44,
            noBid = 0.56,
            noAsk = 0.60,
            lastPrice = 0.42,
            yesProbabilityPercent = 42.0,
            noProbabilityPercent = 58.0,
            volume = null,
            volume24h = null,
            openInterest = null,
            liquidityDollars = null,
            closeTimeLocal = null,
            closeTimeEpochMs = null,
            status = "open",
            seriesLabel = "Bitcoin"
        )
        val byLabel = PreTradeChecklist.items(market).associate { it.label to it.value }
        assertTrue(byLabel["Likely side"]!!.isNotBlank())
        assertTrue(byLabel["Value side"]!!.isNotBlank())
        assertEquals("—", byLabel["Size"])
        assertEquals("—", byLabel["Net EV"])
        assertEquals("—", byLabel["Confidence"])
        assertEquals("—", byLabel["Regime"])
        assertEquals("—", byLabel["TTE"])
        assertEquals("—", byLabel["RL stake"])
        byLabel.values.forEach { v ->
            assertTrue("blank checklist value", v.isNotBlank())
        }
    }
}

class DefaultConfigV21Test {
    @Test
    fun parsesDecisionSupportDefaults() {
        val cfg = DefaultSignalConfig.parse(
            """{"bankrollUsd":2500,"useKelly":false,"kellyFraction":0.5,"feeRate":0.05,"autoMute":false,"muteHitRateFloor":0.35,"streakPauseN":3,"drawdownUsd":80}"""
        )
        assertEquals(2500.0, cfg.bankrollUsd, 1e-9)
        assertFalse(cfg.useKelly)
        assertEquals(0.5, cfg.kellyFraction, 1e-9)
        assertEquals(0.05, cfg.feeRate, 1e-9)
        assertFalse(cfg.autoMute)
        assertEquals(0.35, cfg.muteHitRateFloor, 1e-9)
        assertEquals(3, cfg.streakPauseN)
        assertEquals(80.0, cfg.drawdownUsd, 1e-9)
    }
}

private fun settled(
    ticker: String,
    series: String,
    hit: Boolean,
    ts: Long,
    mid: Double = 0.50,
    side: String = "YES",
    pYes: Double = if (side == "YES") 0.62 else 0.38,
    regime: String? = "TREND",
    tte: String? = "EARLY",
    features: Map<String, Double> = emptyMap()
): PredictionLogEntry {
    val outcomeYes = if (side == "YES") hit else !hit
    return PredictionLogEntry(
        ticker = ticker,
        series = series,
        predictedYes = pYes,
        predictedNo = 1.0 - pYes,
        marketMid = mid,
        timestampMs = ts,
        closeTimeMs = ts,
        outcome = if (outcomeYes) "yes" else "no",
        score = if (hit) 1 else 0,
        brier = 0.1,
        predictedSide = side,
        edgePp = 6.0,
        regime = regime,
        tteBucket = tte,
        settledAtMs = ts,
        featureDevs = features
    )
}
