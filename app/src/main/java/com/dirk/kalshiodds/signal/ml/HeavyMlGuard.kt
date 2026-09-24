package com.dirk.kalshiodds.signal.ml

import com.dirk.kalshiodds.data.local.results.CrashBreadcrumb
import com.dirk.kalshiodds.signal.config.SignalSettings
import java.util.concurrent.atomic.AtomicInteger

/**
 * Session circuit-breaker for Heavy ML / Extended AI. After [FAIL_LIMIT]
 * native or inference failures (or a recent process-killing breadcrumb)
 * scoring stays on the 0.2.x MLP blend for the rest of the process.
 */
object HeavyMlGuard {
    const val FAIL_LIMIT = 3

    private val failures = AtomicInteger(0)
    @Volatile var sessionDisabled: Boolean = false
        private set
    @Volatile var lastReason: String? = null
        private set
    @Volatile var disabledByCrashHint: Boolean = false
        private set

    fun noteFailure(error: Throwable, label: String = "ml") {
        lastReason = "${error.javaClass.simpleName}: ${error.message ?: label}"
        val n = failures.incrementAndGet()
        CrashBreadcrumb.record("ml-fail n=$n $label ${lastReason}")
        if (n >= FAIL_LIMIT) {
            sessionDisabled = true
            CrashBreadcrumb.record("ml-guard OFF after $n failures — light 0.2.x scoring")
        }
    }

    fun disableForSession(reason: String, fromCrashHint: Boolean = false) {
        sessionDisabled = true
        lastReason = reason
        disabledByCrashHint = fromCrashHint
        CrashBreadcrumb.record("ml-guard OFF $reason")
    }

    fun reset() {
        failures.set(0)
        sessionDisabled = false
        lastReason = null
        disabledByCrashHint = false
    }

    fun failureCount(): Int = failures.get()

    fun apply(settings: SignalSettings): SignalSettings {
        if (!sessionDisabled) return settings
        if (!settings.heavyMlEnabled && !settings.extendedAiEnabled) return settings
        return settings.copy(heavyMlEnabled = false, extendedAiEnabled = false)
    }

    fun applyCrashHintIfNeeded() {
        if (sessionDisabled) return
        if (CrashBreadcrumb.recentFatalHint()) {
            disableForSession("recent crash breadcrumb (OOM / native / TFLite)", fromCrashHint = true)
        }
    }
}
