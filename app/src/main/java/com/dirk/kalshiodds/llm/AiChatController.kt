package com.dirk.kalshiodds.llm

import com.dirk.kalshiodds.decision.ScalpControlsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 1.0.4 AI tab state: persistent history, a busy flag, and the confirm-gated set_setting tool.
 * Errors (missing key, HTTP, model mismatch) are added to the chat as "error" messages — never swallowed.
 * The only write this class can do is [ScalpControlsRepository.save] of a PAPER setting after [applyProposal].
 */
class AiChatController(
    private val store: ChatHistoryStore,
    private val controls: ScalpControlsRepository,
    private val ask: (List<Pair<String, String>>) -> Pair<String, String>,
    private val hasKey: () -> Boolean,
    private val modelName: () -> String,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy
    private var nextId = 1L
    private val lock = Any()
    /** Bumped by [clear]; a reply that started under an older generation is dropped (never lands in a cleared chat). */
    @Volatile private var generation = 0L
    private var job: kotlinx.coroutines.Job? = null

    init { scope.launch { val h = store.load(); synchronized(lock) { val all = h + _messages.value.filter { m -> h.none { it.id == m.id } }; nextId = maxOf(nextId, (all.maxOfOrNull { it.id } ?: 0L) + 1); _messages.value = all } } }

    private fun add(role: String, text: String, key: String? = null, old: String? = null, new: String? = null, status: String? = null, gen: Long = generation): ChatMessage? =
        synchronized(lock) {
            if (gen != generation) return null
            ChatMessage(nextId++, role, text, now(), key, old, new, status).also { _messages.value = _messages.value + it; store.saveAll(_messages.value) }
        }

    private fun replace(m: ChatMessage) = synchronized(lock) { _messages.value = _messages.value.map { if (it.id == m.id) m else it }; store.saveAll(_messages.value) }

    /** OpenAI-compatible APIs want user/assistant alternation: errors and change logs become assistant turns, runs are merged. */
    internal fun llmHistory(msgs: List<ChatMessage>): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (m in msgs) {
            val turn = when (m.role) {
                ChatMessage.USER -> "user" to m.text
                ChatMessage.AI -> "assistant" to m.text
                ChatMessage.ERROR -> "assistant" to "(request failed: ${m.text})"
                ChatMessage.CHANGE -> "assistant" to "(settings log: ${m.text})"
                ChatMessage.PROPOSAL -> "assistant" to "(proposed ${m.key}: ${m.oldValue} → ${m.newValue}, ${m.status})"
                else -> continue
            }
            if (out.isNotEmpty() && out.last().first == turn.first) out[out.lastIndex] = turn.first to (out.last().second + "\n" + turn.second) else out += turn
        }
        while (out.isNotEmpty() && out.first().first != "user") out.removeAt(0)
        return out
    }

    /** [context] is a short leaderboard / strategy status block from the UI. */
    fun send(question: String, context: String) {
        val q = question.trim(); if (q.isEmpty() || _busy.value) return
        _busy.value = true
        val gen = generation
        job = scope.launch {
            try {
                add(ChatMessage.USER, q, gen = gen) ?: return@launch
                if (!hasKey()) { add(ChatMessage.ERROR, "No LLM API key saved. Enter it in the key settings above the chat (stored encrypted on this phone).", gen = gen); return@launch }
                // History before this question, alternating roles; the question itself goes last as the user turn.
                val history = llmHistory(_messages.value.dropLast(1).takeLast(24)).let { h -> if (h.lastOrNull()?.first == "user") h.dropLast(1) else h }.takeLast(16)
                    .let { h -> h.dropWhile { it.first != "user" } }
                val sys = "You are the AI tab of a PAPER Kalshi BTC 15-minute scalping app. Explain results concisely and plainly. " +
                    "You cannot place orders and can never touch real money.\n" + SettingTools.describe(controls.state.value) + "\nApp state:\n" + context
                val (model, text) = runCatching { ask(listOf("system" to sys) + history + ("user" to q)) }
                    .getOrElse { e -> if (e is kotlinx.coroutines.CancellationException) throw e
                        add(ChatMessage.ERROR, "Error (${modelName()}, no fallback model): ${e.message ?: e.javaClass.simpleName}", gen = gen); return@launch }
                val body = SettingTools.stripCalls(text)
                add(ChatMessage.AI, "[model $model] " + body.ifBlank { "(proposed a settings change)" }, gen = gen) ?: return@launch
                SettingTools.parseCalls(text).take(5).forEach { (k, v) -> propose(k, v, gen) }
            } finally { if (gen == generation) _busy.value = false }
        }
    }

    /** Public so tests (and a future manual entry) can drive the tool directly. */
    fun propose(key: String, value: String, gen: Long = generation): ChatMessage? = when (val r = SettingTools.propose(controls.state.value, key, value)) {
        is SettingTools.Result.Proposal -> add(ChatMessage.PROPOSAL, "Change ${r.key}?", r.key, r.oldValue, r.newValue, ChatMessage.PENDING, gen)
        is SettingTools.Result.Rejected -> add(ChatMessage.CHANGE, (if (r.realMoneyBlocked) "BLOCKED " else "Rejected ") + "set_setting ${r.key} = $value: ${r.reason}", r.key, null, value, "rejected", gen)
    }

    fun applyProposal(id: Long) = scope.launch { resolve(id, apply = true) }
    fun cancelProposal(id: Long) = scope.launch { resolve(id, apply = false) }

    fun resolve(id: Long, apply: Boolean) {
        val m = _messages.value.firstOrNull { it.id == id && it.role == ChatMessage.PROPOSAL && it.status == ChatMessage.PENDING } ?: return
        val key = m.key ?: return; val new = m.newValue ?: return
        if (!apply) { replace(m.copy(status = ChatMessage.CANCELLED)); add(ChatMessage.CHANGE, "Cancelled: $key stays ${m.oldValue}", key, m.oldValue, new, ChatMessage.CANCELLED); return }
        var before = m.oldValue.orEmpty()
        runCatching { controls.update { cur -> before = SettingTools.get(cur, key); SettingTools.apply(cur, SettingTools.Result.Proposal(key, before, new)) } }
            .onSuccess { saved ->
                replace(m.copy(status = ChatMessage.APPLIED))
                add(ChatMessage.CHANGE, "Applied: $key $before → ${SettingTools.get(saved, key)}", key, before, new, ChatMessage.APPLIED)
            }
            .onFailure { replace(m.copy(status = "rejected")); add(ChatMessage.ERROR, "Could not apply $key = $new: ${it.message}") }
    }

    fun clear() {
        synchronized(lock) { generation++; job?.cancel(); job = null; _messages.value = emptyList(); _busy.value = false }
        scope.launch { synchronized(lock) { if (_messages.value.isEmpty()) store.clear() else store.saveAll(_messages.value) } }
    }
}
