package com.dirk.kalshiodds.signal.scalper.fleet

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.nio.FloatBuffer
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Google's TimesFM 2.5 (200M) on ONNX Runtime: `bakshinghampalace/timesfm-2.5-200m-onnx`, 8-bit weights
 * (`onnx/model_quantized.onnx`, Apache-2.0). One prefill pass over a 512-point context gives 128 steps ahead, a mean
 * and q10…q90 per step. [prepare] and [postprocess] follow the forecast-web reference (`src/models/timesfm/
 * preprocess.ts`, `postprocess.ts`): normalise, run the series and its negation, average with the quantile flip,
 * rebuild quantiles from the continuous head, fix crossings, de-normalise.
 */
class TimesFm(private val file: File, private val threads: Int = 2) : Forecaster, AutoCloseable {
    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private val lock = Any()

    override val name: String = "TimesFM 2.5 (8-bit)"
    override val ready: Boolean get() = file.isFile && file.length() == SIZE

    private fun open(): OrtSession = synchronized(lock) {
        session ?: run {
            val e = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(threads)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            opts.use { e.createSession(file.absolutePath, it) }.also { env = e; session = it }
        }
    }

    /** Quantiles q10..q90 per step ahead (128 steps), or null when the model is missing or fails. */
    override fun forecast(series: DoubleArray): Array<DoubleArray>? = runCatching {
        if (!ready) return null
        val prep = prepare(series) ?: return null
        val s = open()
        val e = env!!
        val shape = longArrayOf(2, CONTEXT.toLong())
        val (point, qspread) = synchronized(lock) {
            OnnxTensor.createTensor(e, FloatBuffer.wrap(prep.inputs), shape).use { x ->
                OnnxTensor.createTensor(e, FloatBuffer.wrap(prep.masks), shape).use { m ->
                    s.run(mapOf("inputs" to x, "masks" to m)).use { r ->
                        @Suppress("UNCHECKED_CAST")
                        val p = r.get("point").get().value as Array<Array<FloatArray>>
                        @Suppress("UNCHECKED_CAST")
                        val q = r.get("qspread").get().value as Array<Array<FloatArray>>
                        p to q
                    }
                }
            }
        }
        postprocess(point, qspread, prep.mu, prep.sigma, prep.positive).map { row -> DoubleArray(9) { row[it + 1] } }.toTypedArray()
    }.getOrNull()

    override fun close() {
        synchronized(lock) { runCatching { session?.close() }; session = null }
    }

    class Prepared(val inputs: FloatArray, val masks: FloatArray, val mu: Double, val sigma: Double, val positive: Boolean)

    companion object {
        const val CONTEXT = 512
        const val HORIZON = 128
        const val CHANNELS = 10
        const val URL = "https://huggingface.co/bakshinghampalace/timesfm-2.5-200m-onnx/resolve/main/onnx/model_quantized.onnx"
        const val SHA256 = "03c0de47701fab96bca31c9e4004f6977572e1d399d8cf4e8b2cf88651f783bb"
        const val SIZE = 261_301_673L
        const val FILE_NAME = "timesfm-2.5-200m-q8.onnx"

        /**
         * strip_leading_nans + linear_interpolation (a trailing gap holds the last value), last 512 points,
         * left-padded with zeros (mask 1), normalised by the padded window's mean and unbiased std; row 2 is the negation.
         */
        fun prepare(series: DoubleArray): Prepared? {
            var start = 0
            while (start < series.size && !series[start].isFinite()) start++
            if (start == series.size) return null
            val clean = DoubleArray(series.size - start)
            var prev = -1
            for (i in start until series.size) {
                val v = series[i]
                val j = i - start
                if (!v.isFinite()) continue
                clean[j] = v
                if (prev >= 0 && j - prev > 1) {
                    val a = clean[prev]
                    val step = (v - a) / (j - prev)
                    for (k in prev + 1 until j) clean[k] = a + step * (k - prev)
                }
                prev = j
            }
            for (k in prev + 1 until clean.size) clean[k] = clean[prev]
            val v = if (clean.size > CONTEXT) clean.copyOfRange(clean.size - CONTEXT, clean.size) else clean
            val pad = CONTEXT - v.size
            val x = DoubleArray(CONTEXT)
            System.arraycopy(v, 0, x, pad, v.size)
            val mu = x.average()
            var ss = 0.0
            var positive = true
            for (a in x) { ss += (a - mu) * (a - mu); if (a < 0) positive = false }
            val sigma = sqrt(ss / (CONTEXT - 1))
            val div = if (sigma < 1e-6) 1.0 else sigma
            val inputs = FloatArray(2 * CONTEXT)
            val masks = FloatArray(2 * CONTEXT)
            for (i in 0 until CONTEXT) {
                val z = ((x[i] - mu) / div).toFloat()
                inputs[i] = z
                inputs[CONTEXT + i] = -z
                val m = if (i < pad) 1f else 0f
                masks[i] = m
                masks[CONTEXT + i] = m
            }
            return Prepared(inputs, masks, mu, sigma, positive)
        }

        private fun flip(q: Int) = if (q == 0) 0 else CHANNELS - q

        /** [point]/[qspread] are [2][128][10]; returns [128][10] (mean, q10..q90) in the series' units. */
        fun postprocess(point: Array<Array<FloatArray>>, qspread: Array<Array<FloatArray>>, mu: Double, sigma: Double, positive: Boolean): Array<DoubleArray> {
            val steps = min(point[0].size, HORIZON)
            return Array(steps) { t ->
                val row = DoubleArray(CHANNELS) { q -> (point[0][t][q] - point[1][t][flip(q)]) / 2.0 }
                val qs = DoubleArray(CHANNELS) { q -> (qspread[0][t][q] - qspread[1][t][flip(q)]) / 2.0 }
                for (q in intArrayOf(1, 2, 3, 4, 6, 7, 8, 9)) row[q] = qs[q] - qs[5] + row[5]
                for (q in intArrayOf(4, 3, 2, 1)) row[q] = min(row[q], row[q + 1])
                for (q in intArrayOf(6, 7, 8, 9)) row[q] = max(row[q], row[q - 1])
                DoubleArray(CHANNELS) { q -> (row[q] * sigma + mu).let { if (positive) max(it, 0.0) else it } }
            }
        }
    }
}

/** Downloads [TimesFm]'s weights once (resumable, checked by SHA-256). */
class TimesFmDownload(private val dir: File, private val scope: CoroutineScope, private val http: OkHttpClient = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()) {
    data class State(val installed: Boolean, val downloading: Boolean, val bytes: Long, val total: Long, val error: String?)

    val file: File get() = File(dir, TimesFm.FILE_NAME)
    private val part: File get() = File(dir, TimesFm.FILE_NAME + ".part")
    private val _state = MutableStateFlow(State(file.isFile && file.length() == TimesFm.SIZE, false, part.length(), TimesFm.SIZE, null))
    val state: StateFlow<State> = _state
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true || _state.value.installed) return
        job = scope.launch {
            runCatching {
                dir.mkdirs()
                var have = if (part.isFile) part.length() else 0L
                val req = Request.Builder().url(TimesFm.URL).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
                http.newCall(req).execute().use { r ->
                    check(r.isSuccessful) { "HTTP ${r.code}" }
                    if (r.code != 206) have = 0L
                    RandomAccessFile(part, "rw").use { out ->
                        out.setLength(have)
                        out.seek(have)
                        val buf = ByteArray(1 shl 16)
                        val input = r.body!!.byteStream()
                        while (isActive) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            have += n
                            _state.value = State(false, true, have, TimesFm.SIZE, null)
                        }
                    }
                }
                check(have == TimesFm.SIZE) { "download stopped at $have bytes" }
                val md = MessageDigest.getInstance("SHA-256")
                part.inputStream().use { s -> val b = ByteArray(1 shl 16); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }
                val sha = md.digest().joinToString("") { "%02x".format(it) }
                if (sha != TimesFm.SHA256) { part.delete(); error("checksum does not match") }
                check(part.renameTo(file)) { "could not move the file into place" }
                _state.value = State(true, false, TimesFm.SIZE, TimesFm.SIZE, null)
            }.onFailure { e -> _state.value = State(false, false, part.length(), TimesFm.SIZE, e.message ?: e.javaClass.simpleName) }
        }
    }

    fun cancel() {
        job?.cancel()
        _state.value = _state.value.copy(downloading = false)
    }

    fun delete() {
        cancel()
        file.delete(); part.delete()
        _state.value = State(false, false, 0L, TimesFm.SIZE, null)
    }
}
