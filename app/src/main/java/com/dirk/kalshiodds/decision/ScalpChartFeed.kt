package com.dirk.kalshiodds.decision

import com.dirk.kalshiodds.signal.paper.PaperFill
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

/**
 * Loads calibration, closed-scalp chart rows, and Eastern-day nets off the main thread.
 * The flow stays cold until a chart collects it, then refreshes every 30 seconds.
 */
class ScalpChartFeed(
    private val load: () -> ScalpChartFeed.Snapshot,
    scope: CoroutineScope,
    private val periodMs: Long = 30_000L
) {
    data class Snapshot(
        val calibration: WindowCalibration.Report = WindowCalibration.Report(),
        val closed: List<ScalpTrade> = emptyList(),
        val daily: List<ScalpDaily> = emptyList()
    )

    val snapshots: StateFlow<Snapshot> = flow {
        while (true) {
            emit(runCatching { load() }.getOrElse { Snapshot() })
            delay(periodMs)
        }
    }.flowOn(Dispatchers.IO).stateIn(scope, SharingStarted.WhileSubscribed(5_000), Snapshot())

    companion object {
        fun snapshot(
            ledger: List<com.dirk.kalshiodds.data.local.ledger.LedgerRow>,
            settlementScalps: List<ScalpTrade>,
            closed: List<ScalpTrade>,
            fills: List<PaperFill>,
            daily: List<ScalpDaily>,
            nowMs: Long
        ): Snapshot = Snapshot(
            calibration = WindowCalibration.merge(ledger, settlementScalps, fills, nowMs),
            closed = closed.filter { it.isPrimary && it.state == ScalpState.CLOSED },
            daily = daily
        )
    }
}
