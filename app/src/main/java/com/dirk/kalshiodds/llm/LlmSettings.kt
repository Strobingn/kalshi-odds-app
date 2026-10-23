package com.dirk.kalshiodds.llm

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * LLM API key for the Kalshi Scalper chat + the single PAPER "LLM" strategy. Entered by Dirk in Settings,
 * stored only in EncryptedSharedPreferences (never committed, never synced). OpenAI-compatible endpoint;
 * defaults to Groq (llama-3.1-8b-instant). xAI stays available as a paid slot. The LLM never reaches any real-order path.
 */
class LlmSettings(context: Context) {
    private val prefs: SharedPreferences? = runCatching {
        EncryptedSharedPreferences.create("scalper_llm", MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC), context.applicationContext,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }.getOrNull()

    init { migrateGroqDefault() }

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
            val id = prefs?.getString("provider", DEFAULT_PROVIDER).orEmpty().ifBlank { DEFAULT_PROVIDER }
            return if (LlmProviders.ALL.any { it.id == id }) id else DEFAULT_PROVIDER
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

    /**
     * 1.0.35: installs left on Mistral by the 1.0.29 default move to Groq once.
     * A later manual choice is kept because the flag is already set.
     */
    private fun migrateGroqDefault() {
        val p = prefs ?: return
        if (p.getBoolean(KEY_MIGRATED_GROQ_1035, false)) return
        val saved = p.getString("provider", null)
        val edit = p.edit().putBoolean(KEY_MIGRATED_GROQ_1035, true)
        val move = groqDefaultMigration(saved, p.getString("model.${LlmProviders.GROQ}", null), already = false)
        if (move.provider != null) {
            edit.putString("provider", move.provider)
            edit.putString("model.${LlmProviders.GROQ}", move.model)
        }
        edit.apply()
    }

    companion object {
        const val KEY_MIGRATED_GROQ_1035 = "migrated_groq_default_1035"
        const val DEFAULT_PROVIDER = LlmProviders.GROQ
        const val DEFAULT_BASE = "https://api.groq.com/openai/v1"
        const val DEFAULT_MODEL = LlmProviders.DEFAULT_GROQ_MODEL
        /** xAI's public API id. The "Fast" serving tier has no separate public API id. */
        const val XAI_MODEL = "grok-4.7"
        /** Blank or the old 1.0.x default "grok-4" → [XAI_MODEL]; any other explicit choice is kept. */
        fun migrateModel(saved: String): String = saved.trim().let { if (it.isEmpty() || it.equals("grok-4", true)) XAI_MODEL else it }

        /** Only a saved Mistral id moves. A missing id keeps the getter default, and any other saved id stays. */
        fun groqDefaultMigration(savedProvider: String?, groqModel: String?, already: Boolean): GroqMove {
            if (already || savedProvider != LlmProviders.MISTRAL) return GroqMove(null, null)
            val model = groqModel?.trim().orEmpty().ifBlank { DEFAULT_MODEL }
            return GroqMove(DEFAULT_PROVIDER, model)
        }
    }

    data class GroqMove(val provider: String?, val model: String?)
}
