package com.dirk.kalshiodds.ui.home

import android.content.Context

/** Home-card toggle for the 11:59 PM ET paper summary. Missing value stays on. */
object HomeSummaryPrefs {
    const val PREFS = "kalshi_scalper_home_summary"
    const val KEY_ENABLED = "notify_1159_et"

    fun isEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)

    fun write(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .commit()
    }
}
