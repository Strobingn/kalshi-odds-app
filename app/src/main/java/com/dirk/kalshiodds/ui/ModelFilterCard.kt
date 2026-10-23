package com.dirk.kalshiodds.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import com.dirk.kalshiodds.decision.ModelFilter
import com.dirk.kalshiodds.decision.QuantSession
import com.dirk.kalshiodds.decision.ResearchCompanion
import com.dirk.kalshiodds.ui.theme.DipTheme
import java.util.Locale

/** Paper model-filter twins, S24 telemetry, and the on-demand research companion. */
@Composable
internal fun ModelFilterCard(
    cards: List<ModelFilter.Card> = ModelFilter.cards(),
    onAskAi: (String, (String) -> Unit) -> Unit = { _, done -> done("The drivers above are the explanation. AI chat is not connected on this screen.") }
) {
    val c = DipTheme.colors
    var rev by remember { mutableIntStateOf(0) }
    val tel = remember(rev) { ModelFilter.telemetry() }
    val boards = remember(rev) { ModelFilter.scorecards() }
    var explain by remember { mutableStateOf<ResearchCompanion.Explain?>(null) }
    var journal by remember { mutableStateOf<ResearchCompanion.Journal?>(null) }
    var aiNote by remember { mutableStateOf("") }
    var emptyNote by remember { mutableStateOf("") }
    var retrainNote by remember { mutableStateOf("") }
    ArenaCard(Modifier.testTag("MODEL_FILTER")) {
        Text("Model filter", color = c.textPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "Control keeps its size and exits. +logistic and +lgbm only accept or reject that candidate. Size stays 1.0× unless the experiment is on. Stops do not wait on the model.",
            color = c.textSecondary,
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            "${tel.modelType} · ${tel.execPath} · graph opt ${tel.graphOpt} · intra_op_num_threads ${tel.threads} · input ${tel.inputShape}",
            color = c.textPrimary,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("MODEL_RUNTIME")
        )
        Text(
            "cold ${us(tel.coldUs)} · warm ${us(tel.warmUs)} · p50 ${us(tel.p50Us)} · p95 ${us(tel.p95Us)} · p99 ${us(tel.p99Us)}",
            color = c.textSecondary,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("MODEL_LATENCY")
        )
        Text(
            if (tel.auto) "Auto-select: ${tel.execPath}" else "Pinned: ${tel.execPath}",
            color = c.textSecondary,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.testTag("MODEL_AUTO")
        )
        if (tel.note.isNotBlank()) {
            Text(tel.note, color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
        }
        tel.benchmark.forEach { sample ->
            val line = if (sample.available) {
                "${QuantSession.label(sample.path)} · cold ${us(sample.coldUs)} · warm ${us(sample.warmUs)}"
            } else {
                "${QuantSession.label(sample.path)} · ${sample.note}"
            }
            Text(line, color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            ModeChip("Logistic") { ModelFilter.setMode(QuantSession.ModelMode.LOGISTIC); rev++ }
            ModeChip("TreeEnsemble") { ModelFilter.setMode(QuantSession.ModelMode.TREE_ENSEMBLE); rev++ }
            ModeChip("Ensemble") { ModelFilter.setMode(QuantSession.ModelMode.ENSEMBLE); rev++ }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            ModeChip("CPU Fast") { ModelFilter.selectPath(QuantSession.ExecPath.CPU_FAST); rev++ }
            ModeChip("QNN HTP") { ModelFilter.selectPath(QuantSession.ExecPath.QNN_HTP_BURST); rev++ }
            ModeChip("QNN GPU") { ModelFilter.selectPath(QuantSession.ExecPath.QNN_GPU); rev++ }
            ModeChip("Benchmark EPs", "BENCHMARK_EPS") {
                ModelFilter.benchmarkEps()
                rev++
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            ModeChip("Explain trade / score", "EXPLAIN_TRADE") {
                val next = ResearchCompanion.explainLatest()
                explain = next
                emptyNote = if (next == null) "No model decision yet." else ""
                journal = null
            }
            ModeChip("Session journal & patterns", "SESSION_JOURNAL") {
                journal = ResearchCompanion.journal()
                explain = null
            }
        }
        if (emptyNote.isNotBlank()) Text(emptyNote, color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
        explain?.let { row ->
            Text(row.title, color = c.textPrimary, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            Text(row.reason, color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
            if (row.drivers.isEmpty()) {
                Text("No model decision yet.", color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
            }
            row.drivers.forEach { d ->
                val sign = if (d.push >= 0.0) "+" else ""
                Text(
                    "${d.name} $sign${String.format(Locale.US, "%.2f", d.push)} · value ${String.format(Locale.US, "%.2f", d.value)}",
                    color = if (d.push >= 0.0) c.up else c.down,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            ModeChip("Ask AI", "RESEARCH_ASK") {
                aiNote = "Asking…"
                onAskAi(row.prompt) { aiNote = it }
            }
            if (aiNote.isNotBlank()) Text(aiNote, color = c.textSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("RESEARCH_NOTE"))
        }
        journal?.let { page ->
            Text(
                if (page.closed == 0) "No closed model trades yet." else "${page.closed} closed model trades",
                color = c.textPrimary,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold
            )
            page.regimes.forEach { r ->
                Text(
                    "${r.group} · ${r.name}: ${r.wins}/${r.n} wins · ${String.format(Locale.US, "%+.2f", r.netUsd)}",
                    color = if (r.netUsd >= 0.0) c.up else c.down,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            page.suggestions.forEach { line ->
                Text(line, color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
            }
            ModeChip("Retrain offline", "RESEARCH_RETRAIN") {
                retrainNote = ResearchCompanion.retrain(System.currentTimeMillis())
                rev++
            }
            if (retrainNote.isNotBlank()) Text(retrainNote, color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
        }
        boards.forEach { s ->
            Text(
                "${s.arm}: net ${String.format(Locale.US, "%+.2f", s.netUsd)} · ${String.format(Locale.US, "%+.2f", s.centsPerFill)}¢/fill · dd ${String.format(Locale.US, "%.2f", s.drawdownUsd)} · fill ${String.format(Locale.US, "%.0f", s.fillRate * 100)}% · partial ${String.format(Locale.US, "%.0f", s.partialRate * 100)}% · vs control ${String.format(Locale.US, "%+.2f", s.incrementalUsd)} · trades ${s.trades} · rejected ${s.rejected} · YES ${String.format(Locale.US, "%+.2f", s.yesNet)} · NO ${String.format(Locale.US, "%+.2f", s.noNet)} · taker ${s.takerN} · fresh ${if (s.freshnessMs < 0) "—" else "${s.freshnessMs} ms"}",
                color = if (s.netUsd >= 0.0) c.up else c.down,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("SCORECARD_${s.arm.uppercase()}")
            )
        }
        val head = cards.firstOrNull()
        if (head != null) {
            Text(
                "Training ${head.trainN} · last retrain ${head.lastTrainEt}" + if (head.warming) " · warming up" else "",
                color = c.textSecondary,
                style = MaterialTheme.typography.labelMedium
            )
            head.buckets.forEach { b ->
                Text(
                    "${b.name}: ${b.n} out-of-sample · ${String.format(Locale.US, "%+.2f", b.netUsd)}",
                    color = if (b.netUsd >= 0.0) c.up else c.down,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        cards.forEach { row ->
            Column(Modifier.fillMaxWidth()) {
                Text(row.label, color = c.textPrimary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                val score = row.score?.let { String.format(Locale.US, "%.2f", it) } ?: "—"
                Text(
                    "score $score · threshold ${String.format(Locale.US, "%.2f", row.threshold)}",
                    color = c.textSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(row.why, color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ModeChip(label: String, tag: String = "MODEL_" + label.uppercase().replace(' ', '_'), onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.testTag(tag)) {
        Text(label, color = DipTheme.colors.accentBlue, style = MaterialTheme.typography.labelMedium)
    }
}

private fun us(v: Long): String = if (v < 0L) "—" else String.format(Locale.US, "%d µs", v)
