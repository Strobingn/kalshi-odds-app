package com.dirk.kalshiodds.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dirk.kalshiodds.prediction.ledger.LedgerReport
import com.dirk.kalshiodds.signal.scalper.PaperBet
import com.dirk.kalshiodds.signal.updown.UpDownLive
import com.dirk.kalshiodds.signal.updown.UpDownModel
import com.dirk.kalshiodds.ui.theme.DipTheme

/** What the AI tab shows of the ONNX up/down model. Built by [UpDownCardState.build]; the cards only draw it. */
data class UpDownCardState(
    val available: Boolean = false,
    val engine: String = "",
    val why: String = "",
    val ticker: String = "",
    val secIn: Int = -1,
    val up: Double? = null,
    val mid: Double? = null,
    val fair: Double? = null,
    val points: List<UpDownLive.Point> = emptyList(),
    val report: UpDownModel.Report? = null,
    val bettorOn: Boolean = true,
    val curve: List<Double> = emptyList(),
    val wins: Int = 0,
    val losses: Int = 0,
    val open: Int = 0,
    val net: Double = 0.0,
    val recent: List<String> = emptyList(),
    /** Settled model bets: the model's average chance for its side, and the share that won (both 0..1). */
    val claimed: Double? = null,
    val wonShare: Double? = null,
    /** The Paper AI's settled bets that carried the model's chance: (average model chance for the AI's side, share won, n). */
    val paperAiWithModel: Triple<Double, Double, Int>? = null
) {
    companion object {
        fun build(
            model: UpDownModel?, live: UpDownLive?, bets: List<PaperBet>, bettorOn: Boolean, paperAiBets: List<PaperBet>
        ): UpDownCardState {
            if (model == null) return UpDownCardState(available = false, why = "the model file could not be read")
            val r = live?.latest?.value
            val settled = bets.filter { it.result == "YES" || it.result == "NO" }.sortedBy { it.openedMs }
            var run = 0.0
            val curve = settled.map { run += it.pnlUsd ?: 0.0; Math.round(run * 100.0) / 100.0 }
            val withModel = paperAiBets.filter { (it.result == "YES" || it.result == "NO") && it.modelCents != null }
            return UpDownCardState(
                available = true,
                engine = model.engine,
                why = live?.why.orEmpty(),
                ticker = r?.ticker.orEmpty(),
                secIn = r?.secIn ?: -1,
                up = r?.up, mid = r?.mid, fair = r?.fair,
                points = live?.points?.value.orEmpty(),
                report = model.report,
                bettorOn = bettorOn,
                curve = curve,
                wins = settled.count { (it.pnlUsd ?: 0.0) > 0 },
                losses = settled.count { (it.pnlUsd ?: 0.0) <= 0 },
                open = bets.count { it.result == null },
                net = settled.sumOf { it.pnlUsd ?: 0.0 },
                recent = bets.takeLast(8).reversed().map { b ->
                    val res = when (b.result) {
                        null -> "open"
                        "VOID" -> "void"
                        else -> (if ((b.pnlUsd ?: 0.0) >= 0) "+" else "-") + "$" + "%.2f".format(kotlin.math.abs(b.pnlUsd ?: 0.0))
                    }
                    "${b.side} x${b.contracts} at ${"%.1f".format(b.price * 100)}c · model ${b.modelCents ?: b.confidence}% · $res"
                },
                claimed = settled.takeIf { it.isNotEmpty() }?.map { (it.modelCents ?: it.confidence.toDouble()) / 100.0 }?.average(),
                wonShare = settled.takeIf { it.isNotEmpty() }?.let { s -> s.count { it.side == it.result }.toDouble() / s.size },
                paperAiWithModel = withModel.takeIf { it.isNotEmpty() }?.let { s ->
                    Triple(s.map { it.modelCents!! / 100.0 }.average(), s.count { it.side == it.result }.toDouble() / s.size, s.size)
                }
            )
        }
    }
}

/** The up/down model's cards on the AI tab's dashboard. */
@Composable
fun UpDownCards(s: UpDownCardState, onBettor: ((Boolean) -> Unit)?) {
    if (!s.available) return
    val c = DipTheme.colors
    val modelColor = c.accentPurple
    val marketColor = c.accentBlue
    val fairColor = c.accentOrange
    fun pct(v: Double?) = v?.let { "${"%.1f".format(it * 100)}%" } ?: "—"

    AiCard("UP/DOWN MODEL (ONNX)", if (s.available) "Runs on: ${s.engine}" else "Not available: ${s.why}") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Model paper bets: once a minute, 100 contracts, when its chance beats the ask plus the fee by 1¢ (asks 10–85¢). Paper only.",
                fontSize = 11.sp, color = c.textSecondary, modifier = Modifier.weight(1f)
            )
            if (onBettor != null) Switch(checked = s.bettorOn, onCheckedChange = onBettor, modifier = Modifier.testTag("SWITCH_UPDOWN_BETTOR"))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Model: UP", pct(s.up), modelColor, if (s.secIn >= 0) "${900 - s.secIn}s left" else "no reading")
            StatTile("Market mid", pct(s.mid), marketColor, "the book")
            StatTile("Gap", s.up?.let { u -> s.mid?.let { m -> "${if (u >= m) "+" else ""}${"%.1f".format((u - m) * 100)}" } } ?: "—", c.textPrimary, "model − market, pts")
        }
        if (s.why.isNotBlank()) Text(s.why, fontSize = 10.sp, color = c.textSecondary)
    }

    AiCard("Chance of UP, this window", "Every second: the ONNX model, the market's mid and the math's fair price (0–100%)") {
        if (s.points.size < 2) {
            Text("Draws once the model has read a few seconds of a Bitcoin window", fontSize = 12.sp, color = c.textSecondary, modifier = Modifier.padding(vertical = 8.dp))
        } else {
            ThreeLines(s.points, modelColor, marketColor, fairColor)
            Legend(listOf("Model" to modelColor, "Market mid" to marketColor, "Fair (math)" to fairColor))
            val gaps = s.points.map { it.up - it.mid }
            Text(
                "This window so far: model and market ${"%.1f".format(gaps.map { kotlin.math.abs(it) }.average() * 100)} pts apart on average, at most ${"%.1f".format(gaps.maxOf { kotlin.math.abs(it) } * 100)}",
                fontSize = 10.sp, color = c.textSecondary
            )
        }
    }

    AiCard("Model paper bets", "Running net of its settled bets, after the taker fee") {
        LineChart(s.curve)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Net", fmtSignedUsd(s.net), if (s.net >= 0) c.up else c.down, "${s.wins}W · ${s.losses}L · ${s.open} open")
            StatTile("Said", pct(s.claimed), modelColor, "avg chance for its side")
            StatTile("Won", pct(s.wonShare), if ((s.wonShare ?: 0.0) >= (s.claimed ?: 1.0)) c.up else c.down, "share of settled bets")
        }
        s.paperAiWithModel?.let { (m, w, n) ->
            Text("Paper AI bets placed with the model in view: $n settled · model gave its side ${pct(m)} · won ${pct(w)}", fontSize = 11.sp, color = c.textSecondary)
        }
        if (s.recent.isEmpty()) Text("No model bets yet", fontSize = 12.sp, color = c.textSecondary)
        s.recent.forEach { Text(it, fontSize = 11.sp, color = c.textPrimary) }
    }

    s.report?.let { r ->
        AiCard(
            "How the model tested",
            "Walk-forward on ${r.testWindows} recorded windows over ${r.testDays} days (${r.firstDay} to ${r.lastDay}): each day predicted by a model trained only on the days before it"
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Log loss", "%.4f".format(r.model.logLoss), modelColor, "market ${"%.4f".format(r.market.logLoss)} · lower is better")
                StatTile("Accuracy", pct(r.model.accuracy), modelColor, "market ${pct(r.market.accuracy)}")
                StatTile("Calibration error", pct(r.model.ece), modelColor, "market ${pct(r.market.ece)}")
            }
            Text(
                verdict(r),
                fontSize = 11.sp, fontWeight = FontWeight.Bold, color = c.textPrimary
            )
            Text("Calibration: what it said (across) against how often UP happened (up)", fontSize = 11.sp, color = c.textSecondary)
            CalibrationChart(buckets(r.calibration), buckets(r.marketCalibration))
            Legend(listOf("Model" to com.dirk.kalshiodds.ui.theme.ArenaColors.current().series[0], "Market mid" to com.dirk.kalshiodds.ui.theme.ArenaColors.current().series[1]))
            if (r.byTimeLeft.isNotEmpty()) {
                Text("Log loss by time left (model · market)", fontSize = 11.sp, color = c.textSecondary)
                SignedBars(r.byTimeLeft.map { (label, m, k) ->
                    SignedBar(label, k - m, "${"%.3f".format(m)} · ${"%.3f".format(k)}", if (m < k) "model better" else "market better")
                }, labelWidth = 110.dp)
            }
            if (r.replay.isNotEmpty()) {
                Text("Replay: one 1-lot a minute when the model beat ask + fee by the cut", fontSize = 11.sp, color = c.textSecondary)
                SignedBars(r.replay.map { (cut, n, cents) ->
                    SignedBar("cut $cut", if (cents.isNaN()) 0.0 else cents, if (n == 0) "no bets" else "${"%+.2f".format(cents)}¢ · $n bets")
                }, labelWidth = 80.dp)
            }
        }
    }
}

private fun verdict(r: UpDownModel.Report): String {
    val d = r.model.logLoss - r.market.logLoss
    return when {
        d.isNaN() -> "No test numbers in the model file"
        kotlin.math.abs(d) < 0.003 -> "About as good as the market's own price, not better: a second opinion, not an edge"
        d < 0 -> "Beat the market's price on these windows"
        else -> "Slightly worse than the market's price on these windows"
    }
}

private fun buckets(cal: List<Triple<Double, Double, Int>>): List<LedgerReport.Bucket> = cal.map { (p, a, n) ->
    val lo = (p * 10).toInt().coerceIn(0, 9) * 10
    LedgerReport.Bucket(lo, lo + 10, n, p, a)
}

/** Three 0..1 lines across the 900 seconds of the window, with a 50% guide. */
@Composable
private fun ThreeLines(points: List<UpDownLive.Point>, model: Color, market: Color, fair: Color) {
    val c = DipTheme.colors
    Canvas(Modifier.fillMaxWidth().height(160.dp).testTag("CHART_UPDOWN_LIVE")) {
        val w = size.width
        val h = size.height
        for (g in listOf(0.25, 0.5, 0.75)) {
            val y = h * (1f - g.toFloat())
            drawLine(
                if (g == 0.5) c.textSecondary.copy(alpha = 0.6f) else c.border, Offset(0f, y), Offset(w, y),
                strokeWidth = 1.dp.toPx(), pathEffect = if (g == 0.5) PathEffect.dashPathEffect(floatArrayOf(6f, 6f)) else null
            )
        }
        fun line(sel: (UpDownLive.Point) -> Double, color: Color, width: Float) {
            val path = Path()
            points.forEachIndexed { i, p ->
                val x = w * p.sec / 900f
                val y = h * (1f - sel(p).coerceIn(0.0, 1.0).toFloat())
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color, style = Stroke(width = width))
        }
        line({ it.fair }, fair, 1.dp.toPx())
        line({ it.mid }, market, 1.5.dp.toPx())
        line({ it.up }, model, 2.dp.toPx())
    }
}
