package com.dirk.kalshiodds.llm

import org.json.JSONArray
import org.json.JSONObject
import java.io.InterruptedIOException

/**
 * Native Gemini generateContent. Not the OpenAI-compatible route.
 * A 45s timeout skips the call. Any HTTP 404 or a NOT_FOUND / "not found" body
 * switches once to [LlmProviders.GEMINI_FALLBACK]. Calls must not overlap.
 */
object GeminiApi {
    const val TIMEOUT_MS = 45_000L

    fun url(base: String, model: String): String =
        base.trimEnd('/').ifBlank { "https://generativelanguage.googleapis.com/v1beta" } +
            "/models/" + model.trim() + ":generateContent"

    fun body(messages: List<Pair<String, String>>, maxTokens: Int): String {
        val sys = messages.filter { it.first == "system" }.joinToString("\n") { it.second }.trim()
        val turns = messages.filter { it.first != "system" }
        val contents = JSONArray()
        val source = if (turns.isEmpty()) listOf("user" to sys.ifBlank { "Hello" }) else turns
        for ((role, text) in source) {
            val geminiRole = if (role == "assistant" || role == "model") "model" else "user"
            contents.put(JSONObject()
                .put("role", geminiRole)
                .put("parts", JSONArray().put(JSONObject().put("text", text))))
        }
        val root = JSONObject().put("contents", contents)
            .put("generationConfig", JSONObject().put("maxOutputTokens", maxTokens.coerceAtLeast(1)).put("temperature", 0.2))
        if (sys.isNotBlank() && turns.isNotEmpty()) {
            root.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sys))))
        }
        return root.toString()
    }

    /**
     * Any 404, or an error response with a NOT_FOUND status or a "not found" / "no longer available" body.
     * A 2xx reply is never retired, even when the model's answer text says "not found".
     */
    fun retired(code: Int, body: String): Boolean {
        if (code == 404) return true
        if (code in 200..299) return false
        val b = body.lowercase()
        return "not_found" in b || "not found" in b || "no longer available" in b
    }

    /** Billing or an explicit paid tier. A 429 rate limit is not billing. */
    fun billing(code: Int, body: String): Boolean {
        if (code == 402) return true
        val b = body.lowercase()
        return listOf(
            "billing",
            "payment required",
            "paid tier",
            "enable billing",
            "insufficient credit",
            "requires payment"
        ).any { it in b }
    }

    data class Raw(val code: Int, val body: String, val headers: Map<String, String> = emptyMap())

    /**
     * One model attempt, then at most one fallback attempt when the first model is retired.
     * The two posts are sequential. A timeout does not switch models.
     */
    fun exchange(
        base: String,
        model: String,
        messages: List<Pair<String, String>>,
        maxTokens: Int,
        post: (url: String, body: String) -> Raw,
        onSwitch: (String) -> Unit = {}
    ): LlmClient.Reply {
        var current = model.trim().ifBlank { LlmProviders.GEMINI_DEFAULT }
        var switched = false
        while (true) {
            val raw = try {
                post(url(base, current), body(messages, maxTokens))
            } catch (e: InterruptedIOException) {
                throw LlmSkipped("timeout")
            }
            if (retired(raw.code, raw.body)) {
                val next = LlmProviders.geminiFallback(current)
                if (!switched && next != null) {
                    switched = true
                    onSwitch(next)
                    current = next
                    continue
                }
            }
            if (billing(raw.code, raw.body)) throw LlmBillingRequired(LlmProviders.GEMINI, raw.body.take(180))
            if (raw.code == 429) {
                throw LlmRateLimited(LlmClient.retryAfterMs(raw.headers["retry-after"]), "$current rate limited")
            }
            if (raw.code !in 200..299) {
                throw IllegalStateException("$current failed: HTTP ${raw.code}: ${raw.body.take(200)}")
            }
            return parse(current, raw.body, raw.headers)
        }
    }

    fun parse(model: String, body: String, headers: Map<String, String>): LlmClient.Reply {
        val js = JSONObject(body)
        val parts = js.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
        val text = buildString {
            if (parts != null) for (i in 0 until parts.length()) append(parts.optJSONObject(i)?.optString("text").orEmpty())
        }
        val usage = js.optJSONObject("usageMetadata")
        val prompt = usage?.optLong("promptTokenCount") ?: 0L
        val completion = usage?.optLong("candidatesTokenCount") ?: 0L
        val (remReq, remTok) = LlmClient.remaining(headers)
        return LlmClient.Reply(text, model, model, prompt, completion, remReq, remTok)
    }
}

/** Automatic caller found a request already in flight, or the Gemini timeout elapsed. Do not retry. */
class LlmSkipped(reason: String) : Exception(reason)

/** Provider asked for billing or reported a paid tier. Automatic calls for that provider stop. */
class LlmBillingRequired(val provider: String, message: String) : IllegalStateException(message)

/** HTTP 429. [retryAfterMs] is how long this model should stay quiet. */
class LlmRateLimited(val retryAfterMs: Long, message: String) : IllegalStateException(message)

/**
 * The model is not on this key's plan (Mistral 403, or 429 with a per-minute limit of 0).
 * Switch models or providers. Do not back off as if the plan were merely busy.
 */
class LlmModelUnavailable(val provider: String, message: String) : IllegalStateException(message)
