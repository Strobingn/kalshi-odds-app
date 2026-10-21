package com.dirk.kalshiodds.signal.v3

/**
 * Adapted from Cursor's ChartSeriesBuilder: keep a single market series and
 * discard invalid samples rather than drawing missing values as zero.
 */
object V3ChartData {
    fun currentMarket(observations: List<V3Observation>): List<V3Observation> {
        val valid = observations.filter {
            it.ticker.isNotBlank() && it.marketYes.isFinite() && it.marketYes in 0.0..1.0 &&
                it.fairYes.isFinite() && it.fairYes in 0.0..1.0
        }
        val latest = valid.maxByOrNull { it.observedAtMs } ?: return emptyList()
        return valid.filter { it.ticker.equals(latest.ticker, ignoreCase = true) }
            .sortedBy { it.observedAtMs }
    }

    fun settledPnl(fills: List<V3ShadowFill>): List<Double> = fills
        .filter { it.settled && it.outcome != "void" && it.pnlPerContract?.isFinite() == true }
        .sortedBy { it.settledAtMs ?: it.createdAtMs }
        .runningFold(0.0) { total, fill -> total + requireNotNull(fill.pnlPerContract) }
}
