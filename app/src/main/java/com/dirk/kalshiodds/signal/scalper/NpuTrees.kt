package com.dirk.kalshiodds.signal.scalper

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/**
 * The scalper's trees on the Snapdragon NPU: `assets/scalper_model_npu.onnx` (written by
 * `tools/research/scalp/export_npu.py`) on ONNX Runtime's QNN execution provider, HTP backend (the Hexagon NPU).
 *
 * The NPU has no tree operator and computes in fp16, so the graph never sees a raw feature or a leaf value: it takes
 * each feature's split bin ([ScalperModel.bins], small whole numbers, exact in fp16) and returns which leaf every tree
 * lands in; [ScalperModel.marginFromLeaves] adds those leaves in double. The answer is the Kotlin trees' to the bit,
 * and [ScalperModel.attachOnnx] checks that on every split before the app uses it.
 *
 * CPU fallback inside ONNX Runtime is switched off for this session, so if any part of the graph cannot go on the NPU,
 * [open] throws (and says why) instead of quietly running on the CPU under the NPU's name.
 */
class NpuTrees private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val model: ScalperModel,
    /** "Snapdragon NPU (QNN HTP)", or "NPU graph on CPU" for the desktop tests. */
    val label: String
) : AutoCloseable {
    private val lock = Any()
    private val shape = longArrayOf(1, model.features.size.toLong())

    /** The trees' sum for one row of features (the model's order). Thread-safe. Throws on a malformed leaf mask. */
    fun margin(x: FloatArray): Double {
        val b = model.bins(x)
        val mask = synchronized(lock) {
            OnnxTensor.createTensor(env, FloatBuffer.wrap(b), shape).use { t ->
                session.run(mapOf(INPUT to t)).use { r ->
                    @Suppress("UNCHECKED_CAST")
                    (r[0].value as Array<FloatArray>)[0]
                }
            }
        }
        return model.marginFromLeaves(mask)
    }

    override fun close() {
        synchronized(lock) { runCatching { session.close() } }
    }

    companion object {
        const val INPUT = "bins"
        const val NPU_LABEL = "Snapdragon NPU (QNN HTP)"

        /** QNN HTP options: the HTP backend in burst mode, fp16 math (exact here: every value is a small whole number). */
        val QNN_OPTIONS: Map<String, String> = linkedMapOf(
            "backend_path" to "libQnnHtp.so",
            "htp_performance_mode" to "burst",
            "htp_graph_finalization_optimization_mode" to "3",
            "enable_htp_fp16_precision" to "1"
        )

        /** The phone's chip, for the screens ("QTI SM8650", or the board name on Android 11 and older). */
        fun chipName(): String = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 31) "${android.os.Build.SOC_MANUFACTURER} ${android.os.Build.SOC_MODEL}"
            else "${android.os.Build.HARDWARE} ${android.os.Build.BOARD}"
        }.getOrDefault("unknown")

        /** True on a Qualcomm Snapdragon (the only chips with the Hexagon NPU the QNN runtime drives). */
        fun isSnapdragon(): Boolean = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                android.os.Build.SOC_MANUFACTURER.equals("QTI", ignoreCase = true) ||
                    android.os.Build.SOC_MANUFACTURER.contains("Qualcomm", ignoreCase = true)
            } else {
                val hw = (android.os.Build.HARDWARE + " " + android.os.Build.BOARD).lowercase()
                hw.contains("qcom") || hw.contains("sm8") || hw.contains("kalama") || hw.contains("pineapple")
            }
        }.getOrDefault(false)

        /**
         * The Hexagon side loads its `libQnnHtpV**Skel.so` from ADSP_LIBRARY_PATH: point it at the app's own native
         * library folder first (where the APK's copy is), then the phone's DSP folders.
         */
        fun dspLibraryPath(nativeLibraryDir: String): String =
            listOf(nativeLibraryDir, "/vendor/dsp/cdsp", "/vendor/lib/rfsa/adsp", "/system/lib/rfsa/adsp", "/dsp")
                .filter { it.isNotBlank() }.joinToString(";")

        /**
         * Opens [npuBytes] for [model], built from [jsonBytes]. [onNpu] = true puts it on the QNN HTP backend with the CPU
         * fallback off (the phone); false runs the same graph on the CPU (desktop tests). Throws when the file is from
         * other trees, laid out differently, or the NPU cannot take it.
         */
        fun open(npuBytes: ByteArray, jsonBytes: ByteArray, model: ScalperModel, onNpu: Boolean, nativeLibraryDir: String = ""): NpuTrees {
            if (onNpu && nativeLibraryDir.isNotBlank()) {
                runCatching { android.system.Os.setenv("ADSP_LIBRARY_PATH", dspLibraryPath(nativeLibraryDir), true) }
            }
            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(1)
                setInterOpNumThreads(1)
                if (onNpu) {
                    addConfigEntry("session.disable_cpu_ep_fallback", "1")
                    addQnn(QNN_OPTIONS)
                } else {
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                }
            }
            val session = opts.use { env.createSession(npuBytes, it) }
            try {
                val meta = session.metadata.customMetadata
                check(meta["source_sha256"] == OnnxTrees.sha256(jsonBytes)) { "built from other trees" }
                check(meta["features"] == model.features.joinToString(",")) { "built for other features" }
                check(meta["trees"] == model.treeCount.toString() && meta["leaves"] == model.leafSlots.toString()) { "laid out for other trees" }
                check(session.inputNames == setOf(INPUT)) { "unexpected inputs ${session.inputNames}" }
                return NpuTrees(env, session, model, if (onNpu) NPU_LABEL else "NPU graph on CPU")
            } catch (e: Throwable) {
                runCatching { session.close() }
                throw e
            }
        }
    }
}
