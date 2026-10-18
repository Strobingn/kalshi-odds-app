package com.dirk.kalshiodds.ui

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Chat client for Bitcoin scalp tuning.
 *
 * The public xAI model id is `grok-4.7`. Grok 4.7 Fast is that same model on
 * faster infrastructure and is not a separate public API id, so the app sends
 * `grok-4.7` with reasoning effort `low` — the fastest public setting — and
 * labels it Grok 4.7 Fast. The key is never written into a log or an error string.
 */
object GrokChat {
    const val MODEL = "grok-4.7"
    const val DISPLAY_NAME = "Grok 4.7 Fast"
    const val REASONING_EFFORT = "low"
    const val URL = "https://api.x.ai/v1/chat/completions"

    const val SYSTEM = """
        You tune paper Bitcoin 15-minute scalps in Kalshi Trader. You do not place Kalshi orders.
        The model predicts the YES contract mid, not a dollar price. Positive means UP (buy YES). Negative means DOWN (buy NO). There is no side bias. A missing forecast is NO CALL.
        Bitcoin entries are only when that side is offered under 50¢ and at least 90 seconds remain. Sell when the forecast for the held side turns down, when the bid is 2¢ under the fill, or when under 60 seconds remain. Do not hold to the end of the 15-minute window.
        You may only tighten settings: lower maxAskCents, fewer contracts, a smaller stopLossPercent, a smaller trailCents, or disable v2- strategy codes that are losing.
        Never raise size, never widen the ask cap, and never turn a strategy back on.
        When you want a change, end with one JSON object and nothing after it:
        {"maxAskCents":40,"contracts":8,"stopLossPercent":4,"trailCents":2,"disable":["v2-example"]}
        Omit any field you are not changing. If nothing should change, say so and do not emit JSON.
    """

    data class Turn(val role: String, val text: String)

    data class Reply(val text: String, val patch: JSONObject?)

    fun parsePatch(text: String): JSONObject? {
        val start = text.lastIndexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val obj = runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull() ?: return null
        val allowed = setOf("maxAskCents", "contracts", "stopLossPercent", "trailCents", "disable")
        if (obj.keys().asSequence().none { it in allowed }) return null
        return obj
    }

    fun requestBody(history: List<Turn>): String {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", SYSTEM.trim()))
        history.takeLast(12).forEach { turn ->
            val role = if (turn.role == "assistant") "assistant" else "user"
            messages.put(JSONObject().put("role", role).put("content", turn.text))
        }
        return JSONObject()
            .put("model", MODEL)
            .put("reasoning_effort", REASONING_EFFORT)
            .put("temperature", 0.2)
            .put("messages", messages)
            .toString()
    }

    fun complete(apiKey: String, history: List<Turn>, client: OkHttpClient = defaultClient()): Reply {
        val key = apiKey.trim()
        require(key.isNotEmpty()) { "Add an xAI API key in this tab. It stays on the phone." }
        val body = requestBody(history).toRequestBody("application/json".toMediaType())
        val call = Request.Builder()
            .url(URL)
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .post(body)
            .build()
        client.newCall(call).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching { JSONObject(raw).optJSONObject("error")?.optString("message") }.getOrNull()
                    .orEmpty()
                    .ifBlank { "Chat request failed (${response.code})" }
                error(message.replace(key, "••••"))
            }
            val text = JSONObject(raw)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
            return Reply(text, parsePatch(text))
        }
    }

    private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
}
