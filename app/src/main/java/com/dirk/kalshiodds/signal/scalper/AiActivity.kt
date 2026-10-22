package com.dirk.kalshiodds.signal.scalper

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * One thing an AI did: an LLM call (ok or failed, which provider, how long), a settings change, a paper bet.
 * [ai] is [SCALPING], [PAPER] or [CHAT].
 */
@Serializable
data class AiEvent(
    val atMs: Long,
    val ai: String,
    val kind: String,
    val provider: String = "",
    val ok: Boolean = true,
    val ms: Long = 0,
    val detail: String = ""
) {
    companion object {
        const val SCALPING = "scalping"
        const val PAPER = "paper"
        const val CHAT = "chat"

        const val CALL = "call"
        const val APPLIED = "applied"
        const val REJECTED = "rejected"
        const val SCORED_BETTER = "better"
        const val SCORED_WORSE = "worse"
        const val BET = "bet"
        const val SKIP = "skip"
        const val SETTLED = "settled"
    }
}

/** Everything both AIs did, newest last, kept on the phone for the AI tab's charts. */
class AiActivity(
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    load: () -> String? = { null },
    private val save: (String) -> Unit = {},
    private val keep: Int = 4000
) {
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true }
    private val ser = ListSerializer(AiEvent.serializer())
    private val _events = MutableStateFlow<List<AiEvent>>(
        runCatching { load()?.let { json.decodeFromString(ser, it) } }.getOrNull().orEmpty().takeLast(keep)
    )
    val events: StateFlow<List<AiEvent>> = _events.asStateFlow()
    private var unsaved = 0

    fun record(ai: String, kind: String, provider: String = "", ok: Boolean = true, ms: Long = 0, detail: String = "") {
        val list = synchronized(lock) {
            _events.value = (_events.value + AiEvent(nowMs(), ai, kind, provider, ok, ms, detail.take(200))).takeLast(keep)
            unsaved += 1
            if (unsaved < SAVE_EVERY && kind == AiEvent.CALL) return
            unsaved = 0
            _events.value
        }
        runCatching { save(json.encodeToString(ser, list)) }
    }

    fun call(ai: String): (String, Boolean, Long) -> Unit = { provider, ok, ms -> record(ai, AiEvent.CALL, provider, ok, ms) }

    companion object {
        const val SAVE_EVERY = 5
    }
}
