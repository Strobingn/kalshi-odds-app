package com.dirk.kalshiodds.signal.paper

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * 0.3.39 always-on Autopilot. Pure policy objects plus a tiny SharedPreferences store.
 * Errors back off and resume — they never switch Autopilot off. 0.3.40: Autopilot is paper-only.
 */
object AlwaysOnAutopilot {
    /** Background cadence for the headless Autopilot loop (UI not visible). */
    const val BACKGROUND_POLL_MS = 5_000L

    /** True while the process-scoped OddsViewModel drives refresh + Autopilot with no UI. */
    val headlessDriving = AtomicBoolean(false)

    /** Paper/shadow Autopilot and Scalp paper run whenever paper trading is on. There is no live Autopilot. */
    fun autopilotWanted(
        paperTradingEnabled: Boolean,
        aiPaperAutopilotEnabled: Boolean
    ): Boolean {
        // Scalp paper follows paper trading even when the AI Autopilot toggle is off.
        return paperTradingEnabled && (aiPaperAutopilotEnabled || SCALP_FOLLOWS_PAPER)
    }

    const val SCALP_FOLLOWS_PAPER = true

    /** The foreground service must stay up for Live signals or for always-on Autopilot. */
    fun serviceWanted(
        liveSignalsEnabled: Boolean,
        paperTradingEnabled: Boolean,
        aiPaperAutopilotEnabled: Boolean
    ): Boolean = liveSignalsEnabled ||
        autopilotWanted(paperTradingEnabled, aiPaperAutopilotEnabled)
}

/**
 * Error backoff: 1 s, doubling, capped at 10 s (0.3.49). [onSuccess] resets.
 * An "episode" starts at the first error after a success; alerts fire once per episode.
 */
class AutopilotBackoff(
    private val baseMs: Long = BASE_MS,
    private val maxMs: Long = MAX_MS
) {
    var failures: Int = 0
        private set
    var resumeAtMs: Long = 0L
        private set
    var episodeStartMs: Long? = null
        private set
    var lastError: String? = null
        private set

    /** Records an error and returns the wait before the next attempt. */
    @Synchronized
    fun onError(nowMs: Long, message: String?): Long {
        failures += 1
        if (episodeStartMs == null) episodeStartMs = nowMs
        lastError = message
        val wait = delayFor(failures, baseMs, maxMs)
        resumeAtMs = nowMs + wait
        return wait
    }

    @Synchronized
    fun onSuccess() {
        failures = 0
        resumeAtMs = 0L
        episodeStartMs = null
        lastError = null
    }

    @Synchronized
    fun blocked(nowMs: Long): Boolean = failures > 0 && nowMs < resumeAtMs

    companion object {
        // 0.3.49: was 30 s doubling to 10 min — one bad eval froze Autopilot for minutes.
        const val BASE_MS = 1_000L
        const val MAX_MS = 10_000L

        fun delayFor(failures: Int, baseMs: Long = BASE_MS, maxMs: Long = MAX_MS): Long {
            if (failures <= 0) return 0L
            val shift = (failures - 1).coerceAtMost(20)
            return min(maxMs, baseMs shl shift)
        }
    }
}

/**
 * 0.3.40 owner decision: Autopilot and Scalp are PAPER-ONLY. On every start (idempotent) delete the
 * persisted live-arming file left by 0.3.39 and rewrite a stored "LIVE" Autopilot mode to PAPER.
 */
object LiveOffMigration {
    /** SharedPreferences file 0.3.39 used for the live-Autopilot armed flag. */
    const val LEGACY_ARM_PREFS = "kashi_live_autopilot_arm"

    /** Deletes the legacy arm file. Returns true when something was removed. */
    fun deleteLegacyArming(context: Context): Boolean = runCatching {
        val prefs = context.getSharedPreferences(LEGACY_ARM_PREFS, Context.MODE_PRIVATE)
        val had = prefs.all.isNotEmpty()
        prefs.edit().clear().commit()
        context.deleteSharedPreferences(LEGACY_ARM_PREFS)
        had
    }.getOrDefault(false)

    /** Returns the mode string to store, or null when nothing needs rewriting. */
    fun rewriteMode(storedRaw: String?): String? =
        if (storedRaw != null && storedRaw.trim().uppercase() !in AutopilotMode.entries.map { it.name }) AutopilotMode.PAPER.name else null
}
