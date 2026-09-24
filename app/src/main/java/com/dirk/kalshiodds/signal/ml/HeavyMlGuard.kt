package com.dirk.kalshiodds.signal.ml

import com.dirk.kalshiodds.data.local.results.CrashBreadcrumb
import com.dirk.kalshiodds.signal.config.SignalSettings
import java.util.concurrent.atomic.AtomicInteger

/**
 * Session + persisted circuit-breaker for Heavy ML / Extended AI.
 *
 * Device confirmation (SM-S928U, 0.3.0): heap growth limit 256MB exhausted,
 * victim thread `CancellableContinuationImpl` — process death mid-session.
 * One [OutOfMemoryError] latches light mode and persists the flag so the
 * next process start does not immediately re-enable Heavy ML.
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
    @Volatile var persistHook: ((reason: String) -> Unit)? = null

    fun noteFailure(error: Throwable, label: String = "ml") {
        lastReason = "${error.javaClass.simpleName}: ${error.message ?: label}"
        val n = failures.incrementAndGet()
        CrashBreadcrumb.record("ml-fail n=$n $label ${lastReason}")
        if (isOom(error) || HeapGuard.isCritical()) {
            disableAndPersist("OOM $label ${lastReason}")
            return
        }
        if (n >= FAIL_LIMIT) {
            disableAndPersist("ml-guard OFF after $n failures")
        }
    }

    fun noteHeapPressure() {
        disableAndPersist(
            "heap pressure ${(HeapGuard.usedRatio() * 100).toInt()}% of ${HeapGuard.maxBytes() / (1024 * 1024)}MB"
        )
    }

    fun disableForSession(reason: String, fromCrashHint: Boolean = false, persist: Boolean = false) {
        sessionDisabled = true
        lastReason = reason
        disabledByCrashHint = fromCrashHint
        CrashBreadcrumb.record("ml-guard OFF $reason")
        if (persist) runCatching { persistHook?.invoke(reason) }
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
            disableAndPersist("recent crash breadcrumb (OOM / native / TFLite)")
            disabledByCrashHint = true
        }
    }

    fun isOom(error: Throwable): Boolean {
        var t: Throwable? = error
        var depth = 0
        while (t != null && depth++ < 6) {
            if (t is OutOfMemoryError || t is VirtualMachineError) return true
            val name = t.javaClass.name
            if (name.contains("OutOfMemory", ignoreCase = true)) return true
            t = t.cause
        }
        return false
    }

    private fun disableAndPersist(reason: String) {
        sessionDisabled = true
        lastReason = reason
        CrashBreadcrumb.record("ml-guard OFF $reason")
        runCatching { persistHook?.invoke(reason) }
    }
}
