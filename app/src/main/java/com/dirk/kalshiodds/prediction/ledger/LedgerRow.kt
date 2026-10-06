package com.dirk.kalshiodds.prediction.ledger

import com.dirk.kalshiodds.prediction.PredictionLogEntry

/**
 * One settled call: what the app said, what Kalshi's price said, and what
 * happened. One row per market window (the last call logged for it).
 */
data class LedgerRow(
    val ticker: String,
    val series: String,
    /** When the call was made (last update before settlement). */
    val calledAtMs: Long,
    val closeTimeMs: Long?,
    /** Model P(YES), 0–1. */
    val modelYes: Double,
    /** Kalshi YES mid at call time, 0–1: the market's forecast. */
    val marketMid: Double,
    /** Ask paid for the picked side at call time, 0–1, when known. */
    val entryAsk: Double?,
    val pickedSide: String?,
    val edgePp: Double?,
    val uncertainty: Double?,
    val regime: String?,
    val tteBucket: String?,
    /** Would the app have alerted / bet on this call. */
    val wouldBet: Boolean?,
    /** App build and edge-model version recorded when the window settled. */
    val modelVersion: String,
    /** "yes" or "no" (void windows are not kept). */
    val outcome: String,
    val settledAtMs: Long
) {
    val yes: Int get() = if (outcome == "yes") 1 else 0

    companion object {
        /** A row for a settled yes/no entry, or null (open, void, or bad numbers). */
        fun from(e: PredictionLogEntry, modelVersion: String): LedgerRow? {
            val outcome = e.outcome?.lowercase() ?: return null
            if (outcome != "yes" && outcome != "no") return null
            if (!e.predictedYes.isFinite() || e.predictedYes !in 0.0..1.0) return null
            if (!e.marketMid.isFinite() || e.marketMid !in 0.0..1.0) return null
            return LedgerRow(
                ticker = e.ticker,
                series = e.series,
                calledAtMs = e.timestampMs,
                closeTimeMs = e.closeTimeMs,
                modelYes = e.predictedYes,
                marketMid = e.marketMid,
                entryAsk = e.entryAsk,
                pickedSide = e.predictedSide,
                edgePp = e.edgePp,
                uncertainty = e.uncertainty,
                regime = e.regime,
                tteBucket = e.tteBucket,
                wouldBet = e.wouldAlert,
                modelVersion = modelVersion,
                outcome = outcome,
                settledAtMs = e.settledAtMs ?: e.timestampMs
            )
        }
    }
}
