package com.dirk.kalshiodds.llm

import com.dirk.kalshiodds.signal.scalper.HttpLlmChat
import com.dirk.kalshiodds.signal.scalper.LlmAttempt
import com.dirk.kalshiodds.signal.scalper.LlmChat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The AIs' chat on Gemma (on the phone). [ask] forces the one tool's JSON with constrained decoding against the tool's
 * own parameter schema, so the Scalping AI and the Paper AI get the same argument JSON a cloud tool call returned.
 * Blocking like [HttpLlmChat]: call off the main thread.
 */
class GemmaLlmChat(
    private val gemma: Gemma,
    private val onError: (String) -> Unit = {},
    private val onCall: (LlmAttempt) -> Unit = {},
    private val maxTokens: Int = 1536
) : LlmChat {
    @Volatile
    var lastError: String = ""
        private set

    override fun lastFailure(): String = lastError

    val isReady: Boolean get() = gemma.isReady

    override fun ask(system: String, user: String, tools: JsonArray): String? {
        val fn = runCatching { tools[0].jsonObject["function"]!!.jsonObject }.getOrNull()
        val name = fn?.get("name")?.jsonPrimitive?.contentOrNull ?: "answer"
        val schema = fn?.get("parameters")?.toString() ?: "{\"type\":\"object\"}"
        val description = fn?.get("description")?.jsonPrimitive?.contentOrNull.orEmpty()
        val sys = system + "\n\nAnswer by calling the function `$name`" +
            (if (description.isNotBlank()) " ($description)" else "") +
            ": reply with ONLY its JSON arguments, matching this JSON schema exactly:\n$schema"
        val r = run(sys, user, emptyList(), schema)
        val text = r ?: return null
        val json = extractJsonObject(text)
        if (json == null) {
            fail("Gemma's answer was not a JSON object: ${text.take(120)}")
            return null
        }
        return json
    }

    /** A plain chat turn: (role, text) pairs, system first → ("Gemma 4 E2B", answer), or null ([lastError] says why). */
    fun chat(messages: List<Pair<String, String>>): Pair<String, String>? {
        val system = messages.firstOrNull { it.first == "system" }?.second.orEmpty()
        val turns = messages.filter { it.first != "system" }
        val lastUser = turns.indexOfLast { it.first == "user" }
        if (lastUser < 0) return null
        val text = run(system, turns[lastUser].second, turns.subList(0, lastUser), null, temperature = 0.4) ?: return null
        return (gemma.installed()?.title ?: "Gemma") to text
    }

    private fun run(system: String, user: String, history: List<Pair<String, String>>, schema: String?, temperature: Double = 0.5): String? {
        val model = gemma.installed()?.title ?: "Gemma"
        val t0 = System.currentTimeMillis()
        val r = runBlocking { gemma.generate(system, user, history, schema, maxOutputTokens = maxTokens, temperature = temperature) }
        val ms = System.currentTimeMillis() - t0
        return r.fold(
            onSuccess = {
                lastError = ""
                onCall(LlmAttempt(PROVIDER, model, true, ms))
                it.text
            },
            onFailure = {
                val msg = it.message ?: it.javaClass.simpleName
                onCall(LlmAttempt(PROVIDER, model, false, ms, msg))
                fail("gemma $model: $msg")
                null
            }
        )
    }

    private fun fail(msg: String) {
        lastError = msg
        onError(msg.replace("\"", "'"))
    }

    companion object {
        const val PROVIDER = "gemma"

        /** The first balanced JSON object in [text] (constrained decoding gives just that; this also strips code fences). */
        fun extractJsonObject(text: String): String? {
            val start = text.indexOf('{')
            if (start < 0) return null
            var depth = 0
            var inString = false
            var escaped = false
            for (i in start until text.length) {
                val ch = text[i]
                if (inString) {
                    when {
                        escaped -> escaped = false
                        ch == '\\' -> escaped = true
                        ch == '"' -> inString = false
                    }
                    continue
                }
                when (ch) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            val candidate = text.substring(start, i + 1)
                            return candidate.takeIf { runCatching { Json.parseToJsonElement(it) is JsonObject }.getOrDefault(false) }
                        }
                    }
                }
            }
            return null
        }
    }
}

/**
 * Gemma replaces the cloud AI: once a Gemma model is on the phone every call goes to it and the cloud keys are never
 * used. Until the download finishes, the saved cloud keys keep the AIs running so nothing goes quiet.
 */
class GemmaFirstChat(
    private val gemma: GemmaLlmChat,
    private val cloud: HttpLlmChat
) : LlmChat {
    val lastError: String get() = if (gemma.isReady) gemma.lastError else cloud.lastError
    override fun lastFailure(): String = lastError

    /** True when Gemma is downloaded or at least one cloud key is saved. */
    val hasKey: Boolean get() = gemma.isReady || cloud.hasKey

    override fun ask(system: String, user: String, tools: JsonArray): String? =
        if (gemma.isReady) gemma.ask(system, user, tools) else cloud.ask(system, user, tools)

    fun chat(messages: List<Pair<String, String>>): Pair<String, String>? =
        if (gemma.isReady) gemma.chat(messages) else cloud.chat(messages)
}
