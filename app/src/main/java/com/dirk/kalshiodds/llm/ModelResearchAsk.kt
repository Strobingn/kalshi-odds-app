package com.dirk.kalshiodds.llm

/**
 * One research question. Local loopback first, then the free provider chain.
 * Paid providers are never called. Nothing is stored after the call returns.
 */
object ModelResearchAsk {
    private const val SYSTEM = "You explain a paper scalp model. No orders. No live trading. Two short paragraphs."

    fun isLoopback(url: String): Boolean {
        val u = url.trim().lowercase()
        return u.startsWith("http://127.0.0.1") || u.startsWith("http://localhost") ||
            u.startsWith("https://127.0.0.1") || u.startsWith("https://localhost")
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
        onBilling: (String, String) -> Unit
    ): String {
        val messages = listOf("system" to SYSTEM, "user" to prompt.trim().take(1_800))
        val loop = local?.takeIf { isLoopback(it.baseUrl) && it.model.isNotBlank() }
        if (loop != null) {
            val ep = if (loop.apiKey.isBlank()) loop.copy(apiKey = "local") else loop
            try {
                val text = ask(ep, messages).text.trim()
                if (text.isNotEmpty()) return text
            } catch (_: Exception) {
                // The on-device call is over. Fall through to a free provider, then stop.
            }
        }
        val free = slots.filter { it.usable && LlmProviders.freeFamily(it.provider, it.model) }
        if (free.isEmpty()) return "No on-device endpoint and no free provider key. The feature drivers are the explanation."
        val hit = AiAutoCalls.callFree(
            selected = free.first(),
            slots = free,
            pacer = pacer,
            nowMs = nowMs,
            messages = messages,
            reserveCompletion = 120,
            quotaOf = quotaOf,
            allowed = allowed,
            ask = ask,
            onBilling = onBilling
        )
        return hit?.second?.text?.trim()?.ifBlank { null }
            ?: "Free providers did not answer. The feature drivers are the explanation."
    }
}
