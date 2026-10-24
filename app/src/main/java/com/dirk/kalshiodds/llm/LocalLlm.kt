package com.dirk.kalshiodds.llm

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.coroutines.coroutineContext

/** A GGUF model the app can download for you. Sizes and hashes are Hugging Face's own for these files. */
data class LocalModel(
    val id: String,
    val title: String,
    val fileName: String,
    val url: String,
    val bytes: Long,
    val sha256: String
) {
    companion object {
        val QWEN_05B = LocalModel(
            id = "qwen2.5-0.5b-q4km",
            title = "Qwen2.5 0.5B Instruct (Q4_K_M, 491 MB) · fastest",
            fileName = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
            url = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            bytes = 491_400_032L,
            sha256 = "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db"
        )
        val QWEN_15B = LocalModel(
            id = "qwen2.5-1.5b-q4km",
            title = "Qwen2.5 1.5B Instruct (Q4_K_M, 1.1 GB) · better answers",
            fileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            url = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            bytes = 1_117_320_736L,
            sha256 = "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e"
        )
        val CATALOG = listOf(QWEN_05B, QWEN_15B)
    }
}

/** What the on-phone model screen shows. */
data class LocalLlmState(
    /** The .gguf file in use, or null when none is installed. */
    val installed: String? = null,
    val installedBytes: Long = 0L,
    /** Every .gguf file on the phone, newest first. */
    val files: List<String> = emptyList(),
    val loaded: Boolean = false,
    val busy: Boolean = false,
    /** "Downloading Qwen2.5 1.5B · 312 of 1,117 MB", "Checking the file…"; blank when idle. */
    val progress: String = "",
    /** 0..1 while downloading or copying, else null. */
    val fraction: Float? = null,
    val message: String = "",
    /** "llama 1.5B Q4_K_M | 1543714304 params | ctx 4096", after the first load. */
    val description: String = "",
    /** Tokens a second of the last answer. */
    val lastSpeed: Double = 0.0,
    /** Use the on-phone model for Explain and the daily review (cloud keys are the fallback). */
    val preferLocal: Boolean = true
)

/** One finished on-phone answer. */
data class LocalAnswer(val text: String, val tokens: Int, val millis: Long, val model: String)

/**
 * The on-phone LLM: one GGUF model at a time, run by llama.cpp on the CPU ([LlamaNative]).
 *
 * The model is loaded only for a job and freed [idleUnloadMs] after the last one, so it never sits in memory next
 * to the live scalper. Jobs run one at a time ([generate] waits its turn). Downloads resume from a `.part` file
 * and are checked against the file's size and SHA-256 before they are used; an imported file must start with the
 * GGUF magic. Nothing here sends anything anywhere: prompts and answers stay on the phone.
 */
class LocalLlm(
    private val dir: File,
    private val engine: Engine,
    private val scope: CoroutineScope,
    private val prefs: Prefs,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build(),
    private val idleUnloadMs: Long = 90_000L,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    /** The native calls, behind an interface so the logic is testable without the library. */
    interface Engine {
        fun available(): Boolean
        fun unavailableReason(): String
        fun load(path: String, nCtx: Int, nThreads: Int): Long
        fun describe(handle: Long): String
        fun lastError(): String
        fun generate(handle: Long, system: String, user: String, maxTokens: Int, temperature: Float, seed: Int, sink: LlamaNative.ByteSink): Int
        fun free(handle: Long)
    }

    /** Where the chosen file name and the local-first switch are kept. */
    interface Prefs {
        var selected: String?
        var preferLocal: Boolean
    }

    private val jobLock = Mutex()
    private var handle = 0L
    private var handlePath: String? = null
    private var unloadJob: Job? = null
    private var transfer: Job? = null

    private val _state = MutableStateFlow(LocalLlmState(preferLocal = prefs.preferLocal))
    val state: StateFlow<LocalLlmState> = _state.asStateFlow()

    init {
        dir.mkdirs()
        refresh()
    }

    /** The installed model file, or null. */
    fun installedFile(): File? {
        val name = prefs.selected ?: return null
        return File(dir, name).takeIf { it.isFile && it.length() > 0L }
    }

    val isInstalled: Boolean get() = installedFile() != null

    fun setPreferLocal(on: Boolean) {
        prefs.preferLocal = on
        _state.update { it.copy(preferLocal = on) }
    }

    /** Use another model file already on the phone. */
    fun select(name: String) {
        if (!File(dir, name).isFile) return
        prefs.selected = name
        scope.launch { unloadNow() }
        refresh()
    }

    private fun refresh() {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".gguf") }.orEmpty().sortedByDescending { it.lastModified() }
        if (prefs.selected != null && files.none { it.name == prefs.selected }) prefs.selected = files.firstOrNull()?.name
        if (prefs.selected == null) prefs.selected = files.firstOrNull()?.name
        val f = installedFile()
        _state.update { it.copy(installed = f?.name, installedBytes = f?.length() ?: 0L, files = files.map { x -> x.name }) }
    }

    /**
     * Runs one prompt on the phone. [onText] gets the answer so far as it grows (on the generating thread).
     * Fails when no model is installed, the library or the model cannot load, or the prompt does not fit.
     */
    suspend fun generate(
        system: String,
        user: String,
        maxTokens: Int = 512,
        /** Sampling temperature of the on-phone model (0.8 since 1.20.4, asked for 2026-10-24; was 0.3). */
        temperature: Float = TEMPERATURE,
        nCtx: Int = DEFAULT_CTX,
        onText: (String) -> Unit = {}
    ): Result<LocalAnswer> = jobLock.withLock {
        withContext(Dispatchers.IO) {
            unloadJob?.cancel()
            _state.update { it.copy(busy = true, message = "") }
            try {
                val file = installedFile() ?: return@withContext Result.failure(IllegalStateException("no on-phone model installed"))
                if (!engine.available()) {
                    return@withContext Result.failure(IllegalStateException("on-phone AI library did not load: ${engine.unavailableReason()}"))
                }
                if (handle == 0L || handlePath != file.path) {
                    freeHandle()
                    val h = engine.load(file.path, nCtx, 0)
                    if (h == 0L) {
                        return@withContext Result.failure(IllegalStateException("model did not load: ${engine.lastError().ifBlank { "unknown error" }}"))
                    }
                    handle = h
                    handlePath = file.path
                    val d = runCatching { engine.describe(h) }.getOrDefault("")
                    _state.update { it.copy(loaded = true, description = d) }
                }
                val job = coroutineContext[Job]
                val out = Utf8Accumulator()
                val t0 = nowMs()
                val n = engine.generate(handle, system, user, maxTokens, temperature, SEED) { bytes ->
                    out.add(bytes)
                    runCatching { onText(out.text()) }
                    job?.isActive != false
                }
                coroutineContext.ensureActive()
                if (n < 0) return@withContext Result.failure(IllegalStateException(engine.lastError().ifBlank { "generation failed" }))
                val ms = (nowMs() - t0).coerceAtLeast(1L)
                val text = out.finish().trim()
                _state.update { it.copy(lastSpeed = n * 1000.0 / ms) }
                Result.success(LocalAnswer(text, n, ms, file.name))
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Result.failure(e)
            } finally {
                _state.update { it.copy(busy = false) }
                scheduleUnload()
            }
        }
    }

    private fun scheduleUnload() {
        unloadJob?.cancel()
        unloadJob = scope.launch {
            delay(idleUnloadMs)
            unloadNow()
        }
    }

    /** Frees the model now (it reloads on the next job). */
    suspend fun unloadNow() = jobLock.withLock { withContext(Dispatchers.IO) { freeHandle() } }

    private fun freeHandle() {
        if (handle != 0L) runCatching { engine.free(handle) }
        handle = 0L
        handlePath = null
        _state.update { it.copy(loaded = false) }
    }

    /** Downloads [m] (resuming a partial download), checks it, and makes it the model in use. */
    fun download(m: LocalModel) {
        if (transfer?.isActive == true) return
        transfer = scope.launch(Dispatchers.IO) {
            val dest = File(dir, m.fileName)
            val part = File(dir, m.fileName + ".part")
            try {
                if (!(dest.isFile && dest.length() == m.bytes)) {
                    fetch(m, part)
                    progress("Checking the file…", null)
                    val sha = sha256(part)
                    if (part.length() != m.bytes || sha != m.sha256) {
                        part.delete()
                        error("the download did not match (size ${part.length()}, sha256 $sha): try again")
                    }
                    if (!part.renameTo(dest)) error("could not save the file")
                }
                prefs.selected = dest.name
                unloadNow()
                refresh()
                done("${m.title.substringBefore(" (")} is installed")
            } catch (e: kotlinx.coroutines.CancellationException) {
                done("Download paused: start it again to resume")
                throw e
            } catch (e: Throwable) {
                done("Download failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private suspend fun fetch(m: LocalModel, part: File) {
        val have = if (part.isFile) part.length() else 0L
        if (have >= m.bytes) return
        val req = Request.Builder().url(m.url).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val resumed = resp.code == 206 && have > 0
            val body = resp.body ?: error("empty response")
            java.io.FileOutputStream(part, resumed).use { out ->
                copy(body.byteStream(), out, if (resumed) have else 0L, m.bytes, "Downloading ${m.title.substringBefore(" (")}")
            }
        }
    }

    /**
     * Copies a .gguf file the person picked (Storage Access Framework stream) onto the phone's app storage and makes
     * it the model in use. [open] opens the stream; [name] is the picked file's display name.
     */
    fun import(name: String, sizeHint: Long, open: () -> InputStream?) {
        if (transfer?.isActive == true) return
        transfer = scope.launch(Dispatchers.IO) {
            val safe = sanitize(name)
            val part = File(dir, "$safe.part")
            val dest = File(dir, safe)
            try {
                val input = open() ?: error("could not open the file")
                input.use { inp ->
                    java.io.FileOutputStream(part, false).use { out -> copy(inp, out, 0L, sizeHint, "Copying $safe") }
                }
                if (!isGguf(part)) {
                    part.delete()
                    error("that is not a GGUF model file")
                }
                if (dest.exists()) dest.delete()
                if (!part.renameTo(dest)) error("could not save the file")
                prefs.selected = dest.name
                unloadNow()
                refresh()
                done("$safe is installed")
            } catch (e: kotlinx.coroutines.CancellationException) {
                part.delete()
                done("Copy cancelled")
                throw e
            } catch (e: Throwable) {
                part.delete()
                done("Could not add the file: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun cancelTransfer() {
        transfer?.cancel()
    }

    /** Deletes a model file from the phone. */
    fun delete(name: String) {
        scope.launch(Dispatchers.IO) {
            if (handlePath?.endsWith("/$name") == true) unloadNow()
            File(dir, name).delete()
            File(dir, "$name.part").delete()
            if (prefs.selected == name) prefs.selected = null
            refresh()
            done("$name deleted")
        }
    }

    private suspend fun copy(input: InputStream, out: java.io.OutputStream, start: Long, total: Long, label: String) {
        val buf = ByteArray(1 shl 16)
        var done = start
        var lastShown = 0L
        while (true) {
            coroutineContext.ensureActive()
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            done += n
            val t = nowMs()
            if (t - lastShown > 300) {
                lastShown = t
                val mb = done / 1_000_000
                progress(
                    if (total > 0) "$label · $mb of ${total / 1_000_000} MB" else "$label · $mb MB",
                    if (total > 0) (done.toDouble() / total).toFloat().coerceIn(0f, 1f) else null
                )
            }
        }
        out.flush()
    }

    private fun progress(p: String, f: Float?) = _state.update { it.copy(progress = p, fraction = f) }

    private fun done(message: String) = _state.update { it.copy(progress = "", fraction = null, message = message) }

    companion object {
        const val DEFAULT_CTX = 4096
        private const val SEED = 42
        /** The on-phone model's sampling temperature. */
        const val TEMPERATURE = 0.8f

        fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { inp ->
                val buf = ByteArray(1 shl 20)
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        /** True when [f] starts with the GGUF magic ("GGUF"). */
        fun isGguf(f: File): Boolean = runCatching {
            f.inputStream().use { val b = ByteArray(4); it.read(b) == 4 && String(b, Charsets.US_ASCII) == "GGUF" }
        }.getOrDefault(false)

        /** A safe file name ending in .gguf. */
        fun sanitize(name: String): String {
            val base = name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.', '_').ifBlank { "model" }
            return if (base.endsWith(".gguf", ignoreCase = true)) base else "$base.gguf"
        }
    }
}

/** [LocalLlm.Engine] on the real library. */
class NativeEngine(private val nativeLibDir: String) : LocalLlm.Engine {
    override fun available(): Boolean = LlamaNative.available(nativeLibDir)
    override fun unavailableReason(): String = LlamaNative.loadError
    override fun load(path: String, nCtx: Int, nThreads: Int): Long = LlamaNative.load(path, nCtx, nThreads)
    override fun describe(handle: Long): String = LlamaNative.describe(handle)
    override fun lastError(): String = LlamaNative.lastError()
    override fun generate(
        handle: Long, system: String, user: String, maxTokens: Int, temperature: Float, seed: Int, sink: LlamaNative.ByteSink
    ): Int = LlamaNative.generate(handle, system, user, maxTokens, temperature, seed, sink)
    override fun free(handle: Long) = LlamaNative.free(handle)
}

/**
 * Decodes UTF-8 that arrives in pieces: a piece may end in the middle of a character, so the last incomplete
 * sequence is held back until the rest of it arrives.
 */
class Utf8Accumulator {
    private val done = StringBuilder()
    private var pending = ByteArray(0)

    fun add(bytes: ByteArray) {
        val all = pending + bytes
        val cut = completePrefix(all)
        if (cut > 0) done.append(String(all, 0, cut, Charsets.UTF_8))
        pending = all.copyOfRange(cut, all.size)
    }

    /** The text so far, without a held-back partial character. */
    fun text(): String = done.toString()

    /** Everything, a dangling partial character included (as U+FFFD). */
    fun finish(): String {
        if (pending.isNotEmpty()) done.append(String(pending, Charsets.UTF_8))
        pending = ByteArray(0)
        return done.toString()
    }

    companion object {
        /** Length of the longest prefix of [b] that does not end inside a multi-byte sequence. */
        fun completePrefix(b: ByteArray): Int {
            val n = b.size
            var i = n - 1
            var back = 0
            while (i >= 0 && back < 4) {
                val v = b[i].toInt() and 0xFF
                if (v and 0xC0 != 0x80) {
                    val need = when {
                        v < 0x80 -> 1
                        v and 0xE0 == 0xC0 -> 2
                        v and 0xF0 == 0xE0 -> 3
                        v and 0xF8 == 0xF0 -> 4
                        else -> 1
                    }
                    return if (n - i >= need) n else i
                }
                i--
                back++
            }
            return n
        }
    }
}
