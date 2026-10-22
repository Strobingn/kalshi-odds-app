package com.dirk.kalshiodds.signal.scalper

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.security.MessageDigest

/**
 * The scalper's trees on ONNX Runtime: `assets/scalper_model.onnx`, written from `scalper_model.json` by
 * `tools/research/scalp/export_onnx.py` (one TreeEnsembleRegressor, input `x` float32 [1, features], output `y`).
 *
 * CPU execution provider, one thread, every graph optimisation: for 146 small trees and one row at a time this is
 * the fastest path (an NPU's dispatch cost is larger than the whole model). The model's metadata must carry the
 * sha256 of the JSON it was built from and the same feature names, or [open] refuses it, so a stale ONNX file can
 * never run next to newer JSON trees. [ScalperModel.attachOnnx] then checks it against the trees before using it.
 */
class OnnxTrees private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val nFeatures: Int
) : AutoCloseable {
    private val lock = Any()
    private val shape = longArrayOf(1, nFeatures.toLong())

    /** Cents a contract for one row of features, in the model's order. Thread-safe. */
    fun predict(x: FloatArray): Double {
        require(x.size == nFeatures) { "expected $nFeatures features, got ${x.size}" }
        return synchronized(lock) {
            OnnxTensor.createTensor(env, FloatBuffer.wrap(x), shape).use { t ->
                session.run(mapOf(INPUT to t)).use { r ->
                    @Suppress("UNCHECKED_CAST")
                    val y = r[0].value as Array<FloatArray>
                    y[0][0].toDouble()
                }
            }
        }
    }

    override fun close() {
        synchronized(lock) { runCatching { session.close() } }
    }

    companion object {
        const val INPUT = "x"

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        /**
         * Opens [onnxBytes] for the trees in [jsonBytes] (the exact scalper_model.json bytes the app parsed).
         * Throws when the model was built from other trees or other features.
         */
        fun open(onnxBytes: ByteArray, jsonBytes: ByteArray, features: List<String>): OnnxTrees {
            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(1)
                setInterOpNumThreads(1)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val session = opts.use { env.createSession(onnxBytes, it) }
            try {
                val meta = session.metadata.customMetadata
                val want = sha256(jsonBytes)
                check(meta["source_sha256"] == want) { "built from other trees" }
                check(meta["features"] == features.joinToString(",")) { "built for other features" }
                check(session.inputNames == setOf(INPUT)) { "unexpected inputs ${session.inputNames}" }
                return OnnxTrees(env, session, features.size)
            } catch (e: Throwable) {
                runCatching { session.close() }
                throw e
            }
        }
    }
}
