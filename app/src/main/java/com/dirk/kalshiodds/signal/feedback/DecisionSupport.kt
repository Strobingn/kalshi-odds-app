package com.dirk.kalshiodds.signal.feedback

import com.dirk.kalshiodds.prediction.PredictionLogStore
import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.engine.ScoringEngine
import com.dirk.kalshiodds.signal.ml.HeavyMlPersisted
import com.dirk.kalshiodds.signal.ml.HeavyMlStore
import com.dirk.kalshiodds.signal.ml.RegimeCalibrator
import com.dirk.kalshiodds.signal.ml.ReplaySample
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
    private val heavyStore: HeavyMlStore? = null,
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
            restoreHeavy(entries, settings)
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
            refreshHeavy(entries, settings)
        }
    }

    suspend fun resumeAlerts() {
        mutex.withLock {
            val next = Guardrails.resume(scoring.guardrails)
            scoring.guardrails = next
            runCatching { guardrailStore.write(next) }
        }
    }

    private suspend fun restoreHeavy(entries: List<com.dirk.kalshiodds.prediction.PredictionLogEntry>, settings: SignalSettings) {
        val stored = heavyStore?.let { runCatching { it.read() }.getOrNull() }
        if (stored != null) {
            scoring.heavy.stack = stored.stack
            scoring.heavy.regimeCal = stored.regime
            scoring.heavy.lastSettledAtMs = stored.lastSettledAtMs
            scoring.heavy.replay.replaceAll(stored.replay)
            if (stored.yesW.size == scoring.heavy.heads.weights.yesW.size) {
                scoring.heavy.heads.weights = scoring.heavy.heads.weights.copy(
                    yesW = stored.yesW.toFloatArray(),
                    yesB = stored.yesB
                )
            }
        }
        refreshHeavy(entries, settings)
    }

    private suspend fun refreshHeavy(
        entries: List<com.dirk.kalshiodds.prediction.PredictionLogEntry>,
        settings: SignalSettings
    ) {
        if (!settings.heavyMlEnabled || !settings.continualFineTune) return
        val settled = entries.filter { it.outcome.equals("yes", true) || it.outcome.equals("no", true) }
        val cal = settled.map {
            RegimeCalibrator.Sample(
                series = it.series,
                tte = it.tteBucket ?: "EARLY",
                pYes = it.predictedYes,
                outcomeYes = it.outcome.equals("yes", true)
            )
        }
        val replay = settled.map { e ->
            ReplaySample(
                ticker = e.ticker,
                series = e.series,
                tte = e.tteBucket ?: "EARLY",
                pYes = e.predictedYes,
                outcomeYes = e.outcome.equals("yes", true),
                settledAtMs = e.settledAtMs ?: e.timestampMs,
                mlpYes = e.mlpYes,
                cnnYes = e.cnnYes,
                gbmYes = e.gbmYes,
                mid = e.marketMid,
                edgePp = e.edgePp
            )
        }
        scoring.heavy.applySettlements(replay, cal, enabled = true)
        persistHeavy()
    }

    private suspend fun persistHeavy() {
        val store = heavyStore ?: return
        val h = scoring.heavy
        runCatching {
            store.write(
                HeavyMlPersisted(
                    stack = h.stack,
                    regime = h.regimeCal,
                    yesW = h.heads.weights.yesW.toList(),
                    yesB = h.heads.weights.yesB,
                    lastSettledAtMs = h.lastSettledAtMs,
                    replay = h.replay.snapshot()
                )
            )
        }
    }

    fun thresholds(settings: SignalSettings) = Guardrails.Thresholds(
        streakN = settings.streakPauseN,
        drawdownUsd = settings.drawdownUsd,
        resumeOnNewSession = settings.resumeOnNewSession
    )
}
