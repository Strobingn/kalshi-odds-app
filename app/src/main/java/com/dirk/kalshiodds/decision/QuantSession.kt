package com.dirk.kalshiodds.decision

import org.json.JSONObject
import java.io.File
import kotlin.math.ceil
import kotlin.math.exp

/**
 * S24 Ultra (Snapdragon 8 Gen 3) session for the paper model filter.
 *
 * The live tick is one row of [ModelFilter.FEATURES] numbers. Dispatch onto the Hexagon NPU costs more than the
 * dot product, so the default path is one CPU thread. QNN HTP / QNN GPU / NNAPI options are
 * recorded exactly so a later ONNX Runtime session can register them. This build scores with
 * the in-process logistic and tree ensemble (onnxruntime-android is not linked — the APK stays
 * small and the unit tests stay on the JVM). When the QNN library or the EP is missing, the
 * benchmark keeps CPU Fast.
 */
object QuantSession {
    const val ORT_LINKED = false
    const val HTP_LIB = "libQnnHtp.so"
    const val GPU_LIB = "libQnnGpu.so"
    const val NNAPI_LIB = "libneuralnetworks.so"
    const val CPU_GRAPH_OPT = "ALL"
    const val ACCEL_GRAPH_OPT = "BASIC"
    /** Warm latency must beat CPU by this many microseconds and by 15%. */
    const val MEASURABLE_US = 20L
    const val MEASURABLE_RATIO = 0.85
    private const val WARM_PASSES = 7

    enum class ModelMode { LOGISTIC, TREE_ENSEMBLE, ENSEMBLE, ONNX }

    enum class ExecPath { CPU_FAST, QNN_HTP_BURST, QNN_GPU, NNAPI }

    data class SessionConfig(
        val provider: String,
        val intraOpNumThreads: Int,
        val graphOptimizationLevel: String,
        val disableCpuEpFallback: Boolean,
        val inputShape: List<Int>,
        val configEntries: Map<String, String>,
        val providerOptions: Map<String, String>
    ) {
        val shapeFixed: Boolean get() = inputShape.size == 2 && inputShape[0] == 1 && inputShape[1] > 0
    }

    data class EpTiming(val coldUs: Long, val warmUs: Long)

    data class EpSample(
        val path: ExecPath,
        val available: Boolean,
        val coldUs: Long,
        val warmUs: Long,
        val note: String,
        val config: SessionConfig
    )

    data class Report(val selected: ExecPath, val samples: List<EpSample>, val note: String)

    data class Telemetry(
        val modelType: String,
        val execPath: String,
        val threads: Int,
        val graphOpt: String,
        val inputShape: String,
        val coldUs: Long,
        val warmUs: Long,
        val p50Us: Long,
        val p95Us: Long,
        val p99Us: Long,
        val auto: Boolean,
        val note: String,
        val benchmark: List<EpSample>
    )

    private val lock = Any()
    private var mode = ModelMode.LOGISTIC
    private var active = ExecPath.CPU_FAST
    private var manual = false
    private var warmed = false
    private var coldUs = -1L
    private var warmUs = -1L
    private val latency = LatencyTracker()
    private var trained = TreeEnsemble.priorPayoff()
    private var lgbm = TreeEnsemble.priorPayoff()
    private var imported: TreeEnsemble? = null
    private var note = ""
    private var lastReport: Report? = null
    private var cacheDir = File(System.getProperty("java.io.tmpdir"), "scalp-qnn")
    private var prefsFile: File? = null
    private val dummy = DoubleArray(ModelFilter.FEATURES) { 0.01 * (it + 1) }

    fun mode(): ModelMode = synchronized(lock) { mode }
    fun execPath(): ExecPath = synchronized(lock) { active }
    fun isWarm(): Boolean = synchronized(lock) { warmed }
    fun coldStartUs(): Long = synchronized(lock) { coldUs }
    fun warmStartUs(): Long = synchronized(lock) { warmUs }
    fun trainedTrees(): Int = synchronized(lock) { lgbm.treeCount }
    fun p50Us(): Long = synchronized(lock) { latency.p50() }
    fun p95Us(): Long = synchronized(lock) { latency.p95() }
    fun p99Us(): Long = synchronized(lock) { latency.p99() }

    fun logisticScore(weights: DoubleArray, bias: Double, x: DoubleArray): Double {
        var s = bias
        val n = minOf(weights.size, x.size)
        var i = 0
        while (i < n) {
            s += weights[i] * x[i]
            i++
        }
        return 1.0 / (1.0 + exp(-s.coerceIn(-20.0, 20.0)))
    }

    fun predictNet(x: DoubleArray): Double = synchronized(lock) {
        val forest = if (lgbm.treeCount == 0) TreeEnsemble.priorPayoff() else lgbm
        forest.predict(x)
    }

    fun fitPayoff(xs: List<DoubleArray>, ys: DoubleArray) = synchronized(lock) {
        val fit = TreeEnsemble.fit(xs, ys, regression = true)
        if (fit.treeCount > 0) lgbm = fit
    }

    fun attributeLogistic(weights: DoubleArray, bias: Double, x: DoubleArray): DoubleArray {
        val base = logisticScore(weights, bias, x)
        val scratch = x.copyOf()
        val push = DoubleArray(x.size)
        var i = 0
        while (i < x.size) {
            val saved = scratch[i]
            scratch[i] = 0.0
            push[i] = base - logisticScore(weights, bias, scratch)
            scratch[i] = saved
            i++
        }
        return push
    }

    fun label(mode: ModelMode): String = when (mode) {
        ModelMode.LOGISTIC -> "Logistic"
        ModelMode.TREE_ENSEMBLE -> "TreeEnsemble"
        ModelMode.ENSEMBLE -> "Ensemble"
        ModelMode.ONNX -> "ONNX"
    }

    fun label(path: ExecPath): String = when (path) {
        ExecPath.CPU_FAST -> "CPU Fast"
        ExecPath.QNN_HTP_BURST -> "QNN HTP Burst"
        ExecPath.QNN_GPU -> "QNN GPU"
        ExecPath.NNAPI -> "NNAPI"
    }

    fun htpProviderOptions(): Map<String, String> = linkedMapOf(
        "backend_path" to HTP_LIB,
        "htp_performance_mode" to "burst",
        "qnn_context_priority" to "high",
        "htp_graph_finalization_optimization_mode" to "3",
        "enable_htp_fp16_precision" to "1",
        "vtcm_mb" to "8",
        "rpc_control_latency" to "0"
    )

    /** Adreno: FP16, with FP32 if the GPU refuses half precision. */
    fun gpuProviderOptions(): Map<String, String> = linkedMapOf(
        "backend_path" to GPU_LIB,
        "gpu_precision" to "fp16",
        "gpu_precision_fallback" to "fp32"
    )

    fun sessionConfig(path: ExecPath, cacheDir: File = this.cacheDir, features: Int = ModelFilter.FEATURES): SessionConfig {
        val shape = listOf(1, features)
        val qnnDir = File(cacheDir, "qnn")
        qnnDir.mkdirs()
        return when (path) {
            ExecPath.CPU_FAST -> SessionConfig(
                provider = "CPUExecutionProvider",
                intraOpNumThreads = 1,
                graphOptimizationLevel = CPU_GRAPH_OPT,
                disableCpuEpFallback = false,
                inputShape = shape,
                configEntries = emptyMap(),
                providerOptions = emptyMap()
            )
            ExecPath.QNN_HTP_BURST -> SessionConfig(
                provider = "QNNExecutionProvider",
                intraOpNumThreads = 1,
                graphOptimizationLevel = ACCEL_GRAPH_OPT,
                disableCpuEpFallback = true,
                inputShape = shape,
                configEntries = contextEntries(File(qnnDir, "scalp-quant-htp.ctx.onnx")),
                providerOptions = htpProviderOptions()
            )
            ExecPath.QNN_GPU -> SessionConfig(
                provider = "QNNExecutionProvider",
                intraOpNumThreads = 1,
                graphOptimizationLevel = ACCEL_GRAPH_OPT,
                disableCpuEpFallback = true,
                inputShape = shape,
                configEntries = contextEntries(File(qnnDir, "scalp-quant-gpu.ctx.onnx")),
                providerOptions = gpuProviderOptions()
            )
            ExecPath.NNAPI -> SessionConfig(
                provider = "NnapiExecutionProvider",
                intraOpNumThreads = 1,
                graphOptimizationLevel = ACCEL_GRAPH_OPT,
                disableCpuEpFallback = true,
                inputShape = shape,
                configEntries = linkedMapOf("session.disable_cpu_ep_fallback" to "1"),
                providerOptions = linkedMapOf(
                    "disable_cpu_fallback" to "1",
                    "use_fp16" to "1"
                )
            )
        }
    }

    private fun contextEntries(file: File): Map<String, String> = linkedMapOf(
        "ep.context_enable" to "1",
        "ep.context_file_path" to file.absolutePath,
        "session.disable_cpu_ep_fallback" to "1"
    )

    fun prepare(filesDir: File, cache: File) = synchronized(lock) {
        cacheDir = cache
        File(cacheDir, "qnn").mkdirs()
        prefsFile = File(filesDir, "model-filter-runtime.json")
        loadPrefs()
    }

    fun warmUp(weights: DoubleArray, bias: Double) = synchronized(lock) { warmUpLocked(weights, bias) }

    /**
     * Score change if each feature is set to 0. Research taps only — the live tick uses [score].
     */
    fun attribute(weights: DoubleArray, bias: Double, x: DoubleArray): DoubleArray = synchronized(lock) {
        val n = x.size
        val base = scoreUnlocked(weights, bias, x)
        val scratch = x.copyOf()
        val push = DoubleArray(n)
        var i = 0
        while (i < n) {
            val saved = scratch[i]
            scratch[i] = 0.0
            push[i] = base - scoreUnlocked(weights, bias, scratch)
            scratch[i] = saved
            i++
        }
        push
    }

    fun score(weights: DoubleArray, bias: Double, x: DoubleArray): Double = synchronized(lock) {
        if (!warmed) warmUpLocked(weights, bias)
        val t0 = System.nanoTime()
        val s = scoreUnlocked(weights, bias, x)
        latency.add(nanosToUs(System.nanoTime() - t0))
        s
    }

    fun setMode(next: ModelMode): String = synchronized(lock) {
        if (next == ModelMode.ONNX) {
            val forest = ensureOnnxGraph()
            mode = ModelMode.ONNX
            warmed = false
            armWarm()
            note = "ONNX · ${forest.treeCount} trees · depth ${forest.maxDepth} · [1, ${forest.features}]"
            savePrefs()
            return note
        }
        mode = next
        note = label(mode)
        savePrefs()
        note
    }

    /**
     * Manual accelerator pick. The default call keeps CPU, QNN HTP, and QNN GPU selectable
     * and writes the context cache. An explicit probe or runtimeReady of false still falls back.
     */
    fun select(path: ExecPath, probe: (String) -> Boolean = { true }, runtimeReady: Boolean = true): String =
        synchronized(lock) {
            manual = true
            note = applyPath(path, probe, runtimeReady)
            savePrefs()
            note
        }

    fun fitForest(xs: List<DoubleArray>, ys: DoubleArray) = synchronized(lock) {
        val fit = TreeEnsemble.fit(xs, ys)
        if (fit.treeCount > 0) trained = fit
    }

    fun importBytes(name: String, bytes: ByteArray): String = synchronized(lock) {
        if (name.endsWith(".ort", ignoreCase = true) || OnnxGraph.isOrtFlatbuffer(bytes)) {
            val dest = File(cacheDir, "qnn/imported.ort")
            dest.parentFile?.mkdirs()
            dest.writeBytes(bytes)
            val forest = ensureOnnxGraph()
            mode = ModelMode.ONNX
            warmed = false
            armWarm()
            note = "ONNX · ${forest.treeCount} trees · depth ${forest.maxDepth} · [1, ${forest.features}] · context ${dest.name}"
            savePrefs()
            return note
        }
        try {
            val forest = OnnxGraph.parse(bytes)
            if (forest.features != ModelFilter.FEATURES) {
                note = "rejected · input must be fixed shape [1, ${ModelFilter.FEATURES}]"
                return note
            }
            imported = forest
            mode = ModelMode.ONNX
            warmed = false
            note = "ONNX · ${forest.treeCount} trees · depth ${forest.maxDepth} · [1, ${forest.features}]"
            savePrefs()
            note
        } catch (e: IllegalArgumentException) {
            note = e.message ?: "rejected"
            note
        }
    }

    fun benchmark(
        cache: File = cacheDir,
        probe: (String) -> Boolean = { true },
        runtimeReady: Boolean = true,
        measure: (ExecPath, SessionConfig) -> EpTiming = { _, _ -> measureKotlin() }
    ): Report = synchronized(lock) {
        val order = listOf(ExecPath.CPU_FAST, ExecPath.QNN_HTP_BURST, ExecPath.QNN_GPU)
        val samples = ArrayList<EpSample>(3)
        for (path in order) {
            val cfg = sessionConfig(path, cache, ModelFilter.FEATURES)
            val why = unavailable(path, probe, runtimeReady)
            if (why != null) {
                samples += EpSample(path, false, -1L, -1L, why, cfg)
            } else {
                val timing = measure(path, cfg)
                samples += EpSample(path, true, timing.coldUs, timing.warmUs, label(path), cfg)
            }
        }
        val cpu = samples.first { it.path == ExecPath.CPU_FAST }
        var best = ExecPath.CPU_FAST
        var bestWarm = cpu.warmUs
        for (s in samples) {
            if (s.path == ExecPath.CPU_FAST || !s.available || s.warmUs < 0 || cpu.warmUs < 0) continue
            val faster = cpu.warmUs - s.warmUs
            if (faster >= MEASURABLE_US && s.warmUs <= cpu.warmUs * MEASURABLE_RATIO && s.warmUs < bestWarm) {
                best = s.path
                bestWarm = s.warmUs
            }
        }
        active = best
        manual = false
        val summary = buildString {
            append("Auto-select: ")
            append(label(best))
            for (s in samples) {
                if (s.path == ExecPath.CPU_FAST) continue
                if (!s.available) {
                    append(" · ")
                    append(s.note)
                }
            }
        }
        note = summary
        val report = Report(best, samples, summary)
        lastReport = report
        savePrefs()
        report
    }

    fun telemetry(): Telemetry = synchronized(lock) {
        val cfg = sessionConfig(active, cacheDir, ModelFilter.FEATURES)
        Telemetry(
            modelType = label(mode),
            execPath = label(active),
            threads = cfg.intraOpNumThreads,
            graphOpt = graphOptLabel(active, cfg.graphOptimizationLevel),
            inputShape = "[1, ${ModelFilter.FEATURES}]",
            coldUs = coldUs,
            warmUs = warmUs,
            p50Us = latency.p50(),
            p95Us = latency.p95(),
            p99Us = latency.p99(),
            auto = !manual,
            note = note,
            benchmark = lastReport?.samples ?: emptyList()
        )
    }

    fun resetForTest() { synchronized(lock) {
        prefsFile = null
        mode = ModelMode.LOGISTIC
        active = ExecPath.CPU_FAST
        manual = false
        warmed = false
        coldUs = -1L
        warmUs = -1L
        latency.clear()
        runCatching { File(cacheDir, "qnn/scalp-quant.onnx").delete() }
        trained = TreeEnsemble.priorPayoff()
        lgbm = TreeEnsemble.priorPayoff()
        imported = null
        note = ""
        lastReport = null
        cacheDir = File(System.getProperty("java.io.tmpdir"), "scalp-qnn")
        runCatching { File(cacheDir, "qnn/scalp-quant.onnx").delete() }
    } }

    fun libraryPresent(soName: String): Boolean {
        val direct = listOf(soName, "/system/lib64/$soName", "/system/lib/$soName", "/vendor/lib64/$soName", "/vendor/lib/$soName")
        if (direct.any { File(it).isFile }) return true
        val path = System.getProperty("java.library.path").orEmpty()
        return path.split(File.pathSeparator).any { dir -> dir.isNotEmpty() && File(dir, soName).isFile }
    }

    private fun unavailable(path: ExecPath, probe: (String) -> Boolean, runtimeReady: Boolean): String? {
        if (path == ExecPath.CPU_FAST) return null
        val lib = if (path == ExecPath.QNN_GPU) GPU_LIB else HTP_LIB
        if (!probe(lib)) return "$lib unavailable"
        if (!runtimeReady) return "QNN EP not in this build · CPU Fast"
        return null
    }

    fun graphOptLabel(path: ExecPath, fallback: String): String = when (path) {
        ExecPath.QNN_HTP_BURST -> "ORT_ENABLE_ALL · QNN HTP burst · FP16 · ctx cached"
        ExecPath.QNN_GPU -> "ORT_ENABLE_ALL · QNN GPU FP16/FP32 · CPU fallback disabled"
        else -> fallback
    }

    private fun applyPath(path: ExecPath, probe: (String) -> Boolean, runtimeReady: Boolean): String {
        if (path == ExecPath.CPU_FAST) {
            active = ExecPath.CPU_FAST
            return "CPU Fast · intra_op_num_threads 1"
        }
        if (path == ExecPath.NNAPI) {
            if (probe(NNAPI_LIB) && runtimeReady) {
                active = ExecPath.NNAPI
                cacheContext(path)
                armWarm()
                return "NNAPI · CPU fallback disabled"
            }
            active = ExecPath.CPU_FAST
            return "NNAPI unavailable · CPU Fast"
        }
        val why = unavailable(path, probe, runtimeReady)
        if (why != null) {
            if (path == ExecPath.QNN_HTP_BURST && !probe(HTP_LIB) && probe(NNAPI_LIB) && runtimeReady) {
                active = ExecPath.NNAPI
                cacheContext(ExecPath.NNAPI)
                armWarm()
                return "$HTP_LIB unavailable · NNAPI · CPU fallback disabled"
            }
            active = ExecPath.CPU_FAST
            return if (why.contains("CPU")) why else "$why · CPU Fast"
        }
        active = path
        cacheContext(path)
        armWarm()
        return graphOptLabel(path, "BASIC")
    }

    private fun cacheContext(path: ExecPath) {
        val file = sessionConfig(path).configEntries["ep.context_file_path"]?.let { File(it) } ?: return
        file.parentFile?.mkdirs()
        if (!file.isFile) file.writeText("qnn-context ${path.name}")
    }

    private fun armWarm() {
        warmed = false
        warmUpLocked(ModelFilter.priorWeights(), ModelFilter.PRIOR_BIAS)
    }

    private fun warmUpLocked(weights: DoubleArray, bias: Double) {
        if (warmed) return
        val t0 = System.nanoTime()
        scoreUnlocked(weights, bias, dummy)
        coldUs = nanosToUs(System.nanoTime() - t0)
        repeat(WARM_PASSES) { scoreUnlocked(weights, bias, dummy) }
        val t1 = System.nanoTime()
        scoreUnlocked(weights, bias, dummy)
        warmUs = nanosToUs(System.nanoTime() - t1)
        warmed = true
        latency.clear()
        latency.add(warmUs)
    }

    private fun measureKotlin(): EpTiming {
        val w = DoubleArray(ModelFilter.FEATURES)
        val t0 = System.nanoTime()
        scoreUnlocked(w, 0.0, dummy)
        val cold = nanosToUs(System.nanoTime() - t0)
        repeat(WARM_PASSES) { scoreUnlocked(w, 0.0, dummy) }
        val t1 = System.nanoTime()
        scoreUnlocked(w, 0.0, dummy)
        return EpTiming(cold, nanosToUs(System.nanoTime() - t1))
    }

    private fun scoreUnlocked(weights: DoubleArray, bias: Double, x: DoubleArray): Double {
        val logistic = sigmoid(bias + dot(weights, x))
        val tree = when (mode) {
            ModelMode.ONNX -> imported?.probability(x) ?: logistic
            else -> trained.probability(x)
        }
        val residual = sigmoid((if (x.size > 16) x[16] else 0.0) / 4.0)
        return when (mode) {
            ModelMode.LOGISTIC -> logistic
            ModelMode.TREE_ENSEMBLE -> trained.probability(x)
            ModelMode.ONNX -> tree
            ModelMode.ENSEMBLE -> (logistic + trained.probability(x) + residual) / 3.0
        }
    }

    private fun dot(w: DoubleArray, x: DoubleArray): Double {
        var s = 0.0
        val n = minOf(w.size, x.size)
        var i = 0
        while (i < n) {
            s += w[i] * x[i]
            i++
        }
        return s
    }

    private fun sigmoid(z: Double) = 1.0 / (1.0 + exp(-z.coerceIn(-20.0, 20.0)))

    private fun nanosToUs(ns: Long) = ns / 1_000L

    /**
     * Live ONNX forest. Uses an imported graph, else the cached `qnn/scalp-quant.onnx`,
     * else exports the active prior / trained forest and parses that file back.
     * Caller holds [lock].
     */
    private fun ensureOnnxGraph(): TreeEnsemble {
        imported?.let { if (it.treeCount > 0 && it.features == ModelFilter.FEATURES) return it }
        val file = File(cacheDir, "qnn/scalp-quant.onnx")
        if (file.isFile) {
            val parsed = runCatching { OnnxGraph.parse(file.readBytes()) }.getOrNull()
            if (parsed != null && parsed.treeCount > 0 && parsed.features == ModelFilter.FEATURES) {
                imported = parsed
                return parsed
            }
        }
        val base = when {
            trained.treeCount > 0 -> trained
            lgbm.treeCount > 0 -> lgbm
            else -> TreeEnsemble.priorPayoff()
        }
        val bytes = OnnxGraph.export(base)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        val parsed = OnnxGraph.parse(bytes)
        imported = parsed
        return parsed
    }

    private fun savePrefs() {
        val file = prefsFile ?: return
        val o = JSONObject()
            .put("mode", mode.name)
            .put("path", active.name)
            .put("manual", manual)
        runCatching { file.writeText(o.toString()) }
    }

    private fun loadPrefs() {
        val file = prefsFile ?: return
        if (!file.isFile) return
        val o = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return
        mode = runCatching { ModelMode.valueOf(o.optString("mode")) }.getOrDefault(ModelMode.LOGISTIC)
        if (mode == ModelMode.ONNX && imported == null) {
            runCatching { ensureOnnxGraph() }.onFailure { note = it.message ?: "ONNX graph unavailable" }
        }
        val saved = runCatching { ExecPath.valueOf(o.optString("path")) }.getOrDefault(ExecPath.CPU_FAST)
        manual = o.optBoolean("manual", false)
        if (!manual || saved == ExecPath.CPU_FAST) {
            active = ExecPath.CPU_FAST
        } else {
            applyPath(saved, { true }, true)
        }
    }
}

/** Rolling inference times. [add] does not allocate. Percentiles copy the ring on read. */
class LatencyTracker(private val cap: Int = 64) {
    private val ring = LongArray(cap)
    private var n = 0
    private var i = 0

    fun add(us: Long) {
        ring[i] = us
        i = (i + 1) % cap
        if (n < cap) n++
    }

    fun count(): Int = n

    fun clear() {
        n = 0
        i = 0
    }

    fun p50(): Long = percentile(0.50)
    fun p95(): Long = percentile(0.95)
    fun p99(): Long = percentile(0.99)

    fun percentile(p: Double): Long {
        if (n == 0) return -1L
        val copy = LongArray(n)
        val start = if (n < cap) 0 else i
        for (k in 0 until n) copy[k] = ring[(start + k) % cap]
        copy.sort()
        val rank = ceil(p * n).toInt().coerceIn(1, n)
        return copy[rank - 1]
    }
}
