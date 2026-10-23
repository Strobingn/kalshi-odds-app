package com.dirk.kalshiodds.decision

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.math.exp

class QuantSessionTest {
    @Before fun reset() = QuantSession.resetForTest()
    @After fun clear() = QuantSession.resetForTest()

    @Test fun stumpWalksTheTrueLeaf() {
        val model = stump()
        val low = DoubleArray(ModelFilter.FEATURES)
        low[0] = -1.0
        val high = DoubleArray(ModelFilter.FEATURES)
        high[0] = 1.0
        assertEquals(sigmoid(1.0), model.probability(low), 1e-9)
        assertEquals(sigmoid(-1.0), model.probability(high), 1e-9)
        assertEquals(1, model.maxDepth)
        assertTrue(model.maxDepth <= TreeEnsemble.MAX_DEPTH)
    }

    @Test fun rejectsDeepOrHugeForests() {
        val deep = ArrayList<TreeEnsemble.Spec>()
        val splits = 7
        for (i in 0 until splits) {
            val trueId = if (i == splits - 1) splits + 1 else i + 1
            deep += TreeEnsemble.Spec(0, i, 0, 0.0, "BRANCH_LEQ", trueId, splits, 0.0)
        }
        deep += TreeEnsemble.Spec(0, splits, 0, 0.0, "LEAF", 0, 0, 0.0)
        deep += TreeEnsemble.Spec(0, splits + 1, 0, 0.0, "LEAF", 0, 0, 1.0)
        val deepMsg = try {
            TreeEnsemble.build(intArrayOf(1, 1), deep, true, 0.0)
            ""
        } catch (e: IllegalArgumentException) {
            e.message.orEmpty()
        }
        assertTrue(deepMsg, deepMsg.contains("depth"))
        val many = (0 until 301).map { TreeEnsemble.Spec(it, 0, 0, 0.0, "LEAF", 0, 0, 0.1) }
        val hugeMsg = try {
            TreeEnsemble.build(intArrayOf(1, 1), many, true, 0.0)
            ""
        } catch (e: IllegalArgumentException) {
            e.message.orEmpty()
        }
        assertTrue(hugeMsg, hugeMsg.contains("300"))
    }

    @Test fun fitStaysInsideTheShallowBandAndSeparates() {
        val xs = ArrayList<DoubleArray>(80)
        val ys = DoubleArray(80)
        for (i in 0 until 80) {
            val pos = i % 2 == 0
            xs += DoubleArray(4) { f -> if (f == 0) if (pos) 1.0 else -1.0 else 0.0 }
            ys[i] = if (pos) 1.0 else 0.0
        }
        val forest = TreeEnsemble.fit(xs, ys)
        assertEquals(TreeEnsemble.TRAIN_TREES, forest.treeCount)
        assertTrue(forest.maxDepth in 1..TreeEnsemble.MAX_DEPTH)
        assertTrue(forest.trainedBand)
        var pos = 0.0
        var neg = 0.0
        var np = 0
        var nn = 0
        for (i in xs.indices) {
            val p = forest.probability(xs[i])
            if (ys[i] > 0.5) { pos += p; np++ } else { neg += p; nn++ }
        }
        assertTrue("pos ${pos / np} neg ${neg / nn}", pos / np > neg / nn + 0.05)
    }

    @Test fun onnxRoundTripAndFixedShape() {
        val model = stump()
        val back = OnnxGraph.parse(OnnxGraph.export(model))
        val x = DoubleArray(ModelFilter.FEATURES)
        x[0] = -1.0
        assertEquals(model.probability(x), back.probability(x), 1e-5)
        x[0] = 1.0
        assertEquals(model.probability(x), back.probability(x), 1e-5)
        val dynamic = OnnxGraph.exportGraph(intArrayOf(1, 17), listOf("TreeEnsembleClassifier"), model, dynamicAxis = true)
        assertTrue(parseError(dynamic).contains("dynamic"))
        val flow = OnnxGraph.exportGraph(intArrayOf(1, 17), listOf("If"))
        assertTrue(parseError(flow).contains("If"))
        val rank = OnnxGraph.exportGraph(intArrayOf(17), listOf("TreeEnsembleClassifier"))
        assertTrue(parseError(rank).contains("fixed shape"))
        assertEquals("dynamic axis is not allowed", OnnxGraph.fixedShapeError(listOf(1L, null), emptyList()))
        assertEquals("control-flow op Loop", OnnxGraph.fixedShapeError(listOf(1L, 17L), listOf("Loop"))?.take(20))
    }

    @Test fun ortFileIsCachedAndDoesNotReplaceTheScorer() {
        val ort = ByteArray(16)
        "ORTM".toByteArray(Charsets.ISO_8859_1).copyInto(ort, 4)
        val msg = QuantSession.importBytes("book.ort", ort)
        assertTrue(msg, msg.contains("context"))
        assertEquals(QuantSession.ModelMode.LOGISTIC, QuantSession.mode())
        assertTrue(File(System.getProperty("java.io.tmpdir"), "scalp-qnn/qnn/imported.ort").isFile)
    }

    @Test fun warmupThenLiveScore() {
        assertFalse(QuantSession.isWarm())
        val w = DoubleArray(ModelFilter.FEATURES)
        QuantSession.warmUp(w, 0.0)
        assertTrue(QuantSession.isWarm())
        val cold = QuantSession.coldStartUs()
        val warm = QuantSession.warmStartUs()
        assertTrue(cold >= 0L)
        assertTrue(warm >= 0L)
        QuantSession.warmUp(w, 0.0)
        assertEquals(cold, QuantSession.coldStartUs())
        assertEquals(warm, QuantSession.warmStartUs())
        QuantSession.resetForTest()
        QuantSession.score(w, 0.0, DoubleArray(ModelFilter.FEATURES))
        assertTrue(QuantSession.isWarm())
        assertTrue(QuantSession.p50Us() >= 0L)
        assertTrue(QuantSession.p99Us() >= QuantSession.p50Us())
    }

    @Test fun latencyPercentiles() {
        val t = LatencyTracker()
        assertEquals(-1L, t.p50())
        t.add(10)
        t.add(20)
        t.add(30)
        t.add(40)
        assertEquals(20L, t.p50())
        assertEquals(40L, t.p95())
        assertEquals(40L, t.p99())
        val ring = LatencyTracker()
        for (i in 0 until 100) ring.add(i.toLong())
        assertEquals(64, ring.count())
        assertEquals(67L, ring.p50())
        assertEquals(96L, ring.p95())
        assertEquals(99L, ring.p99())
    }

    @Test fun modeSwitchUsesLogisticTreeEnsembleAndOnnx() {
        val w = DoubleArray(ModelFilter.FEATURES)
        w[0] = 2.0
        val x = DoubleArray(ModelFilter.FEATURES)
        x[0] = 1.0
        QuantSession.setMode(QuantSession.ModelMode.LOGISTIC)
        val logistic = QuantSession.score(w, 0.0, x)
        assertEquals(sigmoid(2.0), logistic, 1e-9)
        assertEquals("Logistic", QuantSession.telemetry().modelType)
        QuantSession.fitForest(listOf(x, DoubleArray(ModelFilter.FEATURES)), doubleArrayOf(1.0, 0.0))
        // Neutral until a real forest exists; a two-row fit stays empty and the score stays 0.5.
        QuantSession.setMode(QuantSession.ModelMode.TREE_ENSEMBLE)
        assertEquals(0.5, QuantSession.score(w, 0.0, x), 1e-9)
        assertEquals("TreeEnsemble", QuantSession.telemetry().modelType)
        val msg = QuantSession.importBytes("stump.onnx", OnnxGraph.export(stump()))
        assertTrue(msg, msg.startsWith("ONNX"))
        assertEquals("ONNX", QuantSession.telemetry().modelType)
        val onnx = QuantSession.score(w, 0.0, x)
        assertEquals(sigmoid(-1.0), onnx, 1e-5)
        QuantSession.setMode(QuantSession.ModelMode.ENSEMBLE)
        val ensemble = QuantSession.score(w, 0.0, x)
        val tree = 0.5
        val residual = sigmoid(0.0)
        assertEquals((sigmoid(2.0) + tree + residual) / 3.0, ensemble, 1e-9)
        assertEquals("Ensemble", QuantSession.telemetry().modelType)
        QuantSession.setMode(QuantSession.ModelMode.ONNX)
        assertEquals(sigmoid(-1.0), QuantSession.score(w, 0.0, x), 1e-5)
        assertEquals("ONNX", QuantSession.telemetry().modelType)
    }

    @Test fun qnnOptionsAndBenchmarkKeepCpuUnlessFaster() {
        val cache = File(System.getProperty("java.io.tmpdir"), "scalp-qnn-test")
        val htp = QuantSession.sessionConfig(QuantSession.ExecPath.QNN_HTP_BURST, cache)
        assertEquals(1, htp.intraOpNumThreads)
        assertEquals("BASIC", htp.graphOptimizationLevel)
        assertTrue(htp.disableCpuEpFallback)
        assertEquals(listOf(1, ModelFilter.FEATURES), htp.inputShape)
        assertTrue(htp.shapeFixed)
        assertEquals("libQnnHtp.so", htp.providerOptions["backend_path"])
        assertEquals("burst", htp.providerOptions["htp_performance_mode"])
        assertEquals("high", htp.providerOptions["qnn_context_priority"])
        assertEquals("3", htp.providerOptions["htp_graph_finalization_optimization_mode"])
        assertEquals("1", htp.providerOptions["enable_htp_fp16_precision"])
        assertEquals("8", htp.providerOptions["vtcm_mb"])
        assertEquals("0", htp.providerOptions["rpc_control_latency"])
        assertEquals("1", htp.configEntries["ep.context_enable"])
        assertEquals("1", htp.configEntries["session.disable_cpu_ep_fallback"])
        val ctx = htp.configEntries.getValue("ep.context_file_path")
        assertTrue(ctx.startsWith(cache.absolutePath))
        assertTrue(ctx.endsWith("scalp-quant-htp.ctx.onnx"))
        val gpu = QuantSession.sessionConfig(QuantSession.ExecPath.QNN_GPU, cache)
        assertEquals("libQnnGpu.so", gpu.providerOptions["backend_path"])
        assertEquals("fp16", gpu.providerOptions["gpu_precision"])
        assertEquals("fp32", gpu.providerOptions["gpu_precision_fallback"])
        assertTrue(gpu.configEntries.getValue("ep.context_file_path").endsWith("scalp-quant-gpu.ctx.onnx"))
        val cpu = QuantSession.sessionConfig(QuantSession.ExecPath.CPU_FAST, cache)
        assertEquals(1, cpu.intraOpNumThreads)
        assertEquals("ALL", cpu.graphOptimizationLevel)
        assertFalse(cpu.disableCpuEpFallback)
        assertTrue(cpu.configEntries.isEmpty())
        val nn = QuantSession.sessionConfig(QuantSession.ExecPath.NNAPI, cache)
        assertEquals("1", nn.providerOptions["disable_cpu_fallback"])
        assertTrue(nn.disableCpuEpFallback)
        val missing = QuantSession.benchmark(cache, probe = { false }, runtimeReady = true, measure = { path, _ ->
            if (path != QuantSession.ExecPath.CPU_FAST) error(path.name)
            QuantSession.EpTiming(20, 10)
        })
        assertEquals(QuantSession.ExecPath.CPU_FAST, missing.selected)
        assertEquals(listOf(QuantSession.ExecPath.CPU_FAST, QuantSession.ExecPath.QNN_HTP_BURST, QuantSession.ExecPath.QNN_GPU), missing.samples.map { it.path })
        assertTrue(missing.samples[1].note.contains("libQnnHtp.so"))
        assertFalse(missing.samples[1].available)
        val unlinked = QuantSession.benchmark(cache, probe = { true }, runtimeReady = false, measure = { path, _ ->
            if (path != QuantSession.ExecPath.CPU_FAST) error("qnn measured")
            QuantSession.EpTiming(50, 40)
        })
        assertEquals(QuantSession.ExecPath.CPU_FAST, unlinked.selected)
        assertTrue(unlinked.samples[1].note.contains("not in this build"))
        val faster = QuantSession.benchmark(cache, probe = { true }, runtimeReady = true, measure = { path, _ ->
            when (path) {
                QuantSession.ExecPath.CPU_FAST -> QuantSession.EpTiming(300, 200)
                QuantSession.ExecPath.QNN_HTP_BURST -> QuantSession.EpTiming(80, 40)
                QuantSession.ExecPath.QNN_GPU -> QuantSession.EpTiming(120, 90)
                else -> QuantSession.EpTiming(100, 100)
            }
        })
        assertEquals(QuantSession.ExecPath.QNN_HTP_BURST, faster.selected)
        assertEquals("QNN HTP Burst", QuantSession.telemetry().execPath)
        assertFalse(QuantSession.telemetry().auto.not())
        val noise = QuantSession.benchmark(cache, probe = { name -> name == QuantSession.GPU_LIB }, runtimeReady = true, measure = { path, _ ->
            when (path) {
                QuantSession.ExecPath.CPU_FAST -> QuantSession.EpTiming(120, 100)
                QuantSession.ExecPath.QNN_GPU -> QuantSession.EpTiming(110, 90)
                else -> error(path.name)
            }
        })
        assertEquals(QuantSession.ExecPath.CPU_FAST, noise.selected)
        val gpuWins = QuantSession.benchmark(cache, probe = { name -> name == QuantSession.GPU_LIB }, runtimeReady = true, measure = { path, _ ->
            when (path) {
                QuantSession.ExecPath.CPU_FAST -> QuantSession.EpTiming(400, 300)
                QuantSession.ExecPath.QNN_GPU -> QuantSession.EpTiming(80, 40)
                else -> error(path.name)
            }
        })
        assertEquals(QuantSession.ExecPath.QNN_GPU, gpuWins.selected)
        val note = QuantSession.select(QuantSession.ExecPath.QNN_HTP_BURST)
        assertEquals(QuantSession.ExecPath.CPU_FAST, QuantSession.execPath())
        assertTrue(note.contains("CPU"))
    }

    @Test fun proguardKeepsOnnxRuntime() {
        val file = listOf(File("proguard-rules.pro"), File("app/proguard-rules.pro")).first { it.isFile }
        assertTrue(file.readText().contains("-keep class ai.onnxruntime.** { *; }"))
    }

    private fun stump(): TreeEnsemble {
        val specs = listOf(
            TreeEnsemble.Spec(0, 0, 0, 0.0, "BRANCH_LEQ", 1, 2, 0.0),
            TreeEnsemble.Spec(0, 1, 0, 0.0, "LEAF", 0, 0, 1.0),
            TreeEnsemble.Spec(0, 2, 0, 0.0, "LEAF", 0, 0, -1.0)
        )
        return TreeEnsemble.build(intArrayOf(1, ModelFilter.FEATURES), specs, true, 0.0)
    }

    private fun parseError(bytes: ByteArray): String = try {
        OnnxGraph.parse(bytes)
        ""
    } catch (e: IllegalArgumentException) {
        e.message.orEmpty()
    }

    private fun sigmoid(z: Double) = 1.0 / (1.0 + exp(-z))
}
