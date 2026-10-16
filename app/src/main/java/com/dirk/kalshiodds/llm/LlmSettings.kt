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
        get() = prefs?.getString("model", DEFAULT_MODEL).orEmpty().ifBlank { DEFAULT_MODEL }
        set(v) { prefs?.edit()?.putString("model", v.trim())?.apply() }
    val hasKey: Boolean get() = apiKey.isNotBlank()

    companion object {
        const val DEFAULT_BASE = "https://api.x.ai/v1"
        const val DEFAULT_MODEL = "grok-4"
    }
}
