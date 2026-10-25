package com.dirk.kalshiodds.signal.scalper

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** One line of a chat thread. [role] is user | ai | error | change | proposal. */
@Serializable
data class ChatMsg(
    val id: Long,
    val thread: String,
    val role: String,
    val text: String,
    val atMs: Long,
    /** Set on a proposal: the scalping setting it would change. */
    val strategy: String? = null,
    val param: String? = null,
    val value: Double? = null,
    /** proposal: pending | applied | cancelled | rejected */
    val status: String? = null
)

/**
 * The chat screen's brain. Two threads, one per AI, so the Paper AI and the Scalping AI stay separate things:
 *  - [SCALPING]: talks about the scalping algorithms. It may propose `SET <STRATEGY> <setting> <value>` lines; each shows
 *    as a card and only changes anything when you tap Apply, and then only through [SupervisorBounds.apply] (the same
 *    hard bounds the scalping AI obeys, paper settings only).
 *  - [PAPER]: talks about the Paper AI's bets. It cannot change anything.
 * Neither thread can place an order or touch a real-money setting.
 */
class AiChat(
    private val ask: (List<Pair<String, String>>) -> Pair<String, String>?,
    private val tuning: ScalpTuning,
    private val context: (String) -> String,
    private val load: () -> String? = { null },
    private val save: (String) -> Unit = {},
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val lastError: () -> String = { "" }
) {
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true }
    private val ser = ListSerializer(ChatMsg.serializer())
    private val _messages = MutableStateFlow<List<ChatMsg>>(emptyList())
    val messages: StateFlow<List<ChatMsg>> = _messages.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private var nextId = 1L

    init {
        val saved = runCatching { load()?.let { json.decodeFromString(ser, it) } }.getOrNull().orEmpty()
        _messages.value = saved
        nextId = (saved.maxOfOrNull { it.id } ?: 0L) + 1
    }

    private fun add(m: ChatMsg): ChatMsg = synchronized(lock) {
        val withId = m.copy(id = nextId++, atMs = nowMs())
        _messages.value = (_messages.value + withId).takeLast(KEEP)
        runCatching { save(json.encodeToString(ser, _messages.value)) }
        withId
    }

    private fun replace(id: Long, f: (ChatMsg) -> ChatMsg) = synchronized(lock) {
        _messages.value = _messages.value.map { if (it.id == id) f(it) else it }
        runCatching { save(json.encodeToString(ser, _messages.value)) }
    }

    fun thread(which: String): List<ChatMsg> = _messages.value.filter { it.thread == which }

    fun clear(which: String) = synchronized(lock) {
        _messages.value = _messages.value.filter { it.thread != which }
        runCatching { save(json.encodeToString(ser, _messages.value)) }
        Unit
    }

    /** Ask the AI of [which] thread. Blocks on the network: run it off the main thread. */
    fun send(which: String, question: String) {
        val q = question.trim()
        if (q.isEmpty()) return
        synchronized(lock) { if (_busy.value) return; _busy.value = true }
        try {
            add(ChatMsg(0, which, "user", q, 0))
            val history = historyFor(which).dropLast(1) // the question itself goes last
            val msgs = listOf("system" to system(which, context(which))) + history + ("user" to q)
            val answer = runCatching { ask(msgs) }.getOrNull()
            if (answer == null) {
                add(ChatMsg(0, which, "error", "No answer: " + lastError().ifBlank { "check your keys in AI → Keys" }, 0))
                return
            }
            val (provider, text) = answer
            val sets = if (which == SCALPING) parseSets(text) else emptyList()
            val shown = stripSets(text).ifBlank { if (sets.isNotEmpty()) "(proposed a settings change)" else "(empty answer)" }
            add(ChatMsg(0, which, "ai", "[$provider] $shown", 0))
            for ((s, p, v) in sets.take(MAX_PROPOSALS)) {
                add(ChatMsg(0, which, "proposal", "Change $s $p to $v?", 0, s, p, v, "pending"))
            }
        } catch (e: Exception) {
            add(ChatMsg(0, which, "error", "Error: ${e.message ?: e.javaClass.simpleName}", 0))
        } finally {
            _busy.value = false
        }
    }

    /** Apply a pending proposal (paper settings only, inside the supervisor's hard bounds). */
    fun apply(id: Long): String {
        val m = synchronized(lock) {
            val p = _messages.value.firstOrNull { it.id == id && it.role == "proposal" && it.status == "pending" } ?: return "already handled"
            replace(id) { it.copy(status = "applying") }
            p
        }
        // You tap Apply yourself, so this one may switch a strategy off.
        val r = SupervisorBounds.apply(tuning, m.strategy.orEmpty(), m.param.orEmpty(), m.value ?: Double.NaN, allowOff = true)
        return when (r) {
            is SupervisorBounds.Change.Applied -> {
                replace(id) { it.copy(status = "applied") }
                val line = "Applied: ${r.strategy} ${r.param} ${r.old ?: "default"} → ${r.new} (you asked ${m.value}, bounds may clamp)"
                add(ChatMsg(0, m.thread, "change", line, 0)); line
            }
            is SupervisorBounds.Change.Rejected -> {
                replace(id) { it.copy(status = "rejected") }
                val line = "Rejected: ${r.strategy} ${r.param}: ${r.why}"
                add(ChatMsg(0, m.thread, "change", line, 0)); line
            }
        }
    }

    fun cancel(id: Long) {
        val m = _messages.value.firstOrNull { it.id == id && it.role == "proposal" && it.status == "pending" } ?: return
        replace(id) { it.copy(status = "cancelled") }
        add(ChatMsg(0, m.thread, "change", "Cancelled: ${m.strategy} ${m.param} stays as it is", 0))
    }

    /** user/assistant alternation for the model: errors and change logs become assistant notes, runs merge. */
    internal fun historyFor(which: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (m in thread(which).takeLast(HISTORY)) {
            val turn = when (m.role) {
                "user" -> "user" to m.text
                "ai" -> "assistant" to m.text.replace(Regex("^\\[[^\\]]*\\]\\s*"), "")
                "change" -> "assistant" to "(settings log: ${m.text})"
                "proposal" -> "assistant" to "(proposed ${m.strategy} ${m.param} ${m.value}: ${m.status})"
                else -> continue
            }
            if (out.isNotEmpty() && out.last().first == turn.first) out[out.lastIndex] = turn.first to (out.last().second + "\n" + turn.second)
            else out += turn
        }
        while (out.isNotEmpty() && out.first().first != "user") out.removeAt(0)
        return out
    }

    companion object {
        const val SCALPING = "scalping"
        const val PAPER = "paper"
        const val KEEP = 120
        const val HISTORY = 16
        const val MAX_PROPOSALS = 5

        private val SET = Regex("""(?m)^\s*SET\s+([A-Za-z0-9_]+)\s+([A-Za-z]+)\s+(-?\d+(?:\.\d+)?)\b[^\n]*$""")

        fun parseSets(text: String): List<Triple<String, String, Double>> =
            SET.findAll(text).mapNotNull { m ->
                val v = m.groupValues[3].toDoubleOrNull() ?: return@mapNotNull null
                Triple(m.groupValues[1].uppercase(), m.groupValues[2], v)
            }.toList()

        fun stripSets(text: String): String = SET.replace(text, "").replace(Regex("\n{3,}"), "\n\n").trim()

        fun system(which: String, ctx: String): String = if (which == SCALPING) {
            "You are the SCALPING AI inside a Bitcoin-only Kalshi 15-minute paper-trading app. You tune the paper scalping algorithms " +
                "(the arena). A separate Paper AI places paper bets; you do not. Answer any question directly and briefly. " +
                "You are following the live window through the app's feed (the block below is current). " +
                "You can never place an order or touch real money. To propose a paper setting change, put one line per change " +
                "exactly like `SET <STRATEGY_NAME> <setting> <number>` (settings: ${SupervisorBounds.params.joinToString()}). " +
                "The user must tap Apply and the change is clamped to hard bounds. Propose few changes and only with evidence.\n" +
                "Live context:\n" + ctx
        } else {
            "You are the PAPER AI inside a Bitcoin-only Kalshi 15-minute paper-trading app. You decide one paper bet per window " +
                "(YES = UP, NO = DOWN, or SKIP). A separate Scalping AI tunes the scalping algorithms; you do not. " +
                "Answer any question directly and briefly, explain your bets and what you learned. You cannot change settings, " +
                "place real orders or touch real money in this chat.\nLive context:\n" + ctx
        }
    }
}
