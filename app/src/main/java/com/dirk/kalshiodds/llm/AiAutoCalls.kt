package com.dirk.kalshiodds.llm

import java.util.ArrayDeque

/**
 * 1.0.17: the provider is called on its own only when Dirk turned that on.
 * Paid providers default off (and an upgrade with no saved switch stays off).
 * A free-tier provider defaults on once it is selected, and calls are paced under the free quota
 * and the optional daily auto-call budget. Manual chat is not paced here.
 */
object AiAutoCalls {
    const val SWITCH = "AI calls on its own (uses credits)"
    const val FREE_TIER = "This key is free tier"
    const val PAUSED = "Paused - AI auto calls off"

    /** Free-family providers start with automatic calls on. Paid providers start off. */
    fun defaultAuto(providerId: String, model: String): Boolean = LlmProviders.freeFamily(providerId, model)

    /** A settings file from 1.0.16 has no switch. That upgrade stays off. */
    fun upgraded(saved: Boolean?): Boolean = saved ?: false

    fun paceLabel(auto: Boolean, ep: LlmEndpoint, quota: FreeQuota?): String {
        if (!auto) return "Auto pace: off"
        if (quota == null) return "Auto pace: at most every 60s"
        val models = LlmProviders.paceModels(ep)
        val ms = if (models.size >= 2) quota.perModelPaceMs / 2 else quota.perModelPaceMs
        val sec = (ms / 1000L).coerceAtLeast(1L)
        return if (models.size >= 2) "Auto pace: every ${sec}s, rotating ${models.joinToString(" and ")}"
        else "Auto pace: every ${sec}s"
    }

    /**
     * One automatic free-tier call, or null when the pace, budget, overlap, or a dead key says to skip.
     * On 429, try the next configured free provider. Never a paid one.
     * A billing response is reported and that provider is left for the caller to shut off; the next free provider may run.
     */
    fun callFree(
        selected: LlmEndpoint,
        slots: List<LlmEndpoint>,
        pacer: FreeCallPacer,
        nowMs: Long,
        messages: List<Pair<String, String>>,
        reserveCompletion: Int,
        quotaOf: (LlmEndpoint) -> FreeQuota?,
        allowed: (LlmEndpoint) -> Boolean,
        ask: (LlmEndpoint, List<Pair<String, String>>) -> LlmClient.Reply,
        onBilling: (String, String) -> Unit
    ): Pair<LlmEndpoint, LlmClient.Reply>? {
        if (!allowed(selected) || !LlmProviders.freeFamily(selected.provider, selected.model)) return null
        val order = ArrayList<LlmEndpoint>()
        order += selected
        for (slot in slots) {
            if (!slot.usable || slot.provider == selected.provider) continue
            if (!LlmProviders.freeFamily(slot.provider, slot.model) || !allowed(slot)) continue
            order += slot
        }
        val tokens = AiCompact.tokens(messages, reserveCompletion)
        for (ep in order) {
            if (!allowed(ep)) continue
            val quota = quotaOf(ep) ?: continue
            val model = pacer.allow(ep.provider, LlmProviders.paceModels(ep), quota, nowMs, tokens) ?: continue
            val use = ep.copy(model = model)
            try {
                val reply = ask(use, messages)
                pacer.record(use.provider, model, nowMs, tokens)
                pacer.noteRemaining(use.provider, model, reply.remainingRequests, reply.remainingTokens, nowMs)
                return use to reply
            } catch (e: LlmSkipped) {
                return null
            } catch (e: LlmRateLimited) {
                pacer.note429(use.provider, model, e.retryAfterMs, nowMs)
            } catch (e: LlmBillingRequired) {
                onBilling(e.provider.ifBlank { use.provider }, e.message ?: "billing")
            } catch (e: Exception) {
                pacer.note429(use.provider, model, quota.perModelPaceMs, nowMs)
                return null
            }
        }
        return null
    }
}

/** Short automatic prompts so a free token-per-minute ceiling still fits the request pace. */
object AiCompact {
    const val GROQ_CALL_TOKENS = 180
    const val RESERVE_COMPLETION = 40

    fun watch(context: String): List<Pair<String, String>> {
        val system = "Paper tuner. JSON only {\"adjustments\":[]}. At most 1 change. No orders. No UP bias."
        val room = (GROQ_CALL_TOKENS - RESERVE_COMPLETION) * 4 - system.length
        val user = context.replace(Regex("\\s+"), " ").trim().take(room.coerceAtLeast(40))
        return listOf("system" to system, "user" to user)
    }

    fun tokens(messages: List<Pair<String, String>>, reserveCompletion: Int): Int {
        val prompt = messages.sumOf { it.second.length } / 4
        return (prompt + reserveCompletion).coerceAtLeast(1)
    }
}

/**
 * Per-model free-tier pace. Request, token, minute, and day ceilings are all hard stops.
 * Groq's two models alternate, so the gap between any two calls is half the per-model gap.
 */
class FreeCallPacer {
    private data class Hit(val at: Long, val provider: String, val model: String, val tokens: Int)
    private val hits = ArrayDeque<Hit>()
    private val backoffUntil = HashMap<String, Long>()
    private var cursor = 0

    fun allow(provider: String, models: List<String>, quota: FreeQuota, now: Long, tokens: Int): String? =
        pick(provider, models, quota, now, tokens, advance = true)

    fun ready(provider: String, models: List<String>, quota: FreeQuota, now: Long, tokens: Int): Boolean =
        pick(provider, models, quota, now, tokens, advance = false) != null

    fun record(provider: String, model: String, now: Long, tokens: Int) {
        hits.addLast(Hit(now, provider, model, tokens.coerceAtLeast(0)))
        prune(now)
    }

    fun note429(provider: String, model: String, retryAfterMs: Long, now: Long) {
        backoffUntil["$provider|$model"] = now + retryAfterMs.coerceAtLeast(1_000L)
    }

    fun noteRemaining(provider: String, model: String, remainingRequests: Int?, remainingTokens: Int?, now: Long) {
        if ((remainingRequests != null && remainingRequests <= 0) || (remainingTokens != null && remainingTokens <= 0)) {
            note429(provider, model, 60_000L, now)
        }
    }

    fun hits(): List<Pair<Long, String>> = hits.map { it.at to it.model }

    private fun pick(provider: String, models: List<String>, quota: FreeQuota, now: Long, tokens: Int, advance: Boolean): String? {
        if (models.isEmpty() || tokens <= 0 || quota.requestsPerMinute <= 0 || quota.requestsPerDay <= 0) return null
        prune(now)
        val n = models.size
        val globalPace = if (n >= 2) quota.perModelPaceMs / 2 else quota.perModelPaceMs
        val lastAny = hits.lastOrNull { it.provider == provider }?.at
        if (lastAny != null && globalPace > 0L && now - lastAny < globalPace) return null
        for (i in 0 until n) {
            val model = models[(cursor + i) % n]
            if (now < (backoffUntil["$provider|$model"] ?: Long.MIN_VALUE)) continue
            if (!within(provider, model, quota, now, tokens)) continue
            if (advance) cursor = (cursor + i + 1) % n
            return model
        }
        return null
    }

    private fun within(provider: String, model: String, quota: FreeQuota, now: Long, tokens: Int): Boolean {
        var minuteCalls = 0
        var dayCalls = 0
        var minuteTokens = 0L
        var dayTokens = 0L
        var last = Long.MIN_VALUE
        for (hit in hits) {
            if (hit.provider != provider || hit.model != model) continue
            val age = now - hit.at
            if (age in 0 until DAY_MS) {
                dayCalls++
                dayTokens += hit.tokens
                if (age < MINUTE_MS) {
                    minuteCalls++
                    minuteTokens += hit.tokens
                }
            }
            if (hit.at > last) last = hit.at
        }
        if (minuteCalls >= quota.requestsPerMinute) return false
        if (dayCalls >= quota.requestsPerDay) return false
        if (minuteTokens + tokens > quota.tokensPerMinute) return false
        if (dayTokens + tokens > quota.tokensPerDay) return false
        if (last != Long.MIN_VALUE && quota.perModelPaceMs > 0L && now - last < quota.perModelPaceMs) return false
        return true
    }

    private fun prune(now: Long) {
        while (hits.isNotEmpty() && now - hits.first().at >= DAY_MS) hits.removeFirst()
    }

    private companion object {
        const val MINUTE_MS = 60_000L
        const val DAY_MS = 86_400_000L
    }
}
