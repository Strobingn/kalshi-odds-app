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
        val raw = if (models.size >= 2) quota.perModelPaceMs / 2 else quota.perModelPaceMs
        val ms = raw.coerceAtLeast(LlmProviders.AUTO_PACE_MS)
        val label = if (ms < 1_000L) String.format(java.util.Locale.US, "%.1fs", ms / 1000.0) else "${(ms / 1000L).coerceAtLeast(1L)}s"
        return if (models.size >= 2) "Auto pace: every $label, rotating ${models.joinToString(" and ")}"
        else "Auto pace: every $label"
    }

    /**
     * One automatic free-tier call, or null when the pace, budget, overlap, or a dead key says to skip.
     * On a rate limit, quota, timeout, or other error, try the next free provider. Never a paid one.
     * A billing response pauses that one provider; the next free provider may still run.
     * [onFallback] is display-only. It must not change the saved provider, model, or switches.
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
        onBilling: (String, String) -> Unit,
        onFallback: (LlmEndpoint, LlmEndpoint, String) -> Unit = { _, _, _ -> },
        /** Today's saved calls for this provider. Combined with the pacer's session hits. */
        callsToday: (LlmEndpoint) -> Int = { 0 }
    ): Pair<LlmEndpoint, LlmClient.Reply>? {
        if (!LlmProviders.freeFamily(selected.provider, selected.model)) return null
        val tail = slots.asSequence()
            .filter { it.usable && it.provider != selected.provider }
            .filter { LlmProviders.freeFamily(it.provider, it.model) && allowed(it) }
            .distinctBy { it.provider }
            .sortedBy { LlmProviders.freeFallbackRank(it.provider) }
            .toList()
        val order = (if (allowed(selected)) listOf(selected) else emptyList()) + tail
        if (order.isEmpty()) return null
        val tokens = AiCompact.tokens(messages, reserveCompletion)
        var failed: Pair<LlmEndpoint, String>? = if (allowed(selected)) null else selected to "paused"
        for (ep in order) {
            if (!allowed(ep)) continue
            val quota = quotaOf(ep) ?: continue
            val cap = quota.requestsPerDay
            val used = maxOf(callsToday(ep).coerceAtLeast(0), pacer.dayCalls(ep.provider, nowMs))
            if (cap > 0 && used >= cap) {
                failed = ep to "daily cap $used/$cap"
                continue
            }
            val models = LlmProviders.paceModels(ep)
            val model = pacer.allow(ep.provider, models, quota, nowMs, tokens)
            if (model == null) {
                if (ep.provider == selected.provider) {
                    val why = pacer.hold(ep.provider, models, quota, nowMs, tokens)?.reason ?: "pace"
                    failed = selected to why
                }
                continue
            }
            val use = ep.copy(model = model)
            try {
                val reply = ask(use, messages)
                pacer.record(use.provider, model, nowMs, tokens)
                pacer.noteRemaining(use.provider, model, reply.remainingRequests, reply.remainingTokens, nowMs)
                val prior = failed
                if (prior != null && prior.first.provider != use.provider) onFallback(prior.first, use, prior.second)
                return use to reply
            } catch (e: LlmSkipped) {
                if (e.message == "timeout") failed = use to "timeout"
                else return null
            } catch (e: LlmRateLimited) {
                pacer.note429(use.provider, model, e.retryAfterMs, nowMs)
                failed = use to "rate limit"
            } catch (e: LlmModelUnavailable) {
                // The plan rejected every model on this provider. Try the next free provider. Do not back off.
                failed = use to "model not on plan"
            } catch (e: LlmBillingRequired) {
                onBilling(e.provider.ifBlank { use.provider }, e.message ?: "billing")
                failed = use to "billing required"
            } catch (e: Exception) {
                pacer.note429(use.provider, model, quota.perModelPaceMs.coerceAtLeast(1_000L), nowMs)
                failed = use to "error"
            }
        }
        return null
    }

    data class Wait(val reason: String, val resumeAtMs: Long)

    /**
     * Why every free provider is quiet, and the soonest ET-ready instant.
     * Null when one of them can be called now. Paid providers are not in [endpoints].
     */
    fun explainWait(
        endpoints: List<LlmEndpoint>,
        pacer: FreeCallPacer,
        nowMs: Long,
        tokens: Int,
        quotaOf: (LlmEndpoint) -> FreeQuota?,
        allowed: (LlmEndpoint) -> Boolean
    ): Wait? {
        var soonest: Wait? = null
        var sawFree = false
        for (ep in endpoints) {
            if (!ep.usable || !allowed(ep) || !LlmProviders.freeFamily(ep.provider, ep.model)) continue
            sawFree = true
            val quota = quotaOf(ep) ?: continue
            val hold = pacer.hold(ep.provider, LlmProviders.paceModels(ep), quota, nowMs, tokens) ?: return null
            val wait = Wait("${LlmProviders.shortLabel(ep.provider)} ${hold.reason}", hold.resumeAtMs)
            if (soonest == null || wait.resumeAtMs < soonest.resumeAtMs) soonest = wait
        }
        if (!sawFree) return Wait("no free provider with a key", nowMs)
        return soonest
    }

    /** Shown only when the wait is a real cap or backoff, not the steady inter-call gap. */
    fun paceStatus(wait: Wait?, nowMs: Long): String {
        if (wait == null) return ""
        val gap = wait.resumeAtMs - nowMs
        if (wait.reason.endsWith("pace") && gap <= 2_500L) return ""
        val whenEt = com.dirk.kalshiodds.domain.EtTime.clock(wait.resumeAtMs)
        return "waiting for the free-tier pace · ${wait.reason} · resumes $whenEt"
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
    private val backoffStrikes = HashMap<String, Int>()
    /** Extra quiet time from a low x-ratelimit-remaining header. Cleared when the header is healthy again. */
    private val adaptiveUntil = HashMap<String, Long>()
    private var cursor = 0

    fun allow(provider: String, models: List<String>, quota: FreeQuota, now: Long, tokens: Int): String? =
        pick(provider, models, quota, now, tokens, advance = true)

    fun ready(provider: String, models: List<String>, quota: FreeQuota, now: Long, tokens: Int): Boolean =
        pick(provider, models, quota, now, tokens, advance = false) != null

    data class Hold(val reason: String, val resumeAtMs: Long)

    /** Null when [ready] is true. Otherwise the soonest this provider can be called, and why. */
    fun hold(provider: String, models: List<String>, quota: FreeQuota, now: Long, tokens: Int): Hold? {
        if (ready(provider, models, quota, now, tokens)) return null
        if (models.isEmpty() || tokens <= 0 || quota.requestsPerMinute <= 0 || quota.requestsPerDay <= 0) {
            return Hold("quota", now + 60_000L)
        }
        prune(now)
        val globalPace = paceGap(quota, models.size)
        val lastAny = hits.lastOrNull { it.provider == provider }?.at
        val globalAt = if (lastAny != null && globalPace > 0L && now - lastAny < globalPace) lastAny + globalPace else now
        var modelAt = Long.MAX_VALUE
        var why = "pace"
        for (model in models) {
            val block = modelHold(provider, model, quota, now, tokens)
            if (block.resumeAtMs < modelAt) {
                modelAt = block.resumeAtMs
                why = block.reason
            }
        }
        var at = maxOf(globalAt, modelAt)
        if (globalAt > modelAt) why = "pace"
        if (at == Long.MAX_VALUE || at <= now) at = now + paceGap(quota, models.size)
        return Hold(why, at)
    }

    fun record(provider: String, model: String, now: Long, tokens: Int) {
        hits.addLast(Hit(now, provider, model, tokens.coerceAtLeast(0)))
        backoffStrikes.remove("$provider|$model")
        prune(now)
    }

    /**
     * A real HTTP 429. Mistral starts at the 60s pace (or Retry-After, whichever is longer) and doubles each consecutive 429, capped at 15 min.
     * and a successful [record] resets the count. Other providers keep a single Retry-After wait.
     */
    fun note429(provider: String, model: String, retryAfterMs: Long, now: Long) {
        val key = "$provider|$model"
        val mistral = provider == LlmProviders.MISTRAL
        val strikes = if (mistral) {
            val n = (backoffStrikes[key] ?: 0) + 1
            backoffStrikes[key] = n
            n
        } else 1
        val base = if (mistral) maxOf(retryAfterMs, LlmProviders.MISTRAL_PACE_MS).coerceAtLeast(1_000L)
        else retryAfterMs.coerceAtLeast(1_000L)
        val factor = if (mistral) 1L shl (strikes - 1).coerceAtMost(4) else 1L
        backoffUntil[key] = now + (base * factor).coerceAtMost(15 * 60_000L)
    }

    /**
     * Stretch the 30s floor when the remaining-request or remaining-token header is getting low.
     * A healthy header keeps the provider's own pace. Zero remaining is a hard minute of quiet.
     */
    fun noteRemaining(provider: String, model: String, remainingRequests: Int?, remainingTokens: Int?, now: Long) {
        if ((remainingRequests != null && remainingRequests <= 0) || (remainingTokens != null && remainingTokens <= 0)) {
            note429(provider, model, 60_000L, now)
            return
        }
        val key = "$provider|$model"
        val stretch = stretchMs(remainingRequests, remainingTokens)
        if (stretch > 0L) adaptiveUntil[key] = now + stretch else adaptiveUntil.remove(key)
    }

    fun stretchMs(remainingRequests: Int?, remainingTokens: Int?): Long {
        var wait = 0L
        if (remainingRequests != null) {
            wait = maxOf(wait, when {
                remainingRequests >= 40 -> 0L
                remainingRequests >= 15 -> 45_000L
                else -> 90_000L
            })
        }
        if (remainingTokens != null) {
            wait = maxOf(wait, when {
                remainingTokens >= 50_000 -> 0L
                remainingTokens >= 10_000 -> 45_000L
                else -> 90_000L
            })
        }
        return wait
    }

    fun hits(): List<Pair<Long, String>> = hits.map { it.at to it.model }

    private fun pick(provider: String, models: List<String>, quota: FreeQuota, now: Long, tokens: Int, advance: Boolean): String? {
        if (models.isEmpty() || tokens <= 0 || quota.requestsPerMinute <= 0 || quota.requestsPerDay <= 0) return null
        prune(now)
        val n = models.size
        val globalPace = paceGap(quota, n)
        val lastAny = hits.lastOrNull { it.provider == provider }?.at
        if (lastAny != null && globalPace > 0L && now - lastAny < globalPace) return null
        for (i in 0 until n) {
            val model = models[(cursor + i) % n]
            if (now < (backoffUntil["$provider|$model"] ?: Long.MIN_VALUE)) continue
            if (now < (adaptiveUntil["$provider|$model"] ?: Long.MIN_VALUE)) continue
            if (!within(provider, model, quota, now, tokens)) continue
            if (advance) cursor = (cursor + i + 1) % n
            return model
        }
        return null
    }

    private fun modelHold(provider: String, model: String, quota: FreeQuota, now: Long, tokens: Int): Hold {
        val key = "$provider|$model"
        var at = now
        var why = "pace"
        fun later(t: Long, reason: String) {
            if (t > at) { at = t; why = reason }
        }
        later(backoffUntil[key] ?: Long.MIN_VALUE, "backoff")
        later(adaptiveUntil[key] ?: Long.MIN_VALUE, "backoff")
        var minuteCalls = 0
        var dayCalls = 0
        var minuteTokens = 0L
        var dayTokens = 0L
        var last = Long.MIN_VALUE
        var oldestMinute = Long.MAX_VALUE
        var oldestDay = Long.MAX_VALUE
        for (hit in hits) {
            if (hit.provider != provider || hit.model != model) continue
            val age = now - hit.at
            if (age in 0 until DAY_MS) {
                dayCalls++
                dayTokens += hit.tokens
                if (hit.at < oldestDay) oldestDay = hit.at
                if (age < MINUTE_MS) {
                    minuteCalls++
                    minuteTokens += hit.tokens
                    if (hit.at < oldestMinute) oldestMinute = hit.at
                }
            }
            if (hit.at > last) last = hit.at
        }
        val pace = paceGap(quota, 1)
        if (last != Long.MIN_VALUE && pace > 0L && now - last < pace) {
            later(last + pace, "pace")
        }
        if (minuteCalls >= quota.requestsPerMinute && oldestMinute != Long.MAX_VALUE) later(oldestMinute + MINUTE_MS, "minute cap")
        if (dayCalls >= quota.requestsPerDay && oldestDay != Long.MAX_VALUE) later(oldestDay + DAY_MS, "day cap")
        if (minuteTokens + tokens > quota.tokensPerMinute && oldestMinute != Long.MAX_VALUE) later(oldestMinute + MINUTE_MS, "token cap")
        if (dayTokens + tokens > quota.tokensPerDay && oldestDay != Long.MAX_VALUE) later(oldestDay + DAY_MS, "token cap")
        if (at <= now) at = now + paceGap(quota, 1)
        return Hold(why, at)
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
        val pace = paceGap(quota, 1)
        if (last != Long.MIN_VALUE && pace > 0L && now - last < pace) return false
        return true
    }

    /** Calls recorded for this provider since the start of the rolling day window. */
    fun dayCalls(provider: String, now: Long): Int {
        prune(now)
        return hits.count { it.provider == provider && now - it.at in 0 until DAY_MS }
    }

    private fun paceGap(quota: FreeQuota, modelCount: Int): Long {
        val raw = if (modelCount >= 2) quota.perModelPaceMs / 2 else quota.perModelPaceMs
        return raw.coerceAtLeast(LlmProviders.AUTO_PACE_MS)
    }

    private fun prune(now: Long) {
        while (hits.isNotEmpty() && now - hits.first().at >= DAY_MS) hits.removeFirst()
    }

    private companion object {
        const val MINUTE_MS = 60_000L
        const val DAY_MS = 86_400_000L
    }
}
