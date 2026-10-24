package com.dirk.kalshiodds.ml

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import com.dirk.kalshiodds.signal.scalper.MlEngine
import com.dirk.kalshiodds.signal.scalper.ModelEngines
import com.dirk.kalshiodds.signal.scalper.fleet.TimesFm
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The app's side of [MlService]: binds it, opens the trees there, runs rows and TimesFM forecasts through it. Every
 * call throws when the ML process is gone (a native crash there), so the trees fall back to Kotlin
 * ([com.dirk.kalshiodds.signal.scalper.ScalperModel.margin]) and TimesFM simply has no forecast that time. Call it off
 * the main thread.
 */
class MlClient(private val app: Context) {
    @Volatile private var binder: IBinder? = null
    @Volatile private var latch = CountDownLatch(1)
    @Volatile private var bound = false
    /** Why the ML process went away last, for the screens. */
    @Volatile var lastDeath: String? = null
        private set

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service
            runCatching { service?.linkToDeath({ binder = null; lastDeath = "the ML process stopped (native crash or killed)" }, 0) }
            latch.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
            lastDeath = "the ML process stopped"
        }

        override fun onBindingDied(name: ComponentName?) {
            binder = null
            bound = false
            runCatching { app.unbindService(this) }
        }
    }

    /** The service's binder, binding and waiting up to [timeoutMs] when needed. Null on the main thread when not bound yet. */
    @Synchronized
    private fun connect(timeoutMs: Long = 10_000L): IBinder {
        binder?.takeIf { it.isBinderAlive }?.let { return it }
        if (!bound) {
            latch = CountDownLatch(1)
            bound = app.bindService(Intent(app, MlService::class.java), conn, Context.BIND_AUTO_CREATE)
            check(bound) { "could not start the ML process" }
        }
        check(Looper.myLooper() != Looper.getMainLooper()) { "not bound yet" }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return binder?.takeIf { it.isBinderAlive } ?: error("the ML process did not answer")
    }

    private fun <T> call(code: Int, write: (Parcel) -> Unit, read: (Parcel) -> T): T {
        val b = connect()
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(MlProtocol.DESCRIPTOR)
            write(data)
            if (!b.transact(code, data, reply, 0)) error("the ML process refused the call")
            if (reply.readInt() != MlProtocol.OK) error(reply.readString() ?: "failed in the ML process")
            return read(reply)
        } catch (e: android.os.DeadObjectException) {
            binder = null
            lastDeath = "the ML process stopped (native crash or killed)"
            throw IllegalStateException(lastDeath)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** Opens the ML scalper's trees on [engine] in the ML process. Throws with the reason when it cannot. */
    fun openTrees(engine: MlEngine): ModelEngines.Opened {
        val label = call(MlProtocol.OPEN_TREES, { it.writeString(engine.name) }) { it.readString() ?: engine.label }
        return ModelEngines.Opened(label + " · separate process", { x -> runTrees(x) }, { runCatching { closeTrees() } })
    }

    fun runTrees(x: FloatArray): Double = call(MlProtocol.RUN_TREES, { it.writeFloatArray(x) }) { it.readDouble() }

    fun closeTrees() {
        call(MlProtocol.CLOSE_TREES, {}) { }
    }

    /** TimesFM's session runs in the ML process too. */
    val timesFm: TimesFm.Runner = object : TimesFm.Runner {
        override fun run(file: File, threads: Int, inputs: FloatArray, masks: FloatArray): Pair<Array<Array<FloatArray>>, Array<Array<FloatArray>>>? =
            call(MlProtocol.TIMESFM, { p ->
                p.writeString(file.absolutePath); p.writeInt(threads); p.writeFloatArray(inputs); p.writeFloatArray(masks)
            }) { r -> MlProtocol.read3(r) to MlProtocol.read3(r) }

        override fun close() {}
    }
}
