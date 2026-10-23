package com.dirk.kalshiodds.llm

/** Words an LLM wrote, and which one. */
data class Written(val text: String, val source: String, val onPhone: Boolean)

/**
 * Writes the Explain and daily-review text. The on-phone model ([LocalLlm]) goes first when one is installed and
 * "Use the on-phone model" is on; otherwise, or when it fails, the cloud keys (Groq / Mistral) answer. When neither
 * can, the failure says what to set up.
 */
class AiWriter(
    private val local: LocalLlm,
    /** (role, text) messages, system first → ("provider model", text), or null when no provider answered. */
    private val cloud: (List<Pair<String, String>>) -> Pair<String, String>?,
    private val cloudError: () -> String,
    private val hasCloudKey: () -> Boolean
) {
    suspend fun write(system: String, user: String, maxTokens: Int, onText: (String) -> Unit = {}): Result<Written> {
        val localFirst = local.isInstalled && local.state.value.preferLocal
        var localFailure: String? = null
        if (localFirst) {
            val r = local.generate(system, user, maxTokens = maxTokens, onText = onText)
            r.getOrNull()?.let { if (it.text.isNotBlank()) return Result.success(Written(it.text, "On-phone · ${it.model}", true)) }
            localFailure = r.exceptionOrNull()?.message ?: "the on-phone model gave an empty answer"
        }
        if (hasCloudKey()) {
            val r = runCatching { cloud(listOf("system" to system, "user" to user)) }.getOrNull()
            if (r != null && r.second.isNotBlank()) {
                onText(r.second)
                val note = localFailure?.let { " (on-phone model: $it)" }.orEmpty()
                return Result.success(Written(r.second, r.first + note, false))
            }
            if (!localFirst && local.isInstalled) return fromLocal(system, user, maxTokens, onText, "cloud: ${cloudError().ifBlank { "no answer" }}")
            return Result.failure(IllegalStateException(listOfNotNull(localFailure?.let { "on-phone model: $it" }, "cloud: ${cloudError().ifBlank { "no answer" }}").joinToString("; ")))
        }
        if (!localFirst && local.isInstalled) return fromLocal(system, user, maxTokens, onText, null)
        return Result.failure(
            IllegalStateException(localFailure?.let { "on-phone model: $it; no Groq or Mistral key saved" }
                ?: "install an on-phone model or add a Groq or Mistral key (AI → Keys)")
        )
    }

    private suspend fun fromLocal(system: String, user: String, maxTokens: Int, onText: (String) -> Unit, cloudNote: String?): Result<Written> {
        val r = local.generate(system, user, maxTokens = maxTokens, onText = onText)
        r.getOrNull()?.let { if (it.text.isNotBlank()) return Result.success(Written(it.text, "On-phone · ${it.model}" + (cloudNote?.let { n -> " ($n)" } ?: ""), true)) }
        return Result.failure(IllegalStateException(listOfNotNull(cloudNote, "on-phone model: ${r.exceptionOrNull()?.message ?: "empty answer"}").joinToString("; ")))
    }
}
