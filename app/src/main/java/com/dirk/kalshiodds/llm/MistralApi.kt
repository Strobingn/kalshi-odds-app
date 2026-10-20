package com.dirk.kalshiodds.llm

import org.json.JSONArray
import org.json.JSONObject
import java.io.InterruptedIOException

/**
 * OpenAI-compatible Mistral chat on the free plan.
 * Default [LlmProviders.MISTRAL_DEFAULT], then [LlmProviders.MISTRAL_NEMO], then [LlmProviders.MISTRAL_3B].
 * A 403, or a 429 whose `x-ratelimit-limit-req-minute` is 0, means the model is not on the plan:
 * switch models immediately. A real 429 backs off. A 45s timeout skips the call.
 * Attempts inside one exchange are sequential, and the shared flight lock keeps calls from overlapping.
 */
object MistralApi {
    const val TIMEOUT_MS = 45_000L

    data class Raw(val code: Int, val body: String, val headers: Map<String, String> = emptyMap())

    fun body(model: String, messages: List<Pair<String, String>>, maxTokens: Int): String =
        JSONObject().put("model", model).put("max_tokens", maxTokens.coerceAtLeast(1)).put("temperature", 0.2)
            .put("messages", JSONArray().apply {
                messages.forEach { (role, content) -> put(JSONObject().put("role", role).put("content", content)) }
            })
            .toString()

    fun headerInt(headers: Map<String, String>, name: String): Int? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.trim()?.toIntOrNull()

    /** 403, or 429 with a per-minute request limit of 0. A 429 that still has quota is a real rate limit. */
    fun notOnPlan(code: Int, headers: Map<String, String>): Boolean {
        if (code == 403) return true
        if (code != 429) return false
        return headerInt(headers, "x-ratelimit-limit-req-minute") == 0
    }

    /**
     * One model, then the next free-plan model when the response says it is not on the plan.
     * The posts run one after another. A timeout does not switch models.
     */
    fun exchange(
        model: String,
        messages: List<Pair<String, String>>,
        maxTokens: Int,
        post: (model: String, body: String) -> Raw,
        onSwitch: (from: String, to: String) -> Unit = { _, _ -> }
    ): LlmClient.Reply {
        var current = model.trim().ifBlank { LlmProviders.MISTRAL_DEFAULT }
        val tried = LinkedHashSet<String>()
        while (tried.size <= LlmProviders.MISTRAL_CHAIN.size) {
            if (!tried.add(current)) break
            val raw = try {
                post(current, body(current, messages, maxTokens))
            } catch (e: InterruptedIOException) {
                throw LlmSkipped("timeout")
            }
            if (notOnPlan(raw.code, raw.headers)) {
                val next = LlmProviders.mistralFallback(current)
                if (next != null && next !in tried) {
                    onSwitch(current, next)
                    current = next
                    continue
                }
                throw LlmModelUnavailable(LlmProviders.MISTRAL, "model not on plan")
            }
            if (GeminiApi.billing(raw.code, raw.body)) throw LlmBillingRequired(LlmProviders.MISTRAL, raw.body.take(180))
            if (raw.code == 429) {
                val retry = raw.headers.entries.firstOrNull { it.key.equals("retry-after", true) }?.value
                throw LlmRateLimited(LlmClient.retryAfterMs(retry), "$current rate limited")
            }
            if (raw.code !in 200..299) {
                throw IllegalStateException("$current failed: HTTP ${raw.code}: ${raw.body.take(200)}")
            }
            return parse(current, raw.body, raw.headers)
        }
        throw LlmModelUnavailable(LlmProviders.MISTRAL, "model not on plan")
    }

    fun parse(model: String, body: String, headers: Map<String, String>): LlmClient.Reply {
        val js = JSONObject(body)
        val used = js.optString("model").ifBlank { model }
        if (!LlmClient.sameModel(model, used)) {
            throw IllegalStateException("model mismatch: asked for $model, server answered with $used — reply discarded")
        }
        val (prompt, completion) = LlmClient.usageTokens(js)
        val text = js.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
        val (remReq, remTok) = LlmClient.remaining(headers)
        return LlmClient.Reply(text, model, used, prompt, completion, remReq, remTok)
    }
}
