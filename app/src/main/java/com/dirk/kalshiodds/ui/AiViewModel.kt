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

    private fun savedKeys() = listOf(
        "Groq" to secrets.groqApiKey.isNotBlank(),
        "Mistral" to secrets.mistralApiKey.isNotBlank(),
        "Gemini" to secrets.geminiApiKey.isNotBlank()
    )

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
            paperStatus = container.paperAi.status.value + " · " + container.paperAi.record.value
        )
        val bets = container.paperAi.openBets() + container.paperAi.settledBets()
        return AiDashboard.build(base, System.currentTimeMillis(), container.aiActivity.events.value, strategies, bets)
    }

    fun setDraft(provider: String, v: String) = _keys.update {
        when (provider) {
            "Groq" -> it.copy(groqDraft = v)
            "Mistral" -> it.copy(mistralDraft = v)
            else -> it.copy(geminiDraft = v)
        }
    }

    /** Saves the typed keys (encrypted), then tests them and starts both AIs at once. */
    fun saveKeys() {
        val k = _keys.value
        if (k.groqDraft.isNotBlank()) secrets.groqApiKey = k.groqDraft.trim()
        if (k.mistralDraft.isNotBlank()) secrets.mistralApiKey = k.mistralDraft.trim()
        if (k.geminiDraft.isNotBlank()) secrets.geminiApiKey = k.geminiDraft.trim()
        val any = k.groqDraft.isNotBlank() || k.mistralDraft.isNotBlank() || k.geminiDraft.isNotBlank()
        _keys.value = keysNow().copy(message = if (any) "Saved (encrypted on this phone). Testing…" else "Type a key first", testLines = k.testLines)
        if (any) { testKeys(); container.kickAi() }
    }

    fun clearKey(provider: String) {
        when (provider) {
            "Groq" -> secrets.groqApiKey = ""
            "Mistral" -> secrets.mistralApiKey = ""
            else -> secrets.geminiApiKey = ""
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

    companion object {
        val THREADS = listOf(AiChat.SCALPING, AiChat.PAPER)
    }
}
