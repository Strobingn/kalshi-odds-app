package com.dirk.kalshiodds.ui

import androidx.compose.ui.platform.testTag
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.decision.*
import com.dirk.kalshiodds.ui.theme.DipTheme

internal enum class KashiArenaTab(val label: String) { OVERVIEW("Overview"), STRATEGIES("Strategies"), ANALYSIS("Analysis"), TRADES("Trades") }

/** Claude's Arena layout, backed by Kashi's existing primary strategy ledger. */
@Composable
internal fun KashiArenaContent(st: ScalpTabState, onOpen: (ScalpModels.Model) -> Unit = {}, onSettings: () -> Unit = {},
    onHistory: () -> Unit = {}, onLadder: () -> Unit = {}, initialTab: KashiArenaTab = KashiArenaTab.OVERVIEW,
    onResetPaper: (() -> Unit)? = null) {
    val c = DipTheme.colors
    var tabName by rememberSaveable { mutableStateOf(initialTab.name) }
    val tab = KashiArenaTab.valueOf(tabName)
    var v2 by rememberSaveable { mutableStateOf(true) }
    var sortName by rememberSaveable { mutableStateOf(KashiArenaData.Sort.NET.name) }
    val sort = KashiArenaData.Sort.valueOf(sortName)
    val rows = remember(st.algoAllTime, v2, sort) { KashiArenaData.rows(st, v2, sort) }
    val models = rows.map { it.model }.toSet()
    val active = st.activeScalps.filter { ScalpModels.modelOf(it) in models && it.isPrimary }
    val liveTrades = st.arenaChart.trades.filter { ScalpModels.modelOf(it) in models && it.isPrimary }
    val curve = remember(st.windows, v2, st.updatedAtMs) { KashiArenaData.curve(st.windows, st.updatedAtMs, models) }
    LazyColumn(Modifier.fillMaxSize().background(c.bg), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "arena-header") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Scalping arena", color = c.textPrimary, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("BITCOIN · PAPER", color = c.spot, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
                if (onResetPaper != null) androidx.compose.material3.TextButton(onClick = onResetPaper,
                    modifier = Modifier.testTag("SCALP_RESET_PAPER")) { Text(ScalpResetCopy.BUTTON, maxLines = 2) }
                IconButton(onClick = onSettings) { Icon(Icons.Outlined.Tune, "Scalping settings", tint = c.accentBlue) }
            }
        }
        item(key = "arena-tabs") { PillTabs(KashiArenaTab.values().toList(), tab, { it.label }, { "ARENA_TAB_${it.name}" }) { tabName = it.name } }
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
                item(key = "live") {
                    ScalpWindowChart(ScalpAlgoDetail(chart = st.arenaChart.copy(trades = liveTrades)), title = "Live window") { id -> liveTrades.firstOrNull { it.id == id }?.let { ScalpModels.modelOf(it)?.let(onOpen) } }
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
                item(key = "analysis-data") { OutlinedButton(onClick = onHistory, modifier = Modifier.fillMaxWidth()) { Text("Detailed results & comparison") } }
            }
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
                Text("${row.roundTrips} trades · ${ScalpTabCopy.pct(row.winRate)} won · ${row.open} open", color = c.textSecondary, style = MaterialTheme.typography.labelSmall)
                com.dirk.kalshiodds.decision.G14Copies.sourceOf(row.model.code)?.let { src ->
                    val live = st.leaderAllTime.firstOrNull { it.model.code == src }?.takeIf { it.roundTrips > 0 }
                    val snap = com.dirk.kalshiodds.decision.G14Copies.SOURCES.getValue(src)
                    Text("rebuilt 0.3.80 · original $src: " + (live?.let { "${it.roundTrips} trades · ${ScalpTabCopy.money(it.netUsd)}" }
                        ?: "${snap.trades} trades · ${ScalpTabCopy.money(snap.netUsd)} (Oct 17)"), color = c.textSecondary, style = MaterialTheme.typography.labelSmall)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(if (row.roundTrips > 0) ScalpTabCopy.money(row.netUsd) else "—", color = pnlColor(row.netUsd), style = ArenaUi.mono(MaterialTheme.typography.titleMedium), fontWeight = FontWeight.Bold)
                if (points.size >= 2) Box(Modifier.width(76.dp)) { ArenaProfitChart(points, compact = true) }
            }
        }
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
