package com.dirk.kalshiodds.decision

import com.dirk.kalshiodds.data.supabase.SupabaseSync
import com.dirk.kalshiodds.signal.trade.KalshiFee
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ModelFilterTest {
    @Before fun reset() = ModelFilter.resetForTest()
    @After fun clear() = ModelFilter.resetForTest()

    @Test fun featuresAreFinite() {
        val now = 1_700_000_000_000L
        repeat(6) { i -> ModelFilter.observe(quote(now + i * 5_000L, spot = 100_000.0 + i * 25)) }
        val x = ModelFilter.features(quote(now + 30_000L), "YES", 0.55, 0.20)
        assertEquals(ModelFilter.FEATURES, x.size)
        assertEquals(ModelFilter.FEATURES, ModelFilter.FEATURE_NAMES.size)
        assertTrue(x.all { it.isFinite() })
    }

    @Test fun takerFeeMatchesThePaperBook() {
        val raw = 0.07 * 10 * 0.4 * 0.6
        assertEquals(raw, KalshiFee.raw(10, 0.4), 1e-9)
        assertEquals(ScalpRule.orderFee(10, 0.4), ModelFilter.takerFee(10, 0.4), 0.0)
        assertEquals(ScalpRule.orderFee(8, 0.18), ModelFilter.takerFee(8, 0.18), 0.0)
        assertTrue(ModelFilter.takerFee(10, 0.4) + 1e-9 >= raw)
    }

    @Test fun walkForwardHoldsOutTheEmbargo() {
        val rows = List(50) { i ->
            ModelFilter.Sample(DoubleArray(ModelFilter.FEATURES), if (i % 2 == 0) 0.2 else -0.2, i * 60_000L, 700.0, "sf41-model")
        }
        val used = ModelFilter.walkForward(rows)
        assertTrue(used.isNotEmpty())
        val testStart = rows[40].closeMs
        val allowed = ModelFilter.trainAllowed(rows, testStart)
        assertTrue(allowed.none { it.closeMs >= testStart - ModelFilter.EMBARGO_MS })
        assertTrue(rows.any { it.closeMs >= testStart - ModelFilter.EMBARGO_MS && it.closeMs < testStart })
        assertTrue(allowed.none { it.closeMs == testStart })
        assertTrue(used.none { (sample, _) -> sample.closeMs < rows[ModelFilter.MIN_TRAIN].closeMs })
    }

    @Test fun hardExitAndSizeAndFloor() {
        ModelFilter.setExitBeforeSeconds(90.0)
        assertTrue(ModelFilter.mustExit(90.0))
        assertFalse(ModelFilter.mustExit(91.0))
        ModelFilter.setExitBeforeSeconds(10.0)
        assertEquals(60.0, ModelFilter.exitBeforeSeconds(), 0.0)
        ModelFilter.setExitBeforeSeconds(200.0)
        assertEquals(90.0, ModelFilter.exitBeforeSeconds(), 0.0)
        assertEquals(10, ModelFilter.sized(10, 0.0, 10))
        ModelFilter.setSizeExperiment(true)
        assertEquals(1, ModelFilter.sized(10, 0.0, 10))
        assertEquals(10, ModelFilter.sized(10, 1.5, 10))
        ModelFilter.setSizeExperiment(false)
        val penny = ModelFilter.decide(ModelFilter.TWINS.first(), quote(ask = 0.01, bid = 0.0), "YES", 0.01, 0.50)
        assertFalse(penny.enter)
        assertTrue(penny.reason, penny.reason.contains("floor"))
        val rich = ModelFilter.decide(ModelFilter.TWINS.first(), quote(ask = 0.90, bid = 0.88), "YES", 0.90, 0.95)
        assertFalse(rich.enter)
        assertTrue(rich.reason, rich.reason.contains("55"))
    }

    @Test fun warmupEntersOnNeutralWeightsWhenTheResidualClears() {
        val d = ModelFilter.decide(ModelFilter.TWINS.first(), quote(), "YES", 0.20, 0.55)
        assertTrue(d.reason, d.enter)
        assertTrue(d.reason, d.reason.contains("enter"))
        assertEquals(1.0, d.scalar, 0.0)
        assertTrue(d.policyVersion.contains("fee=quadratic-0.07"))
        assertTrue(d.score in 0.0..1.0)
        assertTrue(QuantSession.isWarm())
        assertTrue(QuantSession.coldStartUs() >= 0L)
        assertTrue(QuantSession.warmStartUs() >= 0L)
    }

    @Test fun retrainFitsAShallowForest() {
        val old = 1_700_000_000_000L
        val trades = List(45) { i ->
            ScalpTrade(
                id = "t$i",
                ticker = "KXBTC15M-26OCT122015-15",
                side = "YES",
                state = ScalpState.CLOSED,
                signalAtMs = old + i * 1_000L,
                signalAsk = 0.40,
                fairAtSignal = 0.55,
                contracts = 5,
                entryPrice = 0.40,
                entryAtMs = old + i * 1_000L,
                closedAtMs = old + i * 1_000L + 400,
                netUsd = if (i % 2 == 0) 0.40 else -0.20,
                ruleVersion = "v|sf41-logistic|P"
            )
        }
        ModelFilter.retrainFrom(trades, old + ModelFilter.EMBARGO_MS + 120_000L)
        assertTrue(QuantSession.trainedTrees() in TreeEnsemble.MIN_TREES..TreeEnsemble.MAX_TREES)
    }

    @Test fun syncPushesModelDecisionsAndNeverPullsThem() {
        val trade = ScalpTrade(
            id = "t",
            ticker = "KXBTC15M-26OCT122015-15",
            side = "YES",
            state = ScalpState.CLOSED,
            signalAtMs = 1_000L,
            signalAsk = 0.40,
            fairAtSignal = 0.50,
            contracts = 5,
            entryPrice = 0.40,
            entryAtMs = 2_000L,
            closedAtMs = 9_000L,
            netUsd = 0.20,
            ruleVersion = "v|sf41-logistic|P"
        )
        val decision = ModelFilter.SyncRow("mf:1", 9_000L, "sf41-logistic", 0.62, 0.9, true, "enter", null)
        val packed = SupabaseSync().pack(SupabaseSync.LocalBundle(scalps = listOf(trade), modelDecisions = listOf(decision)))
        val scalp = packed.first { it.kind == "scalp" }
        assertFalse(scalp.deleted)
        assertEquals("sf41-logistic", JSONObject(scalp.payload).getString("model"))
        val row = packed.first { it.kind == "model_decision" }
        assertFalse(row.deleted)
        assertEquals("sf41-logistic", JSONObject(row.payload).getString("model"))
        assertFalse(SupabaseSync.PULL_KINDS.contains("model_decision"))
        assertTrue(com.dirk.kalshiodds.data.supabase.SyncMerge.ALWAYS_PUSH.contains("model_decision"))
    }

    @Test fun fiftyThreeCentRoundTripIsALossBeforeRounding() {
        assertEquals(-0.4937, ModelFilter.rawNetCents(0.50, 0.53), 1e-4)
        assertEquals(1.5175, ModelFilter.rawNetCents(0.50, 0.55), 1e-4)
        val contracts = 10
        val entry = 0.50
        val exitPx = 0.53
        val entryFee = ScalpRule.orderFee(contracts, entry)
        val exitFee = ScalpRule.orderFee(contracts, exitPx)
        val per = ModelFilter.netPayoffPerContract(contracts * exitPx, contracts * entry, entryFee, exitFee, contracts)
        val spreadAlreadyInFills = (exitPx - entry)
        assertEquals(spreadAlreadyInFills - entryFee / contracts - exitFee / contracts, per, 1e-9)
        assertEquals(0.25 * per, ModelFilter.makerExpected(0.25, per), 1e-12)
    }

    @Test fun yesAndNoUseTheSameStrikeDistance() {
        val up = ModelFilter.features(quote(spot = 110_000.0, strike = 100_000.0), "YES", 0.55, 0.40)
        val down = ModelFilter.features(quote(spot = 100_000.0 / 1.1, strike = 100_000.0), "NO", 0.55, 0.40)
        assertEquals(up[1], down[1], 1e-6)
        assertTrue(up[1] > 0.0)
    }

    @Test fun entryScoringIsCadencedAndExitsAreNot() {
        assertTrue(ModelFilter.dueForEntry("KXBTC", 1_000L))
        assertFalse(ModelFilter.dueForEntry("KXBTC", 1_100L))
        assertTrue(ModelFilter.dueForEntry("KXBTC", 1_000L + ModelFilter.ENTRY_CADENCE_MS))
        val scalp = source("Scalp.kt")
        val exit = scalp.substring(scalp.indexOf("fun modelExitReason"), scalp.indexOf("fun enterModelFair"))
        assertFalse(exit.contains("dueForEntry"))
        assertFalse(exit.contains("decide("))
    }

    @Test fun lgbmGateIsPredictedNetPayoff() {
        val twin = ModelFilter.TWINS.first { it.arm == ModelFilter.Arm.LGBM }
        val d = ModelFilter.decide(twin, quote(), "YES", 0.20, 0.55)
        assertTrue(d.reason, d.enter)
        assertTrue(d.reason, d.reason.contains("lgbm"))
        assertTrue(d.modelVersion.startsWith("lgbm-"))
    }

    private fun source(name: String): String = listOf(
        java.io.File("src/main/java/com/dirk/kalshiodds/decision/$name"),
        java.io.File("app/src/main/java/com/dirk/kalshiodds/decision/$name")
    ).first { it.isFile }.readText()

    private fun quote(
        now: Long = 1_700_000_000_000L,
        tauS: Double = 600.0,
        bid: Double = 0.19,
        ask: Double = 0.20,
        spot: Double = 100_000.0,
        strike: Double = 100_000.0
    ) = ScalpRule.Quote(
        ticker = "KXBTC15M-26OCT221200-00",
        nowMs = now,
        closeMs = now + (tauS * 1000.0).toLong(),
        bookAtMs = now,
        yesBid = bid,
        yesBidSize = 20.0,
        yesAsk = ask,
        yesAskSize = 20.0,
        spot = spot,
        strike = strike,
        sigmaPerSec = 3e-5
    )
}
