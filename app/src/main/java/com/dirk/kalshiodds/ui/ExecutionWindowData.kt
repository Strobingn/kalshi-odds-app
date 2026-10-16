package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.chart.BidPoint

/** Shared chart contract: prices are dollars; chart coordinates are cents. */
data class ExecutionWindowData(
    val ticker: String = "",
    val closeMs: Long = 0,
    val nowMs: Long = 0,
    val quoteAtMs: Long? = null,
    val points: List<BidPoint> = emptyList(),
    val executions: List<Execution> = emptyList(),
    val error: String? = null
) {
    val startMs get() = closeMs - WINDOW_MS
    enum class Kind(val label: String) {
        SIGNAL("Signal observed"), ORDER("BUY limit posted"), BUY("BUY fill"),
        SELL("SELL fill"), SETTLEMENT("Settlement"), LEGACY_EXIT("Historical average exit")
    }
    data class Execution(
        val id: String, val tradeId: String, val side: String,
        val atMs: Long, val price: Double, val qty: Int, val kind: Kind,
        val recordedAtMs: Long = atMs, val feeUsd: Double? = null
    )
    fun markers(side: String) = executions.filter {
        it.side.equals(side, true) && it.atMs in startMs..minOf(closeMs, nowMs) &&
            it.price.isFinite() && it.price in 0.0..1.0
    }.sortedBy { it.atMs }

    fun quotes(side: String): List<Pair<Long, Double>> = points.asSequence()
        .filter { it.tMs in startMs..minOf(closeMs, nowMs) }
        .mapNotNull { p -> (if (side == "NO") p.downBidCents else p.upBidCents)
            ?.takeIf { it.isFinite() && it in 0f..100f }?.let { p.tMs to it / 100.0 } }
        .sortedBy { it.first }.distinctBy { it.first }.toList()

    fun bid(side: String) = quotes(side).lastOrNull()?.second
    fun nearestQuote(side: String, atMs: Long) = quotes(side).minByOrNull { kotlin.math.abs(it.first - atMs) }

    companion object { const val WINDOW_MS = 900_000L }
}
