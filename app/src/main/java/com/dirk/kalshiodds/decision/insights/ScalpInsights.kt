package com.dirk.kalshiodds.decision.insights

import com.dirk.kalshiodds.decision.ScalpBook
import com.dirk.kalshiodds.decision.ScalpControls
import com.dirk.kalshiodds.decision.ScalpControlsRepository
import com.dirk.kalshiodds.decision.ScalpModels
import com.dirk.kalshiodds.decision.ScalpPersistence
import com.dirk.kalshiodds.decision.ScalpTrade
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 1.0.9: process-wide view of bench decisions for UI badges (paper only). */
object BenchRegistry {
    private val _decisions = MutableStateFlow<Map<String, BenchDecision>>(emptyMap())
    val decisions: StateFlow<Map<String, BenchDecision>> = _decisions
    private val _benched = MutableStateFlow<Set<String>>(emptySet())
    val benched: StateFlow<Set<String>> = _benched
    fun publish(d: Map<String, BenchDecision>) { _decisions.value = d; _benched.value = d.values.filter { !it.trading }.map { it.scalperId }.toSet() }
}

/**
 * 1.0.9 glue: keeps the last settled real + bench-shadow outcomes per scalper, re-runs [ScalpBench] on each
 * settle, pushes the benched set into the [ScalpBook] and raises once-per-event alerts. Paper only —
 * never touches real-money paths. Shadow history is in memory (real history is re-seeded from SQLite).
 */
class ScalpInsights(
    private val book: ScalpBook,
    private val controls: ScalpControlsRepository,
    private val store: ScalpPersistence?,
    private val post: (ScalpAlert) -> Unit
) {
    private val lock = Any()
    private val real = HashMap<String, ArrayDeque<ScalpTrade>>()
    private val shadow = HashMap<String, ArrayDeque<ScalpTrade>>()
    val alerts = ScalpAlertPolicy()
    private var decisions: Map<String, BenchDecision> = emptyMap()

    fun start() {
        book.onSettled = { code, t -> onSettled(code, t) }
        seed()
    }

    /** Loads the last N real settled trades per scalper (off the main thread). */
    fun seed() {
        val n = keep(controls.state.value)
        for (m in ScalpModels.ALL) {
            val rows = runCatching { store?.pageTrades(m.code, null, null, n).orEmpty() }.getOrDefault(emptyList())
            if (rows.isEmpty()) continue
            synchronized(lock) { val q = real.getOrPut(m.code) { ArrayDeque() }; if (q.isEmpty()) rows.sortedBy { it.closedAtMs ?: 0L }.forEach { q.addLast(it) } }
        }
        recompute()
    }

    private fun keep(c: ScalpControls) = c.benchWindow.coerceIn(5, 500) * 2

    fun onSettled(code: String, t: ScalpTrade) {
        val c = controls.state.value
        val shadowTrade = !t.isPrimary
        val recent = synchronized(lock) {
            val q = (if (shadowTrade) shadow else real).getOrPut(code) { ArrayDeque() }
            q.removeAll { it.id == t.id }; q.addLast(t)
            while (q.size > keep(c)) q.removeFirst()
            real[code]?.toList().orEmpty()
        }
        if (!shadowTrade) {
            val toggles = AlertToggles(hotStreak = c.alertHotStreak, bigLoss = c.alertBigLoss, strongAi = c.alertStrongAi, bigLossUsd = c.bigLossUsd)
            val label = ScalpModels.modelByCode(code)?.label ?: code
            alerts.onSettled(label, recent, toggles).forEach { runCatching { post(it) } }
        }
        recompute()
    }

    fun onAiCall(callId: String, direction: String, confidence: Double) {
        val c = controls.state.value
        alerts.onAiCall(callId, direction, confidence, AlertToggles(strongAi = c.alertStrongAi))?.let { runCatching { post(it) } }
    }

    fun recompute() {
        val c = controls.state.value
        val next = synchronized(lock) {
            (real.keys + shadow.keys + c.benchOverrides.keys).associateWith { code ->
                val ov = when (c.benchOverrides[code]) { "ACTIVE" -> BenchOverride.FORCE_ACTIVE; "BENCHED" -> BenchOverride.FORCE_BENCHED; else -> BenchOverride.AUTO }
                val was = decisions[code]?.status == BenchStatus.BENCHED
                if (!c.autoBench && ov == BenchOverride.AUTO) BenchDecision(code, BenchStatus.ACTIVE, real[code]?.size ?: 0, 0.0, 0.0, "auto-bench off")
                else {
                    // Real + bench-shadow outcomes together, so a freshly unbenched scalper is judged on its recent
                    // (shadow) record rather than the frozen losing streak that benched it.
                    val merged = (real[code].orEmpty() + shadow[code].orEmpty()).sortedBy { it.closedAtMs ?: 0L }
                    ScalpBench.decide(code, was, merged, emptyList(), c.benchWindow, ov)
                }
            }
        }
        decisions = next
        book.benched = next.values.filter { !it.trading }.map { it.scalperId }.toSet()
        BenchRegistry.publish(next)
    }
}
