package com.dirk.kalshiodds.llm

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Minimal OpenAI-compatible chat client. Blocking; call from Dispatchers.IO. Returns text or throws. */
class LlmClient(private val settings: LlmSettings) {
    private val http = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build()

    fun chat(messages: List<Pair<String, String>>, maxTokens: Int = 600): String {
        require(settings.hasKey) { "needs key" }
        val body = JSONObject().put("model", settings.model).put("max_tokens", maxTokens).put("temperature", 0.2)
            .put("messages", JSONArray().apply { messages.forEach { (r, c) -> put(JSONObject().put("role", r).put("content", c)) } })
        val req = Request.Builder().url(settings.baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer ${settings.apiKey}")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(req).execute().use { r ->
            val txt = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IllegalStateException("LLM HTTP ${r.code}: ${txt.take(200)}")
            return JSONObject(txt).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
        }
    }
}
