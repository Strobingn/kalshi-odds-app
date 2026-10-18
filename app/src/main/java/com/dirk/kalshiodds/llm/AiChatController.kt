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

    init { scope.launch { val h = store.load(); synchronized(lock) { nextId = (h.maxOfOrNull { it.id } ?: 0L) + 1; _messages.value = h } } }

    private fun add(role: String, text: String, key: String? = null, old: String? = null, new: String? = null, status: String? = null): ChatMessage {
        val m = synchronized(lock) { ChatMessage(nextId++, role, text, now(), key, old, new, status).also { _messages.value = _messages.value + it } }
        store.append(m); return m
    }

    private fun replace(m: ChatMessage) { synchronized(lock) { _messages.value = _messages.value.map { if (it.id == m.id) m else it } }; store.update(m) }

    /** [context] is a short leaderboard / strategy status block from the UI. */
    fun send(question: String, context: String) {
        val q = question.trim(); if (q.isEmpty() || _busy.value) return
        _busy.value = true
        scope.launch {
            try {
                add(ChatMessage.USER, q)
                if (!hasKey()) { add(ChatMessage.ERROR, "No LLM API key saved. Enter it in the key settings above the chat (stored encrypted on this phone)."); return@launch }
                val history = _messages.value.filter { it.role == ChatMessage.USER || it.role == ChatMessage.AI || it.role == ChatMessage.CHANGE }.dropLast(1).takeLast(16)
                    .map { (if (it.role == ChatMessage.USER) "user" else "assistant") to it.text }
                val sys = "You are the AI tab of a PAPER Kalshi BTC 15-minute scalping app. Explain results concisely and plainly. " +
                    "You cannot place orders and can never touch real money.\n" + SettingTools.describe(controls.state.value) + "\nApp state:\n" + context
                val (model, text) = runCatching { ask(listOf("system" to sys) + history + ("user" to q)) }
                    .getOrElse { add(ChatMessage.ERROR, "Error (${modelName()}, no fallback model): ${it.message ?: it.javaClass.simpleName}"); return@launch }
                val body = SettingTools.stripCalls(text)
                add(ChatMessage.AI, "[model $model] " + body.ifBlank { "(proposed a settings change)" })
                SettingTools.parseCalls(text).take(5).forEach { (k, v) -> propose(k, v) }
            } finally { _busy.value = false }
        }
    }

    /** Public so tests (and a future manual entry) can drive the tool directly. */
    fun propose(key: String, value: String): ChatMessage = when (val r = SettingTools.propose(controls.state.value, key, value)) {
        is SettingTools.Result.Proposal -> add(ChatMessage.PROPOSAL, "Change ${r.key}?", r.key, r.oldValue, r.newValue, ChatMessage.PENDING)
        is SettingTools.Result.Rejected -> add(ChatMessage.CHANGE, (if (r.realMoneyBlocked) "BLOCKED " else "Rejected ") + "set_setting ${r.key} = $value: ${r.reason}", r.key, null, value, "rejected")
    }

    fun applyProposal(id: Long) = scope.launch { resolve(id, apply = true) }
    fun cancelProposal(id: Long) = scope.launch { resolve(id, apply = false) }

    fun resolve(id: Long, apply: Boolean) {
        val m = _messages.value.firstOrNull { it.id == id && it.role == ChatMessage.PROPOSAL && it.status == ChatMessage.PENDING } ?: return
        val key = m.key ?: return; val new = m.newValue ?: return
        if (!apply) { replace(m.copy(status = ChatMessage.CANCELLED)); add(ChatMessage.CHANGE, "Cancelled: $key stays ${m.oldValue}", key, m.oldValue, new, ChatMessage.CANCELLED); return }
        val cur = controls.state.value
        runCatching { SettingTools.apply(cur, SettingTools.Result.Proposal(key, SettingTools.get(cur, key), new)) }
            .onSuccess { next ->
                val before = SettingTools.get(cur, key)
                controls.save(next)
                replace(m.copy(status = ChatMessage.APPLIED))
                add(ChatMessage.CHANGE, "Applied: $key $before → ${SettingTools.get(controls.state.value, key)}", key, before, new, ChatMessage.APPLIED)
            }
            .onFailure { replace(m.copy(status = "rejected")); add(ChatMessage.ERROR, "Could not apply $key = $new: ${it.message}") }
    }

    fun clear() = scope.launch { synchronized(lock) { _messages.value = emptyList() }; store.clear() }
}
