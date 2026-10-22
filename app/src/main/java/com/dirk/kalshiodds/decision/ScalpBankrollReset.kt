package com.dirk.kalshiodds.decision

import com.dirk.kalshiodds.decision.insights.ScalpInsights
import com.dirk.kalshiodds.domain.EtTime
import com.dirk.kalshiodds.signal.config.SignalConstants
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Paper-only reset of the scalping bankroll. Cash goes back to [SignalConstants.PAPER_START_USD],
 * open clips flatten with no Kalshi order, and every displayed stat starts at the reset.
 * Local scalp rows and remote sync rows stay. A reset marker is the split point.
 */
object ScalpBankrollReset {
    private val _note = MutableStateFlow<String?>(null)
    /** Confirmation line, including the Eastern clock time. Null until the owner confirms a reset. */
    val note: StateFlow<String?> = _note.asStateFlow()

    fun confirmation(atMs: Long): String {
        val dollars = String.format(Locale.US, "$%,.0f", SignalConstants.PAPER_START_USD)
        return "Scalping bankroll reset to $dollars at ${EtTime.fullDateClock(atMs)}. Prior scalp history archived. No Kalshi orders."
    }

    /** Pending clips never filled. Open clips close at the entry price with no added exit fee. */
    fun flatten(t: ScalpTrade, atMs: Long): ScalpTrade {
        val stamp = "scalping bankroll reset · no Kalshi order"
        val note = listOf(t.note, stamp).filter { it.isNotBlank() }.joinToString(" · ")
        return when (t.state) {
            ScalpState.PENDING_ENTRY -> t.copy(
                state = ScalpState.NO_FILL,
                closedAtMs = atMs,
                exitReason = "scalping bankroll reset",
                note = note
            )
            ScalpState.OPEN, ScalpState.PENDING_EXIT -> {
                val px = t.entryPrice ?: t.signalAsk
                val rem = t.remaining.coerceAtLeast(0)
                val proceeds = t.proceedsUsd + rem * px
                t.copy(
                    state = ScalpState.CLOSED,
                    soldContracts = t.soldContracts + rem,
                    proceedsUsd = proceeds,
                    closedAtMs = atMs,
                    exitDecidedAtMs = atMs,
                    exitReason = "scalping bankroll reset",
                    netUsd = proceeds - t.exitFeeUsd - t.entryCostUsd,
                    note = note
                )
            }
            else -> t
        }
    }

    /**
     * Zero the "AI learns" sample. Slider values, floors, the manual baseline and the current
     * algo overrides stay. The next close starts a new window.
     */
    fun freshWindow(s: ScalpStrategySettings): ScalpStrategySettings = s.copy(
        aiN = 0,
        aiNet = 0.0,
        aiNote = if (s.aiLearns) "reset · AI restarts from your settings" else s.aiNote
    )

    fun apply(
        book: ScalpBook,
        controls: ScalpControlsRepository,
        insights: ScalpInsights? = null,
        nowMs: Long = System.currentTimeMillis(),
        resetCash: () -> Unit = {}
    ): String {
        val msg = confirmation(nowMs)
        book.resetDisplayedRun(nowMs, msg, SignalConstants.PAPER_START_USD)
        controls.update { c -> c.copy(strategySettings = c.strategySettings.mapValues { (_, s) -> freshWindow(s) }) }
        controls.clearRecentPerformance()
        insights?.clearRecent()
        ScalperBrain.resetRecent()
        _note.value = msg
        resetCash()
        return msg
    }

    internal fun clearDisplayedNote() { _note.value = null }
}
