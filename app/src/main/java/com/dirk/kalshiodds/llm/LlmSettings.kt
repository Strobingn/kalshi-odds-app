package com.dirk.kalshiodds.llm

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * LLM API key for the Kalshi Scalper chat + the single PAPER "LLM" strategy. Entered by Dirk in Settings,
 * stored only in EncryptedSharedPreferences (never committed, never synced). OpenAI-compatible endpoint;
 * defaults to xAI. The LLM never reaches any real-order path.
 */
class LlmSettings(context: Context) {
    private val prefs: SharedPreferences? = runCatching {
        EncryptedSharedPreferences.create("scalper_llm", MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC), context.applicationContext,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }.getOrNull()

    var apiKey: String
        get() = prefs?.getString("key", "").orEmpty()
        set(v) { prefs?.edit()?.putString("key", v.trim())?.apply() }
    var baseUrl: String
        get() = prefs?.getString("base", DEFAULT_BASE).orEmpty().ifBlank { DEFAULT_BASE }
        set(v) { prefs?.edit()?.putString("base", v.trim())?.apply() }
    var model: String
        get() = migrateModel(prefs?.getString("model", DEFAULT_MODEL).orEmpty()).also { m ->
            if (prefs != null && prefs.getString("model", null) != m) prefs.edit().putString("model", m).apply() }
        set(v) { prefs?.edit()?.putString("model", v.trim())?.apply() }
    val hasKey: Boolean get() = apiKey.isNotBlank()

    /**
     * 1.0.12: chat + window watcher. The paper strategy keeps using [apiKey] / [model] / [baseUrl] (the xAI slot).
     * An upgrade must not clear this id or any `key.<provider>` slot. [migrateModel] rewrites only the legacy xAI model name.
     */
    var providerId: String
        get() {
            val id = prefs?.getString("provider", LlmProviders.XAI).orEmpty().ifBlank { LlmProviders.XAI }
            return if (LlmProviders.ALL.any { it.id == id }) id else LlmProviders.XAI
        }
        set(v) { prefs?.edit()?.putString("provider", v)?.apply() }

    fun readSlot(id: String): LlmEndpoint {
        if (id == LlmProviders.XAI) return LlmProviders.resolve(id, apiKey, model, baseUrl)
        val k = prefs?.getString("key.$id", "").orEmpty()
        val m = prefs?.getString("model.$id", "").orEmpty()
        val b = prefs?.getString("base.$id", "").orEmpty()
        return LlmProviders.resolve(id, k, m, b)
    }

    fun chatEndpoint(): LlmEndpoint = readSlot(providerId)

    val chatReady: Boolean get() = chatEndpoint().usable

    /** Switching the provider here is what the next chat or watcher call reads. xAI writes stay on the legacy key fields. */
    fun saveSlot(id: String, key: String, model: String, base: String) {
        val spec = LlmProviders.spec(id)
        providerId = spec.id
        if (spec.id == LlmProviders.XAI) {
            apiKey = key
            this.model = model
            baseUrl = base.ifBlank { DEFAULT_BASE }
            return
        }
        prefs?.edit()
            ?.putString("key.${spec.id}", key.trim())
            ?.putString("model.${spec.id}", model.trim())
            ?.putString("base.${spec.id}", base.trim())
            ?.apply()
    }

    /** Gemini writes the fallback model here after a retired-model 404. The key slot is left alone. */
    fun saveModelOnly(id: String, model: String) {
        val spec = LlmProviders.spec(id)
        val next = model.trim()
        if (next.isEmpty()) return
        if (spec.id == LlmProviders.XAI) {
            this.model = next
            return
        }
        prefs?.edit()?.putString("model.${spec.id}", next)?.apply()
    }

    companion object {
        const val DEFAULT_BASE = "https://api.x.ai/v1"
        /** 1.0.8: Grok 4.7 (xAI's public API id; the "Fast" serving tier has no separate public API id). */
        const val DEFAULT_MODEL = "grok-4.7"
        /** Blank or the old 1.0.x default "grok-4" → [DEFAULT_MODEL]; any other explicit choice is kept. */
        fun migrateModel(saved: String): String = saved.trim().let { if (it.isEmpty() || it.equals("grok-4", true)) DEFAULT_MODEL else it }
    }
}
