package com.dirk.kalshiodds.signal.paper

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * 0.3.39 always-on Autopilot. Pure policy objects plus a tiny SharedPreferences store.
 * Errors back off and resume — they never switch Autopilot off or disarm limited live.
 */
object AlwaysOnAutopilot {
    /** Background cadence for the headless Autopilot loop (UI not visible). */
    const val BACKGROUND_POLL_MS = 5_000L

    /** True while the process-scoped OddsViewModel drives refresh + Autopilot with no UI. */
    val headlessDriving = AtomicBoolean(false)

    /** Paper/shadow Autopilot and Scalp paper run whenever paper trading is on. Live runs when armed. */
    fun autopilotWanted(
        paperTradingEnabled: Boolean,
        aiPaperAutopilotEnabled: Boolean,
        liveMode: Boolean,
        liveArmed: Boolean
    ): Boolean {
        // Scalp paper follows paper trading even when the AI Autopilot toggle is off.
        val paperSide = paperTradingEnabled && (aiPaperAutopilotEnabled || SCALP_FOLLOWS_PAPER)
        return paperSide || (liveMode && liveArmed)
    }

    const val SCALP_FOLLOWS_PAPER = true

    /** The foreground service must stay up for Live signals or for always-on Autopilot. */
    fun serviceWanted(
        liveSignalsEnabled: Boolean,
        paperTradingEnabled: Boolean,
        aiPaperAutopilotEnabled: Boolean,
        liveMode: Boolean,
        liveArmed: Boolean
    ): Boolean = liveSignalsEnabled ||
        autopilotWanted(paperTradingEnabled, aiPaperAutopilotEnabled, liveMode, liveArmed)
}

/**
 * Error backoff: 30 s, doubling, capped at 10 min. [onSuccess] resets.
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
        const val BASE_MS = 30_000L
        const val MAX_MS = 10L * 60L * 1000L

        fun delayFor(failures: Int, baseMs: Long = BASE_MS, maxMs: Long = MAX_MS): Long {
            if (failures <= 0) return 0L
            val shift = (failures - 1).coerceAtMost(20)
            return min(maxMs, baseMs shl shift)
        }
    }
}

/** Where the limited-live armed flag lives. Never holds key material. */
interface LiveArmStore {
    fun loadArmed(): Boolean
    fun saveArmed(armed: Boolean, atMs: Long)
}

class InMemoryLiveArmStore(private var armed: Boolean = false) : LiveArmStore {
    override fun loadArmed(): Boolean = armed
    override fun saveArmed(armed: Boolean, atMs: Long) {
        this.armed = armed
    }
}

/** Durable arming (0.3.39): survives restarts, process death and reboots. commit() so it is on disk at once. */
class SharedPrefsLiveArmStore(context: Context) : LiveArmStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    override fun loadArmed(): Boolean = prefs.getBoolean(KEY_ARMED, false)
    override fun saveArmed(armed: Boolean, atMs: Long) {
        prefs.edit().putBoolean(KEY_ARMED, armed).putLong(KEY_AT, atMs).commit()
    }

    companion object {
        const val PREFS = "kashi_live_autopilot_arm"
        const val KEY_ARMED = "armed"
        const val KEY_AT = "armed_at_ms"
    }
}

/**
 * Pre-send checks for limited live Autopilot, in order. Pure.
 * Missing/stale balance pauses (retry next tick); it never guesses a bankroll.
 */
object LiveAutopilotPreflight {
    data class Verdict(val ok: Boolean, val reason: String, val paused: Boolean = false)

    fun check(
        armed: Boolean,
        decisionOk: Boolean,
        balanceFresh: Boolean,
        backoffBlocked: Boolean,
        kellyOk: Boolean,
        allInUsd: Double
    ): Verdict {
        if (!armed) return Verdict(false, "Limited live is off until you tap Approve and type REAL MONEY")
        if (!decisionOk) return Verdict(false, "NO BET gate")
        if (!balanceFresh) {
            return Verdict(false, com.dirk.kalshiodds.decision.LiveBalancePolicy.REASON + " — paused, retrying", paused = true)
        }
        if (backoffBlocked) return Verdict(false, "Backing off after an error — resumes automatically", paused = true)
        if (com.dirk.kalshiodds.decision.AutopilotMinStake.below(allInUsd)) {
            return Verdict(false, com.dirk.kalshiodds.decision.AutopilotMinStake.REASON)
        }
        if (!kellyOk) return Verdict(false, "NO BET — Kelly size unavailable")
        return Verdict(true, "Preflight ok")
    }
}

/** One-time flag storage for [LiveOffMigration]. */
interface MigrationFlag {
    fun done(): Boolean
    fun markDone()
}

class SharedPrefsMigrationFlag(context: Context, private val key: String) : MigrationFlag {
    // Same file as the live arm: excluded from backup / device transfer.
    private val prefs = context.applicationContext.getSharedPreferences(SharedPrefsLiveArmStore.PREFS, Context.MODE_PRIVATE)
    override fun done(): Boolean = prefs.getBoolean(key, false)
    override fun markDone() {
        prefs.edit().putBoolean(key, true).commit()
    }
}

/**
 * 0.3.40 owner decision: live betting OFF. On the first launch of 0.3.40, clear any persisted live
 * arming and set Autopilot mode to PAPER — exactly once. Re-arming afterwards still needs the
 * manual Approve + typed REAL MONEY flow and is then respected (this never runs again).
 */
object LiveOffMigration {
    const val FLAG_KEY = "live_off_migrated_0340"

    /** Synchronous part — call before anything reads the arm store. Returns true when it ran. */
    fun disarmIfFirstRun(store: LiveArmStore, flag: MigrationFlag, nowMs: Long): Boolean {
        if (flag.done()) return false
        runCatching { store.saveArmed(false, nowMs) }
        return true
    }

    /** Mode part (DataStore). Marks the flag only after the mode write succeeded, so a failed write retries next launch. */
    suspend fun forcePaperMode(flag: MigrationFlag, setMode: suspend (String) -> Unit) {
        if (flag.done()) return
        setMode(AutopilotMode.PAPER.name)
        flag.markDone()
    }
}
