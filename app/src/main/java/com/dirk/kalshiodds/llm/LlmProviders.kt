package com.dirk.kalshiodds.llm

/**
 * OpenAI-compatible providers plus the native Gemini API.
 * The paper LLM strategy and the window watcher use whichever provider is selected.
 * Keys never reach a real-money path.
 */
data class LlmEndpoint(val provider: String, val baseUrl: String, val model: String, val apiKey: String) {
    val usable: Boolean get() = apiKey.isNotBlank() && model.isNotBlank() && baseUrl.startsWith("https://")
}

/** Free-tier ceilings the auto caller paces under. Token totals are for one model. */
data class FreeQuota(
    val requestsPerMinute: Int,
    val requestsPerDay: Int,
    val tokensPerMinute: Long,
    val tokensPerDay: Long,
    /** Steady gap between calls to one model. */
    val perModelPaceMs: Long
)

object LlmProviders {
    const val XAI = "xai"
    const val OPENAI = "openai"
    const val MISTRAL = "mistral"
    const val OPENROUTER = "openrouter"
    const val CUSTOM = "custom"
    const val GROQ = "groq"
    const val GEMINI = "gemini"
    const val OPENROUTER_FREE = "openrouter-free"

    const val DEFAULT_GROQ_MODEL = "llama-3.1-8b-instant"
    const val GROQ_120 = "openai/gpt-oss-120b"
    const val GROQ_20 = "openai/gpt-oss-20b"
    const val GEMINI_DEFAULT = "gemini-3.8-flash"
    const val GEMINI_FALLBACK = "gemini-3.5-flash-lite"
    const val OPENROUTER_FREE_MODEL = "minimax/minimax-m3.1-flash:free"
    /** Free-plan default. Live check: about 0.4s, 188 requests/min, 625K tokens/min. */
    const val MISTRAL_DEFAULT = "ministral-8b-latest"
    /** Second free-plan model. Same 188 requests/min ceiling as the default. */
    const val MISTRAL_NEMO = "open-mistral-nemo"
    /** Last free-plan model. 750 requests/min. Used only after the first two are not on the plan. */
    const val MISTRAL_3B = "ministral-3b-latest"
    val MISTRAL_CHAIN = listOf(MISTRAL_DEFAULT, MISTRAL_NEMO, MISTRAL_3B)
    /** One call per free-plan minute slot (188/min). The day cap is a hard stop, not a 30s idle gap. */
    /** Automatic free-provider calls. Same floor as [com.dirk.kalshiodds.llm.AiLiveFeed.MIN_SPACING_MS]. */
    const val MISTRAL_PACE_MS = 60_000L
    const val AUTO_PACE_MS = 60_000L
    const val FREE_KEY_HINT = "Get a free key at console.mistral.ai, console.groq.com, or aistudio.google.com."

    data class Spec(
        val id: String,
        val label: String,
        val defaultBase: String,
        val defaultModel: String,
        val free: Boolean = false,
        /** Native generateContent, not the OpenAI-compatible route. */
        val nativeGemini: Boolean = false
    )

    val ALL = listOf(
        Spec(XAI, "xAI / Grok", "https://api.x.ai/v1", "grok-4.7"),
        Spec(OPENAI, "OpenAI", "https://api.openai.com/v1", "gpt-4.1"),
        Spec(MISTRAL, "Mistral (free)", "https://api.mistral.ai/v1", MISTRAL_DEFAULT, free = true),
        Spec(OPENROUTER, "OpenRouter", "https://openrouter.ai/api/v1", "openai/gpt-4o-mini"),
        Spec(GROQ, "Groq (free)", "https://api.groq.com/openai/v1", DEFAULT_GROQ_MODEL, free = true),
        Spec(GEMINI, "Google Gemini (free)", "https://generativelanguage.googleapis.com/v1beta", GEMINI_DEFAULT, free = true, nativeGemini = true),
        Spec(OPENROUTER_FREE, "OpenRouter free models", "https://openrouter.ai/api/v1", OPENROUTER_FREE_MODEL, free = true),
        Spec(CUSTOM, "Custom", "", "")
    )

    /**
     * Automatic free fallback. Groq, then Mistral, then Gemini. OpenRouter `:free` stays selectable
     * and is last, so a paid provider is never a fallback. Saved keys are not part of this list.
     */
    val FREE_IDS = listOf(GROQ, MISTRAL, GEMINI, OPENROUTER_FREE)

    fun spec(id: String): Spec = ALL.firstOrNull { it.id == id } ?: ALL.first()

    /** Blank model/base fill from the provider defaults. Custom stays blank until Dirk types them. */
    fun resolve(id: String, apiKey: String, model: String, base: String): LlmEndpoint {
        val s = spec(id)
        val m = model.trim().ifBlank { s.defaultModel }
        val b = base.trim().ifBlank { s.defaultBase }.trimEnd('/')
        return LlmEndpoint(s.id, b, m, apiKey.trim())
    }

    /**
     * Mistral free-plan models, Groq, Gemini, an OpenRouter free preset, or any model id that ends in `:free`.
     * A paid Mistral model Dirk typed or saved (mistral-large-latest and the like) is not free, so it never
     * gets automatic calls. Blank means the free default.
     */
    fun freeFamily(id: String, model: String = ""): Boolean {
        val m = model.trim()
        if (id == MISTRAL) return m.isEmpty() || m in MISTRAL_CHAIN
        return spec(id).free || m.endsWith(":free", ignoreCase = true)
    }

    fun isGemini(id: String): Boolean = spec(id).nativeGemini

    /**
     * Groq rotates the two free gpt-oss models on one key.
     * A model Dirk typed that is not one of those two stays on its own pace.
     */
    fun paceModels(ep: LlmEndpoint): List<String> {
        if (ep.provider != GROQ) return listOf(ep.model.ifBlank { spec(ep.provider).defaultModel })
        val pair = listOf(GROQ_120, GROQ_20)
        val m = ep.model.trim()
        return if (m.isEmpty() || m in pair) pair else listOf(m)
    }

    /**
     * Provider ceilings before the optional daily budget.
     * Every automatic free provider waits at least 60 seconds between calls.
     * Mistral's day cap is 2,880. Groq's day cap is 1,000. Gemini's safety budget is 500.
     */
    fun baseQuota(id: String, model: String = ""): FreeQuota? {
        if (!freeFamily(id, model)) return null
        return when (id) {
            MISTRAL -> FreeQuota(188, 2_880, 625_000, 900_000_000, MISTRAL_PACE_MS)
            GROQ -> FreeQuota(30, 1_000, 8_000, 200_000, AUTO_PACE_MS)
            GEMINI -> FreeQuota(10, 500, 250_000, 50_000_000, AUTO_PACE_MS)
            else -> FreeQuota(20, 50, 250_000, 50_000_000, AUTO_PACE_MS)
        }
    }

    /** Optional daily auto-call budget. Groq is per model. 0 means no extra cap. Mistral's 2,880 is the hard day stop. */
    fun defaultDailyCap(id: String, model: String = ""): Int = when {
        id == GROQ -> 1_000
        id == GEMINI -> 500
        id == MISTRAL -> 2_880
        freeFamily(id, model) -> 50
        else -> 0
    }

    /** The tighter of the provider ceiling and Dirk's daily auto-call budget. */
    fun effectiveQuota(id: String, model: String, dailyCap: Int): FreeQuota? {
        val base = baseQuota(id, model) ?: return null
        val cap = if (dailyCap > 0) minOf(base.requestsPerDay, dailyCap) else base.requestsPerDay
        // Day and minute ceilings are hard stops inside the pacer. Do not stretch the gap
        // across 24h: that left the paper strategy idle while quota was still left.
        return base.copy(requestsPerDay = cap)
    }

    /** Spread [perDay] calls across 24h with one slot of margin so a simulated day stays under the cap. */
    fun spreadPaceMs(perDay: Int): Long {
        val slots = (perDay - 1).coerceAtLeast(1)
        return kotlin.math.ceil(86_400_000.0 / slots).toLong().coerceAtLeast(1L)
    }

    /** Retired Gemini models switch here once. The fallback itself has nowhere further to go. */
    fun geminiFallback(model: String): String? =
        if (model.trim() == GEMINI_FALLBACK) null else GEMINI_FALLBACK

    /**
     * Next free-plan Mistral model. A saved id that is not on the plan (mistral-large-latest and the like)
     * starts the chain at [MISTRAL_DEFAULT]. The last model has nowhere further to go.
     */
    fun mistralFallback(model: String): String? {
        val i = MISTRAL_CHAIN.indexOf(model.trim())
        if (i < 0) return MISTRAL_CHAIN.first()
        return MISTRAL_CHAIN.getOrNull(i + 1)
    }

    /** Lower rank is tried first after the provider Dirk selected. Paid ids sort last and are then dropped. */
    fun freeFallbackRank(id: String): Int = FREE_IDS.indexOf(id).let { if (it < 0) FREE_IDS.size + 1 else it }

    /** Short name for the key field. The spec label keeps the "(free)" marker. */
    fun shortLabel(id: String): String = when (id) {
        MISTRAL -> "Mistral"
        GROQ -> "Groq"
        GEMINI -> "Gemini"
        OPENROUTER, OPENROUTER_FREE -> "OpenRouter"
        OPENAI -> "OpenAI"
        XAI -> "xAI"
        CUSTOM -> "custom"
        else -> spec(id).label
    }

    fun keyPrompt(id: String): String = "Enter your ${shortLabel(id)} API key"

    /** Blank provider on an old hour row is inferred from the model id. A saved provider always wins. */
    fun chartProvider(provider: String, model: String): String {
        if (provider.isNotBlank()) return provider
        val m = model.trim().lowercase()
        return when {
            m.startsWith("gemini") -> GEMINI
            m.startsWith("openai/gpt-oss") -> GROQ
            m.endsWith(":free") -> OPENROUTER_FREE
            m.startsWith("ministral") || m.startsWith("open-mistral") || m.startsWith("mistral") -> MISTRAL
            m.startsWith("grok") -> XAI
            m.startsWith("gpt-") -> OPENAI
            m.isEmpty() -> "other"
            else -> m
        }
    }
}
