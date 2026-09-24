package com.dirk.kalshiodds.data.local.results

import android.content.Context

/**
 * Survives DataStore being wedged during OOM. SharedPreferences.apply()
 * is fire-and-forget and does not allocate a 400-row JSON blob.
 */
class OomFlagStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isDisabled(): Boolean = runCatching { prefs.getBoolean(KEY_DISABLED, false) }.getOrElse { false }

    fun reason(): String? = runCatching { prefs.getString(KEY_REASON, null) }.getOrNull()

    fun setDisabled(reason: String) {
        runCatching {
            prefs.edit()
                .putBoolean(KEY_DISABLED, true)
                .putString(KEY_REASON, reason.take(240))
                .apply()
        }
    }

    fun clear() {
        runCatching { prefs.edit().clear().apply() }
    }

    companion object {
        const val PREFS = "diphunter_oom"
        const val KEY_DISABLED = "heavy_ml_oom_disabled"
        const val KEY_REASON = "heavy_ml_oom_reason"
    }
}
