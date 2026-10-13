package com.dirk.kalshiodds.signal.paper

import java.time.Instant
import java.time.ZoneOffset
import java.util.Random
import kotlin.math.floor

/**
 * Evidence-only review for one paper scalp track.
 *
 * A result is eligible only when it is settled, non-void, and has a recorded
 * after-fee P&L.  The test resamples whole UTC days, not individual trades, so
 * a busy or unusually directional day cannot masquerade as many independent
 * observations.  It labels a track for the next experimental revision; it
 * never submits orders, changes a live model, or silently stops a track.
 */
object ScalpReview {
    const val MIN_SETTLED_FILLS = 30
    const val MIN_DISTINCT_DAYS = 3
    private const val BOOTSTRAP_SAMPLES = 2_000

    enum class Decision(val label: String) {
        COLLECT("Collect evidence"),
        KEEP("Keep as-is"),
        TWEAK("Tweak next run")
    }

    data class Assessment(
        val decision: Decision,
        val settledFills: Int,
        val distinctDays: Int,
        /** Realized P&L, including the entry/exit fees already stored on each fill. */
        val realizedPnlUsd: Double,
        /** Quantity-weighted dollars per contract, used to compare different displayed depths. */
        val meanPnlPerContractUsd: Double?,
        /** Whole-day bootstrap 95% lower bound of P&L per contract. */
        val lower95PerContractUsd: Double?,
        val explanation: String
    )

    fun assess(fills: List<PaperFill>): Assessment {
        val eligible = fills.filter {
            it.settled && it.outcome != "void" && it.pnlUsd?.isFinite() == true && it.contracts > 0
        }
        val realized = eligible.sumOf { it.pnlUsd ?: 0.0 }
        val byDay = eligible.groupBy { utcDay(it.createdAtMs) }
        val days = byDay.keys.size
        if (eligible.size < MIN_SETTLED_FILLS || days < MIN_DISTINCT_DAYS) {
            return Assessment(
                decision = Decision.COLLECT,
                settledFills = eligible.size,
                distinctDays = days,
                realizedPnlUsd = realized,
                meanPnlPerContractUsd = weightedMean(eligible),
                lower95PerContractUsd = null,
                explanation = "Needs at least $MIN_SETTLED_FILLS settled non-void fills across $MIN_DISTINCT_DAYS UTC days before a keep/tweak call."
            )
        }

        val daily = byDay.toSortedMap().values.map(::weightedMean).filterNotNull()
        val mean = weightedMean(eligible)
        val lower = bootstrapLower95(daily)
        val decision = if (mean != null && lower != null && mean > 0.0 && lower > 0.0) {
            Decision.KEEP
        } else {
            Decision.TWEAK
        }
        val explanation = when (decision) {
            Decision.KEEP -> "Positive after-fee P&L per contract survived whole-day resampling; retain this fixed formula for the next review window."
            Decision.TWEAK -> "The after-fee result did not clear the whole-day 95% test; keep its ledger visible and test a named replacement rather than retuning from one window."
            Decision.COLLECT -> error("Handled above")
        }
        return Assessment(
            decision = decision,
            settledFills = eligible.size,
            distinctDays = days,
            realizedPnlUsd = realized,
            meanPnlPerContractUsd = mean,
            lower95PerContractUsd = lower,
            explanation = explanation
        )
    }

    private fun weightedMean(fills: List<PaperFill>): Double? {
        val contracts = fills.sumOf { it.contracts }.takeIf { it > 0 } ?: return null
        return fills.sumOf { it.pnlUsd ?: 0.0 } / contracts.toDouble()
    }

    /** Deterministic bootstrap so the same stored ledger always yields the same review label. */
    private fun bootstrapLower95(dailyMeans: List<Double>): Double? {
        if (dailyMeans.isEmpty()) return null
        val random = Random(0x5CA1F)
        val values = DoubleArray(BOOTSTRAP_SAMPLES) {
            var sum = 0.0
            repeat(dailyMeans.size) { sum += dailyMeans[random.nextInt(dailyMeans.size)] }
            sum / dailyMeans.size.toDouble()
        }
        values.sort()
        return values[floor(0.025 * (values.size - 1)).toInt()]
    }

    private fun utcDay(epochMs: Long): String =
        Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC).toLocalDate().toString()
}
