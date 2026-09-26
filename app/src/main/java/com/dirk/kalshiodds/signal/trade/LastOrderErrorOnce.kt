package com.dirk.kalshiodds.signal.trade

/**
 * Dedupes ticket-session errors before they are written to
 * [LastOrderErrorStore]. A voided-window notice is recorded at most once
 * per appearance; identical repeats from refresh must not re-write.
 */
object LastOrderErrorOnce {

    fun accept(previous: String?, incoming: String?): String? {
        val err = incoming?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (err.startsWith("PAPER ", ignoreCase = true)) return null
        if (err == previous) return null
        return err
    }
}
