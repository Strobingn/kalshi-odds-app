package com.dirk.kalshiodds.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.decision.*
import com.dirk.kalshiodds.ui.charts.ChartGallery
import com.dirk.kalshiodds.ui.charts.ChartSeries
import com.dirk.kalshiodds.ui.charts.ChartShapes
import com.dirk.kalshiodds.ui.charts.DailyNetBars
import com.dirk.kalshiodds.ui.charts.NetBar
import com.dirk.kalshiodds.ui.charts.PnlSparkline
import com.dirk.kalshiodds.ui.charts.RankedNetBars
import com.dirk.kalshiodds.ui.theme.DipTheme

internal enum class KashiArenaTab(val label: String) { OVERVIEW("Overview"), STRATEGIES("Strategies"), ANALYSIS("Analysis"), TRADES("Trades"), CHAT("AI chat") }

/** Claude's Arena layout, backed by Kashi's existing primary strategy ledger. */
@Composable
internal fun KashiArenaContent(st: ScalpTabState, onOpen: (ScalpModels.Model) -> Unit = {}, onSettings: () -> Unit = {},
    onHistory: () -> Unit = {}, onLadder: () -> Unit = {}, initialTab: KashiArenaTab = KashiArenaTab.OVERVIEW,
    aiPanel: AiWatchPanelModel? = null, onAiFollows: (Boolean) -> Unit = {}, extras: LiveWindowExtras = LiveWindowExtras()) {
    val c = DipTheme.colors
    val openAi = LocalOpenAiTab.current
    var tabName by rememberSaveable { mutableStateOf(initialTab.name) }
    val tab = KashiArenaTab.valueOf(tabName)
    var v2 by rememberSaveable { mutableStateOf(true) }
    var sortName by rememberSaveable { mutableStateOf(KashiArenaData.Sort.NET.name) }
    val sort = KashiArenaData.Sort.valueOf(sortName)
    val bench = com.dirk.kalshiodds.decision.insights.BenchRegistry.decisions.collectAsState().value
    val rows = remember(st.algoAllTime, v2, sort) { KashiArenaData.rows(st, v2, sort) }
    val models = rows.map { it.model }.toSet()
    val active = st.activeScalps.filter { ScalpModels.modelOf(it) in models && it.isPrimary }
    val liveTrades = st.arenaChart.trades.filter { ScalpModels.modelOf(it) in models && it.isPrimary }
    val curve = remember(st.windows, v2, st.updatedAtMs) { KashiArenaData.curve(st.windows, st.updatedAtMs, models) }
    val daily = remember(st.windows, v2, st.updatedAtMs) { ChartSeries.dailyNet(st.windows, st.updatedAtMs, models) }
    LazyColumn(Modifier.fillMaxSize().background(c.bg), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (aiPanel != null) item(key = "ai-watch") { AiWatchPanel(aiPanel, onAiFollows) }
        item(key = "arena-header") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Scalping arena", color = c.textPrimary, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("BITCOIN · PAPER", color = c.spot, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
                IconButton(onClick = onSettings) { Icon(Icons.Outlined.Tune, "Scalping settings", tint = c.accentBlue) }
            }
        }
        item(key = "arena-tabs") { PillTabs(KashiArenaTab.values().toList(), tab, { it.label }, { "ARENA_TAB_${it.name}" }) { if (it == KashiArenaTab.CHAT) openAi() else tabName = it.name } }
        item(key = "arena-group") {
            ChipRow(listOf(true, false), v2, { if (it) "V2 strategies" else "Originals" }, { "ARENA_GROUP_$it" }) { v2 = it }
        }
        st.migrateNote?.let { item(key = "migration") { Text(it, color = c.textSecondary, style = MaterialTheme.typography.labelSmall) } }
        if (v2 && (!st.controls.enabled || st.controls.killSwitch)) item(key = "paused") {
            ArenaCard(accent = c.spot) {
                Text("New entries paused", color = c.textPrimary, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = onSettings) { Text("Open scalping settings") }
            }
        }
        if (st.arenaChart.trades.size >= 600 && tab in listOf(KashiArenaTab.OVERVIEW, KashiArenaTab.TRADES)) item(key = "sample") {
            Text("Live window shows the latest 600 signals plus open positions. Open a strategy for its full window history.", color = c.textSecondary, style = MaterialTheme.typography.labelSmall)
        }
        when (tab) {
            KashiArenaTab.OVERVIEW -> {
                item(key = "hero") { ArenaSummary(rows, st.algoToday.values.filter { it.model in models }, active.size) }
                item(key = "curve") { ArenaCard {
                    Text("Running profit", color = c.textPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Net after fees · recent completed windows", color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
                    ArenaProfitChart(curve)
                    Text("Starts at $0 for this range. Each strategy has its own paper ledger.", color = c.textSecondary, style = MaterialTheme.typography.labelSmall)
                } }
                item(key = "net-ranked") { ArenaCard {
                    Text("Net by scalper", color = c.textPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("All time · net after fees · best first", color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
                    RankedNetBars(rows.filter { it.roundTrips > 0 }.map { NetBar(it.model.label, it.netUsd) }, Modifier.fillMaxWidth())
                } }
                item(key = "daily-net") { ArenaCard {
                    Text("Daily net", color = c.textPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Latest recorded windows (up to 48) · ET days · oldest day may be partial", color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
                    DailyNetBars(daily, Modifier.fillMaxWidth())
                } }
                item(key = "live") {
                    ScalpWindowChart(ScalpAlgoDetail(chart = st.arenaChart.copy(trades = liveTrades)), title = "Live window", extras = extras) { id -> liveTrades.firstOrNull { it.id == id }?.let { ScalpModels.modelOf(it)?.let(onOpen) } }
                }
                item(key = "top-header") { ArenaSectionHeader("Top strategies", "See all") { tabName = KashiArenaTab.STRATEGIES.name } }
                items(rows.filter { it.roundTrips > 0 }.sortedByDescending { it.netUsd }.take(5), key = { "top-${it.model.code}" }) { r -> ArenaStrategyRow(r, st) { onOpen(r.model) } }
                if (rows.none { it.roundTrips > 0 }) item(key = "no-trades") { Text("Waiting for the first closed trade. Open Strategies to see every model.", color = c.textSecondary) }
                item(key = "tools") { Row { TextButton(onClick = onLadder) { Text("Manual ladder") }; TextButton(onClick = onHistory) { Text("All data") } } }
            }
            KashiArenaTab.STRATEGIES -> {
                item(key = "sort") {
                    ArenaSectionHeader("Every strategy · ${rows.size}")
                    ChipRow(KashiArenaData.Sort.values().toList(), sort, { it.label }, { "ARENA_SORT_${it.name}" }) { sortName = it.name }
                    Text("Tap a strategy for its live chart, orders, fills and results.", color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
                }
                items(rows, key = { "strategy-${it.model.code}" }) { r -> ArenaStrategyRow(r, st) { onOpen(r.model) } }
            }
            KashiArenaTab.ANALYSIS -> {
                val galleryTrades = (st.arenaChart.trades + st.activeScalps).distinctBy { it.id }
                val leaders = rows.map { row ->
                    val frozen = com.dirk.kalshiodds.decision.insights.ScalpFreeze.isFrozen(bench[row.model.code])
                    row to frozen
                }.filter { (row, frozen) -> row.roundTrips > 0 || frozen }.map { (row, frozen) ->
                    ChartGallery.LeaderRow(row.model.label, row.netUsd, frozen)
                }
                val sparks = ChartGallery.equitySparks(
                    st.windows,
                    st.updatedAtMs,
                    rows.mapNotNull { row ->
                        val frozen = com.dirk.kalshiodds.decision.insights.ScalpFreeze.isFrozen(bench[row.model.code])
                        if (row.roundTrips > 0 || frozen) row.model to frozen else null
                    }
                )
                item(key = "race") { ArenaCard {
                    Text("Strategy race", style = MaterialTheme.typography.titleMedium, color = c.textPrimary, fontWeight = FontWeight.SemiBold)
                    Text("Top four by all-time profit · recorded window results", color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
                    ArenaRaceChart(st, rows.sortedByDescending { it.netUsd })
                } }
                item(key = "bars") { ArenaCard {
                    Text("Profit by strategy", style = MaterialTheme.typography.titleMedium, color = c.textPrimary, fontWeight = FontWeight.SemiBold)
                    Text("All time · net after fees · first eight in selected sort", color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
                    ArenaBars(rows)
                } }
                item(key = "fees") { ArenaCard {
                    Text("What fees cost", style = MaterialTheme.typography.titleMedium, color = c.textPrimary, fontWeight = FontWeight.SemiBold)
                    Text("All time · first eight in selected sort", color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
                    ArenaBars(rows, fees = true)
                } }
                item(key = "insight-charts") {
                    ArenaCard {
                        ScalpWindowInsightCharts(
                            windows = st.windows,
                            nowMs = st.updatedAtMs,
                            models = rows.map { it.model },
                            pnlBars = rows.filter { it.roundTrips > 0 }.map { NetBar(it.model.label, it.netUsd) },
                            gauges = rows.filter { it.roundTrips > 0 }.map { Triple(it.model.label, it.winRate, it.roundTrips) },
                            tweaks = rows.mapNotNull { r -> ChartShapes.tweakMark(r.model.label, st.controls.settings(r.model.code).aiNote) }
                        )
                    }
                }
                item(key = "live-trade-charts") {
                    val live = remember(st.arenaChart.trades, st.activeScalps) {
                        (st.arenaChart.trades + st.activeScalps).distinctBy { it.id }
                    }
                    ArenaCard {
                        ScalpTradeInsightCharts(live, "Live window and open positions. All-time curves are above.")
                    }
                }
                // After the existing analysis cards, so the race, profit bars and fees stay on top.
                item(key = "analysis-gallery") {
                    AnalysisGallery(galleryTrades, leaders, sparks, st.windows, st.updatedAtMs, rows.map { it.model })
                }
                item(key = "analysis-data") { OutlinedButton(onClick = onHistory, modifier = Modifier.fillMaxWidth()) { Text("Detailed results & comparison") } }
            }
            KashiArenaTab.CHAT -> item(key = "chat") { LlmChatPanel(rows) }
            KashiArenaTab.TRADES -> {
                item(key = "trade-hero") { ArenaSummary(rows, st.algoToday.values.filter { it.model in models }, active.size) }
                item(key = "open-head") { ArenaSectionHeader("Open now · ${active.size}", "Ladder", onLadder) }
                if (active.isEmpty()) item(key = "flat") { Text("No open positions in this group.", color = c.textSecondary) }
                items(active.sortedByDescending { it.signalAtMs }, key = { "open-${it.id}" }) { t ->
                    ArenaTradeCard(t, st.scalpBids[t.id]) { ScalpModels.modelOf(t)?.let(onOpen) }
                }
                val closed = liveTrades.filter { it.state == ScalpState.CLOSED }.sortedByDescending { it.closedAtMs }
                item(key = "closed-head") { ArenaSectionHeader("Closed in this window · ${closed.size}", "All history", onHistory) }
                if (closed.isEmpty()) item(key = "none-closed") { Text("No closed trades in the live window for this group.", color = c.textSecondary) }
                items(closed, key = { "closed-${it.id}" }) { t -> ArenaTradeCard(t, null) { ScalpModels.modelOf(t)?.let(onOpen) } }
            }
        }
    }
}

@Composable
internal fun ArenaSummary(rows: List<ScalpModels.Row>, today: Collection<ScalpModels.Row>, open: Int) {
    val n = rows.sumOf { it.roundTrips }; val wins = rows.sumOf { it.wins }; val net = rows.sumOf { it.netUsd }
    HeroCard("Net after fees · ${rows.size} strategies", net.takeIf { n > 0 },
        "won ${ScalpTabCopy.money(rows.sumOf { it.grossWinUsd })} · lost ${ScalpTabCopy.money(rows.sumOf { it.grossLossUsd })} · fees $${String.format(java.util.Locale.US, "%.2f", rows.sumOf { it.feesUsd })}",
        listOf(Kpi("Trades", "$n"), Kpi("Win rate", ScalpTabCopy.pct(if (n > 0) wins.toDouble() / n else null)), Kpi("W / L", "$wins / ${rows.sumOf { it.losses }}"),
            Kpi("Today · ET", ScalpTabCopy.money(today.sumOf { it.netUsd }), pnlColor(today.sumOf { it.netUsd })),
            Kpi("Open now", "$open"), Kpi("Open P&L", ScalpTabCopy.money(rows.sumOf { it.unrealizedUsd }), pnlColor(rows.sumOf { it.unrealizedUsd }))))
}

@Composable
internal fun ArenaStrategyRow(row: ScalpModels.Row, st: ScalpTabState, onOpen: () -> Unit) {
    val c = DipTheme.colors; val paused = ScalpModels.isV2(row.model) && !st.controls.permits(row.model.code)
    val points = remember(st.windows, row.model, st.updatedAtMs) { KashiArenaData.curve(st.windows, st.updatedAtMs, setOf(row.model)) }
    ArenaCard(modifier = Modifier.clickable(onClick = onOpen), accent = if (row.roundTrips > 0) pnlColor(row.netUsd) else c.border) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(row.model.label, color = c.textPrimary, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (ScalpModels.isResearch(row.model)) Text("RESEARCH", color = c.spot, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                Text("${row.roundTrips} trades · ${ScalpTabCopy.pct(row.winRate)} won · ${row.open} open", color = c.textSecondary, style = MaterialTheme.typography.labelSmall)
                if (row.roundTrips > 0) Text("Gross ${ScalpTabCopy.money(row.netUsd + row.feesUsd)} · fees ${ScalpTabCopy.money(-row.feesUsd)} · net ${ScalpTabCopy.money(row.netUsd)}", color = c.textSecondary, style = MaterialTheme.typography.labelSmall)
                // 1.0.9 reality check: net after fees, then after estimated slippage / thinner fills.
                if (row.roundTrips > 0) {
                    val r = com.dirk.kalshiodds.decision.insights.ScalpNetReality.estimate(row.netUsd, row.feesUsd, row.roundTrips, com.dirk.kalshiodds.decision.ScalpRule.CONTRACTS, st.controls.slipCents)
                    Text("Reality check: after est. slippage ${ScalpTabCopy.money(r.netAfterSlippageUsd)} (−$${String.format(java.util.Locale.US, "%.2f", r.slippageUsd)} @ ${st.controls.slipCents}¢/ct/leg)", color = pnlColor(r.netAfterSlippageUsd), style = MaterialTheme.typography.labelSmall)
                }
                val bench = com.dirk.kalshiodds.decision.insights.BenchRegistry.decisions.collectAsState().value[row.model.code]
                // One FROZEN badge. Plain auto-bench keeps its own line. Force trade shows the override instead.
                if (com.dirk.kalshiodds.decision.insights.ScalpFreeze.isFrozen(bench)) Text(bench!!.reason.replaceFirst(com.dirk.kalshiodds.decision.insights.ScalpFreeze.BADGE, com.dirk.kalshiodds.decision.insights.ScalpFreeze.BADGE + " · no new paper trades"), color = c.down, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, maxLines = 2, modifier = Modifier.testTag("FROZEN_BADGE_" + row.model.code))
                else if (bench != null && !bench.trading) Text("BENCHED · shadow only · " + bench.reason, color = c.down, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, maxLines = 2)
                else if (bench?.status == com.dirk.kalshiodds.decision.insights.BenchStatus.FORCED_ACTIVE) Text("Bench override · forced trading", color = c.spot, style = MaterialTheme.typography.labelSmall)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(if (row.roundTrips > 0) ScalpTabCopy.money(row.netUsd) else "—", color = pnlColor(row.netUsd), style = ArenaUi.mono(MaterialTheme.typography.titleMedium), fontWeight = FontWeight.Bold)
            }
        }
        PnlSparkline(points.map { it.net }, Modifier.fillMaxWidth().padding(vertical = 4.dp), height = 32.dp)
        val ai = st.controls.settings(row.model.code)
        Text(if (ai.aiLearns) "AI learns: on" + (if (ai.aiNote.isNotBlank()) " · last adjusted: ${ai.aiNote}" else "") else "AI learns: off", color = c.textSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 2)
        if (ScalpModels.isResearch(row.model)) {
            val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as? com.dirk.kalshiodds.KalshiOddsApp
            val rs = app?.let { runCatching { it.container.scalp.researchStatus }.getOrNull() }?.collectAsState()?.value
            if (rs != null) Text(rs, color = c.textSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 2)
        }
        if (row.model == ScalpModels.Model.V2_LLM) {
            val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as? com.dirk.kalshiodds.KalshiOddsApp
            val s = app?.let { runCatching { it.container.llmStrategy.status }.getOrNull() }?.collectAsState()?.value ?: "needs key"
            Text("LLM: $s", color = c.textSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 2)
        }
        if (ScalpModels.isV2(row.model)) Text(com.dirk.kalshiodds.decision.ScalperBrain.summary(row.model.code), color = c.textSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 2)
        Text(if (paused) "Entries paused · view chart →" else "Live 15m chart & trades →", color = c.accentBlue, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
internal fun ArenaTradeCard(t: ScalpTrade, bid: Double?, onOpen: () -> Unit) {
    val c = DipTheme.colors
    ArenaCard(modifier = Modifier.clickable(onClick = onOpen), accent = t.netUsd?.let { pnlColor(it) } ?: c.border) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(t.algoLabel, color = c.textPrimary, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 2)
            Text(t.netUsd?.let(ScalpTabCopy::money) ?: t.unrealizedUsd(bid)?.let(ScalpTabCopy::money) ?: "—", color = pnlColor(t.netUsd ?: t.unrealizedUsd(bid) ?: 0.0), style = ArenaUi.mono(MaterialTheme.typography.titleMedium))
        }
        Text(ScalpAlgoCopy.priceLine(t), color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
        Text("${ScalpAlgoCopy.state(t)} · ${windowTime(t.closedAtMs ?: t.entryAtMs ?: t.signalAtMs)} ET", color = c.textSecondary, style = MaterialTheme.typography.labelSmall)
        if (ScalpV2.isV2(t.variantId) && t.state in listOf(ScalpState.OPEN, ScalpState.PENDING_EXIT)) Text(ScalpV2.riskLabel(t), color = c.textSecondary, style = MaterialTheme.typography.labelSmall)
        t.exitReason?.let { Text(it, color = c.textSecondary, style = MaterialTheme.typography.labelSmall) }
    }
}
