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

    data class Reply(val text: String, val requested: String, val model: String)

    fun chat(messages: List<Pair<String, String>>, maxTokens: Int = 600): String = chatReply(messages, maxTokens).text

    fun chatReply(messages: List<Pair<String, String>>, maxTokens: Int = 600): Reply {
        require(settings.hasKey) { "needs key" }
        val requested = settings.model
        val body = JSONObject().put("model", requested).put("max_tokens", maxTokens).put("temperature", 0.2)
            .put("messages", JSONArray().apply { messages.forEach { (r, c) -> put(JSONObject().put("role", r).put("content", c)) } })
        val req = Request.Builder().url(settings.baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer ${settings.apiKey}")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(req).execute().use { r ->
            val txt = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IllegalStateException("$requested failed: HTTP ${r.code}: ${txt.take(200)}")
            val js = JSONObject(txt)
            val used = js.optString("model").ifBlank { requested }
            if (!sameModel(requested, used)) throw IllegalStateException("model mismatch: asked for $requested, server answered with $used — reply discarded")
            return Reply(js.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content"), requested, used)
        }
    }

    companion object {
        /** Same model or a dated snapshot of it (grok-4 → grok-4-0709). grok-4 → grok-4-fast / grok-3 is a mismatch. */
        fun sameModel(requested: String, used: String): Boolean {
            val a = requested.trim().lowercase(); val b = used.trim().lowercase()
            return a == b || Regex("^" + Regex.escape(a) + "-\\d{4}(-\\d{2}-\\d{2})?$").matches(b) || Regex("^" + Regex.escape(a) + "-\\d{8}$").matches(b)
        }
    }
}
