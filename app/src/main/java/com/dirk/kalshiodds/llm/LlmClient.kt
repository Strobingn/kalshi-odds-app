package com.dirk.kalshiodds.llm

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Chat client. Blocking; call from Dispatchers.IO.
 * OpenAI-compatible providers reject a mismatched model (no silent paid-model switch).
 * Gemini uses native generateContent with x-goog-api-key, a 45s timeout, and one 404 fallback.
 * Mistral uses the free-plan model chain, a 45s timeout, and the same single in-flight lock.
 * At most one HTTP call is in flight. Automatic callers skip instead of queueing behind it.
 */
class LlmClient(private val settings: LlmSettings, private val flight: LlmFlight = LlmFlight()) {
    private val http = OkHttpClient.Builder().callTimeout(150, TimeUnit.SECONDS).readTimeout(150, TimeUnit.SECONDS).build()
    private val geminiHttp = OkHttpClient.Builder()
        .callTimeout(GeminiApi.TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(GeminiApi.TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(GeminiApi.TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()
    private val mistralHttp = OkHttpClient.Builder()
        .callTimeout(MistralApi.TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(MistralApi.TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(MistralApi.TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    data class Reply(
        val text: String,
        val requested: String,
        val model: String,
        val promptTokens: Long = 0,
        val completionTokens: Long = 0,
        val remainingRequests: Int? = null,
        val remainingTokens: Int? = null,
        /** Wall time of this call. Below 0 means it was not measured. */
        val latencyMs: Long = -1
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
        val started = System.nanoTime()
        try {
            val reply = when {
                LlmProviders.isGemini(ep.provider) -> gemini(ep, messages, maxTokens)
                ep.provider == LlmProviders.MISTRAL -> mistral(ep, messages, maxTokens)
                else -> openAi(ep, messages, maxTokens)
            }
            return finish(ep, reply, started, automatic)
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
            onSwitch = { next ->
                LlmEvents.push(ModelSwitchEvent(System.currentTimeMillis(), ep.provider, ep.model, next, "404"))
                settings.saveModelOnly(ep.provider, next)
            }
        )

    private fun mistral(ep: LlmEndpoint, messages: List<Pair<String, String>>, maxTokens: Int): Reply =
        MistralApi.exchange(
            model = ep.model,
            messages = messages,
            maxTokens = maxTokens,
            post = { _, body ->
                val req = Request.Builder().url(ep.baseUrl.trimEnd('/') + "/chat/completions")
                    .header("Authorization", "Bearer ${ep.apiKey}")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                mistralHttp.newCall(req).execute().use { response ->
                    MistralApi.Raw(response.code, response.body?.string().orEmpty(), headerMap(response))
                }
            },
            onSwitch = { from, to ->
                LlmEvents.push(ModelSwitchEvent(System.currentTimeMillis(), ep.provider, from, to, "model not on plan"))
                settings.saveModelOnly(ep.provider, to)
            }
        )

    private fun finish(ep: LlmEndpoint, reply: Reply, startedNs: Long, automatic: Boolean): Reply {
        val latency = ((System.nanoTime() - startedNs) / 1_000_000L).coerceAtLeast(0L)
        val out = if (reply.latencyMs >= 0) reply else reply.copy(latencyMs = latency)
        LlmCallClock.publish(LlmCallClock.Stamp(ep.provider, out.model.ifBlank { ep.model }, out.latencyMs, automatic, System.currentTimeMillis()))
        return out
    }

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
            val lower = HashMap<String, String>(headers.size)
            headers.forEach { (k, v) -> lower[k.lowercase()] = v }
            fun num(vararg names: String): Int? = names.firstNotNullOfOrNull { lower[it]?.trim()?.toIntOrNull()?.takeIf { n -> n >= 0 } }
            return num("x-ratelimit-remaining-requests", "x-ratelimit-remaining-req-minute", "x-ratelimit-remaining") to
                num("x-ratelimit-remaining-tokens", "x-ratelimit-remaining-tokens-minute")
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

/**
 * Last successful call's latency, so [AiWatchStore.record] can attach it without every caller
 * threading the number through. Claimed once, and only when the provider and model match.
 */
object LlmCallClock {
    data class Stamp(val provider: String, val model: String, val latencyMs: Long, val automatic: Boolean, val atMs: Long)

    private val stamp = java.util.concurrent.atomic.AtomicReference<Stamp?>(null)

    fun publish(s: Stamp) { stamp.set(s) }

    fun claim(provider: String, model: String, nowMs: Long): Stamp? {
        val s = stamp.get() ?: return null
        if (s.provider != provider) return null
        if (nowMs - s.atMs > 15_000L) return null
        val same = s.model == model || LlmClient.sameModel(s.model, model) || LlmClient.sameModel(model, s.model)
        if (!same) return null
        return if (stamp.compareAndSet(s, null)) s else null
    }
}

/** Model switches waiting to be folded into the usage file. */
object LlmEvents {
    private val lock = Any()
    private val pending = ArrayDeque<ModelSwitchEvent>()

    fun push(e: ModelSwitchEvent) = synchronized(lock) {
        pending.addLast(e)
        while (pending.size > 50) pending.removeFirst()
    }

    fun drain(): List<ModelSwitchEvent> = synchronized(lock) {
        if (pending.isEmpty()) return emptyList()
        val out = pending.toList()
        pending.clear()
        out
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
