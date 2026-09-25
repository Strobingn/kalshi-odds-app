package com.dirk.kalshiodds.signal.trade

import android.content.Context

/**
 * Last Live Approve / Test-connection error, copyable from Settings.
 * Never stores PEM / Key ID material.
 */
class LastOrderErrorStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Volatile
    private var cached: String? = prefs.getString(KEY_MSG, null)

    @Volatile
    private var cachedAtMs: Long = prefs.getLong(KEY_AT, 0L)

    fun record(message: String, atMs: Long = System.currentTimeMillis()) {
        val clean = redact(message).trim().take(MAX)
        if (clean.isEmpty()) return
        cached = clean
        cachedAtMs = atMs
        prefs.edit().putString(KEY_MSG, clean).putLong(KEY_AT, atMs).apply()
    }

    fun snapshot(): Pair<String, Long>? {
        val msg = cached ?: return null
        if (msg.isBlank()) return null
        return msg to cachedAtMs
    }

    fun clear() {
        cached = null
        cachedAtMs = 0L
        prefs.edit().remove(KEY_MSG).remove(KEY_AT).apply()
    }

    companion object {
        private const val PREFS = "diphunter_last_order_error"
        private const val KEY_MSG = "message"
        private const val KEY_AT = "at_ms"
        private const val MAX = 4_000

        fun redact(raw: String): String {
            var s = raw
            s = s.replace(Regex("(?is)-----BEGIN[^-]*PRIVATE[^-]*-----.*?-----END[^-]*PRIVATE[^-]*-----"), "[redacted-pem]")
            s = s.replace(Regex("(?i)BEGIN [A-Z ]*PRIVATE[A-Z ]*"), "[redacted]")
            return s
        }
    }
}
