package com.dirk.kalshiodds.signal.feedback

import com.dirk.kalshiodds.prediction.PredictionLogStore
import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.engine.ScoringEngine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Bootstraps and refreshes on-device adapter, allowlist, and guardrails
 * from the settlement log. All local — no remote training.
 */
class DecisionSupport(
    private val logStore: PredictionLogStore,
    private val adapterStore: LearnedWeightsStore,
    private val guardrailStore: GuardrailStore,
    private val scoring: ScoringEngine,
    private val sessionId: Long = System.currentTimeMillis()
) {
    private val mutex = Mutex()

    suspend fun bootstrap(settings: SignalSettings = SignalSettings()) {
        mutex.withLock {
            scoring.adapter = runCatching { adapterStore.read() }.getOrElse { OnlineAdapter.identity() }
            val rawGuard = runCatching { guardrailStore.read() }.getOrElse { Guardrails.identity() }
            val started = Guardrails.onNewSession(
                rawGuard,
                thresholds(settings),
                sessionId,
                System.currentTimeMillis()
            )
            scoring.guardrails = started
            runCatching { guardrailStore.write(started) }
            val entries = runCatching { logStore.readAll() }.getOrElse { emptyList() }
            scoring.allowlist = Allowlist.evaluate(entries, floor = settings.muteHitRateFloor)
        }
    }

    suspend fun refreshFromSettlements(settings: SignalSettings) {
        mutex.withLock {
            val entries = runCatching { logStore.readAll() }.getOrElse { emptyList() }
            val adapter = OnlineAdapter.update(scoring.adapter, entries)
            scoring.adapter = adapter
            runCatching { adapterStore.write(adapter) }
            scoring.allowlist = Allowlist.evaluate(entries, floor = settings.muteHitRateFloor)
            val guard = Guardrails.update(scoring.guardrails, entries, thresholds(settings))
            scoring.guardrails = guard
            runCatching { guardrailStore.write(guard) }
        }
    }

    suspend fun resumeAlerts() {
        mutex.withLock {
            val next = Guardrails.resume(scoring.guardrails)
            scoring.guardrails = next
            runCatching { guardrailStore.write(next) }
        }
    }

    fun thresholds(settings: SignalSettings) = Guardrails.Thresholds(
        streakN = settings.streakPauseN,
        drawdownUsd = settings.drawdownUsd,
        resumeOnNewSession = settings.resumeOnNewSession
    )
}
