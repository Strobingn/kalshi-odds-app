package com.dirk.kalshiodds.signal.scalper

/**
 * Picks where the ML scalper's trees run: the Snapdragon NPU first, then ONNX Runtime on the CPU, then the Kotlin
 * trees. An engine is used only after it gives the Kotlin trees' answer on every split ([ScalperModel.attachOnnx]).
 * Every engine that opens is timed, so the screens can show each one's speed and why any was not used
 * ([ScalperModel.engines]).
 */
/** Where you choose the ML scalper's trees run (AI → ML scalper model). */
enum class MlEngine(val label: String) {
    KOTLIN("Kotlin trees"),
    ONNX_CPU("ONNX Runtime (CPU)"),
    NPU(NpuTrees.NPU_LABEL);

    companion object {
        val DEFAULT = ONNX_CPU
        fun of(name: String?): MlEngine = values().firstOrNull { it.name == name } ?: DEFAULT
    }
}

object ModelEngines {

    /**
     * Runs the trees on the engine you chose: [open] opens it (it throws when it cannot), and it is used only after it
     * gives the Kotlin trees' answer on every split. Anything that fails leaves the Kotlin trees in use and says why in
     * [ScalperModel.engines]. Returns the attached engine (still open; the caller keeps it), or null. Never throws.
     */
    fun use(model: ScalperModel, choice: MlEngine, open: (MlEngine) -> Opened, probes: List<FloatArray> = model.probeRows()): Opened? {
        val out = ArrayList<ScalperModel.EngineTiming>()
        var attached: Opened? = null
        var why = "your choice"
        if (choice != MlEngine.KOTLIN) {
            val t0 = System.nanoTime()
            val e = try {
                open(choice)
            } catch (t: Throwable) {
                why = "${choice.label}: ${short(t)}"
                out += ScalperModel.EngineTiming(choice.label, false, short(t), (System.nanoTime() - t0) / 1_000_000, 0.0)
                null
            }
            if (e != null) {
                val openMs = (System.nanoTime() - t0) / 1_000_000
                val warm = runCatching { model.timeMicros(e.run, probes) }.getOrDefault(0.0)
                val failed = model.attachOnnx(e.run, e.label, probes)
                if (failed == null) {
                    attached = e
                    out += ScalperModel.EngineTiming(e.label, true, "in use (your choice) · matches the trees on every split", openMs, warm)
                } else {
                    why = "${e.label}: $failed"
                    out += ScalperModel.EngineTiming(e.label, false, failed, openMs, warm)
                    runCatching { e.close() }
                }
            }
        }
        val kotlinWarm = runCatching { model.timeMicros({ model.marginTrees(it) }, probes) }.getOrDefault(0.0)
        out += ScalperModel.EngineTiming(
            "Kotlin trees", true,
            when {
                attached != null -> "fallback"
                choice == MlEngine.KOTLIN -> "in use (your choice)"
                else -> "in use (fallback)"
            },
            0L, kotlinWarm
        )
        if (attached == null) model.markKotlin(why)
        model.engines = out
        return attached
    }

    /** An opened engine: its trees' sum for a row, and how to shut it. */
    class Opened(val label: String, val run: (FloatArray) -> Double, val close: () -> Unit)

    /**
     * Tries [npu] then [cpu] (each opens its engine or throws). Returns the engine left attached to [model]
     * (still open; the caller keeps it), or null when the Kotlin trees run. Never throws.
     */
    fun choose(model: ScalperModel, npu: () -> Opened, cpu: () -> Opened, probes: List<FloatArray> = model.probeRows()): Opened? {
        val out = ArrayList<ScalperModel.EngineTiming>()
        var attached: Opened? = null
        val reasons = ArrayList<String>()
        for ((name, open) in listOf(NpuTrees.NPU_LABEL to npu, "ONNX Runtime (CPU)" to cpu)) {
            val t0 = System.nanoTime()
            val e = try {
                open()
            } catch (t: Throwable) {
                val why = short(t)
                out += ScalperModel.EngineTiming(name, false, why, (System.nanoTime() - t0) / 1_000_000, 0.0)
                reasons += "$name: $why"
                continue
            }
            val openMs = (System.nanoTime() - t0) / 1_000_000
            val warm = runCatching { model.timeMicros(e.run, probes) }.getOrDefault(0.0)
            if (attached == null) {
                val failed = model.attachOnnx(e.run, e.label, probes)
                if (failed == null) {
                    attached = e
                    out += ScalperModel.EngineTiming(e.label, true, "in use · matches the trees on every split", openMs, warm)
                    continue
                }
                reasons += "${e.label}: $failed"
                out += ScalperModel.EngineTiming(e.label, false, failed, openMs, warm)
            } else {
                val worst = runCatching { probes.maxOf { kotlin.math.abs(e.run(it) - model.marginTrees(it)) } }.getOrDefault(Double.NaN)
                val same = worst <= ScalperModel.PARITY_TOLERANCE_CENTS
                out += ScalperModel.EngineTiming(e.label, same, if (same) "works · not needed" else "differs from the trees", openMs, warm)
            }
            runCatching { e.close() }
        }
        val kotlinWarm = runCatching { model.timeMicros({ model.marginTrees(it) }, probes) }.getOrDefault(0.0)
        out += ScalperModel.EngineTiming("Kotlin trees", true, if (attached == null) "in use" else "fallback", 0L, kotlinWarm)
        if (attached == null) model.markKotlin(reasons.lastOrNull() ?: "no ONNX engine")
        model.engines = out
        return attached
    }

    private fun short(t: Throwable): String {
        val m = (t.message ?: t.javaClass.simpleName).lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return (if (t is UnsatisfiedLinkError || t is NoClassDefFoundError || t is ExceptionInInitializerError) "native library missing: $m" else m).take(160)
    }
}
