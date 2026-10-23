package com.dirk.kalshiodds.llm

/**
 * The JNI side of the on-phone LLM (`app/src/main/cpp/llama_jni.cpp`, llama.cpp built from source).
 * Use [LocalLlm]: it loads the library, serialises calls and frees the model when idle.
 */
object LlamaNative {

    /** Receives the raw UTF-8 bytes of each generated piece; return false to stop. */
    fun interface ByteSink {
        fun onBytes(bytes: ByteArray): Boolean
    }

    @Volatile
    private var state: Boolean? = null

    /** Why the library could not be loaded, when [available] is false. */
    @Volatile
    var loadError: String = ""
        private set

    /** Loads `libkalshi_llm.so` once (with llama.cpp and the CPU backend variants in [nativeLibDir]). */
    @Synchronized
    fun available(nativeLibDir: String): Boolean {
        state?.let { return it }
        val ok = try {
            System.loadLibrary("kalshi_llm")
            init(nativeLibDir)
            true
        } catch (e: Throwable) {
            loadError = e.message ?: e.javaClass.simpleName
            false
        }
        state = ok
        return ok
    }

    @JvmStatic external fun init(nativeLibDir: String)

    /** A model handle, or 0 when the file could not be loaded ([lastError] says why). */
    @JvmStatic external fun load(path: String, nCtx: Int, nThreads: Int): Long

    @JvmStatic external fun describe(handle: Long): String

    @JvmStatic external fun lastError(): String

    /** Tokens generated, or -1 on an error. Blocks until done: call off the main thread. */
    @JvmStatic external fun generate(
        handle: Long,
        system: String,
        user: String,
        maxTokens: Int,
        temperature: Float,
        seed: Int,
        sink: ByteSink
    ): Int

    @JvmStatic external fun free(handle: Long)
}
