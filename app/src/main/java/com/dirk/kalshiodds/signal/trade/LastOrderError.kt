package com.dirk.kalshiodds.signal.trade

/**
 * Last live-order / Approve error shown on the ticket strip and the
 * copyable Settings panel. Never stores PEM or key material.
 */
object LastOrderError {
    @Volatile
    var message: String? = null
        private set

    fun record(msg: String?) {
        val cleaned = msg?.trim()?.takeIf { it.isNotEmpty() }?.let(::redact)
        message = cleaned
    }

    fun clear() {
        message = null
    }

    fun redact(raw: String): String {
        val lower = raw.lowercase()
        if (lower.contains("begin") && lower.contains("private")) {
            return "Order failed — credential error (secrets not logged)"
        }
        return raw.replace(Regex("(?i)-----BEGIN[\\s\\S]+?-----END[\\s\\S]+?-----"), "[redacted PEM]")
    }
}
