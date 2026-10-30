package com.dirk.kalshiodds.llm

import com.dirk.kalshiodds.decision.EvidenceCard
import com.dirk.kalshiodds.decision.QuantCompanion

/**
 * One research question. Local loopback first, then the free provider chain, then the built-in quant analyst.
 * Manual button taps are not blocked by the 120-second background watcher pace.
 * Paid providers are never called. Nothing is stored after the call returns.
 */
object ModelResearchAsk {
    private const val SYSTEM = "You explain a paper scalp model. No orders. No live trading. Two short paragraphs."

    fun isLoopback(url: String): Boolean {
        val u = url.trim().lowercase()
        return u.startsWith("http://127.0.0.1") || u.startsWith("http://localhost") ||
            u.startsWith("https://127.0.0.1") || u.startsWith("https://localhost")
    }

    fun candidateModels(ep: LlmEndpoint): List<String> {
        val primary = ep.model.trim().ifBlank { LlmProviders.spec(ep.provider).defaultModel }
        if (ep.provider != LlmProviders.GROQ) return listOf(primary)
        return listOf(primary, LlmProviders.DEFAULT_GROQ_MODEL, LlmProviders.GROQ_120, LlmProviders.GROQ_20).distinct()
    }

    fun ask(
        prompt: String,
        local: LlmEndpoint?,
        slots: List<LlmEndpoint>,
        nowMs: Long,
        pacer: FreeCallPacer,
        quotaOf: (LlmEndpoint) -> FreeQuota?,
        allowed: (LlmEndpoint) -> Boolean,
        ask: (LlmEndpoint, List<Pair<String, String>>) -> LlmClient.Reply,
        onBilling: (String, String) -> Unit,
        callsToday: (LlmEndpoint) -> Int = { 0 },
        onSuccess: (LlmEndpoint, LlmClient.Reply) -> Unit = { _, _ -> }
    ): String {
        val trimmed = prompt.trim()
        val raw = askRaw(trimmed, local, slots, nowMs, pacer, quotaOf, allowed, ask, onBilling, callsToday, onSuccess)
        if (!trimmed.contains(EvidenceCard.MARK)) return raw
        return EvidenceCard.phrase(trimmed, raw).text.ifBlank { EvidenceCard.templateOf(trimmed).ifBlank { raw } }
    }

    private fun askRaw(
        trimmed: String,
        local: LlmEndpoint?,
        slots: List<LlmEndpoint>,
        nowMs: Long,
        pacer: FreeCallPacer,
        quotaOf: (LlmEndpoint) -> FreeQuota?,
        allowed: (LlmEndpoint) -> Boolean,
        ask: (LlmEndpoint, List<Pair<String, String>>) -> LlmClient.Reply,
        onBilling: (String, String) -> Unit,
        callsToday: (LlmEndpoint) -> Int,
        onSuccess: (LlmEndpoint, LlmClient.Reply) -> Unit
    ): String {
        val evidence = trimmed.contains(EvidenceCard.MARK)
        val system = if (evidence) {
            "Phrase the evidence card. Use only numbers printed in the card. Do not invent reasons or numbers. gates_enter decides the trade. No orders."
        } else SYSTEM
        val cap = if (evidence) 4_000 else 1_800
        val messages = listOf("system" to system, "user" to trimmed.take(cap))
        val loop = local?.takeIf { isLoopback(it.baseUrl) && it.model.isNotBlank() }
        if (loop != null) {
            val ep = if (loop.apiKey.isBlank()) loop.copy(apiKey = "local") else loop
            try {
                val reply = ask(ep, messages)
                val text = reply.text.trim()
                if (text.isNotEmpty()) {
                    onSuccess(ep, reply)
                    return text
                }
            } catch (_: Exception) {
                // The on-device call is over. Fall through to a free provider, then built-in quant analyst.
            }
        }
        val free = slots.filter { it.usable && LlmProviders.freeFamily(it.provider, it.model) }
            .sortedBy { LlmProviders.freeFallbackRank(it.provider) }
        val useBuiltinFallback = local?.provider == LlmProviders.BUILTIN || trimmed.length > 20
        if (free.isEmpty()) {
            return if (useBuiltinFallback) QuantCompanion.explainResearch(trimmed)
            else "No on-device endpoint and no free provider key. The feature drivers are the explanation."
        }
        for (ep in free) {
            if (!allowed(ep)) continue
            val quota = quotaOf(ep)
            if (quota != null && quota.requestsPerDay > 0) {
                val used = maxOf(callsToday(ep).coerceAtLeast(0), pacer.dayCalls(ep.provider, nowMs))
                if (used >= quota.requestsPerDay) continue
            }
            for (model in candidateModels(ep)) {
                if (pacer.cooling(ep.provider, model, nowMs)) continue
                val use = ep.copy(model = model)
                try {
                    val reply = ask(use, messages)
                    val text = reply.text.trim()
                    if (text.isNotEmpty()) {
                        pacer.noteRemaining(use.provider, model, reply.remainingRequests, reply.remainingTokens, nowMs)
                        onSuccess(use, reply)
                        return text
                    }
                } catch (e: LlmRateLimited) {
                    pacer.note429(use.provider, model, e.retryAfterMs, nowMs)
                } catch (e: LlmBillingRequired) {
                    onBilling(e.provider.ifBlank { use.provider }, e.message ?: "billing")
                    break
                } catch (_: Exception) {
                    // Try the next candidate model or free provider.
                }
            }
        }
        return if (useBuiltinFallback) QuantCompanion.explainResearch(trimmed)
        else "Free providers did not answer. The feature drivers are the explanation."
    }
}
