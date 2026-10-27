package com.dirk.kalshiodds.signal.scalper

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Picks the provider that answers fastest right now, so the AIs can follow a live 15-minute window instead of
 * checking in once a minute.
 *
 * Every call is timed. A provider's time is a running average of its last answers ([ALPHA] on the newest one), and
 * [order] puts the quickest one first; one that has never answered is placed by [seeded], which lists the custom-
 * silicon providers (Cerebras, then Groq) ahead of the ones that run on GPUs.
 *
 * Calling the fastest provider as often as it will answer runs into the free tiers (Cerebras: 5 calls a minute,
 * 1M tokens a day), so a provider that reports a rate limit or a spent quota is set aside for a while and the
 * next-fastest answers meanwhile. Each limit in a row doubles that wait, from [LIMIT_COOLDOWN_MS] up to
 * [MAX_COOLDOWN_MS], so a spent daily quota costs one wasted call every few minutes rather than one per tick; the
 * first answer clears it. Anything else that goes wrong (a network drop, a bad key) waits [ERROR_COOLDOWN_MS].
 *
 * [waitMs] is the other half: it tells the caller's loop how long to sleep before the next tick — [floorMs] while
 * some provider is ready, otherwise until the first one comes back.
 *
 * Thread-safe: the AIs, the chat and the writer all share one instance.
 */
class LlmSpeed(
    /** Where a provider that has not answered yet goes in the order, quickest first. */
    private val seeded: List<String> = listOf("cerebras", "groq", "gemini", "mistral"),
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    /** What the AI tab shows: who answers now and how long it takes. */
    data class State(val provider: String = "", val millis: Long = 0L, val resting: List<String> = emptyList())

    private class Stat {
        /** Running average answer time in ms, or null until the first answer. */
        var millis: Double? = null
        var coolUntilMs: Long = 0L
        /** Rate limits in a row; each one doubles the wait. */
        var limits: Int = 0
    }

    private val lock = Any()
    private val stats = HashMap<String, Stat>()
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** One answered call: [ms] is how long it took, end to end. */
    fun answered(provider: String, ms: Long) {
        synchronized(lock) {
            val s = stats.getOrPut(provider) { Stat() }
            s.millis = s.millis?.let { it + ALPHA * (ms - it) } ?: ms.toDouble()
            s.coolUntilMs = 0L
            s.limits = 0
        }
        publish()
    }

    /** A call [provider] could not answer. [why] is the provider's own words, which say whether it was a limit. */
    fun failed(provider: String, why: String) {
        synchronized(lock) {
            val s = stats.getOrPut(provider) { Stat() }
            if (isLimit(why)) {
                s.limits += 1
                val wait = LIMIT_COOLDOWN_MS shl (s.limits - 1).coerceAtMost(SHIFTS)
                s.coolUntilMs = nowMs() + wait.coerceAtMost(MAX_COOLDOWN_MS)
            } else {
                s.coolUntilMs = nowMs() + ERROR_COOLDOWN_MS
            }
        }
        publish()
    }

    /** [available] in the order to try them: the quickest one that is ready, then the rest, then the ones resting. */
    fun order(available: List<String>): List<String> = synchronized(lock) {
        val now = nowMs()
        available.sortedWith(
            compareBy(
                { resting(it, now) },
                { estimateMs(it) },
                { seedRank(it) }
            )
        )
    }

    /** How long the next call should wait: [floorMs] while one provider is ready, else until the first comes back. */
    fun waitMs(available: List<String>, floorMs: Long): Long = synchronized(lock) {
        if (available.isEmpty()) return floorMs
        val now = nowMs()
        val backAt = available.minOf { stats[it]?.coolUntilMs ?: 0L }
        return if (backAt <= now) floorMs else (backAt - now).coerceIn(floorMs, MAX_COOLDOWN_MS)
    }

    /** The running average answer time of [provider] in ms, or null before its first answer. */
    fun millisOf(provider: String): Long? = synchronized(lock) { stats[provider]?.millis?.toLong() }

    /** One line for the Keys screen. */
    fun line(available: List<String>): String {
        if (available.isEmpty()) return "No API key saved yet — add Groq, Cerebras, Gemini or Mistral below"
        val now = synchronized(lock) { nowMs() }
        val first = order(available).first()
        val resting = synchronized(lock) { available.filter { resting(it, now) == 1 } }
        val ms = millisOf(first)
        val speed = if (ms != null) " · ${ms} ms a call" else " · not timed yet"
        val rest = if (resting.isEmpty()) "" else " · resting: " + resting.joinToString { it.replaceFirstChar { c -> c.uppercase() } }
        return "Fastest first: ${first.replaceFirstChar { it.uppercase() }}$speed$rest"
    }

    /** 0 while [p] may be called, 1 while it is resting off a limit or an error. Caller holds [lock]. */
    private fun resting(p: String, now: Long): Int = if ((stats[p]?.coolUntilMs ?: 0L) > now) 1 else 0

    /**
     * What a call to [p] is expected to cost in ms: its measured average, or, before its first answer, a guess from
     * [seeded] that still leaves it behind every provider that has been timed and found quick.
     */
    private fun estimateMs(p: String): Double = stats[p]?.millis ?: (UNTIMED_MS + seedRank(p) * UNTIMED_STEP_MS)

    private fun seedRank(p: String): Int = seeded.indexOf(p).let { if (it < 0) seeded.size else it }

    private fun publish() {
        val s = synchronized(lock) {
            val now = nowMs()
            val ready = stats.entries.filter { it.value.coolUntilMs <= now && it.value.millis != null }
            val best = ready.minByOrNull { estimateMs(it.key) }
            State(
                provider = best?.key.orEmpty(),
                millis = best?.value?.millis?.toLong() ?: 0L,
                resting = stats.entries.filter { it.value.coolUntilMs > now }.map { it.key }.sorted()
            )
        }
        _state.value = s
    }

    companion object {
        /** Weight of the newest answer in a provider's running average. */
        const val ALPHA = 0.35

        /** First rest after a rate limit or a spent quota; doubles on each limit in a row. */
        const val LIMIT_COOLDOWN_MS = 45_000L

        /** Rest after a network drop, a bad key or anything else that is not a limit. */
        const val ERROR_COOLDOWN_MS = 15_000L

        /** A spent daily quota never costs more than one wasted call every five minutes. */
        const val MAX_COOLDOWN_MS = 300_000L

        /** Doublings of [LIMIT_COOLDOWN_MS] before [MAX_COOLDOWN_MS] caps it. */
        private const val SHIFTS = 4

        /** Assumed cost of a provider that has never answered, and the gap between [seeded] places. */
        private const val UNTIMED_MS = 2_500.0
        private const val UNTIMED_STEP_MS = 500.0

        /** True when [why] is the provider saying it is out of calls or tokens rather than broken. */
        fun isLimit(why: String): Boolean {
            val m = why.lowercase()
            return m.contains("429") || m.contains("rate limit") || m.contains("rate_limit") ||
                m.contains("quota") || m.contains("exhausted") || m.contains("too many requests")
        }
    }
}
