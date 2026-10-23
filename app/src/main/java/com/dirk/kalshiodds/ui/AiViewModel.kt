package com.dirk.kalshiodds.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dirk.kalshiodds.KalshiOddsApp
import com.dirk.kalshiodds.signal.scalper.AiChat
import com.dirk.kalshiodds.signal.scalper.ChatMsg
import com.dirk.kalshiodds.signal.scalper.ScalpStrategy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Keys screen state: drafts are only in memory until Save; saved keys are encrypted on the phone. */
data class AiKeysState(
    val groqDraft: String = "",
    val mistralDraft: String = "",
    val geminiDraft: String = "",
    val cerebrasDraft: String = "",
    val saved: List<Pair<String, Boolean>> = emptyList(),
    val scalpingOn: Boolean = true,
    val paperOn: Boolean = true,
    val learningOn: Boolean = true,
    val testing: Boolean = false,
    val testLines: List<String> = emptyList(),
    val message: String = "",
    val rotation: String = ""
)

/** The AI tab: dashboard (rebuilt every 2 s), the two chat threads, keys, switches and the key test. */
class AiViewModel(application: Application) : AndroidViewModel(application) {
    private val container = KalshiOddsApp.from(application).container
    private val secrets = container.extraSecrets

    private val _dashboard = MutableStateFlow(AiDashboardState())
    val dashboard: StateFlow<AiDashboardState> = _dashboard.asStateFlow()

    private val _keys = MutableStateFlow(keysNow())
    val keys: StateFlow<AiKeysState> = _keys.asStateFlow()

    val messages: StateFlow<List<ChatMsg>> = container.aiChat.messages
    val busy: StateFlow<Boolean> = container.aiChat.busy

    init {
        viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                runCatching { _dashboard.value = buildDashboard() }
                _keys.update { it.copy(rotation = rotationLine()) }
                delay(2_000L)
            }
        }
    }

    private fun savedKeys() = com.dirk.kalshiodds.signal.scalper.LlmProvider.NAMES.map { (id, name) -> name to container.llmKey(id).isNotBlank() }

    private fun idOf(name: String) = com.dirk.kalshiodds.signal.scalper.LlmProvider.NAMES.entries.firstOrNull { it.value == name }?.key ?: name.lowercase()

    private fun rotationLine(): String {
        val r = container.llmRotation.state.value
        return "Now using ${r.provider.replaceFirstChar { it.uppercase() }} · ${r.calls} of ${r.every} calls, then the next provider with a key"
    }

    private fun keysNow() = AiKeysState(
        rotation = rotationLine(),
        saved = savedKeys(),
        scalpingOn = secrets.supervisorEnabled,
        paperOn = secrets.paperAiEnabled,
        learningOn = container.learningMode
    )

    private fun buildDashboard(): AiDashboardState {
        val ledger = container.scalperStore.ledger.snapshot()
        val strategies = ScalpStrategy.values().map { st ->
            val s = ledger.stats(st.name)
            StrategyResult(st.name, s.closed, s.wins, s.pnlUsd)
        }
        val base = AiDashboardState(
            version = AppVersion.label,
            rotation = rotationLine(),
            keys = savedKeys(),
            scalpingOn = secrets.supervisorEnabled,
            paperOn = secrets.paperAiEnabled,
            learningOn = container.learningMode,
            scalpingStatus = container.llmSupervisor.status.value,
            paperStatus = container.paperAi.status.value + " · " + container.paperAi.record.value,
            paperLessons = container.paperAi.lessons().takeLast(8).reversed()
        )
        val bets = container.paperAi.openBets() + container.paperAi.settledBets()
        return AiDashboard.build(base, System.currentTimeMillis(), container.aiActivity.events.value, strategies, bets)
    }

    fun setDraft(provider: String, v: String) = _keys.update {
        when (provider) {
            "Groq" -> it.copy(groqDraft = v)
            "Gemini" -> it.copy(geminiDraft = v)
            "Cerebras" -> it.copy(cerebrasDraft = v)
            else -> it.copy(mistralDraft = v)
        }
    }

    /** Saves the typed keys (encrypted), then tests them and starts both AIs at once. */
    fun saveKeys() {
        val k = _keys.value
        if (k.groqDraft.isNotBlank()) secrets.groqApiKey = k.groqDraft.trim()
        if (k.mistralDraft.isNotBlank()) secrets.mistralApiKey = k.mistralDraft.trim()
        if (k.geminiDraft.isNotBlank()) secrets.geminiApiKey = k.geminiDraft.trim()
        if (k.cerebrasDraft.isNotBlank()) secrets.cerebrasApiKey = k.cerebrasDraft.trim()
        val any = listOf(k.groqDraft, k.mistralDraft, k.geminiDraft, k.cerebrasDraft).any { it.isNotBlank() }
        _keys.value = keysNow().copy(message = if (any) "Saved (encrypted on this phone). Testing…" else "Type a key first", testLines = k.testLines)
        if (any) { testKeys(); container.kickAi() }
    }

    fun clearKey(provider: String) {
        when (provider) {
            else -> container.setLlmKey(idOf(provider), "")
        }
        _keys.value = keysNow().copy(message = "$provider key removed")
    }

    fun testKeys() {
        if (_keys.value.testing) return
        _keys.update { it.copy(testing = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val lines = runCatching { container.testLlmKeys() }.getOrElse { listOf("Test failed: ${it.message}") }
            _keys.update { it.copy(testing = false, testLines = lines, message = "") }
        }
    }

    fun setScalpingOn(on: Boolean) { secrets.supervisorEnabled = on; _keys.update { it.copy(scalpingOn = on) }; if (on) container.kickAi() }
    fun setPaperOn(on: Boolean) { secrets.paperAiEnabled = on; _keys.update { it.copy(paperOn = on) }; if (on) container.kickAi() }
    fun setLearning(on: Boolean) { container.learningMode = on; _keys.update { it.copy(learningOn = on) } }
    fun runNow() = container.kickAi()

    fun send(which: String, text: String) { viewModelScope.launch(Dispatchers.IO) { container.aiChat.send(which, text) } }
    fun apply(id: Long) { viewModelScope.launch(Dispatchers.IO) { container.aiChat.apply(id) } }
    fun cancel(id: Long) = container.aiChat.cancel(id)
    fun clear(which: String) = container.aiChat.clear(which)

    // ---- on-phone model, daily review, the ML model's report

    val localLlm: StateFlow<com.dirk.kalshiodds.llm.LocalLlmState> = container.localLlm.state
    val review: StateFlow<com.dirk.kalshiodds.llm.ReviewUi> = container.reviews.state
    val reviewList: StateFlow<List<com.dirk.kalshiodds.signal.scalper.SavedReview>> = container.reviews.reviews

    private val _autoReview = MutableStateFlow(container.autoReview)
    val autoReview: StateFlow<Boolean> = _autoReview.asStateFlow()

    private val _llmTest = MutableStateFlow("")
    val llmTest: StateFlow<String> = _llmTest.asStateFlow()
    private val _llmTesting = MutableStateFlow(false)
    val llmTesting: StateFlow<Boolean> = _llmTesting.asStateFlow()

    /** Tokens a second of the on-phone model's answers this session, oldest first. */
    private val _speeds = MutableStateFlow<List<Double>>(emptyList())
    val speeds: StateFlow<List<Double>> = _speeds.asStateFlow()

    private val _modelReport = MutableStateFlow<ModelReport?>(null)
    val modelReport: StateFlow<ModelReport?> = _modelReport.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.Default) {
            var last = 0.0
            container.localLlm.state.collect { st ->
                if (st.lastSpeed > 0 && st.lastSpeed != last) {
                    last = st.lastSpeed
                    _speeds.update { (it + st.lastSpeed).takeLast(40) }
                }
            }
        }
        viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                runCatching { _modelReport.value = container.scalperStore.model?.let { modelReport(it) } }
                delay(5_000L)
            }
        }
    }

    fun downloadModel(m: com.dirk.kalshiodds.llm.LocalModel) = container.localLlm.download(m)
    fun cancelTransfer() = container.localLlm.cancelTransfer()
    fun selectModel(name: String) = container.localLlm.select(name)
    fun deleteModel(name: String) = container.localLlm.delete(name)
    fun setPreferLocal(on: Boolean) = container.localLlm.setPreferLocal(on)

    /** Copies a .gguf file the person picked into the app (the model needs a real file path). */
    fun importModel(uri: android.net.Uri) {
        val app = getApplication<Application>()
        val cr = app.contentResolver
        var name = "model.gguf"
        var size = -1L
        runCatching {
            cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)?.use { cur ->
                if (cur.moveToFirst()) {
                    cur.getString(0)?.let { name = it }
                    if (!cur.isNull(1)) size = cur.getLong(1)
                }
            }
        }
        container.localLlm.import(name, size) { cr.openInputStream(uri) }
    }

    fun testLocal() {
        if (_llmTesting.value) return
        _llmTesting.value = true
        _llmTest.value = ""
        viewModelScope.launch(Dispatchers.IO) {
            val r = container.localLlm.generate(
                system = "You are a concise assistant inside a paper-trading app.",
                user = "In two short sentences, say what a 1 cent scalp on a 15-minute Bitcoin up/down market is.",
                maxTokens = 96
            ) { partial -> _llmTest.value = partial }
            _llmTest.value = r.fold(
                { a -> a.text + String.format(java.util.Locale.US, "\n\n%d tokens in %.1f s (%.1f tokens a second) · %s", a.tokens, a.millis / 1000.0, a.tokens * 1000.0 / a.millis, a.model) },
                { e -> "Failed: ${e.message}" }
            )
            _llmTesting.value = false
        }
    }

    fun setAutoReview(on: Boolean) { container.autoReview = on; _autoReview.value = on }
    fun reviewToday() = container.reviews.runToday()
    fun reviewDay(day: String) = container.reviews.run(day)
    fun cancelReview() = container.reviews.cancel()
    fun yesterday(): String = com.dirk.kalshiodds.signal.scalper.DailyReview.yesterday(System.currentTimeMillis())

    companion object {
        val THREADS = listOf(AiChat.SCALPING, AiChat.PAPER)
    }
}
