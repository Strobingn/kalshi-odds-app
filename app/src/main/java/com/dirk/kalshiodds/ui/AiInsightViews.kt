package com.dirk.kalshiodds.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dirk.kalshiodds.llm.ExplainUi
import com.dirk.kalshiodds.llm.LocalLlmState
import com.dirk.kalshiodds.llm.LocalModel
import com.dirk.kalshiodds.llm.ReviewUi
import com.dirk.kalshiodds.signal.scalper.DayStats
import com.dirk.kalshiodds.signal.scalper.SavedReview
import com.dirk.kalshiodds.signal.scalper.ScalperResults
import com.dirk.kalshiodds.signal.scalper.TradeExplainer
import com.dirk.kalshiodds.ui.theme.DipTheme
import java.util.Locale
import kotlin.math.roundToInt

/** The ML scalper model's own report (from the model file), for the dashboard's charts. */
data class ModelReport(
    val title: String,
    val engine: String,
    val features: Int,
    val trees: Int,
    val maxDepth: Int?,
    val tested: String,
    val testLine: String,
    /** Test-day calibration: calibrated chance → share that made money. */
    val chanceBins: List<CalPoint>,
    /** Test-day expected cents → made cents, per bin. */
    val centsBins: List<CalPoint>,
    /** Profit lost on the validation days when each kept feature was shuffled ($ a contract-lot). */
    val moneyMakers: List<SignedBar>,
    val gain: List<Triple<String, Double, String>>,
    /** VALID total ($) at each step of the feature selection, most features first. */
    val selection: List<Double>,
    val selectionLabels: List<String>,
    /** Every engine tried for the trees (NPU, ONNX on the CPU, Kotlin), with its speed or why it was not used. */
    val engines: List<com.dirk.kalshiodds.signal.scalper.ScalperModel.EngineTiming> = emptyList()
)

// ---------------------------------------------------------------- on-phone model (Keys tab)

@Composable
fun OnPhoneModelCard(
    s: LocalLlmState,
    testText: String,
    testing: Boolean,
    onDownload: (LocalModel) -> Unit,
    onPickFile: () -> Unit,
    onCancel: () -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onPreferLocal: (Boolean) -> Unit,
    onTest: () -> Unit,
    speeds: List<Double>
) {
    val c = DipTheme.colors
    AiCard(
        "On-phone AI",
        "A small LLM that runs on this phone's CPU (llama.cpp, any .gguf model). It writes Explain and the daily review; nothing is sent anywhere."
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Model", s.installed?.removeSuffix(".gguf") ?: "none", if (s.installed != null) c.up else c.textSecondary,
                if (s.installed != null) "${s.installedBytes / 1_000_000} MB" else "download or pick one")
            StatTile("State", if (s.busy) "writing" else if (s.loaded) "loaded" else "unloaded", c.accentBlue, "frees itself when idle")
            StatTile("Speed", if (s.lastSpeed > 0) "${String.format(Locale.US, "%.1f", s.lastSpeed)} tok/s" else "–", c.textPrimary, "last answer")
        }
        if (speeds.size >= 2) LineChart(speeds, height = 70.dp) { String.format(Locale.US, "%.1f tok/s", it) }
        if (s.description.isNotBlank()) Text(s.description, fontSize = 11.sp, color = c.textSecondary)
        if (s.progress.isNotBlank()) {
            Text(s.progress, fontSize = 12.sp, color = c.textPrimary)
            s.fraction?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
                ?: LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Stop") }
        } else {
            for (m in LocalModel.CATALOG) {
                val have = s.files.contains(m.fileName)
                OutlinedButton(onClick = { if (have) onSelect(m.fileName) else onDownload(m) }, modifier = Modifier.fillMaxWidth().testTag("LLM_${m.id}")) {
                    Text(if (have) (if (s.installed == m.fileName) "✓ ${m.title}" else "Use ${m.title}") else "Download ${m.title}", fontSize = 12.sp)
                }
            }
            OutlinedButton(onClick = onPickFile, modifier = Modifier.fillMaxWidth()) { Text("Add a .gguf file from this phone", fontSize = 12.sp) }
        }
        if (s.message.isNotBlank()) Text(s.message, fontSize = 12.sp, color = c.textSecondary)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Use the on-phone model first", fontWeight = FontWeight.SemiBold, color = c.textPrimary)
                Text("For Explain and the daily review; your cloud keys (Groq, Cerebras, Gemini, Mistral) are the fallback", fontSize = 11.sp, color = c.textSecondary)
            }
            Switch(checked = s.preferLocal, onCheckedChange = onPreferLocal)
        }
        Button(onClick = onTest, enabled = s.installed != null && !testing && !s.busy, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Text(if (testing) "Writing…" else "Test the on-phone model")
        }
        if (testText.isNotBlank()) {
            Text(testText, fontSize = 12.sp, color = c.textPrimary,
                modifier = Modifier.fillMaxWidth().background(c.surfaceAlt, RoundedCornerShape(10.dp)).padding(10.dp))
        }
        for (f in s.files) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text((if (f == s.installed) "● " else "○ ") + f, Modifier.weight(1f), fontSize = 11.sp, color = c.textPrimary)
                if (f != s.installed) TextButton(onClick = { onSelect(f) }) { Text("Use", fontSize = 11.sp) }
                TextButton(onClick = { onDelete(f) }) { Text("Delete", color = c.down, fontSize = 11.sp) }
            }
        }
    }
}

// ---------------------------------------------------------------- Reviews tab

@Composable
fun AiReviewsContent(
    ui: ReviewUi,
    reviews: List<SavedReview>,
    autoOn: Boolean,
    onAuto: (Boolean) -> Unit,
    onRunToday: () -> Unit,
    onRunDay: (String) -> Unit,
    onCancel: () -> Unit,
    yesterday: String
) {
    val c = DipTheme.colors
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AiCard("Daily review", "An AI write-up of a day of the arena's paper trades, with the numbers behind it. Uses the on-phone model first.") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Review every day by itself", fontWeight = FontWeight.SemiBold, color = c.textPrimary)
                    Text("Writes up yesterday (UTC) once it is over", fontSize = 11.sp, color = c.textSecondary)
                }
                Switch(checked = autoOn, onCheckedChange = onAuto)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRunToday, enabled = !ui.running, modifier = Modifier.weight(1f).height(48.dp)) { Text("Review today so far") }
                OutlinedButton(onClick = { onRunDay(yesterday) }, enabled = !ui.running, modifier = Modifier.weight(1f).height(48.dp)) { Text("Review yesterday") }
            }
            if (ui.running) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text("Writing the review of ${ui.day}…", fontSize = 12.sp, color = c.textSecondary)
                TextButton(onClick = onCancel) { Text("Stop") }
            }
            ui.error?.let { Text(it, fontSize = 12.sp, color = c.down) }
            if (ui.running && ui.text.isNotBlank()) Text(ui.text, fontSize = 13.sp, color = c.textPrimary)
        }
        val history = reviews.filter { !it.partial }.sortedBy { it.day }
        if (history.size >= 2) {
            AiCard("Day by day", "Net after fees of every reviewed day") {
                SignedColumns(history.map { it.day.takeLast(5) }, history.map { it.stats.netUsd }, labelEvery = maxOf(1, history.size / 6))
                LineChart(history.runningFold(0.0) { a, r -> a + r.stats.netUsd }.drop(1))
                PercentBars(history.takeLast(10).reversed().map { Triple(it.day.takeLast(5), it.stats.winRate, "${it.stats.trades} trades") }, labelWidth = 60.dp)
            }
        }
        val live = ui.stats?.takeIf { ui.running || ui.text.isNotBlank() }
        val shown = (live?.let { listOf(it) } ?: emptyList()) + reviews.map { it.stats }
        val top = shown.firstOrNull()
        if (top != null) DayCharts(top)
        if (reviews.isEmpty() && !ui.running) {
            AiCard("No reviews yet", null) { Text("Tap Review today so far, or leave the switch on and one arrives each day.", fontSize = 12.sp, color = c.textSecondary) }
        }
        for (r in reviews) {
            AiCard("${r.day}${if (r.partial) " · so far" else ""}", "${r.source} · net ${fmtSignedUsd(r.stats.netUsd)} · ${r.stats.trades} trades") {
                Text(r.text, fontSize = 13.sp, color = c.textPrimary, maxLines = if (open == r.day) Int.MAX_VALUE else 4)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { open = if (open == r.day) null else r.day }) { Text(if (open == r.day) "Less" else "Charts and full text") }
                    TextButton(onClick = { onRunDay(r.day) }, enabled = !ui.running) { Text("Write again") }
                }
                if (open == r.day && r.stats != top) DayCharts(r.stats)
            }
        }
    }
}

/** Every chart of one reviewed day. */
@Composable
fun DayCharts(s: DayStats) {
    val c = DipTheme.colors
    AiCard("The numbers · ${s.day}", "Closed paper scalps of the UTC day, after Kalshi fees") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Net", fmtSignedUsd(s.netUsd), if (s.netUsd >= 0) c.up else c.down, "${s.trades} trades")
            StatTile("Won", "${(s.winRate * 100).roundToInt()}%", c.textPrimary, "${s.wins} of ${s.trades}")
            StatTile("Fees", fmtUsd(s.feesUsd), c.accentOrange, "taker fees paid")
        }
        Text("Through the day", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = c.textSecondary)
        LineChart(s.curve)
        val exits = s.byExit
        if (exits.isNotEmpty()) {
            Donut(
                exits.map { e ->
                    Triple(ScalperResults.kindLabel(e.label), e.trades.toFloat(), when (e.label) {
                        "TARGET" -> c.up; "STOP" -> c.down; "TIMEOUT" -> c.accentOrange; else -> c.accentBlue
                    })
                },
                center = "${s.trades}", centerSub = "trades", size = 100.dp
            )
            SignedBars(exits.map { SignedBar(ScalperResults.kindLabel(it.label), it.netUsd, fmtSignedUsd(it.netUsd), "${it.trades} trades") })
        }
    }
    AiCard("By strategy", "Best and worst of the day") {
        val winners = s.strategies.filter { it.netUsd > 0 }.take(8)
        val losers = s.strategies.filter { it.netUsd < 0 }.sortedBy { it.netUsd }.take(8)
        SignedBars((winners + losers).map {
            SignedBar(ScalperResults.strategyLabel(it.name), it.netUsd, fmtSignedUsd(it.netUsd),
                "${it.trades} · ${(it.winRate * 100).roundToInt()}% won · ${String.format(Locale.US, "%.2f", it.centsPerContract)}¢/ct")
        })
        PercentBars(s.strategies.sortedByDescending { it.trades }.take(10).map {
            Triple(ScalperResults.strategyLabel(it.name), it.winRate, "${it.wins}/${it.trades}")
        })
    }
    AiCard("When and at what price", "Net by minute of the window, entry price band and hour (UTC)") {
        SignedColumns(s.byMinute.map { it.label.removePrefix("min ") }, s.byMinute.map { it.netUsd })
        SignedBars(s.byPrice.map { SignedBar(it.label, it.netUsd, fmtSignedUsd(it.netUsd), "${it.trades} trades") }, labelWidth = 70.dp)
        SignedColumns(s.byHour.map { it.label }, s.byHour.map { it.netUsd }, labelEvery = 3)
    }
    if (s.mlBins.isNotEmpty()) {
        AiCard("ML scalper: predicted vs made", "Cents a contract per prediction bin; on the dashed line the model's cents were right") {
            CalibrationPlot(s.mlBins.map { CalPoint(it.predictedCents, it.madeCents, it.trades) }, { String.format(Locale.US, "%.2f¢", it) })
        }
    }
    if (s.best.isNotBlank()) Text("Best: ${s.best} · Worst: ${s.worst}", fontSize = 11.sp, color = c.textSecondary)
}

// ---------------------------------------------------------------- Explain this trade

@Composable
fun ExplainDialog(ui: ExplainUi, onClose: () -> Unit) {
    val c = DipTheme.colors
    val clip = LocalClipboardManager.current
    val f = ui.facts
    val t = f.trade
    AlertDialog(
        onDismissRequest = onClose,
        modifier = Modifier.testTag("EXPLAIN_DIALOG"),
        title = { Text("Why this trade", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${ScalperResults.strategyLabel(t.strategy)} · ${ScalperResults.sideLabel(t.side)} x${t.contracts} · " +
                    "${ScalperResults.cents(t.entry)} → ${if (t.kind == "SETTLED") (if (t.exit >= 0.5) "$1" else "$0") else ScalperResults.cents(t.exit)}",
                    fontSize = 12.sp, color = c.textPrimary)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatTile("Net", fmtSignedUsd(t.pnlUsd), if (t.pnlUsd >= 0) c.up else c.down, ScalperResults.kindLabel(t.kind))
                    StatTile("¢/contract", String.format(Locale.US, "%+.1f", f.pnlCentsPerContract), c.textPrimary, "after fees")
                    StatTile("Held", "${f.holdSeconds} s", c.accentBlue, f.enteredSecond?.let { "in at ${it / 60}:${"%02d".format(Locale.US, it % 60)}" } ?: "")
                }
                if (f.modelCents != null) {
                    f.modelChance?.let { p ->
                        PercentBars(listOf(Triple("Model chance", p, "${(p * 100).roundToInt()}% to make money")), labelWidth = 90.dp)
                    }
                    SignedBars(listOf(
                        SignedBar("Expected", f.modelCents, String.format(Locale.US, "%+.2f¢", f.modelCents)),
                        SignedBar("Its cut", f.modelTheta ?: 0.0, String.format(Locale.US, "%+.2f¢", f.modelTheta ?: 0.0)),
                        SignedBar("Made", f.pnlCentsPerContract, String.format(Locale.US, "%+.2f¢", f.pnlCentsPerContract))
                    ), labelWidth = 70.dp)
                    if (f.pushes.isNotEmpty()) {
                        Text("What pushed the model (toward making money → green)", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = c.textSecondary)
                        SignedBars(f.pushes.take(8).map {
                            SignedBar(it.label, it.push, String.format(Locale.US, "%+.3f", it.push), "${it.name} = ${String.format(Locale.US, "%.3g", it.value)}")
                        }, labelWidth = 130.dp)
                    }
                } else if (f.modelNote != null) {
                    Text("Model breakdown: ${f.modelNote}", fontSize = 11.sp, color = c.textSecondary)
                }
                f.order?.let { o ->
                    val book = listOfNotNull(
                        o.bid?.let { "bid ${ScalperResults.cents(it)}" }, o.ask?.let { "ask ${ScalperResults.cents(it)}" },
                        o.bidQty?.let { "${it.roundToInt()} on bid" }, o.askQty?.let { "${it.roundToInt()} on ask" },
                        o.move10?.let { String.format(Locale.US, "%+.1f¢ in 10 s", it * 100) }, o.move30?.let { String.format(Locale.US, "%+.1f¢ in 30 s", it * 100) }
                    )
                    if (book.isNotEmpty()) Text("At entry: " + book.joinToString(" · "), fontSize = 11.sp, color = c.textSecondary)
                }
                Text("AI explanation", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = c.textSecondary)
                if (ui.running && ui.text.isBlank()) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                if (ui.text.isNotBlank()) Text(ui.text, fontSize = 13.sp, color = c.textPrimary)
                ui.error?.let { Text("No explanation: $it", fontSize = 12.sp, color = c.down) }
                if (ui.source.isNotBlank()) Text("Written by ${ui.source}", fontSize = 10.sp, color = c.textSecondary)
                for (r in f.rules.take(3)) Text("• $r", fontSize = 10.sp, color = c.textSecondary)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
        dismissButton = {
            if (ui.text.isNotBlank()) TextButton(onClick = { clip.setText(AnnotatedString(ui.text + "\n\n" + TradeExplainer.prompt(f))) }) { Text("Copy") }
        }
    )
}

// ---------------------------------------------------------------- the ML scalper model (Dashboard tab)

@Composable
fun ModelReportCard(r: ModelReport) {
    val c = DipTheme.colors
    AiCard(r.title, "${r.features} features · ${r.trees} trees" + (r.maxDepth?.let { " · depth ≤ $it" } ?: "") + " · runs on ${r.engine}") {
        Text(r.tested, fontSize = 11.sp, color = c.textSecondary)
        Text(r.testLine, fontSize = 12.sp, color = c.textPrimary)
        if (r.engines.isNotEmpty()) {
            Text("Where the model runs: microseconds a prediction (warm)", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = c.textSecondary)
            SignedBars(r.engines.filter { it.ok && it.warmMicros > 0 }.map { e ->
                SignedBar(e.name, e.warmMicros, String.format(Locale.US, "%.0f µs", e.warmMicros), e.name)
            }, labelWidth = 150.dp)
            for (e in r.engines) {
                val open = if (e.openMs > 0) " · opened in ${e.openMs} ms" else ""
                Text("${if (e.ok) "✓" else "✗"} ${e.name}: ${e.note}$open", fontSize = 11.sp, color = if (e.ok) c.textPrimary else c.textSecondary)
            }
        }
        if (r.chanceBins.isNotEmpty()) {
            Text("Calibration on the test days: chance it gave → share that made money", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = c.textSecondary)
            CalibrationPlot(r.chanceBins, { "${(it * 100).roundToInt()}%" }, xName = "model chance", yName = "made money")
        }
        if (r.centsBins.isNotEmpty()) {
            Text("Expected cents → made cents (test days)", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = c.textSecondary)
            CalibrationPlot(r.centsBins, { String.format(Locale.US, "%.2f¢", it) })
        }
        if (r.moneyMakers.isNotEmpty()) {
            Text("The money makers: profit lost on the validation days when each is shuffled", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = c.textSecondary)
            SignedBars(r.moneyMakers, labelWidth = 130.dp)
        }
        if (r.gain.isNotEmpty()) {
            Text("Share of the trees' splitting gain", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = c.textSecondary)
            SignedBars(r.gain.take(12).map { (label, share, name) ->
                SignedBar(label, share, "${(share * 100).roundToInt()}%", name)
            }, labelWidth = 130.dp)
        }
        if (r.selection.size >= 2) {
            Text("Feature selection: validation profit as features were dropped (${r.selectionLabels.joinToString(" → ")})", fontSize = 11.sp, color = c.textSecondary)
            LineChart(r.selection, height = 80.dp)
        }
    }
}

/** Builds [ModelReport] from the model file's report block. Null for a file without one. */
fun modelReport(m: com.dirk.kalshiodds.signal.scalper.ScalperModel): ModelReport? {
    val rep = m.report ?: return null
    return runCatching {
        val test = rep.optJSONObject("test")
        val bins = rep.optJSONArray("calibration_bins")
        val chance = ArrayList<CalPoint>()
        val cents = ArrayList<CalPoint>()
        if (bins != null) for (i in 0 until bins.length()) {
            val b = bins.getJSONObject(i)
            chance += CalPoint(b.getDouble("p"), b.getDouble("won"), b.getInt("n"))
            cents += CalPoint(b.getDouble("cents_pred"), b.getDouble("cents"), b.getInt("n"))
        }
        val makers = rep.optJSONArray("money_makers")?.let { a ->
            (0 until a.length()).map { i -> a.getJSONArray(i) }.map { row ->
                val name = row.getString(0)
                val v = row.getDouble(1)
                SignedBar(TradeExplainer.label(name), v, String.format(Locale.US, "%+.1f$", v), name)
            }
        }.orEmpty()
        val gains = rep.optJSONArray("gain")?.let { a -> (0 until a.length()).map { i -> a.getJSONArray(i) } }.orEmpty()
        val gTotal = gains.sumOf { it.getDouble(1) }.takeIf { it > 0 } ?: 1.0
        val sel = rep.optJSONArray("selection")?.let { a -> (0 until a.length()).map { i -> a.getJSONObject(i) } }.orEmpty()
        ModelReport(
            title = "ML scalper model",
            engine = m.engine,
            engines = m.engines,
            features = m.features.size,
            trees = m.treeCount,
            maxDepth = rep.optInt("max_depth").takeIf { it > 0 },
            tested = "Calibrated chance it makes money, from depth-capped trees",
            testLine = test?.let {
                String.format(Locale.US, "Test days: its picks %+.3f¢ an order (%d orders, 95%% [%+.2f, %+.2f]) vs %+.3f¢ for every order; up %d of %d days; Brier %.4f, calibration error %.4f",
                    it.optDouble("mean_cents"), it.optInt("n"), it.optDouble("lo"), it.optDouble("hi"), it.optDouble("every_cents"),
                    it.optInt("up_days"), it.optInt("days"), it.optDouble("brier"), it.optDouble("ece"))
            }.orEmpty(),
            chanceBins = chance,
            centsBins = cents,
            moneyMakers = makers,
            gain = gains.map { Triple(TradeExplainer.label(it.getString(0)), it.getDouble(1) / gTotal, it.getString(0)) },
            selection = sel.map { it.getDouble("valid_total_usd") },
            selectionLabels = sel.map { "${it.getInt("n")}" }
        )
    }.getOrNull()
}
