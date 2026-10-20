package com.dirk.kalshiodds.llm

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Chat client. Blocking; call from Dispatchers.IO.
 * OpenAI-compatible providers reject a mismatched model (no silent paid-model switch).
 * Gemini uses native generateContent with x-goog-api-key, a 45s timeout, and one retired-model fallback.
 * At most one HTTP call is in flight. Automatic callers skip instead of queueing behind it.
 */
class LlmClient(private val settings: LlmSettings, private val flight: LlmFlight = LlmFlight()) {
    private val http = OkHttpClient.Builder().callTimeout(150, TimeUnit.SECONDS).readTimeout(150, TimeUnit.SECONDS).build()
    private val geminiHttp = OkHttpClient.Builder()
        .callTimeout(GeminiApi.TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(GeminiApi.TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(GeminiApi.TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    data class Reply(
        val text: String,
        val requested: String,
        val model: String,
        val promptTokens: Long = 0,
        val completionTokens: Long = 0,
        val remainingRequests: Int? = null,
        val remainingTokens: Int? = null
    )

    fun chat(messages: List<Pair<String, String>>, maxTokens: Int = 600): String = chatReply(messages, maxTokens).text

    /**
     * [endpoint] overrides the saved slot. [automatic] skips immediately when another call is in flight.
     * A manual send waits up to the Gemini timeout for that single in-flight call to finish, then runs alone.
     */
    fun chatReply(
        messages: List<Pair<String, String>>,
        maxTokens: Int = 600,
        endpoint: LlmEndpoint? = null,
        automatic: Boolean = false
    ): Reply {
        val ep = endpoint ?: LlmEndpoint(LlmProviders.XAI, settings.baseUrl, settings.model, settings.apiKey)
        require(ep.apiKey.isNotBlank()) { "needs key" }
        if (!flight.enter(automatic)) throw LlmSkipped(if (automatic) "overlap" else "busy")
        try {
            return if (LlmProviders.isGemini(ep.provider)) gemini(ep, messages, maxTokens) else openAi(ep, messages, maxTokens)
        } finally {
            flight.exit()
        }
    }

    private fun gemini(ep: LlmEndpoint, messages: List<Pair<String, String>>, maxTokens: Int): Reply =
        GeminiApi.exchange(
            base = ep.baseUrl,
            model = ep.model,
            messages = messages,
            maxTokens = maxTokens,
            post = { url, body ->
                val req = Request.Builder().url(url)
                    .header("x-goog-api-key", ep.apiKey)
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                geminiHttp.newCall(req).execute().use { response ->
                    GeminiApi.Raw(response.code, response.body?.string().orEmpty(), headerMap(response))
                }
            },
            onSwitch = { next -> settings.saveModelOnly(ep.provider, next) }
        )

    private fun openAi(ep: LlmEndpoint, messages: List<Pair<String, String>>, maxTokens: Int): Reply {
        val requested = ep.model
        val body = JSONObject().put("model", requested).put("max_tokens", maxTokens).put("temperature", 0.2)
            .put("messages", JSONArray().apply { messages.forEach { (role, content) -> put(JSONObject().put("role", role).put("content", content)) } })
        val req = Request.Builder().url(ep.baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer ${ep.apiKey}")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { response ->
            val txt = response.body?.string().orEmpty()
            val headers = headerMap(response)
            if (GeminiApi.billing(response.code, txt)) throw LlmBillingRequired(ep.provider, txt.take(180))
            if (response.code == 429) throw LlmRateLimited(retryAfterMs(headers["retry-after"]), "$requested rate limited: ${txt.take(160)}")
            if (!response.isSuccessful) throw IllegalStateException("$requested failed: HTTP ${response.code}: ${txt.take(200)}")
            val js = JSONObject(txt)
            val used = js.optString("model").ifBlank { requested }
            if (!sameModel(requested, used)) throw IllegalStateException("model mismatch: asked for $requested, server answered with $used — reply discarded")
            val (prompt, completion) = usageTokens(js)
            val (remReq, remTok) = remaining(headers)
            return Reply(js.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content"), requested, used, prompt, completion, remReq, remTok)
        }
    }

    private fun headerMap(response: okhttp3.Response): Map<String, String> {
        val out = HashMap<String, String>()
        for (i in 0 until response.headers.size) out[response.headers.name(i).lowercase()] = response.headers.value(i)
        return out
    }

    companion object {
        /** Prompt + completion tokens from an OpenAI-compatible `usage` object. Missing usage is zero, not a failure. */
        fun usageTokens(js: JSONObject): Pair<Long, Long> {
            val u = js.optJSONObject("usage") ?: return 0L to 0L
            return longField(u, "prompt_tokens", "input_tokens") to longField(u, "completion_tokens", "output_tokens")
        }

        fun remaining(headers: Map<String, String>): Pair<Int?, Int?> {
            fun num(vararg names: String): Int? = names.firstNotNullOfOrNull { headers[it]?.trim()?.toIntOrNull()?.takeIf { n -> n >= 0 } }
            return num("x-ratelimit-remaining-requests", "x-ratelimit-remaining") to
                num("x-ratelimit-remaining-tokens")
        }

        /** Retry-After seconds. Missing or unparseable headers wait a minute. */
        fun retryAfterMs(header: String?): Long {
            val seconds = header?.trim()?.toLongOrNull()
            return if (seconds == null) 60_000L else (seconds.coerceAtLeast(1) * 1000L).coerceAtMost(3_600_000L)
        }

        private fun longField(o: JSONObject, primary: String, alt: String): Long = when {
            o.has(primary) && !o.isNull(primary) -> o.optLong(primary)
            o.has(alt) && !o.isNull(alt) -> o.optLong(alt)
            else -> 0L
        }

        /**
         * Same model, or a version/snapshot the alias resolves to: requested + "." or "-" followed by a digit
         * (grok-4 → grok-4.3, grok-4-0709). A different family is a mismatch.
         */
        fun sameModel(requested: String, used: String): Boolean {
            val a = requested.trim().lowercase(); val b = used.trim().lowercase()
            if (a.isEmpty() || a == b) return a == b
            if (!b.startsWith(a)) return false
            val rest = b.substring(a.length)
            return Regex("^[.-]\\d[0-9.-]*$").matches(rest)
        }
    }
}

/** One in-flight provider call. Not reentrant, so a nested call cannot stack on the same thread. */
class LlmFlight {
    private val busy = AtomicBoolean(false)

    fun enter(automatic: Boolean): Boolean {
        if (automatic) return busy.compareAndSet(false, true)
        val deadline = System.nanoTime() + GeminiApi.TIMEOUT_MS * 1_000_000L
        while (!busy.compareAndSet(false, true)) {
            if (System.nanoTime() >= deadline) return false
            try {
                Thread.sleep(20)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return true
    }

    fun exit() {
        busy.set(false)
    }
}
