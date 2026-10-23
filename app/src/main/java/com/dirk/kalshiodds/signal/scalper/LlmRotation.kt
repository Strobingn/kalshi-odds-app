package com.dirk.kalshiodds.signal.scalper

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared by every AI: all calls go to one provider until it has answered [every] calls, then the next provider with a
 * key takes over (Groq → Cerebras → Gemini → Mistral → Groq …). Spreads the load over the free tiers. A provider that
 * fails still falls through to the next one for that call; only answered calls count. When the current one fails
 * [maxFailures] calls in a row (over its free limit, a bad key), the rotation moves on at once instead of trying it
 * first on every call.
 */
class LlmRotation(
    private val names: List<String>,
    private val every: Int = 500,
    private val hasKey: (String) -> Boolean,
    private val maxFailures: Int = 3,
    load: () -> Pair<String, Int>? = { null },
    private val save: (String, Int) -> Unit = { _, _ -> }
) {
    data class State(val provider: String, val calls: Int, val every: Int)

    private val lock = Any()
    private var current: String
    private var calls: Int
    private var failures = 0
    private val _state: MutableStateFlow<State>
    val state: StateFlow<State>

    init {
        val saved = runCatching { load() }.getOrNull()
        current = saved?.first?.takeIf { it in names } ?: names.first()
        calls = saved?.second?.coerceAtLeast(0) ?: 0
        _state = MutableStateFlow(State(current, calls, every))
        state = _state.asStateFlow()
    }

    /** [available] providers in the order to try them now: the current one first, then the rest of the cycle. */
    fun order(available: List<String>): List<String> = synchronized(lock) {
        if (!hasKey(current)) advance()
        val start = names.indexOf(current).coerceAtLeast(0)
        val cycle = names.indices.map { names[(start + it) % names.size] }
        cycle.filter { it in available } + available.filter { it !in cycle }
    }

    /** A call [provider] could not answer. [maxFailures] in a row on the current one moves the rotation on. */
    fun failed(provider: String) {
        synchronized(lock) {
            if (provider != current) return
            failures += 1
            if (failures >= maxFailures) advance()
        }
    }

    /** One answered call by [provider]. Counts toward the switch only when it is the current one. */
    fun answered(provider: String) {
        val s = synchronized(lock) {
            if (provider != current) return
            failures = 0
            calls += 1
            if (calls >= every) advance()
            State(current, calls, every)
        }
        _state.value = s
        runCatching { save(s.provider, s.calls) }
    }

    /** Next provider in the cycle that has a key (stays put when none other has one). Caller holds [lock]. */
    private fun advance() {
        val i = names.indexOf(current)
        for (k in 1..names.size) {
            val next = names[(i + k) % names.size]
            if (hasKey(next) && next != current) {
                current = next
                calls = 0
                failures = 0
                _state.value = State(current, calls, every)
                runCatching { save(current, calls) }
                return
            }
        }
        if (calls >= every) calls = 0
        failures = 0
    }
}
