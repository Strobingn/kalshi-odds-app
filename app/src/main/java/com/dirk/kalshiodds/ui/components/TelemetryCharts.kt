package com.dirk.kalshiodds.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.chart.BidPoint
import com.dirk.kalshiodds.chart.ChartSeriesBuilder
import com.dirk.kalshiodds.data.local.archive.DataStats
import com.dirk.kalshiodds.data.local.history.HistoryAssembler
import com.dirk.kalshiodds.data.local.history.HistoryBet
import com.dirk.kalshiodds.domain.MarketQuoteView
import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.signal.feedback.ScorecardLedger
import com.dirk.kalshiodds.signal.feedback.ScorecardMetrics
import com.dirk.kalshiodds.signal.model.SignalStance
import com.dirk.kalshiodds.ui.ScorecardCopy
import com.dirk.kalshiodds.ui.SignalCopy
import com.dirk.kalshiodds.ui.theme.Contrast
import com.dirk.kalshiodds.ui.theme.DipTheme
import com.dirk.kalshiodds.ui.theme.checklistLabelColor
import com.dirk.kalshiodds.ui.theme.checklistValueColor
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure telemetry & chart data projections for live markets, scorecards,
 * signal history, and archive stats. Never places an order or mutates state.
 */
object TelemetryChartMath {

    data class QuoteGauge(
        val yesBidCents: Double?,
        val yesAskCents: Double?,
        val noBidCents: Double?,
        val noAskCents: Double?,
        val midCents: Double?,
        val spreadCents: Double?,
        val aiYesCents: Double?,
        val digitalFairCents: Double?,
        val combinedAskCents: Double?
    ) {
        val hasBook: Boolean get() = yesBidCents != null || yesAskCents != null || aiYesCents != null
    }

    data class DriverBar(
        val name: String,
        val deltaPp: Double,
        val fractionOfMax: Float
    )

    data class EnsembleBar(
        val label: String,
        val cents: Double
    )

    data class MicroGaugeItem(
        val label: String,
        val valueText: String,
        val fillFraction: Float,
        val warning: Boolean = false
    )

    data class SpreadDeltaPoint(
        val tMs: Long,
        val combinedBidCents: Float?,
        val impliedSpreadCents: Float?,
        val spotDeltaUsd: Double?
    )

    data class CalibrationPoint(
        val label: String,
        val predicted01: Double,
        val actual01: Double,
        val count: Int
    )

    data class EquityCurveSummary(
        val points: List<Pair<Long, Double>>,
        val drawdownPoints: List<Pair<Long, Double>>,
        val peakUsd: Double,
        val maxDrawdownUsd: Double,
        val currentUsd: Double
    )

    data class BucketBar(
        val label: String,
        val wins: Int,
        val losses: Int,
        val settledCount: Int,
        val hitRate01: Double?,
        val pnlUsd: Double,
        val maxAbsPnlUsd: Double
    ) {
        val winFraction: Float
            get() = if (settledCount <= 0) 0f else (wins.toFloat() / settledCount.toFloat()).coerceIn(0f, 1f)
        val lossFraction: Float
            get() = if (settledCount <= 0) 0f else (losses.toFloat() / settledCount.toFloat()).coerceIn(0f, 1f)
        val pnlFraction: Float
            get() = if (maxAbsPnlUsd <= 1e-9) 0f else (abs(pnlUsd) / maxAbsPnlUsd).toFloat().coerceIn(0f, 1f)
    }

    data class EdgeBandCount(
        val label: String,
        val upCount: Int,
        val downCount: Int,
        val noBetCount: Int
    ) {
        val total: Int get() = upCount + downCount + noBetCount
    }

    fun quoteGauge(market: MarketUiModel): QuoteGauge {
        val q = MarketQuoteView.of(market)
        val yb = q.yesBid?.takeIf { it.isFinite() && it > 0.0 }?.times(100.0)
        val ya = q.yesAsk?.takeIf { it.isFinite() && it > 0.0 }?.times(100.0)
        val nb = q.noBid?.takeIf { it.isFinite() && it > 0.0 }?.times(100.0)
        val na = q.noAsk?.takeIf { it.isFinite() && it > 0.0 }?.times(100.0)
        val mid = when {
            yb != null && ya != null && ya >= yb -> (yb + ya) / 2.0
            market.yesProbabilityPercent?.isFinite() == true -> market.yesProbabilityPercent
            else -> null
        }
        val spread = when {
            yb != null && ya != null && ya >= yb -> ya - yb
            market.spreadDollars?.isFinite() == true -> market.spreadDollars * 100.0
            else -> null
        }
        val ai = SignalStance.homeModelYes(market.importedModelPp, market.aiYesPercent)
            ?.takeIf { it.isFinite() && it in 0.0..100.0 }
        val fv = market.digitalFairPp?.takeIf { it.isFinite() && it in 0.0..100.0 }
        val combinedAsk = if (ya != null && na != null) ya + na else null
        return QuoteGauge(
            yesBidCents = yb,
            yesAskCents = ya,
            noBidCents = nb,
            noAskCents = na,
            midCents = mid,
            spreadCents = spread,
            aiYesCents = ai,
            digitalFairCents = fv,
            combinedAskCents = combinedAsk
        )
    }

    fun driverBars(featureDevs: Map<String, Double>, maxItems: Int = 6): List<DriverBar> {
        val filtered = featureDevs
            .filter { it.value.isFinite() && abs(it.value) >= 0.05 }
            .entries
            .sortedByDescending { abs(it.value) }
            .take(maxItems)
        if (filtered.isEmpty()) return emptyList()
        val maxAbs = filtered.maxOf { abs(it.value) }.coerceAtLeast(1.0)
        return filtered.map { (k, v) ->
            DriverBar(
                name = k,
                deltaPp = v,
                fractionOfMax = (abs(v) / maxAbs).toFloat().coerceIn(0f, 1f)
            )
        }
    }

    fun ensembleBars(market: MarketUiModel): List<EnsembleBar> = buildList {
        val mkt = market.yesProbabilityPercent?.takeIf { it.isFinite() && it in 0.0..100.0 }
        val ai = SignalStance.homeModelYes(market.importedModelPp, market.aiYesPercent)
            ?.takeIf { it.isFinite() && it in 0.0..100.0 }
        val fv = market.digitalFairPp?.takeIf { it.isFinite() && it in 0.0..100.0 }
        val mlp = market.mlpPp?.takeIf { it.isFinite() && it in 0.0..100.0 }
        val gbm = market.gbmPp?.takeIf { it.isFinite() && it in 0.0..100.0 }
        val cnn = market.cnnPp?.takeIf { it.isFinite() && it in 0.0..100.0 }
        val mm = market.mmShadowPp?.takeIf { it.isFinite() && it in 0.0..100.0 }
        if (mkt != null) add(EnsembleBar("Mkt mid", mkt))
        if (ai != null) add(EnsembleBar(if (market.calibrated) "FV blend" else "AI blend", ai))
        if (fv != null && (ai == null || abs(fv - ai) >= 0.5)) add(EnsembleBar("Digital FV", fv))
        if (mlp != null) add(EnsembleBar("MLP", mlp))
        if (gbm != null) add(EnsembleBar("GBM", gbm))
        if (cnn != null) add(EnsembleBar("CNN", cnn))
        if (mm != null) add(EnsembleBar("MM shadow", mm))
    }

    fun microGauges(market: MarketUiModel): List<MicroGaugeItem> = buildList {
        market.aiConfidence?.takeIf { it.isFinite() }?.let { c ->
            add(
                MicroGaugeItem(
                    label = "Confidence",
                    valueText = String.format(Locale.US, "%.0f%%", c * 100.0),
                    fillFraction = c.toFloat().coerceIn(0f, 1f),
                    warning = c < 0.50
                )
            )
        }
        market.pFill?.takeIf { it.isFinite() }?.let { pf ->
            add(
                MicroGaugeItem(
                    label = "P(fill)",
                    valueText = String.format(Locale.US, "%.0f%%", pf * 100.0),
                    fillFraction = pf.toFloat().coerceIn(0f, 1f),
                    warning = pf < 0.35
                )
            )
        }
        market.uncertainty?.takeIf { it.isFinite() }?.let { u ->
            add(
                MicroGaugeItem(
                    label = "Uncertainty",
                    valueText = String.format(Locale.US, "%.2f", u),
                    fillFraction = (u / 0.50).toFloat().coerceIn(0f, 1f),
                    warning = !market.uncertaintyPassed
                )
            )
        }
        market.midVolPp?.takeIf { it.isFinite() }?.let { vol ->
            add(
                MicroGaugeItem(
                    label = "Mid vol",
                    valueText = String.format(Locale.US, "%.1fpp", vol),
                    fillFraction = (vol / 10.0).toFloat().coerceIn(0f, 1f)
                )
            )
        }
    }

    fun spreadDeltaSeries(points: List<BidPoint>, strikeUsd: Double?): List<SpreadDeltaPoint> {
        if (points.isEmpty()) return emptyList()
        val k = strikeUsd?.takeIf { it.isFinite() && it > 0.0 }
        return points.mapNotNull { p ->
            val up = ChartSeriesBuilder.chartableBidCents(p.upBidCents)
            val down = ChartSeriesBuilder.chartableBidCents(p.downBidCents)
            val combined = if (up != null && down != null) up + down else null
            val spread = combined?.let { (100f - it).coerceAtLeast(0f) }
            val delta = if (k != null && p.spotUsd != null && p.spotUsd.isFinite() && p.spotUsd > 0.0) {
                p.spotUsd - k
            } else null
            if (combined == null && delta == null) null
            else SpreadDeltaPoint(
                tMs = p.tMs,
                combinedBidCents = combined,
                impliedSpreadCents = spread,
                spotDeltaUsd = delta
            )
        }
    }

    fun equityCurve(points: List<Pair<Long, Double>>): EquityCurveSummary {
        if (points.isEmpty()) {
            return EquityCurveSummary(emptyList(), emptyList(), 0.0, 0.0, 0.0)
        }
        var peak = 0.0
        var maxDd = 0.0
        val dd = ArrayList<Pair<Long, Double>>(points.size)
        for ((t, v) in points) {
            if (v > peak) peak = v
            val curDd = (v - peak).coerceAtMost(0.0)
            if (curDd < maxDd) maxDd = curDd
            dd.add(t to curDd)
        }
        return EquityCurveSummary(
            points = points,
            drawdownPoints = dd,
            peakUsd = peak,
            maxDrawdownUsd = maxDd,
            currentUsd = points.last().second
        )
    }

    fun bucketBars(buckets: List<ScorecardCopy.Bucket>): List<BucketBar> {
        val visible = buckets.filter { it.settledCount > 0 || it.key != ScorecardLedger.UNKNOWN_KEY }
        val maxAbsPnl = visible.maxOfOrNull { abs(it.pnlUsd) }?.coerceAtLeast(0.01) ?: 0.01
        return visible.map { b ->
            BucketBar(
                label = b.label,
                wins = b.wins,
                losses = b.losses,
                settledCount = b.settledCount,
                hitRate01 = b.hitRate,
                pnlUsd = b.pnlUsd,
                maxAbsPnlUsd = maxAbsPnl
            )
        }
    }

    fun calibrationCurve(picks: List<ScorecardLedger.PickRow>): List<CalibrationPoint> {
        val valid = picks.filter { !it.noBetWouldHave && it.aiPct != null && it.aiPct.isFinite() }
        if (valid.isEmpty()) return emptyList()
        val bands = listOf(
            "≤55%" to (0.0..55.0),
            "56–65%" to (55.0001..65.0),
            "66–75%" to (65.0001..75.0),
            "76–85%" to (75.0001..85.0),
            ">85%" to (85.0001..100.0)
        )
        return bands.mapNotNull { (label, range) ->
            val rows = valid.filter { (it.aiPct ?: 0.0) in range }
            if (rows.isEmpty()) null
            else CalibrationPoint(
                label = label,
                predicted01 = rows.map { ((it.aiPct ?: 50.0) / 100.0).coerceIn(0.0, 1.0) }.average(),
                actual01 = rows.count { it.won }.toDouble() / rows.size.toDouble(),
                count = rows.size
            )
        }
    }

    fun signalCardDistribution(cards: List<SignalCopy.Card>): List<EdgeBandCount> {
        if (cards.isEmpty()) return emptyList()
        val edgeRegex = Regex("""edge\s+([+-−]?\d+(?:\.\d+)?)\s*pts""", RegexOption.IGNORE_CASE)
        data class Parsed(val call: String, val absEdge: Double)
        val parsed = cards.map { c ->
            val raw = edgeRegex.find(c.modelLine)?.groupValues?.getOrNull(1)?.replace('−', '-')
            val edge = abs(raw?.toDoubleOrNull() ?: 0.0)
            Parsed(c.call.uppercase(Locale.US), edge)
        }
        val bands = listOf(
            "<3 pts" to { e: Double -> e < 3.0 },
            "3–6 pts" to { e: Double -> e >= 3.0 && e < 6.0 },
            "6–10 pts" to { e: Double -> e >= 6.0 && e < 10.0 },
            "10+ pts" to { e: Double -> e >= 10.0 }
        )
        return bands.map { (label, pred) ->
            val inBand = parsed.filter { pred(it.absEdge) }
            EdgeBandCount(
                label = label,
                upCount = inBand.count { it.call == "UP" },
                downCount = inBand.count { it.call == "DOWN" },
                noBetCount = inBand.count { it.call != "UP" && it.call != "DOWN" }
            )
        }
    }

    fun historySignalDistribution(signals: List<HistoryAssembler.SignalLine>): List<EdgeBandCount> {
        if (signals.isEmpty()) return emptyList()
        val bands = listOf(
            "<3 pp" to { e: Double -> e < 3.0 },
            "3–6 pp" to { e: Double -> e >= 3.0 && e < 6.0 },
            "6–10 pp" to { e: Double -> e >= 6.0 && e < 10.0 },
            "10+ pp" to { e: Double -> e >= 10.0 }
        )
        return bands.map { (label, pred) ->
            val inBand = signals.filter { pred(abs(it.edgePp)) }
            EdgeBandCount(
                label = label,
                upCount = inBand.count { SignalCopy.callLabel(it.side) == "UP" },
                downCount = inBand.count { SignalCopy.callLabel(it.side) == "DOWN" },
                noBetCount = inBand.count {
                    val c = SignalCopy.callLabel(it.side)
                    c != "UP" && c != "DOWN"
                }
            )
        }
    }
}

/**
 * Horizontal 0–100¢ book & fair-value gauge showing YES Bid..Ask spread,
 * market midpoint, and AI / Fair Value markers.
 */
@Composable
fun BookSpreadFairValueGauge(
    market: MarketUiModel,
    modifier: Modifier = Modifier
) {
    val gauge = remember(
        market.yesBid, market.yesAsk, market.noBid, market.noAsk,
        market.yesProbabilityPercent, market.aiYesPercent, market.importedModelPp, market.digitalFairPp
    ) {
        TelemetryChartMath.quoteGauge(market)
    }
    if (!gauge.hasBook) return
    val colors = DipTheme.colors
    val bg = MaterialTheme.colorScheme.surface
    val labelColor = checklistLabelColor(bg)
    val valueColor = checklistValueColor(bg)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surfaceAlt.copy(alpha = 0.65f), RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "UP PRICE TAPE (0–100¢)",
                style = MaterialTheme.typography.labelSmall,
                color = labelColor,
                fontWeight = FontWeight.Bold
            )
            val spreadPart = gauge.spreadCents?.let { String.format(Locale.US, "spread %.1f¢", it) }
            val askSumPart = gauge.combinedAskCents?.let { String.format(Locale.US, "asks %.0f¢", it) }
            val rightText = listOfNotNull(spreadPart, askSumPart).joinToString(" · ")
            if (rightText.isNotEmpty()) {
                Text(
                    rightText,
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor
                )
            }
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(22.dp)
        ) {
            val trackY = size.height / 2f
            val trackH = 6.dp.toPx()
            drawRoundRect(
                color = colors.border.copy(alpha = 0.55f),
                topLeft = Offset(0f, trackY - trackH / 2f),
                size = Size(size.width, trackH),
                cornerRadius = CornerRadius(trackH / 2f, trackH / 2f)
            )
            // 25 / 50 / 75c tick marks
            for (tick in listOf(25f, 50f, 75f)) {
                val tx = size.width * (tick / 100f)
                drawLine(
                    color = labelColor.copy(alpha = if (tick == 50f) 0.55f else 0.25f),
                    start = Offset(tx, 2.dp.toPx()),
                    end = Offset(tx, size.height - 2.dp.toPx()),
                    strokeWidth = if (tick == 50f) 1.5f else 1f
                )
            }
            fun xOf(cents: Double): Float = (size.width * (cents / 100.0).toFloat()).coerceIn(0f, size.width)
            val bid = gauge.yesBidCents
            val ask = gauge.yesAskCents
            if (bid != null && ask != null && ask >= bid) {
                val x0 = xOf(bid)
                val x1 = max(xOf(ask), x0 + 3.dp.toPx())
                drawRoundRect(
                    color = valueColor.copy(alpha = 0.28f),
                    topLeft = Offset(x0, trackY - trackH / 2f),
                    size = Size(x1 - x0, trackH),
                    cornerRadius = CornerRadius(trackH / 2f, trackH / 2f)
                )
            }
            if (bid != null) {
                drawCircle(colors.up, radius = 4.dp.toPx(), center = Offset(xOf(bid), trackY))
            }
            if (ask != null) {
                drawCircle(colors.down, radius = 4.dp.toPx(), center = Offset(xOf(ask), trackY))
            }
            gauge.midCents?.let { mid ->
                val xm = xOf(mid)
                drawLine(
                    color = valueColor,
                    start = Offset(xm, 3.dp.toPx()),
                    end = Offset(xm, size.height - 3.dp.toPx()),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
            gauge.aiYesCents?.let { ai ->
                val xa = xOf(ai)
                drawCircle(colors.accentBlue, radius = 5.dp.toPx(), center = Offset(xa, trackY))
                drawCircle(bg, radius = 2.dp.toPx(), center = Offset(xa, trackY))
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                gauge.yesBidCents?.let { String.format(Locale.US, "Bid %.0f¢", it) } ?: "Bid —",
                style = MaterialTheme.typography.labelSmall,
                color = colors.up,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                gauge.midCents?.let { String.format(Locale.US, "Mid %.1f¢", it) } ?: "Mid —",
                style = MaterialTheme.typography.labelSmall,
                color = labelColor
            )
            Text(
                gauge.aiYesCents?.let { String.format(Locale.US, "AI %.0f%%", it) } ?: "AI —",
                style = MaterialTheme.typography.labelSmall,
                color = colors.accentBlue,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                gauge.yesAskCents?.let { String.format(Locale.US, "Ask %.0f¢", it) } ?: "Ask —",
                style = MaterialTheme.typography.labelSmall,
                color = colors.down,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/**
 * Diverging horizontal bar chart for blend feature deviations (featureFair − mid in pp)
 * and ensemble sub-model comparisons (MLP, GBM, CNN, Digital FV, AI blend vs Market mid).
 */
@Composable
fun FeatureDriversChart(
    market: MarketUiModel,
    modifier: Modifier = Modifier
) {
    val drivers = remember(market.featureDevs) { TelemetryChartMath.driverBars(market.featureDevs) }
    val ensemble = remember(
        market.yesProbabilityPercent, market.aiYesPercent, market.importedModelPp,
        market.digitalFairPp, market.mlpPp, market.gbmPp, market.cnnPp, market.mmShadowPp
    ) {
        TelemetryChartMath.ensembleBars(market)
    }
    val gauges = remember(
        market.aiConfidence, market.pFill, market.uncertainty,
        market.uncertaintyPassed, market.midVolPp
    ) {
        TelemetryChartMath.microGauges(market)
    }
    if (drivers.isEmpty() && ensemble.size < 2 && gauges.isEmpty()) return

    val colors = DipTheme.colors
    val bg = MaterialTheme.colorScheme.surface
    val labelColor = checklistLabelColor(bg)
    val valueColor = checklistValueColor(bg)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surfaceAlt.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (gauges.isNotEmpty()) {
            Text(
                "SIGNAL TELEMETRY",
                style = MaterialTheme.typography.labelSmall,
                color = labelColor,
                fontWeight = FontWeight.Bold
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                gauges.forEach { g ->
                    val barColor = if (g.warning) colors.accentOrange else colors.accentBlue
                    Column(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(g.label, style = MaterialTheme.typography.labelSmall, color = labelColor)
                            Text(
                                g.valueText,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (g.warning) colors.accentOrange else valueColor,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Canvas(Modifier.fillMaxWidth().height(6.dp)) {
                            val r = size.height / 2f
                            drawRoundRect(
                                color = colors.border.copy(alpha = 0.5f),
                                size = size,
                                cornerRadius = CornerRadius(r, r)
                            )
                            val w = size.width * g.fillFraction.coerceIn(0f, 1f)
                            if (w > 0f) {
                                drawRoundRect(
                                    color = barColor,
                                    size = Size(w, size.height),
                                    cornerRadius = CornerRadius(r, r)
                                )
                            }
                        }
                    }
                }
            }
        }

        if (drivers.isNotEmpty()) {
            Text(
                "FEATURE DRIVERS (Δ VS MID)",
                style = MaterialTheme.typography.labelSmall,
                color = labelColor,
                fontWeight = FontWeight.Bold
            )
            drivers.forEach { d ->
                val isPositive = d.deltaPp >= 0.0
                val barColor = if (isPositive) colors.up else colors.down
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        d.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = labelColor,
                        modifier = Modifier.fillMaxWidth(0.24f)
                    )
                    Canvas(
                        Modifier
                            .weight(1f)
                            .height(10.dp)
                    ) {
                        val center = size.width / 2f
                        val h = 6.dp.toPx()
                        val top = (size.height - h) / 2f
                        drawRoundRect(
                            color = colors.border.copy(alpha = 0.35f),
                            topLeft = Offset(0f, top),
                            size = Size(size.width, h),
                            cornerRadius = CornerRadius(h / 2f, h / 2f)
                        )
                        drawLine(
                            color = labelColor.copy(alpha = 0.6f),
                            start = Offset(center, 0f),
                            end = Offset(center, size.height),
                            strokeWidth = 1.5f
                        )
                        val w = (center * d.fractionOfMax).coerceAtLeast(2f)
                        val startX = if (isPositive) center else (center - w).coerceAtLeast(0f)
                        drawRoundRect(
                            color = barColor,
                            topLeft = Offset(startX, top),
                            size = Size(w, h),
                            cornerRadius = CornerRadius(h / 2f, h / 2f)
                        )
                    }
                    Text(
                        String.format(Locale.US, "%+.1fpp", d.deltaPp),
                        style = MaterialTheme.typography.labelSmall,
                        color = Contrast.readable(barColor, bg, minRatio = Contrast.AA_LARGE),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        if (ensemble.size >= 2) {
            Text(
                "MODEL ENSEMBLE (UP PROBABILITY)",
                style = MaterialTheme.typography.labelSmall,
                color = labelColor,
                fontWeight = FontWeight.Bold
            )
            val mktMid = market.yesProbabilityPercent
            ensemble.forEach { item ->
                val isMkt = item.label == "Mkt mid"
                val barColor = when {
                    isMkt -> labelColor
                    mktMid != null && item.cents > mktMid + 0.5 -> colors.up
                    mktMid != null && item.cents < mktMid - 0.5 -> colors.down
                    else -> colors.accentBlue
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        item.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = labelColor,
                        modifier = Modifier.fillMaxWidth(0.24f)
                    )
                    Canvas(
                        Modifier
                            .weight(1f)
                            .height(8.dp)
                    ) {
                        val r = size.height / 2f
                        drawRoundRect(
                            color = colors.border.copy(alpha = 0.35f),
                            size = size,
                            cornerRadius = CornerRadius(r, r)
                        )
                        val w = (size.width * (item.cents / 100.0).toFloat()).coerceIn(0f, size.width)
                        if (w > 0f) {
                            drawRoundRect(
                                color = barColor,
                                size = Size(w, size.height),
                                cornerRadius = CornerRadius(r, r)
                            )
                        }
                        if (mktMid != null && mktMid.isFinite()) {
                            val mx = (size.width * (mktMid / 100.0).toFloat()).coerceIn(0f, size.width)
                            drawLine(
                                color = valueColor.copy(alpha = 0.7f),
                                start = Offset(mx, 0f),
                                end = Offset(mx, size.height),
                                strokeWidth = 1.5f
                            )
                        }
                    }
                    Text(
                        String.format(Locale.US, "%.0f¢", item.cents),
                        style = MaterialTheme.typography.labelSmall,
                        color = valueColor,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

/**
 * ChartDetailScreen companion panel: Spot − Target delta curve (above/below strike)
 * and implied book spread / combined bid sum over the 15m window.
 */
@Composable
fun WindowMicrostructureChart(
    points: List<BidPoint>,
    market: MarketUiModel,
    modifier: Modifier = Modifier
) {
    val series = remember(points, market.floorStrike) {
        TelemetryChartMath.spreadDeltaSeries(points, market.floorStrike)
    }
    val colors = DipTheme.colors
    val bg = colors.surface
    val labelColor = checklistLabelColor(bg)
    val valueColor = checklistValueColor(bg)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        BookSpreadFairValueGauge(market = market)
        FeatureDriversChart(market = market)

        val deltas = series.mapNotNull { p -> p.spotDeltaUsd?.let { p.tMs to it } }
        if (deltas.size >= 2) {
            val minD = min(deltas.minOf { it.second }, 0.0) - 2.0
            val maxD = max(deltas.maxOf { it.second }, 0.0) + 2.0
            val spanD = (maxD - minD).coerceAtLeast(1.0)
            val latestDelta = deltas.last().second
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "SPOT VS TARGET DELTA",
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    String.format(Locale.US, "%+.2f USD vs strike", latestDelta),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (latestDelta >= 0.0) colors.up else colors.down,
                    fontWeight = FontWeight.Bold
                )
            }
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(84.dp)
            ) {
                val t0 = deltas.first().first
                val t1 = deltas.last().first
                val spanT = (t1 - t0).coerceAtLeast(1L)
                fun xOf(t: Long) = size.width * ((t - t0).toFloat() / spanT.toFloat()).coerceIn(0f, 1f)
                fun yOf(v: Double) = size.height * (1f - ((v - minD) / spanD).toFloat()).coerceIn(0f, 1f)
                val zeroY = yOf(0.0)
                drawLine(
                    color = labelColor.copy(alpha = 0.5f),
                    start = Offset(0f, zeroY),
                    end = Offset(size.width, zeroY),
                    strokeWidth = 1.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
                )
                val path = Path()
                deltas.forEachIndexed { idx, (t, d) ->
                    val x = xOf(t)
                    val y = yOf(d)
                    if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                val lineColor = if (latestDelta >= 0.0) colors.up else colors.down
                drawPath(path, lineColor, style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round))
                drawCircle(lineColor, radius = 3.5.dp.toPx(), center = Offset(xOf(deltas.last().first), yOf(latestDelta)))
            }
        }

        val spreads = series.mapNotNull { p -> p.impliedSpreadCents?.let { p.tMs to it } }
        if (spreads.size >= 2) {
            val minS = 0f
            val maxS = (spreads.maxOf { it.second } + 2f).coerceAtLeast(6f)
            val spanS = (maxS - minS).coerceAtLeast(2f)
            val latestSpread = spreads.last().second
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "IMPLIED BOOK VIG / SPREAD (100¢ − BID SUM)",
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    String.format(Locale.US, "%.1f¢", latestSpread),
                    style = MaterialTheme.typography.labelSmall,
                    color = valueColor,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(64.dp)
            ) {
                val t0 = spreads.first().first
                val t1 = spreads.last().first
                val spanT = (t1 - t0).coerceAtLeast(1L)
                fun xOf(t: Long) = size.width * ((t - t0).toFloat() / spanT.toFloat()).coerceIn(0f, 1f)
                fun yOf(v: Float) = size.height * (1f - ((v - minS) / spanS).coerceIn(0f, 1f))
                for (i in 1..2) {
                    val gy = size.height * i / 3f
                    drawLine(labelColor.copy(alpha = 0.18f), Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1f)
                }
                val path = Path()
                spreads.forEachIndexed { idx, (t, s) ->
                    val x = xOf(t)
                    val y = yOf(s)
                    if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, colors.accentOrange, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
                drawCircle(colors.accentOrange, radius = 3.dp.toPx(), center = Offset(xOf(spreads.last().first), yOf(latestSpread)))
            }
        }
    }
}

/**
 * Enhanced cumulative P&L and Underwater Drawdown chart card.
 */
@Composable
fun EquityAndDrawdownChartCard(
    title: String,
    points: List<Pair<Long, Double>>,
    modifier: Modifier = Modifier
) {
    if (points.size < 2) return
    val colors = DipTheme.colors
    val summary = remember(points) { TelemetryChartMath.equityCurve(points) }
    val last = summary.currentUsd
    val lineColor = if (last >= 0.0) colors.up else colors.down

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(title, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
                Text(
                    ScorecardLedger.signedUsd(last),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = lineColor
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "Peak ${ScorecardLedger.signedUsd(summary.peakUsd)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary
                )
                Text(
                    "Max DD ${ScorecardLedger.signedUsd(summary.maxDrawdownUsd)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (summary.maxDrawdownUsd < -1e-9) colors.down else colors.textSecondary,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(104.dp)
                .padding(top = 4.dp)
        ) {
            val ys = points.map { it.second }
            val minY = min(ys.minOrNull() ?: 0.0, 0.0) - 1.0
            val maxY = max(ys.maxOrNull() ?: 0.0, 0.0) + 1.0
            val spanY = (maxY - minY).coerceAtLeast(1.0)
            val zeroY = size.height * (1f - ((0.0 - minY) / spanY).toFloat()).coerceIn(0f, 1f)

            for (i in 1..3) {
                val gy = size.height * i / 4f
                drawLine(colors.textSecondary.copy(alpha = 0.16f), Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1f)
            }
            drawLine(
                colors.textSecondary.copy(alpha = 0.45f),
                Offset(0f, zeroY),
                Offset(size.width, zeroY),
                strokeWidth = 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
            )

            val path = Path()
            val fillPath = Path()
            points.forEachIndexed { i, p ->
                val x = size.width * i / (points.size - 1).coerceAtLeast(1).toFloat()
                val y = size.height * (1f - ((p.second - minY) / spanY).toFloat()).coerceIn(0f, 1f)
                if (i == 0) {
                    path.moveTo(x, y)
                    fillPath.moveTo(x, zeroY)
                    fillPath.lineTo(x, y)
                } else {
                    path.lineTo(x, y)
                    fillPath.lineTo(x, y)
                }
                if (i == points.lastIndex) {
                    fillPath.lineTo(x, zeroY)
                    fillPath.close()
                }
            }
            drawPath(fillPath, lineColor.copy(alpha = 0.12f))
            drawPath(path, lineColor, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
            val lastY = size.height * (1f - ((last - minY) / spanY).toFloat()).coerceIn(0f, 1f)
            drawCircle(lineColor, radius = 3.5.dp.toPx(), center = Offset(size.width, lastY))
        }

        if (summary.maxDrawdownUsd < -0.01 && summary.drawdownPoints.size >= 2) {
            Text(
                "Drawdown from peak",
                style = MaterialTheme.typography.labelSmall,
                color = colors.textSecondary
            )
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
            ) {
                val minDd = (summary.maxDrawdownUsd * 1.15).coerceAtMost(-0.5)
                val ddPath = Path()
                val ddFill = Path()
                summary.drawdownPoints.forEachIndexed { i, (_, d) ->
                    val x = size.width * i / (summary.drawdownPoints.size - 1).coerceAtLeast(1).toFloat()
                    val y = size.height * (1f - ((d - minDd) / (0.0 - minDd)).toFloat()).coerceIn(0f, 1f)
                    if (i == 0) {
                        ddPath.moveTo(x, y)
                        ddFill.moveTo(x, 0f)
                        ddFill.lineTo(x, y)
                    } else {
                        ddPath.lineTo(x, y)
                        ddFill.lineTo(x, y)
                    }
                    if (i == summary.drawdownPoints.lastIndex) {
                        ddFill.lineTo(x, 0f)
                        ddFill.close()
                    }
                }
                drawLine(colors.textSecondary.copy(alpha = 0.35f), Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1f)
                drawPath(ddFill, colors.down.copy(alpha = 0.14f))
                drawPath(ddPath, colors.down, style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round))
            }
        }
    }
}

/**
 * Visual W/L stacked ratio bar and diverging P&L bar for Scorecard breakdown buckets.
 */
@Composable
fun BucketVisualBar(
    bucket: ScorecardCopy.Bucket,
    maxAbsPnlUsd: Double,
    modifier: Modifier = Modifier
) {
    if (bucket.settledCount <= 0) return
    val colors = DipTheme.colors
    val winFrac = (bucket.wins.toFloat() / bucket.settledCount.toFloat()).coerceIn(0f, 1f)
    val pnlFrac = if (maxAbsPnlUsd <= 1e-9) 0f else (abs(bucket.pnlUsd) / maxAbsPnlUsd).toFloat().coerceIn(0f, 1f)
    val pnlPositive = bucket.pnlUsd >= 0.0

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: W/L proportion bar
        Canvas(
            Modifier
                .weight(1f)
                .height(6.dp)
        ) {
            val r = size.height / 2f
            drawRoundRect(
                color = colors.down.copy(alpha = 0.55f),
                size = size,
                cornerRadius = CornerRadius(r, r)
            )
            val w = size.width * winFrac
            if (w > 0f) {
                drawRoundRect(
                    color = colors.up,
                    size = Size(w, size.height),
                    cornerRadius = CornerRadius(r, r)
                )
            }
        }
        // Right: Diverging P&L bar
        Canvas(
            Modifier
                .weight(1f)
                .height(6.dp)
        ) {
            val center = size.width / 2f
            val r = size.height / 2f
            drawRoundRect(
                color = colors.border.copy(alpha = 0.35f),
                size = size,
                cornerRadius = CornerRadius(r, r)
            )
            drawLine(
                color = colors.textSecondary.copy(alpha = 0.6f),
                start = Offset(center, 0f),
                end = Offset(center, size.height),
                strokeWidth = 1.5f
            )
            val w = center * pnlFrac
            if (w > 0.5f) {
                val x0 = if (pnlPositive) center else (center - w).coerceAtLeast(0f)
                drawRoundRect(
                    color = if (pnlPositive) colors.up else colors.down,
                    topLeft = Offset(x0, 0f),
                    size = Size(w, size.height),
                    cornerRadius = CornerRadius(r, r)
                )
            }
        }
    }
}

/**
 * Calibration curve & Brier comparison chart for ScorecardScreen.
 * Plots predicted probability vs actual empirical win rate against the 45° ideal line,
 * plus visual Brier score bars (Model vs Market, lower wins).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CalibrationAndBrierChartCard(
    honest: ScorecardMetrics.Honest?,
    picks: List<ScorecardLedger.PickRow>,
    modifier: Modifier = Modifier
) {
    val calPoints = remember(picks) { TelemetryChartMath.calibrationCurve(picks) }
    val brierReady = honest != null && honest.showBrier && honest.modelBrier != null && honest.marketBrier != null
    if (calPoints.isEmpty() && !brierReady) return

    val colors = DipTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (brierReady) {
            val h = honest ?: return@Column
            val modelB = h.modelBrier ?: 0.25
            val mktB = h.marketBrier ?: 0.25
            val sideB = h.sideBrier ?: modelB
            val maxB = maxOf(modelB, mktB, sideB, 0.30)
            Text(
                "Forecast Brier comparison (lower is better)",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary
            )
            listOf(
                Triple("Model P(UP)", modelB, if (modelB <= mktB) colors.up else colors.accentOrange),
                Triple("Picked side", sideB, colors.accentBlue),
                Triple("Kalshi market", mktB, colors.textSecondary)
            ).forEach { (label, brier, barColor) ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textSecondary,
                        modifier = Modifier.fillMaxWidth(0.28f)
                    )
                    Canvas(Modifier.weight(1f).height(8.dp)) {
                        val r = size.height / 2f
                        drawRoundRect(colors.border.copy(alpha = 0.35f), size = size, cornerRadius = CornerRadius(r, r))
                        val w = (size.width * (brier / maxB).toFloat()).coerceIn(0f, size.width)
                        drawRoundRect(barColor, size = Size(w, size.height), cornerRadius = CornerRadius(r, r))
                    }
                    Text(
                        String.format(Locale.US, "%.3f", brier),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        if (calPoints.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Reliability curve (AI forecast vs actual win %)",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textSecondary
                )
                Text(
                    "dashed = ideal",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary
                )
            }
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(110.dp)
            ) {
                val pad = 8.dp.toPx()
                val w = (size.width - pad * 2f).coerceAtLeast(1f)
                val h = (size.height - pad * 2f).coerceAtLeast(1f)
                fun xOf(p01: Double) = pad + w * ((p01 - 0.5) / 0.5).toFloat().coerceIn(0f, 1f)
                fun yOf(a01: Double) = pad + h * (1f - a01.toFloat().coerceIn(0f, 1f))

                // Horizontal grid at 0%, 50%, 100%
                for (f in listOf(0.0, 0.5, 1.0)) {
                    val gy = yOf(f)
                    drawLine(colors.border.copy(alpha = 0.4f), Offset(pad, gy), Offset(pad + w, gy), strokeWidth = 1f)
                }
                // Diagonal perfect calibration from (50%, 50%) to (100%, 100%)
                drawLine(
                    color = colors.textSecondary.copy(alpha = 0.55f),
                    start = Offset(xOf(0.5), yOf(0.5)),
                    end = Offset(xOf(1.0), yOf(1.0)),
                    strokeWidth = 1.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
                )
                val path = Path()
                calPoints.forEachIndexed { idx, pt ->
                    val x = xOf(pt.predicted01)
                    val y = yOf(pt.actual01)
                    if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                if (calPoints.size >= 2) {
                    drawPath(path, colors.accentBlue, style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round))
                }
                calPoints.forEach { pt ->
                    val x = xOf(pt.predicted01)
                    val y = yOf(pt.actual01)
                    val hitColor = if (pt.actual01 + 1e-6 >= pt.predicted01) colors.up else colors.accentOrange
                    drawCircle(hitColor, radius = 4.5.dp.toPx(), center = Offset(x, y))
                }
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                calPoints.forEach { pt ->
                    Text(
                        "${pt.label}: ${(pt.actual01 * 100).roundToInt()}% (n=${pt.count})",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textSecondary
                    )
                }
            }
        }
    }
}

/**
 * Signal edge & side distribution bar chart for SignalHistoryScreen and HistoryScreen Signals tab.
 */
@Composable
fun SignalEdgeDistributionCard(
    bands: List<TelemetryChartMath.EdgeBandCount>,
    modifier: Modifier = Modifier
) {
    val total = bands.sumOf { it.total }
    if (total <= 0) return
    val colors = DipTheme.colors
    val maxBand = bands.maxOf { it.total }.coerceAtLeast(1)
    val upTotal = bands.sumOf { it.upCount }
    val downTotal = bands.sumOf { it.downCount }
    val noBetTotal = bands.sumOf { it.noBetCount }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Signal edge distribution ($total)",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textPrimary,
                fontWeight = FontWeight.Bold
            )
            Text(
                "UP $upTotal · DOWN $downTotal · NO BET $noBetTotal",
                style = MaterialTheme.typography.labelSmall,
                color = colors.textSecondary
            )
        }
        bands.forEach { b ->
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    b.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary,
                    modifier = Modifier.fillMaxWidth(0.20f)
                )
                Canvas(
                    Modifier
                        .weight(1f)
                        .height(10.dp)
                ) {
                    val r = size.height / 2f
                    drawRoundRect(
                        color = colors.border.copy(alpha = 0.35f),
                        size = size,
                        cornerRadius = CornerRadius(r, r)
                    )
                    if (b.total > 0) {
                        val upW = size.width * (b.upCount.toFloat() / maxBand.toFloat())
                        val downW = size.width * (b.downCount.toFloat() / maxBand.toFloat())
                        val nbW = size.width * (b.noBetCount.toFloat() / maxBand.toFloat())
                        var x = 0f
                        if (upW > 0f) {
                            drawRect(colors.up, topLeft = Offset(x, 0f), size = Size(upW, size.height))
                            x += upW
                        }
                        if (downW > 0f) {
                            drawRect(colors.down, topLeft = Offset(x, 0f), size = Size(downW, size.height))
                            x += downW
                        }
                        if (nbW > 0f) {
                            drawRect(colors.textSecondary.copy(alpha = 0.55f), topLeft = Offset(x, 0f), size = Size(nbW, size.height))
                        }
                    }
                }
                Text(
                    "${b.total}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textPrimary,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

/**
 * Per-bet P&L sequence bar chart for History Bets tab.
 */
@Composable
fun BetPnlSequenceChart(
    bets: List<HistoryBet>,
    modifier: Modifier = Modifier
) {
    val settled = remember(bets) {
        bets.filter { val pnl = it.pnlUsd; pnl != null && pnl.isFinite() }
            .sortedBy { it.createdAtMs }
            .takeLast(40)
    }
    if (settled.size < 2) return
    val colors = DipTheme.colors
    val maxAbs = settled.maxOf { abs(it.pnlUsd ?: 0.0) }.coerceAtLeast(0.5)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "Per-bet P&L (last ${settled.size} settled)",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary
            )
            val wins = settled.count { (it.pnlUsd ?: 0.0) > 1e-9 }
            val losses = settled.count { (it.pnlUsd ?: 0.0) < -1e-9 }
            Text(
                "${wins}W / ${losses}L",
                style = MaterialTheme.typography.labelSmall,
                color = colors.textPrimary,
                fontWeight = FontWeight.SemiBold
            )
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
        ) {
            val midY = size.height / 2f
            drawLine(
                color = colors.border,
                start = Offset(0f, midY),
                end = Offset(size.width, midY),
                strokeWidth = 1f
            )
            val n = settled.size
            val slotW = size.width / n.toFloat()
            val barW = (slotW * 0.68f).coerceAtLeast(2f)
            settled.forEachIndexed { idx, bet ->
                val pnl = bet.pnlUsd ?: 0.0
                val frac = (abs(pnl) / maxAbs).toFloat().coerceIn(0.05f, 1f)
                val h = (midY - 2.dp.toPx()) * frac
                val x = idx * slotW + (slotW - barW) / 2f
                val top = if (pnl >= 0.0) midY - h else midY
                drawRoundRect(
                    color = if (pnl >= 0.0) colors.up else colors.down,
                    topLeft = Offset(x, top),
                    size = Size(barW, h),
                    cornerRadius = CornerRadius(2f, 2f)
                )
            }
        }
    }
}

/**
 * Visual composition bars for DataScreen StatsCard (coin split + YES/NO settlement split).
 */
@Composable
fun DataArchiveCompositionBars(
    stats: DataStats,
    modifier: Modifier = Modifier
) {
    val coinTotal = stats.btc + stats.eth + stats.sol
    val outcomeTotal = stats.yesSettled + stats.noSettled
    if (coinTotal <= 0 && outcomeTotal <= 0) return
    val colors = DipTheme.colors

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (outcomeTotal > 0) {
            val yesFrac = (stats.yesSettled.toFloat() / outcomeTotal.toFloat()).coerceIn(0f, 1f)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "YES settled ${(yesFrac * 100).roundToInt()}% (${stats.yesSettled})",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.up,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "NO settled ${((1f - yesFrac) * 100).roundToInt()}% (${stats.noSettled})",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.down,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Canvas(Modifier.fillMaxWidth().height(8.dp)) {
                val r = size.height / 2f
                drawRoundRect(colors.down.copy(alpha = 0.7f), size = size, cornerRadius = CornerRadius(r, r))
                val w = size.width * yesFrac
                if (w > 0f) {
                    drawRoundRect(colors.up, size = Size(w, size.height), cornerRadius = CornerRadius(r, r))
                }
            }
        }
        if (coinTotal > 0) {
            val btcFrac = stats.btc.toFloat() / coinTotal.toFloat()
            val ethFrac = stats.eth.toFloat() / coinTotal.toFloat()
            Canvas(Modifier.fillMaxWidth().height(6.dp)) {
                val r = size.height / 2f
                drawRoundRect(colors.border.copy(alpha = 0.4f), size = size, cornerRadius = CornerRadius(r, r))
                val wBtc = size.width * btcFrac
                val wEth = size.width * ethFrac
                val wSol = (size.width - wBtc - wEth).coerceAtLeast(0f)
                if (wBtc > 0f) drawRect(colors.accentOrange, topLeft = Offset(0f, 0f), size = Size(wBtc, size.height))
                if (wEth > 0f) drawRect(colors.accentBlue, topLeft = Offset(wBtc, 0f), size = Size(wEth, size.height))
                if (wSol > 0f) drawRect(colors.up, topLeft = Offset(wBtc + wEth, 0f), size = Size(wSol, size.height))
            }
        }
    }
}
