package com.dirk.kalshiodds.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.signal.scalper.ArenaTable
import com.dirk.kalshiodds.signal.scalper.ScalpStrategy
import com.dirk.kalshiodds.signal.scalper.ScalperResults
import com.dirk.kalshiodds.ui.theme.DipTheme

/**
 * The Arena's "Compare" table: every strategy on one screen, filtered by how
 * it trades and sorted by any number. Tap a row to open that strategy's own
 * page. Numbers come from [ArenaTable].
 */
@Composable
internal fun CompareTable(rows: List<ArenaTable.Row>, onOpen: (ScalpStrategy) -> Unit) {
    val colors = DipTheme.colors
    var familyName by rememberSaveable { mutableStateOf(ArenaTable.Family.ALL.name) }
    var keyName by rememberSaveable { mutableStateOf(ArenaTable.SortKey.NET.name) }
    var bestFirst by rememberSaveable { mutableStateOf(true) }
    val family = ArenaTable.Family.valueOf(familyName)
    val key = ArenaTable.SortKey.valueOf(keyName)
    val shown = ArenaTable.view(rows, family, key, bestFirst)
    val extra = ArenaTable.extraColumn(key)
    fun sortBy(k: ArenaTable.SortKey) {
        if (k == key) bestFirst = !bestFirst else { keyName = k.name; bestFirst = true }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ArenaTable.Family.values().forEach { f ->
                val n = rows.count { ArenaTable.matches(it, f) }
                FilterChip(
                    selected = f == family,
                    onClick = { familyName = f.name },
                    label = { Text("${f.label} · $n", maxLines = 1, style = MaterialTheme.typography.labelMedium) },
                    modifier = Modifier.testTag("COMPARE_FAMILY_${f.name}")
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Muted("Sort:")
            ArenaTable.SortKey.values().forEach { k ->
                FilterChip(
                    selected = k == key,
                    onClick = { sortBy(k) },
                    label = {
                        val arrow = if (k == key) (if (bestFirst) " ↓" else " ↑") else ""
                        Text(k.label + arrow, maxLines = 1, style = MaterialTheme.typography.labelMedium)
                    },
                    modifier = Modifier.testTag("COMPARE_SORT_${k.name}")
                )
            }
        }
        // Header: tap a number's name to sort by it.
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("#", style = MaterialTheme.typography.labelSmall, color = colors.textSecondary, modifier = Modifier.width(24.dp))
            Text("Strategy", style = MaterialTheme.typography.labelSmall, color = colors.textSecondary, modifier = Modifier.weight(1f))
            HeaderCell(ArenaTable.SortKey.NET, 70, key, bestFirst) { sortBy(it) }
            HeaderCell(ArenaTable.SortKey.TRADES, 46, key, bestFirst) { sortBy(it) }
            HeaderCell(ArenaTable.SortKey.WIN_RATE, 42, key, bestFirst) { sortBy(it) }
            HeaderCell(extra, 52, key, bestFirst) { sortBy(it) }
        }
        if (shown.isEmpty()) Muted("No strategy in this group yet.")
        shown.forEachIndexed { i, r ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (i % 2 == 0) colors.surfaceAlt else colors.surface, RoundedCornerShape(8.dp))
                    .clickable { onOpen(r.strategy) }
                    .padding(vertical = 6.dp, horizontal = 2.dp)
                    .testTag("COMPARE_ROW_${r.strategy.name}"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("${i + 1}", style = MaterialTheme.typography.labelMedium, color = colors.textSecondary, modifier = Modifier.width(24.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        r.strategy.label, style = MaterialTheme.typography.bodySmall, color = colors.textPrimary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    val tag = ArenaTable.familyOf(r.strategy).label + if (ArenaTable.isNew(r.strategy)) " · new" else ""
                    Text(tag, style = MaterialTheme.typography.labelSmall, color = colors.textSecondary, maxLines = 1)
                }
                Cell(ArenaTable.format(r, ArenaTable.SortKey.NET), 70, if (r.trades == 0) colors.textSecondary else pnlColor(r.netUsd), bold = true)
                Cell(ArenaTable.format(r, ArenaTable.SortKey.TRADES), 46, colors.textPrimary)
                Cell(ArenaTable.format(r, ArenaTable.SortKey.WIN_RATE), 42, colors.textPrimary)
                Cell(ArenaTable.format(r, extra), 52, colors.textPrimary)
            }
        }
        Muted("Tap a strategy for its own page: rules, every number, its charts and trades.")
        Muted("Rows with no number yet sit at the bottom, whichever way the column is sorted.")
    }
}

@Composable
private fun HeaderCell(
    k: ArenaTable.SortKey,
    widthDp: Int,
    current: ArenaTable.SortKey,
    bestFirst: Boolean,
    onSort: (ArenaTable.SortKey) -> Unit
) {
    val colors = DipTheme.colors
    val on = k == current
    Text(
        k.short + if (on) (if (bestFirst) "↓" else "↑") else "",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
        color = if (on) colors.accentBlue else colors.textSecondary,
        textAlign = TextAlign.End,
        maxLines = 1,
        modifier = Modifier.width(widthDp.dp).clickable { onSort(k) }.testTag("COMPARE_HEADER_${k.name}")
    )
}

@Composable
private fun Cell(text: String, widthDp: Int, color: androidx.compose.ui.graphics.Color, bold: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        color = color,
        textAlign = TextAlign.End,
        maxLines = 1,
        modifier = Modifier.width(widthDp.dp)
    )
}

/** One strategy's headline, rules and numbers, at the top of its page. */
@Composable
internal fun StrategyHeader(st: ScalpStrategy, d: ArenaTable.Detail) {
    val colors = DipTheme.colors
    Panel {
        val tag = ArenaTable.familyOf(st).label + " · from ${st.origin}" + if (ArenaTable.isNew(st)) " · new in 1.9.4" else ""
        Text(tag, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
        Text(
            if (d.row.trades == 0) "No trades yet" else ScalperResults.money(d.row.netUsd),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = if (d.row.trades == 0) colors.textSecondary else pnlColor(d.row.netUsd)
        )
        Text(d.row.verdict, style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary)
        st.historyCents?.let {
            Muted(String.format(java.util.Locale.US, "History on 7 recorded days: %+.2f¢ a filled scalp", it))
        }
    }
}

@Composable
internal fun StrategyRules(st: ScalpStrategy, tuned: com.dirk.kalshiodds.signal.scalper.Tuned? = null) {
    val colors = DipTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("STRATEGY_RULES")) {
        (if (tuned != null) ArenaTable.rules(st, tuned) else ArenaTable.rules(st)).forEachIndexed { i, line ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (i == 0) "When" else "•", style = MaterialTheme.typography.labelMedium, color = colors.accentBlue, modifier = Modifier.width(36.dp))
                Text(line, style = MaterialTheme.typography.bodySmall, color = colors.textPrimary)
            }
        }
    }
}

/** Every number about one strategy, two to a row. */
@Composable
internal fun StrategyNumbers(st: ScalpStrategy, d: ArenaTable.Detail) {
    val colors = DipTheme.colors
    val s = d.stats
    val r = d.row
    fun f(k: ArenaTable.SortKey) = ArenaTable.format(r, k)
    val pairs = buildList {
        add(("Trades closed" to f(ArenaTable.SortKey.TRADES)) to ("Won" to "${s.wins} · ${f(ArenaTable.SortKey.WIN_RATE)}"))
        add(("¢ a contract" to f(ArenaTable.SortKey.CENTS)) to ("Profit factor" to f(ArenaTable.SortKey.PROFIT_FACTOR)))
        add(("Dollars won" to ScalperResults.plainMoney(s.wonUsd)) to ("Dollars lost" to ScalperResults.plainMoney(s.lostUsd)))
        add(("Deepest drawdown" to f(ArenaTable.SortKey.DRAWDOWN)) to ("Average hold" to f(ArenaTable.SortKey.HOLD_TIME)))
        add(("Fees paid" to ScalperResults.plainMoney(s.feesUsd)) to ("Per trade" to (s.perScalpUsd?.let { ScalperResults.money(it) } ?: "–")))
        add(
            ("Best trade" to (d.best?.let { ScalperResults.money(it.pnlUsd) } ?: "–")) to
                ("Worst trade" to (d.worst?.let { ScalperResults.money(it.pnlUsd) } ?: "–"))
        )
        add(("Longest win run" to "${d.streaks.first}") to ("Longest loss run" to "${d.streaks.second}"))
        add(("Orders posted" to "${s.posted}") to ("Filled" to if (st.taker) "${s.filled} (buy now)" else "${s.filled} · ${f(ArenaTable.SortKey.FILL_RATE)}"))
        add(("Target / stop" to "${s.targets} / ${s.stops}") to ("Time-out / settled" to "${s.timeouts} / ${s.settled}"))
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("STRATEGY_NUMBERS")) {
        pairs.forEach { (a, b) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(a, b).forEach { (label, value) ->
                    Column(Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
                        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                    }
                }
            }
        }
        if (d.sampled < r.trades) {
            Muted("¢ a contract, drawdown, hold, runs and best / worst cover its last ${d.sampled} trades on file.")
        }
    }
}
