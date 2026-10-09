package com.dirk.kalshiodds.signal.ml

import com.dirk.kalshiodds.data.local.results.CrashBreadcrumb

/**
 * Fail-soft wrapper: one bad infer / path-sim / WS-adjacent call must
 * never escape to kill the process. [OutOfMemoryError] is caught too
 * (Kotlin [runCatching] already does; this also trips the session guard).
 */
object SafeMl {
    fun <T> run(label: String, fallback: () -> T, block: () -> T): T {
        return try {
            block()
        } catch (t: Throwable) {
            CrashBreadcrumb.record("safe-ml $label", t)
            HeavyMlGuard.noteFailure(t, label)
            try {
                fallback()
            } catch (t2: Throwable) {
                CrashBreadcrumb.record("safe-ml-fallback $label", t2)
                throw t2
            }
        }
    }

    fun runOrNull(label: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            CrashBreadcrumb.record("safe-ml $label", t)
            HeavyMlGuard.noteFailure(t, label)
        }
    }
}
