package com.dirk.kalshiodds.ml

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import com.dirk.kalshiodds.signal.scalper.MlEngine
import com.dirk.kalshiodds.signal.scalper.NpuTrees
import com.dirk.kalshiodds.signal.scalper.OnnxTrees
import com.dirk.kalshiodds.signal.scalper.ScalperModel
import com.dirk.kalshiodds.signal.scalper.fleet.TimesFm
import java.io.File

/**
 * ONNX Runtime's own process (`:ml`, manifest): the ML scalper's trees on the CPU or the Snapdragon NPU, and TimesFM.
 * The app's main process never loads ONNX Runtime, so a native crash here (the QNN libraries, the NPU driver) ends
 * only this process: the app sees a dead binder and goes on with the Kotlin trees ([MlClient]).
 */
class MlService : Service() {
    private val binder by lazy { MlBinder(applicationContext) }
    override fun onBind(intent: Intent?): IBinder = binder
}

/** The calls the app makes, as plain binder transactions (no AIDL). */
internal class MlBinder(private val ctx: Context) : Binder() {
    private val lock = Any()
    private var trees: AutoCloseable? = null
    private var run: ((FloatArray) -> Double)? = null
    private val timesFm = HashMap<String, TimesFm.LocalRunner>()

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code !in MlProtocol.ALL) return super.onTransact(code, data, reply, flags)
        data.enforceInterface(MlProtocol.DESCRIPTOR)
        val r = reply ?: return true
        try {
            when (code) {
                MlProtocol.OPEN_TREES -> {
                    val engine = MlEngine.of(data.readString())
                    val (label, ms) = openTrees(engine)
                    r.writeInt(MlProtocol.OK); r.writeString(label); r.writeLong(ms)
                }
                MlProtocol.RUN_TREES -> {
                    val x = data.createFloatArray() ?: error("no row")
                    val f = synchronized(lock) { run } ?: error("no model open")
                    val v = f(x)
                    r.writeInt(MlProtocol.OK); r.writeDouble(v)
                }
                MlProtocol.CLOSE_TREES -> {
                    synchronized(lock) { runCatching { trees?.close() }; trees = null; run = null }
                    r.writeInt(MlProtocol.OK)
                }
                MlProtocol.TIMESFM -> {
                    val path = data.readString() ?: error("no file")
                    val threads = data.readInt()
                    val inputs = data.createFloatArray() ?: error("no inputs")
                    val masks = data.createFloatArray() ?: error("no masks")
                    val runner = synchronized(lock) { timesFm.getOrPut("$path|$threads") { TimesFm.LocalRunner() } }
                    val (point, q) = runner.run(File(path), threads, inputs, masks) ?: error("TimesFM gave nothing")
                    r.writeInt(MlProtocol.OK)
                    MlProtocol.write3(r, point)
                    MlProtocol.write3(r, q)
                }
            }
        } catch (t: Throwable) {
            r.writeInt(MlProtocol.ERROR)
            r.writeString((t.message ?: t.javaClass.simpleName).lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty().take(200))
        }
        return true
    }

    /** Opens the trees on [engine] from the APK's own model files; returns its label and how long it took. */
    private fun openTrees(engine: MlEngine): Pair<String, Long> = synchronized(lock) {
        runCatching { trees?.close() }
        trees = null
        run = null
        val t0 = System.nanoTime()
        val json = ctx.assets.open(ScalperModel.ASSET_NAME).use { it.readBytes() }
        val model = ScalperModel.parse(String(json, Charsets.UTF_8))
        when (engine) {
            MlEngine.ONNX_CPU -> {
                val t = OnnxTrees.open(ctx.assets.open(ScalperModel.ONNX_ASSET_NAME).use { it.readBytes() }, json, model.features)
                trees = t; run = t::predict
            }
            MlEngine.NPU -> {
                check(NpuTrees.isSnapdragon()) { "not a Snapdragon phone (${NpuTrees.chipName()})" }
                // The DSP library the NPU driver needs: say so plainly when the phone will not hand it to the app.
                ctx.applicationInfo?.nativeLibraryDir?.takeIf { it.isNotBlank() }?.let { dir ->
                    runCatching { android.system.Os.setenv("ADSP_LIBRARY_PATH", NpuTrees.dspLibraryPath(dir), true) }
                }
                val dsp = runCatching { System.loadLibrary("cdsprpc") }.exceptionOrNull()
                val t = try {
                    NpuTrees.open(
                        ctx.assets.open(ScalperModel.NPU_ASSET_NAME).use { it.readBytes() }, json, model, onNpu = true,
                        nativeLibraryDir = ctx.applicationInfo?.nativeLibraryDir.orEmpty()
                    )
                } catch (e: Throwable) {
                    val why = if (dsp != null) "; the phone's DSP library libcdsprpc.so did not load (${dsp.message})" else ""
                    throw IllegalStateException("${e.message ?: e.javaClass.simpleName}$why on ${NpuTrees.chipName()}", e)
                }
                // One real row through it before saying it is open.
                t.margin(model.probeRows(1).first())
                trees = t; run = t::margin
            }
            MlEngine.KOTLIN -> error("the Kotlin trees run in the app, not here")
        }
        engine.label to (System.nanoTime() - t0) / 1_000_000
    }
}

/** Transaction codes and the array packing shared by [MlBinder] and [MlClient]. */
internal object MlProtocol {
    const val DESCRIPTOR = "com.dirk.kalshiodds.ml.MlService"
    const val OPEN_TREES = Binder.FIRST_CALL_TRANSACTION
    const val RUN_TREES = Binder.FIRST_CALL_TRANSACTION + 1
    const val CLOSE_TREES = Binder.FIRST_CALL_TRANSACTION + 2
    const val TIMESFM = Binder.FIRST_CALL_TRANSACTION + 3
    val ALL = setOf(OPEN_TREES, RUN_TREES, CLOSE_TREES, TIMESFM)
    const val OK = 0
    const val ERROR = 1

    fun write3(p: Parcel, a: Array<Array<FloatArray>>) {
        val d0 = a.size
        val d1 = a.firstOrNull()?.size ?: 0
        val d2 = a.firstOrNull()?.firstOrNull()?.size ?: 0
        p.writeInt(d0); p.writeInt(d1); p.writeInt(d2)
        val flat = FloatArray(d0 * d1 * d2)
        var k = 0
        for (i in 0 until d0) for (j in 0 until d1) for (m in 0 until d2) flat[k++] = a[i][j][m]
        p.writeFloatArray(flat)
    }

    fun read3(p: Parcel): Array<Array<FloatArray>> {
        val d0 = p.readInt(); val d1 = p.readInt(); val d2 = p.readInt()
        val flat = p.createFloatArray() ?: FloatArray(0)
        var k = 0
        return Array(d0) { Array(d1) { FloatArray(d2) { flat[k++] } } }
    }
}
