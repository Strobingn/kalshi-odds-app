package com.dirk.kalshiodds.llm

/**
 * 1.0.12: OpenAI-compatible providers for the AI chat and the window watcher.
 * The paper LLM strategy stays on the xAI slot. Keys never reach a real-money path.
 */
data class LlmEndpoint(val provider: String, val baseUrl: String, val model: String, val apiKey: String) {
    val usable: Boolean get() = apiKey.isNotBlank() && model.isNotBlank() && baseUrl.startsWith("https://")
}

object LlmProviders {
    const val XAI = "xai"
    const val OPENAI = "openai"
    const val MISTRAL = "mistral"
    const val OPENROUTER = "openrouter"
    const val CUSTOM = "custom"

    data class Spec(val id: String, val label: String, val defaultBase: String, val defaultModel: String)

    val ALL = listOf(
        Spec(XAI, "xAI / Grok", "https://api.x.ai/v1", "grok-4.7"),
        Spec(OPENAI, "OpenAI", "https://api.openai.com/v1", "gpt-4.1"),
        Spec(MISTRAL, "Mistral", "https://api.mistral.ai/v1", "mistral-large-latest"),
        Spec(OPENROUTER, "OpenRouter", "https://openrouter.ai/api/v1", "openai/gpt-4o-mini"),
        Spec(CUSTOM, "Custom", "", "")
    )

    fun spec(id: String): Spec = ALL.firstOrNull { it.id == id } ?: ALL.first()

    /** Blank model/base fill from the provider defaults. Custom stays blank until Dirk types them. */
    fun resolve(id: String, apiKey: String, model: String, base: String): LlmEndpoint {
        val s = spec(id)
        val m = model.trim().ifBlank { s.defaultModel }
        val b = base.trim().ifBlank { s.defaultBase }.trimEnd('/')
        return LlmEndpoint(s.id, b, m, apiKey.trim())
    }
}
