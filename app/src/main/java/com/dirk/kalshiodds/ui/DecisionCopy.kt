package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.decision.RegimeCalibration
import com.dirk.kalshiodds.decision.StrategyLadder
import java.util.Locale

/** Plain-text copy for the calibration report and strategy ladder screens. Pure, unit-tested. */
object DecisionCopy {
    data class CalibrationRow(
        val id: String,
        val title: String,
        val status: String,
        val metrics: String,
        val reliability: List<String>,
        val approved: Boolean
    )

    fun calibrationHeader(model: RegimeCalibration.Model, ledgerRows: Long?): List<String> {
        val fitted = if (model.fittedAtMs > 0L) {
            java.text.SimpleDateFormat("MMM d HH:mm", Locale.US).format(java.util.Date(model.fittedAtMs))
        } else {
            "not yet"
        }
        return listOfNotNull(
            "Model ${RegimeCalibration.VERSION} · fitted $fitted · ${model.rows} settled samples",
            ledgerRows?.let { "Prediction ledger: $it rows (never truncated)" },
            "Regime → coin → global fallback. Final 60 s use the regime only; until it is calibrated, the final window is NO BET.",
            "Approved = enough samples AND out-of-fold Brier not worse than the market."
        )
    }

    fun calibrationRows(model: RegimeCalibration.Model): List<CalibrationRow> =
        model.sortedReports().map { r -> row(r) }

    fun row(r: RegimeCalibration.BucketReport): CalibrationRow {
        val status = when {
            r.approved -> "APPROVED"
            !r.ready -> "Collecting (n=${r.n})"
            else -> "Not better than market"
        }
        return CalibrationRow(
            id = r.id,
            title = "${r.level.name.lowercase()} · ${r.id}",
            status = status,
            metrics = String.format(
                Locale.US,
                "n=%d · Brier model %s vs market %s (raw %s) · logloss model %s vs market %s",
                r.n,
                fmt(r.modelBrier),
                fmt(r.marketBrier),
                fmt(r.rawBrier),
                fmt(r.modelLogLoss),
                fmt(r.marketLogLoss)
            ),
            reliability = r.reliability.filter { it.n > 0 }.map { b ->
                String.format(
                    Locale.US,
                    "%2.0f–%2.0f%%  pred %.1f%%  obs %.1f%%  n=%d",
                    b.lo * 100,
                    b.hi * 100,
                    b.meanPredicted * 100,
                    b.observedRate * 100,
                    b.n
                )
            },
            approved = r.approved
        )
    }

    fun ladderTitle(s: StrategyLadder.Status): String = "${s.id.label} — ${s.stage.label}"

    fun ladderNeed(s: StrategyLadder.Status): String {
        val noun = if (s.id.countsFilled) "fills" else "settled"
        return "${s.n}/${s.id.need} $noun · ${s.id.rule}"
    }

    private fun fmt(v: Double?): String = v?.let { String.format(Locale.US, "%.4f", it) } ?: "—"
}
