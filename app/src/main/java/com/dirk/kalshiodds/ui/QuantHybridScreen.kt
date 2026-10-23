package com.dirk.kalshiodds.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.decision.LocalAnalyst
import com.dirk.kalshiodds.decision.ModelFilter
import com.dirk.kalshiodds.decision.QuantSession
import com.dirk.kalshiodds.decision.ResearchCompanion
import com.dirk.kalshiodds.decision.TreeEnsemble
import com.dirk.kalshiodds.llm.LlmProviders
import com.dirk.kalshiodds.ui.theme.DipTheme
import java.util.Locale
import kotlin.math.abs

data class ProviderQuota(val id: String, val label: String, val model: String, val used: Int, val cap: Int)

/** Compact Overview banner. The full layout is [QuantHybridScreen]. */
@Composable
internal fun ModelFilterCard(onOpenQuant: () -> Unit = {}) {
    val c = DipTheme.colors
    val tel = ModelFilter.telemetry()
    ArenaCard(Modifier.testTag("MODEL_FILTER")) {
        Text("Quant & Hybrid", color = c.textPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "Live filter is on-device. Groq explains a trade only when you ask.",
            color = c.textSecondary,
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            "${tel.modelType} · ${tel.execPath} · threads ${tel.threads}",
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
        Button(
            onClick = onOpenQuant,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = c.accentBlue, contentColor = c.onAccentBlue)
        ) { Text("Open Quant & Hybrid LLM →") }
    }
}

/** FieldOps Quant tab: live engine, three paper arms, and the on-demand journal. */
@Composable
internal fun QuantHybridScreen(
    onAskAi: (String, (String) -> Unit) -> Unit = { _, done -> done("The drivers above are the explanation. AI chat is not connected on this screen.") },
    quotas: List<ProviderQuota> = defaultQuotas(),
    decisions: List<ModelFilter.LogRow>? = null
) {
    val c = DipTheme.colors
    var rev by remember { mutableIntStateOf(0) }
    val tel = remember(rev) { ModelFilter.telemetry() }
    val boards = remember(rev) { ModelFilter.scorecards() }
    val sourceKey = decisions?.joinToString(",") { "${it.id}:${it.atMs}:${it.score}:${it.entered}:${it.outcomeUsd}" } ?: "live:$rev"
    val fullLog = remember(sourceKey) { decisions ?: ModelFilter.researchLog() }
    val log = remember(sourceKey) { fullLog.takeLast(12).asReversed() }
    var picked by remember { mutableStateOf<String?>(null) }
    val row = log.firstOrNull { it.id == picked } ?: log.firstOrNull()
    val explain = row?.let { ResearchCompanion.explain(it) }
    var aiNote by remember { mutableStateOf("") }
    var journalNote by remember { mutableStateOf("") }
    var retrainNote by remember { mutableStateOf("") }
    var journal by remember { mutableStateOf<ResearchCompanion.Journal?>(null) }
    val cards = remember(rev) { ModelFilter.cards() }
    fun sessionJournal() = if (decisions != null) ResearchCompanion.journalOf(decisions, ModelFilter.threshold()) else ResearchCompanion.journal()

    Column(Modifier.fillMaxWidth().testTag("MODEL_FILTER"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionLabel("Path 1 · Live on-device · under 2 ms · no LLM")
        ArenaCard {
            Text("Engine · S24 Ultra", color = c.textPrimary, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Badge(modelBadge(tel.modelType), c.accentBlue, c.onAccentBlue)
                Badge(execLabel(tel.execPath), c.surfaceAlt, c.textPrimary)
                Badge(if (QuantSession.isWarm()) "WARM" else "COLD", if (QuantSession.isWarm()) c.up else c.spot, c.onUp)
            }
            Text(
                "${tel.modelType} · ${tel.execPath} · graph opt ${tel.graphOpt} · intra_op_num_threads ${tel.threads} · input ${tel.inputShape}",
                color = c.textSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("MODEL_RUNTIME")
            )
            Text(
                "cold ${us(tel.coldUs)} · warm ${us(tel.warmUs)} · p50 ${us(tel.p50Us)} · p95 ${us(tel.p95Us)} · p99 ${us(tel.p99Us)}",
                color = c.textSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("MODEL_LATENCY")
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricTile("Cold", us(tel.coldUs), Modifier.weight(1f))
                MetricTile("Warm", us(tel.warmUs), Modifier.weight(1f))
                MetricTile("p50", us(tel.p50Us), Modifier.weight(1f))
                MetricTile("p95", us(tel.p95Us), Modifier.weight(1f))
                MetricTile("p99", us(tel.p99Us), Modifier.weight(1f))
            }
            Text(if (tel.auto) "Auto-select: ${tel.execPath}" else "Pinned: ${tel.execPath}", color = c.textSecondary, modifier = Modifier.testTag("MODEL_AUTO"))
            Text("Model", color = c.textSecondary, style = MaterialTheme.typography.labelSmall)
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                listOf(
                    QuantSession.ModelMode.LOGISTIC to "Logistic",
                    QuantSession.ModelMode.TREE_ENSEMBLE to "LightGBM ${TreeEnsemble.TRAIN_TREES}t·d${TreeEnsemble.TRAIN_DEPTH}",
                    QuantSession.ModelMode.ENSEMBLE to "Ensemble",
                    QuantSession.ModelMode.ONNX to "ONNX"
                ).forEach { (mode, label) ->
                    val selected = tel.modelType.equals(label, true) || (mode == QuantSession.ModelMode.TREE_ENSEMBLE && tel.modelType == "TreeEnsemble")
                    Pill(label, selected) { ModelFilter.setMode(mode); rev++ }
                }
            }
            Text("Execution", color = c.textSecondary, style = MaterialTheme.typography.labelSmall)
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                listOf(
                    QuantSession.ExecPath.CPU_FAST to "CPU Fast 1-Thread",
                    QuantSession.ExecPath.QNN_HTP_BURST to "QNN HTP Burst",
                    QuantSession.ExecPath.QNN_GPU to "QNN GPU"
                ).forEach { (path, label) ->
                    Pill(label, tel.execPath == QuantSession.label(path)) { ModelFilter.selectPath(path); rev++ }
                }
            }
            Button(
                onClick = { ModelFilter.benchmarkEps(); rev++ },
                modifier = Modifier.fillMaxWidth().testTag("BENCHMARK_EPS"),
                colors = ButtonDefaults.buttonColors(containerColor = c.accentBlue, contentColor = c.onAccentBlue)
            ) { Text("Benchmark EPs") }
            tel.benchmark.forEach { sample ->
                val warm = if (sample.available) sample.warmUs.coerceAtLeast(0L) else 0L
                val cold = if (sample.available) sample.coldUs.coerceAtLeast(0L) else 0L
                val max = maxOf(cold, warm, 1L).toFloat()
                Text(
                    if (sample.available) "${QuantSession.label(sample.path)} · cold ${us(cold)} · warm ${us(warm)}"
                    else "${QuantSession.label(sample.path)} · ${sample.note}",
                    color = c.textSecondary, style = MaterialTheme.typography.bodySmall
                )
                Bar(warm / max, c.accentBlue)
                Bar(cold / max, c.spot)
            }
        }

        ArenaCard {
            Text("Three paper arms", color = c.textPrimary, fontWeight = FontWeight.SemiBold)
            Text("Same stream, same exits, size 1.0× unless you turn an experiment on.", color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
            boards.forEach { s -> ArmRow(s) }
            Text("Out of sample by time left", color = c.textSecondary, style = MaterialTheme.typography.labelMedium)
            oosBars(cards, fullLog).forEach { bar ->
                Text("${bar.label} · ${bar.n} · ${money(bar.netUsd)}", color = if (bar.netUsd >= 0) c.up else c.down, style = MaterialTheme.typography.bodySmall)
                Bar(if (bar.n == 0) 0.08f else (0.15f + (bar.n.coerceAtMost(20) / 20f)), if (bar.netUsd >= 0) c.up else c.down)
            }
            val under5 = cards.firstOrNull()?.buckets?.firstOrNull { it.name == "<5 min" }
            if (fullLog.none { it.outcomeUsd != null && it.features.isNotEmpty() } && under5 != null && under5.n > 0) {
                Text("Under 5m is not split yet · ${under5.n} · ${money(under5.netUsd)}", color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { ModelFilter.setSizeExperiment(ModelFilter.sizeMode() != ModelFilter.SizeMode.SCALAR); rev++ },
                    modifier = Modifier.weight(1f)
                ) { Text(if (ModelFilter.sizeMode() == ModelFilter.SizeMode.SCALAR) "Size 0–1.5× on" else "Size 1.0×", textAlign = TextAlign.Center) }
                OutlinedButton(onClick = {
                    ModelFilter.setSizeMode(if (ModelFilter.sizeMode() == ModelFilter.SizeMode.KELLY) ModelFilter.SizeMode.FIXED else ModelFilter.SizeMode.KELLY)
                    rev++
                }, modifier = Modifier.weight(1f)) {
                    Text(if (ModelFilter.sizeMode() == ModelFilter.SizeMode.KELLY) "Kelly sizing on" else "Kelly sizing off", textAlign = TextAlign.Center)
                }
            }
            Text("Forced exit", color = c.textSecondary, style = MaterialTheme.typography.labelSmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(60.0 to "60s", 75.0 to "75s", 90.0 to "90s").forEach { (sec, label) ->
                    Pill("Exit $label", ModelFilter.exitBeforeSeconds() == sec) { ModelFilter.setExitBeforeSeconds(sec); rev++ }
                }
            }
        }

        ArenaCard {
            Text("Live gates", color = c.textPrimary, fontWeight = FontWeight.SemiBold)
            cards.forEach { rowCard ->
                val score = rowCard.score ?: 0.0
                val frac = ((score.coerceIn(0.0, 1.5)) / 1.5).toFloat()
                val enter = rowCard.why.startsWith("enter")
                val predicted = fullLog.lastOrNull { it.twinId == rowCard.id }?.expectedNet
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(rowCard.label, color = c.textPrimary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                        Bar(frac, if (enter) c.up else c.spot)
                        Text(
                            if (predicted == null) "Predicted net —" else "Predicted net ${cents(predicted)} after fees",
                            color = c.textSecondary, style = MaterialTheme.typography.bodySmall
                        )
                        Text(rowCard.why, color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                    Badge(if (enter) "ENTER" else "SKIP", if (enter) c.up else c.spot, c.onUp)
                }
            }
        }

        SectionLabel("Path 2 · On-demand journal · Groq, then Mistral, then Gemini · 60s")
        ArenaCard {
            Text("Companion", color = c.textPrimary, fontWeight = FontWeight.SemiBold)
            Text("Groq · ${LlmProviders.DEFAULT_GROQ_MODEL}", color = c.textPrimary, style = MaterialTheme.typography.bodyMedium)
            Text("Automatic calls wait 60s. This button does not run in the live loop.", color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
            quotas.forEach { q ->
                val frac = if (q.cap <= 0) 0f else (q.used.toFloat() / q.cap).coerceIn(0f, 1f)
                val over = q.cap > 0 && q.used >= q.cap
                Text("${q.label} ${q.used} / ${q.cap}", color = if (over) c.down else c.textSecondary, style = MaterialTheme.typography.bodySmall)
                Bar(frac, if (over) c.down else if (frac > 0.8f) c.spot else c.up)
            }
        }

        ArenaCard {
            Text("Decision log", color = c.textPrimary, fontWeight = FontWeight.SemiBold)
            if (log.isEmpty()) Text("No model decision yet.", color = c.textSecondary, modifier = Modifier.testTag("EXPLAIN_TRADE"))
            log.forEach { item ->
                val on = item.id == row?.id
                val outcome = item.outcomeUsd?.let { money(it) } ?: if (item.entered) "open" else "skip"
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (on) c.accentBlue else c.surfaceAlt)
                        .clickable { picked = item.id }
                        .testTag("DECISION_${item.id}")
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${item.twinId} · ${item.side} · ${fmt(item.score)}",
                        color = if (on) c.onAccentBlue else c.textPrimary,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "${cents(item.expectedNet)} · $outcome",
                        color = if (on) c.onAccentBlue else c.textSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            explain?.let { ex ->
                Text(ex.title, color = c.textPrimary, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("EXPLAIN_TRADE"))
                val peak = ex.drivers.maxOfOrNull { abs(it.push) }?.coerceAtLeast(0.01) ?: 1.0
                ex.drivers.forEach { d ->
                    Text("${d.name} ${signed(d.push)}", color = if (d.push >= 0) c.up else c.down, style = MaterialTheme.typography.bodySmall)
                    Bar((abs(d.push) / peak).toFloat().coerceIn(0.05f, 1f), if (d.push >= 0) c.up else c.down)
                }
            }
            Button(
                onClick = {
                    val prompt = explain?.prompt
                    if (prompt == null) {
                        aiNote = "No model decision yet."
                    } else {
                        aiNote = "Asking Groq…"
                        onAskAi(prompt) { aiNote = it }
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("RESEARCH_ASK"),
                colors = ButtonDefaults.buttonColors(containerColor = c.up, contentColor = c.onUp)
            ) { Text("Ask Groq to Explain This Trade") }
            if (aiNote.isNotBlank()) {
                Text(
                    aiNote,
                    color = c.textPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("RESEARCH_NOTE").fillMaxWidth().background(c.surfaceAlt, RoundedCornerShape(8.dp)).padding(8.dp)
                )
            }
        }

        ArenaCard {
            Text("Session journal", color = c.textPrimary, fontWeight = FontWeight.SemiBold)
            OutlinedButton(onClick = { journal = sessionJournal() }, modifier = Modifier.testTag("SESSION_JOURNAL")) { Text("Session journal & patterns") }
            journal?.let { page ->
                Text(if (page.closed == 0) "No closed model trades yet." else "${page.closed} closed", color = c.textSecondary)
                page.regimes.forEach { r ->
                    Text("${r.group} · ${r.name}: ${r.wins}/${r.n} · ${money(r.netUsd)}", color = if (r.netUsd >= 0) c.up else c.down, style = MaterialTheme.typography.bodySmall)
                }
                page.suggestions.forEach { Text(it, color = c.textSecondary, style = MaterialTheme.typography.bodySmall) }
            }
            Button(
                onClick = {
                    val page = journal ?: sessionJournal().also { journal = it }
                    journalNote = "Asking Groq…"
                    onAskAi(journalPrompt(page)) { journalNote = it }
                },
                modifier = Modifier.fillMaxWidth().testTag("RESEARCH_JOURNAL_ASK"),
                colors = ButtonDefaults.buttonColors(containerColor = c.accentBlue, contentColor = c.onAccentBlue)
            ) { Text("Ask AI for Session Summary & Retrain Advice") }
            if (journalNote.isNotBlank()) Text(journalNote, color = c.textPrimary, style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(
                onClick = { retrainNote = ResearchCompanion.retrain(System.currentTimeMillis()); rev++ },
                modifier = Modifier.testTag("RESEARCH_RETRAIN")
            ) { Text("Retrain offline") }
            if (retrainNote.isNotBlank()) Text(retrainNote, color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
        }

        StopPolicyCard()
        LocalLlmCard { rev++ }
    }
}

@Composable
private fun StopPolicyCard() {
    val c = DipTheme.colors
    val win = ModelFilter.rawNetCents(0.50, 0.58)
    val loss = ModelFilter.rawNetCents(0.50, 0.45)
    val wr = ModelFilter.breakEvenWinRate(win, loss) * 100.0
    val three = ModelFilter.rawNetCents(0.50, 0.53)
    ArenaCard(Modifier.testTag("STOP_POLICY")) {
        Text("Stop policy and fee break-even", color = c.textPrimary, fontWeight = FontWeight.SemiBold)
        Text("Policy ${ModelFilter.POLICY_VERSION}", color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
        Text("Training target: net cents per contract = 100 × (sale − cost − entry fee − exit fee) / contracts. NO flips the directional features.", color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
        Text("At a 50¢ entry the 49¢/50¢ book is a 1¢ spread.", color = c.textPrimary, style = MaterialTheme.typography.bodySmall)
        Text("Strict 1% stop = ${fmt(0.50)}¢ · already inside that 1¢ spread", color = c.down, style = MaterialTheme.typography.bodySmall)
        Text("Strict 4% stop = ${fmt(2.0)}¢ · 3¢ trail = ${fmt(3.0)}¢ · 5¢ stop = ${fmt(5.0)}¢", color = c.textPrimary, style = MaterialTheme.typography.bodySmall)
        Text("50¢ taker, +8¢ target / −5¢ stop → win ${fmt(win)}¢ vs loss ${fmt(loss)}¢ → required win rate ${fmt(wr)}%", color = if (wr >= 50) c.spot else c.up, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("FEE_BREAKEVEN"))
        Text("50¢ → 53¢ is ${fmt(three)}¢ net after unrounded taker fees.", color = c.down, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun LocalLlmCard(bump: () -> Unit) {
    val c = DipTheme.colors
    val eng = LocalAnalyst.engine()
    ArenaCard(Modifier.testTag("LOCAL_LLM")) {
        Text("Built-in LLM · LiteRT-LM", color = c.textPrimary, fontWeight = FontWeight.SemiBold)
        Text(
            if (LocalAnalyst.imported()) "Active ${LocalAnalyst.activeName()} · ${LocalAnalyst.sha256()}"
            else "No .litertlm imported · built-in quant analyst",
            color = c.textSecondary,
            style = MaterialTheme.typography.bodySmall
        )
        LocalAnalyst.SHORTLIST.forEach { row ->
            val speed = listOfNotNull(row.cpuTokPerSec?.let { "$it tok/s CPU" }, row.gpuTokPerSec?.let { "$it tok/s GPU" }).joinToString(" / ")
            val on = row.name == LocalAnalyst.activeName()
            Pill("${row.role} · ${row.name}${if (speed.isBlank()) "" else " · $speed"}", on) {
                LocalAnalyst.setActiveName(row.name)
                bump()
            }
        }
        Text("Engine · LiteRT-LM 0.18 · maxNumTokens ${eng.maxNumTokens} · thinking off · tools off · one request", color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            Pill("CPU", eng.backend == LocalAnalyst.Backend.CPU) { LocalAnalyst.setBackend(LocalAnalyst.Backend.CPU); bump() }
            Pill("GPU", eng.backend == LocalAnalyst.Backend.GPU) { LocalAnalyst.setBackend(LocalAnalyst.Backend.GPU); bump() }
            Pill("Output 256", eng.maxOutputTokens == 256) { LocalAnalyst.setMaxOutputTokens(256); bump() }
            Pill("Output 384", eng.maxOutputTokens == 384) { LocalAnalyst.setMaxOutputTokens(384); bump() }
        }
        OutlinedButton(onClick = { LocalAnalyst.setPauseDuringActiveEntry(!eng.pauseDuringActiveEntry); bump() }, modifier = Modifier.fillMaxWidth()) {
            Text(if (eng.pauseDuringActiveEntry) "Pause local model during an entry" else "Local model may run during an entry")
        }
        Text("SHA-256 verifies an imported .litertlm. Until that file is on the phone, Groq and the quant analyst answer.", color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun researchQuotas(): List<ProviderQuota> {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as? com.dirk.kalshiodds.KalshiOddsApp
    val watch = app?.let { runCatching { it.container.aiWatch }.getOrNull() } ?: return defaultQuotas()
    val now = System.currentTimeMillis()
    return companionQuotas({ watch.callsFor(it, "", now) }, { watch.dailyCap(it, "") })
}

internal fun companionQuotas(used: (String) -> Int, cap: (String) -> Int): List<ProviderQuota> = listOf(
    ProviderQuota(LlmProviders.GROQ, "Groq", LlmProviders.DEFAULT_GROQ_MODEL, used(LlmProviders.GROQ), cap(LlmProviders.GROQ)),
    ProviderQuota(LlmProviders.MISTRAL, "Mistral", LlmProviders.MISTRAL_DEFAULT, used(LlmProviders.MISTRAL), cap(LlmProviders.MISTRAL)),
    ProviderQuota(LlmProviders.GEMINI, "Gemini", LlmProviders.GEMINI_DEFAULT, used(LlmProviders.GEMINI), cap(LlmProviders.GEMINI))
)

@Composable
private fun ArmRow(s: ModelFilter.Scorecard) {
    val c = DipTheme.colors
    Column(Modifier.fillMaxWidth().testTag("SCORECARD_${s.arm.uppercase()}").padding(vertical = 4.dp)) {
        Text(s.arm.replaceFirstChar { it.uppercase() }, color = c.textPrimary, fontWeight = FontWeight.SemiBold)
        Text(
            "net ${money(s.netUsd)} · ${signed(s.centsPerFill)}¢/fill · dd ${money(s.drawdownUsd)} · fill ${pct(s.fillRate)} · partial ${pct(s.partialRate)}",
            color = if (s.netUsd >= 0) c.up else c.down, style = MaterialTheme.typography.bodySmall
        )
        Text(
            "vs control ${money(s.incrementalUsd)} · trades ${s.trades} / rejected ${s.rejected} · YES ${money(s.yesNet)} · NO ${money(s.noNet)} · fresh ${if (s.freshnessMs < 0) "—" else "${s.freshnessMs} ms"}",
            color = c.textSecondary, style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = DipTheme.colors.spot, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
}

@Composable
private fun Badge(text: String, color: Color, ink: Color) {
    Text(
        text,
        color = ink,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(color).padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

@Composable
private fun Pill(text: String, on: Boolean, tag: String? = null, onClick: () -> Unit) {
    val c = DipTheme.colors
    Text(
        text,
        color = if (on) c.onAccentBlue else c.textPrimary,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier
            .padding(end = 8.dp, bottom = 4.dp)
            .clip(RoundedCornerShape(99.dp))
            .background(if (on) c.accentBlue else c.surfaceAlt)
            .then(if (tag == null) Modifier else Modifier.testTag(tag))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}

@Composable
private fun MetricTile(label: String, value: String, modifier: Modifier) {
    val c = DipTheme.colors
    Column(modifier.clip(RoundedCornerShape(8.dp)).background(c.surfaceAlt).padding(6.dp)) {
        Text(label, color = c.textSecondary, style = MaterialTheme.typography.labelSmall)
        Text(value, color = c.textPrimary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Bar(fraction: Float, color: Color) {
    Box(Modifier.fillMaxWidth().padding(vertical = 2.dp).height(6.dp).clip(RoundedCornerShape(99.dp)).background(DipTheme.colors.surfaceAlt)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0.04f, 1f)).height(6.dp).background(color))
    }
}

private data class OosBar(val label: String, val n: Int, val netUsd: Double)

private fun oosBars(cards: List<ModelFilter.Card>, log: List<ModelFilter.LogRow>): List<OosBar> {
    val labels = listOf("10–15m", "5–10m", "2–5m", "<2m")
    val closed = log.filter { it.outcomeUsd != null && it.features.isNotEmpty() }
    if (closed.isEmpty()) {
        val head = cards.firstOrNull()?.buckets?.associateBy { it.name } ?: emptyMap()
        return listOf(
            OosBar("10–15m", head["10+ min"]?.n ?: 0, head["10+ min"]?.netUsd ?: 0.0),
            OosBar("5–10m", head["5–10 min"]?.n ?: 0, head["5–10 min"]?.netUsd ?: 0.0),
            OosBar("2–5m", 0, 0.0),
            OosBar("<2m", 0, 0.0)
        )
    }
    val n = IntArray(4)
    val net = DoubleArray(4)
    for (row in closed) {
        val tau = row.features.getOrElse(0) { 0.0 } * 900.0
        val i = when {
            tau >= 600.0 -> 0
            tau >= 300.0 -> 1
            tau >= 120.0 -> 2
            else -> 3
        }
        n[i]++
        net[i] += row.outcomeUsd ?: 0.0
    }
    return labels.mapIndexed { i, label -> OosBar(label, n[i], net[i]) }
}

private fun modelBadge(type: String) = when (type) {
    "TreeEnsemble" -> "LightGBM ${TreeEnsemble.TRAIN_TREES}t·d${TreeEnsemble.TRAIN_DEPTH}"
    else -> type
}

private fun execLabel(path: String) = when (path) {
    "CPU Fast" -> "CPU Fast 1-Thread"
    else -> path
}

private fun defaultQuotas() = listOf(
    ProviderQuota(LlmProviders.GROQ, "Groq", LlmProviders.DEFAULT_GROQ_MODEL, 0, LlmProviders.defaultDailyCap(LlmProviders.GROQ)),
    ProviderQuota(LlmProviders.MISTRAL, "Mistral", LlmProviders.MISTRAL_DEFAULT, 0, LlmProviders.defaultDailyCap(LlmProviders.MISTRAL)),
    ProviderQuota(LlmProviders.GEMINI, "Gemini", LlmProviders.GEMINI_DEFAULT, 0, LlmProviders.defaultDailyCap(LlmProviders.GEMINI))
)

private fun journalPrompt(page: ResearchCompanion.Journal): String {
    val regimes = page.regimes.joinToString("\n") { "${it.group} ${it.name}: ${it.wins}/${it.n} net ${money(it.netUsd)}" }
    val tips = page.suggestions.joinToString("\n")
    return "Paper session journal. Closed ${page.closed}.\n$regimes\n$tips\nTwo short paragraphs: what regime is working, and whether to retrain. No orders."
}

private fun us(v: Long) = if (v < 0L) "—" else String.format(Locale.US, "%d µs", v)
private fun money(v: Double) = String.format(Locale.US, "%+.2f", v)
private fun signed(v: Double) = String.format(Locale.US, "%+.2f", v)
private fun fmt(v: Double) = String.format(Locale.US, "%.2f", v)
private fun cents(v: Double) = String.format(Locale.US, "%+.1f¢", v * 100.0)
private fun pct(v: Double) = String.format(Locale.US, "%.0f%%", v * 100.0)
