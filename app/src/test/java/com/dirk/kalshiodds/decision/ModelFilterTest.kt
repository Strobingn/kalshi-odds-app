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
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

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
        assertTrue(rich.reason, rich.reason.contains("75"))
    }

    @Test fun observeKeepsPriorsAndDipTwinsEnter() {
        val q = quote(tauS = 600.0, bid = 0.39, ask = 0.40, spot = 100_200.0, strike = 100_000.0)
        ModelFilter.observe(q)
        val (w, b) = ModelFilter.weightCopy()
        assertEquals(ModelFilter.PRIOR_BIAS, b, 1e-9)
        assertEquals(0.25, w[0], 1e-9)
        assertEquals(0.04, w[1], 1e-9)
        assertFalse(w.all { it == 0.0 })
        for (id in listOf("sf41-logistic", "sf41-lgbm", "sf42-logistic", "sf42-lgbm")) {
            val twin = ModelFilter.twin(id)!!
            val d = ModelFilter.decide(twin, q, "YES", 0.40, 0.48)
            if (twin.arm == ModelFilter.Arm.LOGISTIC) assertTrue("$id ${d.score}", abs(d.score - 0.5) > 1e-4)
            assertTrue(d.reason, d.enter)
            ModelFilter.decide(twin, q, "YES", 0.40, 0.48)
            assertEquals(id, 1, ModelFilter.researchLog().count { it.twinId == id })
        }
        val row = ModelFilter.researchLog().last { it.twinId == "sf41-logistic" }
        val push = QuantSession.attributeLogistic(w, b, row.features)
        val time = ModelFilter.FEATURE_NAMES.indexOf("timeLeft")
        val dist = ModelFilter.FEATURE_NAMES.indexOf("distStrikeBps")
        assertTrue("timeLeft ${push[time]}", abs(push[time]) > 1e-6)
        assertTrue("distStrikeBps ${push[dist]}", abs(push[dist]) > 1e-6)
    }

    @Test fun gemma4E4bIsSelectableAndE2bStaysDefault() {
        LocalAnalyst.resetForTest()
        assertEquals("Gemma 4 E2B", LocalAnalyst.activeName())
        val primary = LocalAnalyst.SHORTLIST.first()
        assertEquals("Gemma 4 E2B", primary.name)
        assertEquals("Primary", primary.role)
        assertEquals(1, LocalAnalyst.SHORTLIST.count { it.role == "Primary" })
        val e4b = LocalAnalyst.SHORTLIST.single { it.name == "Gemma 4 E4B" }
        assertEquals("litert-community/gemma-4-E4B-it-litert-lm", e4b.repo)
        assertEquals("gemma-4-E4B-it.litertlm", e4b.fileName)
        assertEquals("~3.65 GB", e4b.sizeLabel)
        assertEquals("Stronger / larger", e4b.role)
        assertTrue(e4b.role != "Primary")
        LocalAnalyst.setActiveName("Gemma 4 E4B")
        assertEquals("Gemma 4 E4B", LocalAnalyst.activeName())
        LocalAnalyst.setActiveName("not-a-model")
        assertEquals("Gemma 4 E4B", LocalAnalyst.activeName())
        LocalAnalyst.resetForTest()
        assertEquals("Gemma 4 E2B", LocalAnalyst.activeName())
    }

    @Test fun localAnalystSelectionSurvivesRestart() {
        val dir = File(System.getProperty("java.io.tmpdir"), "local-analyst-test")
        dir.mkdirs()
        File(dir, "local-analyst.json").delete()
        LocalAnalyst.bind(dir)
        LocalAnalyst.setActiveName("Gemma 3n E2B")
        LocalAnalyst.setBackend(LocalAnalyst.Backend.GPU)
        LocalAnalyst.setMaxOutputTokens(256)
        LocalAnalyst.setPauseDuringActiveEntry(false)
        LocalAnalyst.resetForTest()
        LocalAnalyst.bind(dir)
        assertEquals("Gemma 3n E2B", LocalAnalyst.activeName())
        assertEquals(LocalAnalyst.Backend.GPU, LocalAnalyst.engine().backend)
        assertEquals(256, LocalAnalyst.engine().maxOutputTokens)
        assertFalse(LocalAnalyst.engine().pauseDuringActiveEntry)
        LocalAnalyst.resetForTest()
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

    @Test fun ofiFeatureBoundsTheContractGapAndThePriorCannotRewardIt() {
        assertEquals(0.0, HftSignals.ofiFeature(0.0), 0.0)
        assertEquals(0.0, HftSignals.ofiFeature(Double.NaN), 0.0)
        val gap = HftSignals.ofiFeature(248.0)
        assertTrue("248 contracts scaled to $gap", gap in 0.5..0.99)
        assertEquals(-gap, HftSignals.ofiFeature(-248.0), 1e-12)
        assertTrue(kotlin.math.abs(HftSignals.ofiFeature(10_000.0)) < 1.0)
        assertTrue(gap < 248.0 / 10.0)
        val (w, _) = ModelFilter.weightCopy()
        assertTrue(w[ModelFilter.OFI_FEATURE] < 0.0)
        assertTrue(w[ModelFilter.OFI_DEPTH_FEATURE] <= 0.0)
        assertEquals(-0.35, w[ModelFilter.OFI_FEATURE], 1e-9)
    }

    @Test fun liveOfiFeatureIsScaledBeforeTheLogit() {
        val now = 1_700_000_000_000L
        val first = quote(now, bid = 0.40, ask = 0.42).copy(yesBidSize = 10.0, yesAskSize = 10.0)
        val second = quote(now + 1_000L, bid = 0.41, ask = 0.42).copy(yesBidSize = 258.0, yesAskSize = 10.0)
        ModelFilter.observe(first)
        ModelFilter.observe(second)
        val raw = HftSignals.ofi(0.40, 10.0, 0.42, 10.0, 0.41, 258.0, 0.42, 10.0)
        assertTrue(kotlin.math.abs(raw) > 200.0)
        val x = ModelFilter.features(second, "YES", 0.50, 0.42)
        val scaled = x[ModelFilter.OFI_FEATURE]
        assertTrue("scaled $scaled from raw $raw", scaled in 0.5..0.99)
        assertTrue(scaled < kotlin.math.abs(raw) / 10.0)
    }

    @Test fun sampleWeightDropsHighScoresAndDownWeightsHotVolume() {
        assertEquals(1.0, ModelFilter.sampleWeight(0.2, 0.60), 0.0)
        assertEquals(0.0, ModelFilter.sampleWeight(0.2, 0.80), 0.0)
        assertEquals(ModelFilter.NONQUIET_WEIGHT, ModelFilter.sampleWeight(0.2, 0.80, residualCents = 4.0), 0.0)
        assertEquals(ModelFilter.NONQUIET_WEIGHT, ModelFilter.sampleWeight(ModelFilter.QUIET_VOL, 0.50), 0.0)
        assertEquals(0.0, ModelFilter.sampleWeight(3.0, 0.90), 0.0)
        assertTrue(ModelFilter.regimeAllows(0.60, 0.2, learned = true))
        assertFalse(ModelFilter.regimeAllows(0.80, 0.2, learned = true))
        assertTrue(ModelFilter.regimeAllows(0.90, 0.0, learned = false))
        assertFalse(ModelFilter.regimeAllows(0.50, 2.0, learned = false))
    }

    @Test fun learnedTwinsSkipHighScoresAndHotVolumeButKeepModestQuietEntries() {
        val logistic = ModelFilter.TWINS.first { it.arm == ModelFilter.Arm.LOGISTIC }
        val lgbm = ModelFilter.TWINS.first { it.arm == ModelFilter.Arm.LGBM }
        val zeros = DoubleArray(ModelFilter.FEATURES)
        // Break-even above 0.80: a high score whose E[net] is still negative stays a reject,
        // even when the fee residual would clear.
        repeat(20) { ModelFilter.onClosed("sf41-logistic", -2.0, 700.0, 1_000L + it, zeros) }
        ModelFilter.onClosed("sf41-logistic", 0.05, 700.0, 2_000L, zeros)
        ModelFilter.installWeightsForTest(zeros, kotlin.math.ln(0.80 / 0.20), ModelFilter.MIN_TRAIN)
        val highLog = ModelFilter.decide(logistic, quote(), "YES", 0.20, 0.55)
        val highTree = ModelFilter.decide(lgbm, quote(now = 1_700_000_000_500L), "YES", 0.20, 0.55)
        assertFalse(highLog.enter)
        assertTrue(highLog.reason, highLog.reason.contains("high-score"))
        assertFalse(highTree.enter)
        assertTrue(highTree.reason, highTree.reason.contains("high-score"))
        ModelFilter.resetForTest()
        ModelFilter.installWeightsForTest(zeros, kotlin.math.ln(0.60 / 0.40), ModelFilter.MIN_TRAIN)
        val modest = ModelFilter.decide(logistic, quote(), "YES", 0.20, 0.55)
        val modestTree = ModelFilter.decide(lgbm, quote(now = 1_700_000_000_500L), "YES", 0.20, 0.55)
        assertTrue(modest.reason, modest.enter)
        assertTrue(modest.score in 0.55..ModelFilter.HIGH_SCORE)
        assertTrue(modestTree.reason, modestTree.enter)
        ModelFilter.resetForTest()
        // Same high bias, and the book clears E[net]. The ceiling still drops it.
        // A 0.55→0.57 floor nudge must not admit the band.
        ModelFilter.installWeightsForTest(zeros, kotlin.math.ln(0.80 / 0.20), ModelFilter.MIN_TRAIN)
        ModelFilter.setThreshold(0.57)
        val paid = ModelFilter.decide(logistic, quote(now = 1_700_000_003_000L), "YES", 0.20, 0.55)
        assertFalse(paid.reason, paid.enter)
        assertTrue(paid.reason, paid.reason.contains("high-score"))
        assertTrue(paid.score > ModelFilter.HIGH_SCORE)
        val paidTree = ModelFilter.decide(lgbm, quote(now = 1_700_000_003_500L), "YES", 0.20, 0.55)
        assertFalse(paidTree.reason, paidTree.enter)
        assertTrue(paidTree.reason, paidTree.reason.contains("high-score"))
        val noEdge = ModelFilter.decide(logistic, quote(now = 1_700_000_004_000L, bid = 0.39, ask = 0.40), "YES", 0.40, 0.40)
        assertFalse(noEdge.enter)
        assertTrue(noEdge.reason, noEdge.reason.contains("high-score") || noEdge.reason.contains("expected net"))
        ModelFilter.resetForTest()
        val now = 1_700_000_000_000L
        repeat(8) { i ->
            val spot = if (i % 2 == 0) 100_000.0 else 100_200.0
            ModelFilter.observe(quote(now + i * 2_000L, spot = spot))
        }
        val hot = ModelFilter.decide(logistic, quote(now + 20_000L), "YES", 0.20, 0.55)
        assertFalse(hot.enter)
        assertTrue(hot.reason, hot.reason.contains("quiet-volume"))
        val hotTree = ModelFilter.decide(lgbm, quote(now + 21_000L), "YES", 0.20, 0.55)
        assertFalse(hotTree.enter)
        assertTrue(hotTree.reason, hotTree.reason.contains("quiet-volume"))
    }

    @Test fun offlineRetrainDoesNotHoldTheLockAcrossFit() {
        val failure = AtomicReference<Throwable>()
        ModelFilter.retrainComputeHook = {
            val acquired = CountDownLatch(1)
            Thread {
                try {
                    ModelFilter.threshold()
                } catch (t: Throwable) {
                    failure.set(t)
                } finally {
                    acquired.countDown()
                }
            }.start()
            assertTrue("ModelFilter lock held across offline fit", acquired.await(3, TimeUnit.SECONDS))
        }
        try {
            repeat(48) { i ->
                ModelFilter.onClosed("sf41-logistic", if (i % 2 == 0) 0.2 else -0.2, 700.0, 1_700_000_000_000L + i * 60_000L, DoubleArray(ModelFilter.FEATURES))
            }
            val note = ModelFilter.retrainOffline(1_700_000_000_000L + 48 * 60_000L + ModelFilter.EMBARGO_MS)
            assertTrue(note, note.contains("Retrained offline"))
            assertTrue(failure.get()?.toString(), failure.get() == null)
        } finally {
            ModelFilter.retrainComputeHook = null
        }
    }

    @Test fun retrainCannotLearnAPositiveOfiWeight() {
        val now = 1_700_000_000_000L
        repeat(48) { i ->
            val x = DoubleArray(ModelFilter.FEATURES)
            val win = i % 2 == 0
            x[ModelFilter.OFI_FEATURE] = if (win) 0.95 else 0.05
            ModelFilter.onClosed("sf41-logistic", if (win) 0.40 else -0.30, 700.0, now + i * 1_000L, x)
        }
        ModelFilter.retrainOffline(now + 48_000L + ModelFilter.EMBARGO_MS + 10_000L)
        val (w, _) = ModelFilter.weightCopy()
        assertTrue("ofi ${w[ModelFilter.OFI_FEATURE]}", w[ModelFilter.OFI_FEATURE] <= 0.0)
        assertTrue(w[ModelFilter.OFI_DEPTH_FEATURE] <= 0.0)
        assertTrue(QuantSession.trainedTrees() in TreeEnsemble.MIN_TREES..TreeEnsemble.MAX_TREES)
    }

    @Test fun losingControlBookDoesNotRejectEveryFeeClearingEntry() {
        val old = 1_700_000_000_000L
        val trades = List(80) { i ->
            ScalpTrade(
                id = "loss$i",
                ticker = "KXBTC15M-26OCT122015-15",
                side = if (i % 5 == 0) "NO" else "YES",
                state = ScalpState.CLOSED,
                signalAtMs = old + i * 60_000L,
                signalAsk = 0.40,
                fairAtSignal = 0.55,
                contracts = 10,
                entryPrice = 0.40,
                entryAtMs = old + i * 60_000L,
                closedAtMs = old + i * 60_000L + 5_000L,
                netUsd = if (i % 7 == 0) 0.40 else -2.80,
                ruleVersion = "v|sf41|P"
            )
        }
        ModelFilter.retrainFrom(trades, old + 80 * 60_000L + ModelFilter.EMBARGO_MS + 60_000L)
        val (w, b) = ModelFilter.weightCopy()
        assertFalse("retrain wiped the logistic to zeros", w.all { it == 0.0 })
        assertEquals(ModelFilter.PRIOR_BIAS, b, 0.5)
        val logistic = ModelFilter.TWINS.first { it.arm == ModelFilter.Arm.LOGISTIC }
        val lgbm = ModelFilter.TWINS.first { it.arm == ModelFilter.Arm.LGBM }
        val q = quote(now = old + 90 * 60_000L, bid = 0.39, ask = 0.40)
        val dLog = ModelFilter.decide(logistic, q, "YES", 0.40, 0.55)
        val dTree = ModelFilter.decide(lgbm, q.copy(nowMs = q.nowMs + 1_000L), "YES", 0.40, 0.55)
        val detail = "log ${dLog.enter} ${dLog.reason} score=${dLog.score} e=${dLog.expectedNet} " +
            "tree ${dTree.enter} ${dTree.reason} score=${dTree.score} e=${dTree.expectedNet} " +
            "bias=$b w16=${w[16]} warming=${ModelFilter.warming()}"
        assertFalse(detail, dLog.enter)
        assertTrue(detail, dLog.reason.contains("high-score"))
        assertTrue(detail, dLog.score > ModelFilter.HIGH_SCORE)
        assertFalse(detail, dTree.enter)
        assertTrue(detail, dTree.reason.contains("high-score"))
        ModelFilter.installWeightsForTest(DoubleArray(ModelFilter.FEATURES), kotlin.math.ln(0.60 / 0.40), ModelFilter.MIN_TRAIN)
        val modestLog = ModelFilter.decide(logistic, quote(now = q.nowMs + 2_000L, bid = 0.39, ask = 0.40), "YES", 0.40, 0.48)
        val modestTree = ModelFilter.decide(lgbm, quote(now = q.nowMs + 2_500L, bid = 0.39, ask = 0.40), "YES", 0.40, 0.48)
        assertTrue(modestLog.reason, modestLog.enter)
        assertTrue(modestLog.score <= ModelFilter.HIGH_SCORE)
        assertTrue(modestTree.reason, modestTree.enter)
        ModelFilter.onClosed(logistic.id, 0.25, 600.0, modestLog.atMs, modestLog.features)
        ModelFilter.onClosed(lgbm.id, 0.10, 600.0, modestTree.atMs, modestTree.features)
        val noEdge = ModelFilter.decide(logistic, quote(now = q.nowMs + 3_000L, bid = 0.39, ask = 0.40), "YES", 0.40, 0.40)
        val noEdgeTree = ModelFilter.decide(lgbm, quote(now = q.nowMs + 4_000L, bid = 0.39, ask = 0.40), "YES", 0.40, 0.40)
        assertFalse(noEdge.reason, noEdge.enter)
        assertFalse(noEdgeTree.reason, noEdgeTree.enter)
        val rich = ModelFilter.decide(logistic, quote(now = q.nowMs + 5_000L, ask = 0.90, bid = 0.88), "YES", 0.90, 0.95)
        assertFalse(rich.reason, rich.enter)
        val cards = ModelFilter.scorecards()
        val logCard = cards.first { it.arm == "logistic" }
        val treeCard = cards.first { it.arm == "lgbm" }
        assertTrue("logistic trades ${logCard.trades} rejected ${logCard.rejected}", logCard.trades >= 1 && logCard.rejected >= 1)
        assertTrue("lgbm trades ${treeCard.trades} rejected ${treeCard.rejected}", treeCard.trades >= 1 && treeCard.rejected >= 1)
    }

    @Test fun negativeForestDoesNotVetoAClearingEdgeTheLogisticWouldTake() {
        val logistic = ModelFilter.TWINS.first { it.arm == ModelFilter.Arm.LOGISTIC }
        val lgbm = ModelFilter.TWINS.first { it.arm == ModelFilter.Arm.LGBM }
        val weights = ModelFilter.priorWeights()
        weights[ModelFilter.RESIDUAL_FEATURE] = 0.0
        ModelFilter.installWeightsForTest(weights, 0.0, ModelFilter.MIN_TRAIN)
        val leaf = TreeEnsemble.build(
            intArrayOf(1, ModelFilter.FEATURES),
            listOf(TreeEnsemble.Spec(0, 0, 0, 0.0, "LEAF", 0, 0, -0.20)),
            logistic = false,
            base = 0.0
        )
        QuantSession.installPayoffForTest(leaf)
        assertTrue(QuantSession.payoffIsFitted())
        val now = 1_700_000_010_000L
        val takeLog = ModelFilter.decide(logistic, quote(now = now, bid = 0.39, ask = 0.40), "YES", 0.40, 0.55)
        val takeTree = ModelFilter.decide(lgbm, quote(now = now + 1_000L, bid = 0.39, ask = 0.40), "YES", 0.40, 0.55)
        assertTrue(takeLog.reason, takeLog.enter)
        assertTrue(takeTree.reason, takeTree.enter)
        assertTrue(takeTree.expectedNet > 0.0)
        val skip = ModelFilter.decide(lgbm, quote(now = now + 2_000L, bid = 0.39, ask = 0.40), "YES", 0.40, 0.40)
        assertFalse(skip.reason, skip.enter)
    }

    @Test fun undertrainedRetrainKeepsATradablePrior() {
        val old = 1_700_000_000_000L
        val trades = List(12) { i ->
            ScalpTrade(
                id = "few$i",
                ticker = "KXBTC15M-26OCT122015-15",
                side = "YES",
                state = ScalpState.CLOSED,
                signalAtMs = old + i * 60_000L,
                signalAsk = 0.40,
                fairAtSignal = 0.55,
                contracts = 10,
                entryPrice = 0.40,
                entryAtMs = old + i * 60_000L,
                closedAtMs = old + i * 60_000L + 400L,
                netUsd = -1.0,
                ruleVersion = "v|sf41|P"
            )
        }
        ModelFilter.retrainFrom(trades, old + ModelFilter.EMBARGO_MS + 120_000L)
        val (w, b) = ModelFilter.weightCopy()
        assertEquals(ModelFilter.PRIOR_BIAS, b, 1e-9)
        assertFalse(w.all { it == 0.0 })
        assertTrue(ModelFilter.warming())
        val d = ModelFilter.decide(ModelFilter.TWINS.first(), quote(now = old + 30_000L), "YES", 0.40, 0.55)
        assertTrue(d.reason, d.enter)
    }

    @Test fun sessionAdviceCapsTheHighScoreRegimeAndKeepsTheLatePocket() {
        val logistic = ModelFilter.TWINS.first { it.arm == ModelFilter.Arm.LOGISTIC }
        val zeros = DoubleArray(ModelFilter.FEATURES)
        ModelFilter.setThreshold(0.57)
        val applied = ModelFilter.applySessionAdvice()
        assertEquals(ModelFilter.DEFAULT_THRESHOLD, applied.scoreFloor, 0.0)
        assertEquals(ModelFilter.HIGH_SCORE, applied.scoreCeiling, 0.0)
        assertEquals(ModelFilter.PREFER_TIME_LEFT_S, applied.preferTimeLeftUnderS, 0.0)
        assertEquals(ModelFilter.PREFER_SCORE_BELOW, applied.preferScoreBelow, 0.0)
        assertFalse(applied.lateWindowOnly)
        assertTrue(applied.note, applied.note.contains("0.57") && applied.note.contains("not applied"))
        assertTrue(applied.summary().contains("ceiling 0.65"))
        assertTrue(applied.summary().contains("floor 0.55"))
        ModelFilter.installWeightsForTest(zeros, kotlin.math.ln(0.80 / 0.20), ModelFilter.MIN_TRAIN)
        val highLate = ModelFilter.decide(logistic, quote(now = 1_700_000_005_000L, tauS = 120.0), "YES", 0.20, 0.55)
        assertFalse(highLate.reason, highLate.enter)
        assertTrue(highLate.reason, highLate.reason.contains("high-score"))
        ModelFilter.installWeightsForTest(zeros, kotlin.math.ln(0.60 / 0.40), ModelFilter.MIN_TRAIN)
        val early = ModelFilter.decide(logistic, quote(now = 1_700_000_006_000L, tauS = 600.0), "YES", 0.20, 0.55)
        assertTrue(early.reason, early.enter)
        assertFalse(early.reason, early.reason.contains("late window"))
        ModelFilter.installWeightsForTest(zeros, kotlin.math.ln(0.40 / 0.60), ModelFilter.MIN_TRAIN)
        val pocket = ModelFilter.decide(logistic, quote(now = 1_700_000_007_000L, tauS = 120.0), "YES", 0.20, 0.55)
        assertTrue(pocket.reason, pocket.enter)
        assertTrue(pocket.score <= ModelFilter.PREFER_SCORE_BELOW)
        assertTrue(pocket.reason, pocket.reason.contains("late pocket"))
        val dir = File(System.getProperty("java.io.tmpdir"), "gate-advice-test")
        dir.mkdirs()
        val file = File(dir, "model-filter-runtime.json")
        file.writeText("""{"mode":"LOGISTIC","path":"CPU_FAST","manual":false}""")
        ModelFilter.bindGateFile(file)
        ModelFilter.applySessionAdvice()
        val saved = org.json.JSONObject(file.readText())
        assertEquals(ModelFilter.HIGH_SCORE, saved.getDouble("scoreCeiling"), 1e-9)
        assertEquals(ModelFilter.DEFAULT_THRESHOLD, saved.getDouble("scoreFloor"), 1e-9)
        assertEquals(ModelFilter.PREFER_TIME_LEFT_S, saved.getDouble("preferTimeLeftUnderS"), 1e-9)
        assertEquals(ModelFilter.PREFER_SCORE_BELOW, saved.getDouble("preferScoreBelow"), 1e-9)
        assertFalse(saved.getBoolean("lateWindowOnly"))
        assertEquals("LOGISTIC", saved.getString("mode"))
        ModelFilter.resetForTest()
        assertFalse(ModelFilter.gatePrefs().lateWindowOnly)
        ModelFilter.bindGateFile(file)
        val restored = ModelFilter.gatePrefs()
        assertFalse(restored.lateWindowOnly)
        assertEquals(ModelFilter.HIGH_SCORE, restored.scoreCeiling, 0.0)
        assertEquals(ModelFilter.DEFAULT_THRESHOLD, restored.scoreFloor, 0.0)
        assertEquals(ModelFilter.PREFER_TIME_LEFT_S, restored.preferTimeLeftUnderS, 0.0)
        assertEquals(ModelFilter.PREFER_SCORE_BELOW, restored.preferScoreBelow, 0.0)
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
