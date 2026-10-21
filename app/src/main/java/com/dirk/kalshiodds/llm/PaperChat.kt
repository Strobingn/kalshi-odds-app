package com.dirk.kalshiodds.llm

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Fixed providers only: a key is never sent to an arbitrary user-supplied host. */
enum class PaperChatProvider(val label: String, val endpoint: String, val model: String) {
    GROQ("Groq", "https://api.groq.com/openai/v1/chat/completions", "openai/gpt-oss-20b"),
    MISTRAL("Mistral", "https://api.mistral.ai/v1/chat/completions", "mistral-small-latest")
}

/** Encrypted key material stays on this installation across normal in-place updates. */
class PaperChatKeys(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("paper_chat_keys", Context.MODE_PRIVATE)

    fun selectedProvider(): PaperChatProvider = PaperChatProvider.values().firstOrNull {
        it.name == prefs.getString("selected_provider", PaperChatProvider.GROQ.name)
    } ?: PaperChatProvider.GROQ

    fun select(provider: PaperChatProvider) {
        check(prefs.edit().putString("selected_provider", provider.name).commit()) { "Could not save provider." }
    }

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        check(create) { "Saved key is unavailable. Re-enter the API key." }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
            generateKey()
        }
    }

    fun save(provider: PaperChatProvider, apiKey: String) {
        require(apiKey.isNotBlank()) { "Enter an API key." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        check(cipher.iv.size == 12) { "Unexpected encryption nonce size." }
        val encrypted = cipher.doFinal(apiKey.trim().toByteArray(Charsets.UTF_8))
        val encoded = Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
        check(prefs.edit().putString(provider.name, encoded).commit()) { "Could not save API key." }
    }

    fun load(provider: PaperChatProvider): String? {
        val raw = prefs.getString(provider.name, null) ?: return null
        val bytes = Base64.decode(raw, Base64.NO_WRAP)
        check(bytes.size > 12) { "Saved API key cannot be read. Re-enter it." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }

    private companion object { const val ALIAS = "chatbitcoin_paper_chat_key_v1" }
}

/** Adapted from Cursor's OpenAI-compatible client; never places or edits orders. */
class PaperChatClient {
    private val http = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    data class Answer(val text: String, val provider: PaperChatProvider)

    /** Try the selected provider first; a bad key or failed request does not mask the other. */
    suspend fun askWithFallback(keys: PaperChatKeys, selected: PaperChatProvider, question: String, context: String, history: List<Pair<String, String>> = emptyList()): Answer {
        val answer = runProviderFallback(selected,
            load = { provider -> withContext(Dispatchers.IO) { keys.load(provider) } },
            request = { provider, key -> ask(provider, key, question, context, history) })
        if (answer.provider != selected) withContext(Dispatchers.IO) { runCatching { keys.select(answer.provider) } }
        return answer
    }

    suspend fun ask(provider: PaperChatProvider, apiKey: String, question: String, context: String, history: List<Pair<String, String>> = emptyList()): String = withContext(Dispatchers.IO) {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "You are a paper-trading research assistant. Supplied market history consists of timestamped on-device samples sent only with each request; you cannot see updates between requests. Respect quote age and unknown freshness. Do not claim profits or guaranteed predictions. You cannot place orders. ${PaperTuning.INSTRUCTIONS}"))
        history.takeLast(6).forEach { (role, text) ->
            if (role == "user" || role == "assistant") messages.put(JSONObject().put("role", role).put("content", text.take(1200)))
        }
        val boundedContext = context.take(20000) + if (context.length > 20000) "\n[Additional data omitted because the prompt is too long.]" else ""
        messages.put(JSONObject().put("role", "user").put("content", "Current app data (observe timestamps and freshness):\n$boundedContext\n\nQuestion: ${question.take(2000)}"))
        val json = JSONObject().put("model", provider.model).put("messages", messages).put("max_tokens", 600)
        val request = Request.Builder().url(provider.endpoint)
            .header("Authorization", "Bearer $apiKey")
            .post(json.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw PaperChatException(httpFailure(provider, response.code, response.header("Retry-After")), response.code)
            val content = JSONObject(response.body?.string().orEmpty()).getJSONArray("choices")
                .getJSONObject(0).getJSONObject("message").optString("content").trim()
            check(content.isNotEmpty()) { "${provider.label} returned an empty answer." }
            content
        }
    }
}

class PaperChatException(message: String, val httpCode: Int? = null) : Exception(message)

internal fun httpFailure(provider: PaperChatProvider, code: Int, retryAfter: String?): String {
    val reason = when (code) {
        400 -> "invalid request; check model or prompt size"
        401 -> "key rejected"
        403 -> "access denied; check account, model, or plan"
        404 -> "model or endpoint unavailable"
        413 -> "request too large"
        429 -> "rate limit or quota reached"
        in 500..599 -> "provider server error"
        else -> "request rejected"
    }
    val retry = retryAfter?.toLongOrNull()?.takeIf { it in 1..86_400 }?.let { "; retry after $it seconds" }.orEmpty()
    return "${provider.label}: HTTP $code ($reason$retry)"
}

/** Never expose provider bodies, keys, URLs, or arbitrary exception messages to the UI. */
internal fun safeFailure(provider: PaperChatProvider, error: Exception): String = when (error) {
    is PaperChatException -> error.message ?: "${provider.label}: request failed"
    is SocketTimeoutException -> "${provider.label}: connection timed out"
    is IOException -> "${provider.label}: network connection failed"
    else -> "${provider.label}: invalid or unreadable provider response"
}

internal suspend fun runProviderFallback(
    selected: PaperChatProvider,
    load: suspend (PaperChatProvider) -> String?,
    request: suspend (PaperChatProvider, String) -> String
): PaperChatClient.Answer {
    val failures = mutableListOf<String>()
    val providers = listOf(selected) + PaperChatProvider.values().filter { it != selected }
    for (provider in providers) {
        val key = try { load(provider) } catch (e: CancellationException) { throw e }
        catch (_: Exception) { failures += "${provider.label}: saved key unreadable; re-enter it"; continue }
        if (key.isNullOrBlank()) { failures += "${provider.label}: no saved key"; continue }
        try { return PaperChatClient.Answer(request(provider, key), provider) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { failures += safeFailure(provider, e) }
    }
    throw PaperChatException(failures.joinToString(" · "))
}
