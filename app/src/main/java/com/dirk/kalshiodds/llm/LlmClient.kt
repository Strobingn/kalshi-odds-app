package com.dirk.kalshiodds.llm

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Minimal OpenAI-compatible chat client. Blocking; call from Dispatchers.IO. Returns text or throws.
 * 1.0.3: never silently switches model — no fallback model exists, and if the server answers with a different model
 * than the one requested (other than a dated snapshot such as grok-4-0709) the reply is rejected with an error.
 */
class LlmClient(private val settings: LlmSettings) {
    private val http = OkHttpClient.Builder().callTimeout(150, TimeUnit.SECONDS).readTimeout(150, TimeUnit.SECONDS).build()

    data class Reply(val text: String, val requested: String, val model: String, val promptTokens: Long = 0, val completionTokens: Long = 0)

    fun chat(messages: List<Pair<String, String>>, maxTokens: Int = 600): String = chatReply(messages, maxTokens).text

    /** [endpoint] overrides the saved xAI slot. Chat and the window watcher pass the selected provider; the paper strategy does not. */
    fun chatReply(messages: List<Pair<String, String>>, maxTokens: Int = 600, endpoint: LlmEndpoint? = null): Reply {
        val ep = endpoint ?: LlmEndpoint(LlmProviders.XAI, settings.baseUrl, settings.model, settings.apiKey)
        require(ep.apiKey.isNotBlank()) { "needs key" }
        val requested = ep.model
        val body = JSONObject().put("model", requested).put("max_tokens", maxTokens).put("temperature", 0.2)
            .put("messages", JSONArray().apply { messages.forEach { (r, c) -> put(JSONObject().put("role", r).put("content", c)) } })
        val req = Request.Builder().url(ep.baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer ${ep.apiKey}")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(req).execute().use { r ->
            val txt = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IllegalStateException("$requested failed: HTTP ${r.code}: ${txt.take(200)}")
            val js = JSONObject(txt)
            val used = js.optString("model").ifBlank { requested }
            if (!sameModel(requested, used)) throw IllegalStateException("model mismatch: asked for $requested, server answered with $used — reply discarded")
            val (prompt, completion) = usageTokens(js)
            return Reply(js.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content"), requested, used, prompt, completion)
        }
    }

    companion object {
        /** Prompt + completion tokens from an OpenAI-compatible `usage` object. Missing usage is zero, not a failure. */
        fun usageTokens(js: JSONObject): Pair<Long, Long> {
            val u = js.optJSONObject("usage") ?: return 0L to 0L
            return longField(u, "prompt_tokens", "input_tokens") to longField(u, "completion_tokens", "output_tokens")
        }

        private fun longField(o: JSONObject, primary: String, alt: String): Long = when {
            o.has(primary) && !o.isNull(primary) -> o.optLong(primary)
            o.has(alt) && !o.isNull(alt) -> o.optLong(alt)
            else -> 0L
        }

        /**
         * 1.0.7: same model, or a version/snapshot the alias resolves to: requested + "." or "-" followed by a digit
         * (grok-4 → grok-4.3, grok-4-0709, grok-4.3-20260101). A different family/variant is a mismatch
         * (grok-4 → grok-4-fast, grok-4-mini, grok-3, grok-40).
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
