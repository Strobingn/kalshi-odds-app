package com.dirk.kalshiodds.signal.scalp

/**
 * Hard guardrails shared by paper and live executors — checked BEFORE any
 * order fires. Pure: the engine fills [ScalpGuardState] from
 * [ScalpPositionStore] and settings, tests construct it directly.
 *
 * Nothing here knows about Android or SQLite; a block returns a human
 * reason string the engine surfaces as `ScalpEvent.GuardrailBlocked`.
 */
object ScalpGuardrails {

    /** Everything the guardrails need to know at decision time. */
    data class ScalpGuardState(
        /** Open positions across modes — the scalper allows at most one. */
        val openPositionCount: Int,
        /** ENTER rows in the trailing hour. */
        val entriesLastHour: Int,
        /** Realized P&L today in cents (EXIT rows, negative when losing). */
        val realizedPnlCentsToday: Long,
        /** Persisted kill switch from [ScalpSettings.killSwitch]. */
        val killSwitch: Boolean
    )

    /** Null = clear to trade; non-null = block reason. */
    fun checkEnter(state: ScalpGuardState, settings: ScalpSettings): String? {
        if (state.killSwitch) {
            return "kill switch tripped — clear it in Scalp settings to resume"
        }
        if (state.openPositionCount >= settings.maxOpenPositions) {
            return "max open scalp positions reached (${settings.maxOpenPositions})"
        }
        if (state.entriesLastHour >= settings.maxTradesPerHour) {
            return "scalp trade cap reached (${settings.maxTradesPerHour}/hour)"
        }
        val lossCents = -state.realizedPnlCentsToday
        val maxLossCents = settings.maxDailyLossUsd * 100.0
        if (lossCents >= maxLossCents + 0.5) {
            return "daily scalp loss limit hit (−$${"%.2f".format(lossCents / 100.0)})"
        }
        return null
    }

    /** Build the state snapshot from the store + current settings. */
    fun snapshot(store: ScalpLedger, settings: ScalpSettings, nowMs: Long): ScalpGuardState =
        ScalpGuardState(
            openPositionCount = store.openPositions().size,
            entriesLastHour = store.entriesSince(nowMs, 60 * 60 * 1000L),
            realizedPnlCentsToday = store.realizedPnlCentsToday(nowMs),
            killSwitch = settings.killSwitch
        )
}
