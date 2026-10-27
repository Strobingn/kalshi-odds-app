package com.dirk.kalshiodds.llm

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
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

/** The Gemma 4 builds that replace the cloud AI. Files and hashes are Hugging Face's (litert-community, Apache-2.0). */
enum class GemmaModel(val title: String, val fileName: String, val bytes: Long, val sha256: String, val blurb: String) {
    E2B(
        title = "Gemma 4 E2B",
        fileName = "gemma-4-E2B-it.litertlm",
        bytes = 2_588_147_712L,
        sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
        blurb = "2.6 GB · faster, keeps up with both AIs every minute"
    ),
    E4B(
        title = "Gemma 4 E4B",
        fileName = "gemma-4-E4B-it.litertlm",
        bytes = 3_659_530_240L,
        sha256 = "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0",
        blurb = "3.7 GB · smarter, needs a recent flagship"
    );

    val url: String get() = "https://huggingface.co/litert-community/${fileName.removeSuffix(".litertlm")}-litert-lm/resolve/main/$fileName"
}

/** What the Gemma card shows. */
data class GemmaState(
    /** The model in use, or null when none is downloaded. */
    val installed: GemmaModel? = null,
    /** Every Gemma model file on the phone. */
    val onPhone: Set<GemmaModel> = emptySet(),
    val chosen: GemmaModel = GemmaModel.E2B,
    /** "GPU" / "CPU" once loaded, "" when not loaded. */
    val backend: String = "",
    val busy: Boolean = false,
    /** "Downloading Gemma 4 E2B · 812 of 2,588 MB"; blank when idle. */
    val progress: String = "",
    val fraction: Float? = null,
    val message: String = "",
    /** Output tokens a second (approximate) of the last answer. */
    val lastSpeed: Double = 0.0,
    val lastMs: Long = 0L,
    val answered: Int = 0,
    val failed: Int = 0
)

/** One finished Gemma answer. */
data class GemmaAnswer(val text: String, val millis: Long, val model: String)

/**
 * Gemma 4 on the phone (LiteRT-LM). Replaces the cloud AI for the Scalping AI, the Paper AI, the chat and the writer
 * once a model is downloaded. Jobs run one at a time; the model stays loaded between the every-minute AI calls and is
 * freed [idleUnloadMs] after the last one. GPU first, CPU when the GPU path won't load. Prompts never leave the phone.
 */
class Gemma(
    private val dir: File,
    private val cacheDir: File,
    private val scope: CoroutineScope,
    private val prefs: Prefs,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build(),
    private val idleUnloadMs: Long = 5 * 60_000L,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    interface Prefs {
        var chosen: String?
    }

    private val jobLock = Mutex()
    private var engine: Engine? = null
    private var enginePath: String? = null
    private var unloadJob: Job? = null
    private var transfer: Job? = null

    private val _state = MutableStateFlow(GemmaState())
    val state: StateFlow<GemmaState> = _state.asStateFlow()

    init {
        dir.mkdirs()
        refresh()
    }

    fun fileFor(m: GemmaModel) = File(dir, m.fileName)
    private fun isOnPhone(m: GemmaModel) = fileFor(m).let { it.isFile && it.length() == m.bytes }

    /** The model in use: the chosen one if it's on the phone, else any that is. */
    fun installed(): GemmaModel? {
        val chosen = chosenModel()
        return chosen.takeIf { isOnPhone(it) } ?: GemmaModel.entries.firstOrNull { isOnPhone(it) }
    }

    val isReady: Boolean get() = installed() != null

    private fun chosenModel(): GemmaModel = prefs.chosen?.let { n -> GemmaModel.entries.firstOrNull { it.name == n } } ?: GemmaModel.E2B

    fun choose(m: GemmaModel) {
        prefs.chosen = m.name
        scope.launch { unloadNow() }
        refresh()
    }

    private fun refresh() {
        _state.update {
            it.copy(installed = installed(), onPhone = GemmaModel.entries.filter { m -> isOnPhone(m) }.toSet(), chosen = chosenModel())
        }
    }

    /**
     * One prompt. [history] is earlier (role, text) turns ("user"/"assistant"); [schema] forces the answer to be JSON
     * matching that JSON schema (constrained decoding). [onText] gets the answer so far.
     */
    suspend fun generate(
        system: String,
        user: String,
        history: List<Pair<String, String>> = emptyList(),
        schema: String? = null,
        maxOutputTokens: Int = 1024,
        temperature: Double = 0.5,
        onText: (String) -> Unit = {}
    ): Result<GemmaAnswer> = jobLock.withLock {
        withContext(Dispatchers.Default) {
            unloadJob?.cancel()
            _state.update { it.copy(busy = true, message = "") }
            try {
                val model = installed() ?: return@withContext Result.failure(IllegalStateException("Gemma isn't downloaded yet (AI → Keys)"))
                val eng = ensureEngine(fileFor(model).path)
                val config = ConversationConfig(
                    systemInstruction = Contents.of(system),
                    initialMessages = fitHistory(history, system.length + user.length).map { (role, text) ->
                        if (role == "assistant" || role == "model") Message.model(text) else Message.user(text)
                    },
                    samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = temperature, seed = 0),
                    maxOutputToken = maxOutputTokens,
                    enableResponseFormat = schema != null,
                )
                val t0 = nowMs()
                val out = StringBuilder()
                eng.createConversation(config).use { conv ->
                    try {
                        conv.sendMessageAsync(
                            contents = Contents.of(listOf<Content>(Content.Text(user))),
                            responseFormat = schema?.let { ResponseFormat.json(it) },
                        ).collect { msg ->
                            val piece = msg.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                            if (piece.isNotEmpty()) {
                                out.append(piece)
                                runCatching { onText(out.toString()) }
                            }
                        }
                    } catch (c: CancellationException) {
                        runCatching { conv.cancelProcess() }
                        throw c
                    }
                }
                coroutineContext.ensureActive()
                val ms = (nowMs() - t0).coerceAtLeast(1L)
                val text = out.toString().trim()
                if (text.isEmpty()) error("Gemma gave an empty answer")
                // ~4 characters a token: close enough for the speed chart.
                _state.update { it.copy(lastSpeed = text.length / 4.0 * 1000.0 / ms, lastMs = ms, answered = it.answered + 1) }
                Result.success(GemmaAnswer(text, ms, model.title))
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                _state.update { it.copy(failed = it.failed + 1) }
                Result.failure(e)
            } finally {
                _state.update { it.copy(busy = false) }
                scheduleUnload()
            }
        }
    }

    private fun ensureEngine(path: String): Engine {
        engine?.let { if (enginePath == path) return it }
        freeEngine()
        var last: Throwable? = null
        for ((name, backend) in listOf("GPU" to Backend.GPU(), "CPU" to Backend.CPU())) {
            val e = Engine(EngineConfig(modelPath = path, backend = backend, maxNumTokens = MAX_TOKENS, cacheDir = cacheDir.path))
            try {
                e.initialize()
                engine = e
                enginePath = path
                _state.update { it.copy(backend = name) }
                return e
            } catch (t: Throwable) {
                last = t
                runCatching { e.close() }
            }
        }
        throw IllegalStateException(
            when (last) {
                is UnsatisfiedLinkError -> "this phone can't run Gemma (needs a 64-bit ARM phone)"
                is OutOfMemoryError -> "not enough memory for Gemma: try E2B or close other apps"
                else -> "Gemma did not load: ${last?.message ?: "unknown error"}"
            }
        )
    }

    private fun scheduleUnload() {
        unloadJob?.cancel()
        unloadJob = scope.launch {
            delay(idleUnloadMs)
            unloadNow()
        }
    }

    suspend fun unloadNow() = jobLock.withLock { withContext(Dispatchers.Default) { freeEngine() } }

    private fun freeEngine() {
        runCatching { engine?.close() }
        engine = null
        enginePath = null
        _state.update { it.copy(backend = "") }
    }

    /** Downloads [m] (resuming a partial download), checks size and SHA-256, and makes it the model in use. */
    fun download(m: GemmaModel) {
        if (transfer?.isActive == true) return
        transfer = scope.launch(Dispatchers.IO) {
            val dest = fileFor(m)
            val part = File(dir, m.fileName + ".part")
            try {
                if (!isOnPhone(m)) {
                    val free = dir.usableSpace
                    val need = m.bytes - (if (part.isFile) part.length() else 0L) + SPACE_MARGIN
                    if (free in 1 until need) error("not enough space: needs ${need / 1_000_000} MB, ${free / 1_000_000} MB free")
                    fetch(m, part)
                    progress("Checking the file…", null)
                    val sha = LocalLlm.sha256(part)
                    if (part.length() != m.bytes || sha != m.sha256) {
                        part.delete()
                        error("the download did not match (size ${part.length()}, sha256 ${sha.take(12)}…): try again")
                    }
                    dest.delete()
                    if (!part.renameTo(dest)) error("could not save the file")
                }
                prefs.chosen = m.name
                unloadNow()
                refresh()
                done("${m.title} is installed: the AIs now run on this phone")
            } catch (e: CancellationException) {
                done("Download paused: start it again to resume")
                throw e
            } catch (e: Throwable) {
                done("Download failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private suspend fun fetch(m: GemmaModel, part: File) {
        val have = if (part.isFile) part.length() else 0L
        if (have >= m.bytes) return
        val req = Request.Builder().url(m.url).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val resumed = resp.code == 206 && have > 0
            val body = resp.body ?: error("empty response")
            java.io.FileOutputStream(part, resumed).use { out ->
                val buf = ByteArray(1 shl 16)
                var done = if (resumed) have else 0L
                var lastShown = 0L
                val input = body.byteStream()
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    val t = nowMs()
                    if (t - lastShown > 300) {
                        lastShown = t
                        progress("Downloading ${m.title} · ${done / 1_000_000} of ${m.bytes / 1_000_000} MB", (done.toDouble() / m.bytes).toFloat().coerceIn(0f, 1f))
                    }
                }
                out.flush()
            }
        }
    }

    fun cancelTransfer() {
        transfer?.cancel()
    }

    fun delete(m: GemmaModel) {
        scope.launch(Dispatchers.IO) {
            if (enginePath == fileFor(m).path) unloadNow()
            fileFor(m).delete()
            File(dir, m.fileName + ".part").delete()
            refresh()
            done("${m.title} deleted")
        }
    }

    private fun progress(p: String, f: Float?) = _state.update { it.copy(progress = p, fraction = f) }
    private fun done(message: String) = _state.update { it.copy(progress = "", fraction = null, message = message) }

    companion object {
        /** Prompt + answer. The Scalping AI's prompts run ~4–5K tokens. */
        const val MAX_TOKENS = 8_192
        /** Rough character budget for chat history so the prompt stays inside [MAX_TOKENS]. */
        const val MAX_PROMPT_CHARS = 20_000
        private const val SPACE_MARGIN = 300L * 1024 * 1024

        /** Newest turns that fit after [usedChars]; the oldest go first. History starts with the user. */
        fun fitHistory(history: List<Pair<String, String>>, usedChars: Int): List<Pair<String, String>> {
            val budget = (MAX_PROMPT_CHARS - usedChars).coerceAtLeast(0)
            val kept = ArrayDeque<Pair<String, String>>()
            var used = 0
            for (turn in history.asReversed()) {
                if (used + turn.second.length > budget) break
                kept.addFirst(turn)
                used += turn.second.length
            }
            while (kept.isNotEmpty() && kept.first().first != "user") kept.removeFirst()
            return kept.toList()
        }
    }
}
