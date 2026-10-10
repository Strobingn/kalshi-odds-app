package com.dirk.kalshiodds.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dirk.kalshiodds.decision.PriceLadder
import com.dirk.kalshiodds.decision.ScalpModels
import com.dirk.kalshiodds.ui.theme.DipTheme
import java.util.Locale

/** 0.3.50 copy for the Scalp tab (pure, tested). */
object ScalpTabCopy {
    const val TITLE = "Scalp (PAPER)"
    const val PAPER_NOTE = "Paper only. Six models run on every BTC/ETH/SOL 15-min window, each with its own bankroll slice. " +
        "Ladder orders are paper limits: fill at exactly your price on touch, capped by depth, auto-cancel at window close. " +
        "Real-money orders only from the Real Money tab (Approve + typed REAL MONEY). No LLM in any decision."
    fun usd(v: Double?): String = v?.takeIf { it.isFinite() }?.let { String.format(Locale.US, "%+.2f", it) } ?: "—"
    fun pct(v: Double?): String = v?.takeIf { it.isFinite() }?.let { String.format(Locale.US, "%.0f%%", it * 100) } ?: "—"
    fun roi(r: ScalpModels.Row): String {
        val roi = r.roi ?: return "ROI —"
        val ci = r.roiCi
        return if (ci != null && ci.clusters >= 2) String.format(Locale.US, "ROI %+.1f%% [%+.1f, %+.1f]", roi * 100, ci.lo * 100, ci.hi * 100)
        else String.format(Locale.US, "ROI %+.1f%%", roi * 100)
    }
    fun line(r: ScalpModels.Row): String = String.format(
        Locale.US, "%d RT · win %s · avg W %s / L %s · open %d (%s) · no-fill %d",
        r.roundTrips, pct(r.winRate), usd(r.avgWinUsd), usd(r.avgLossUsd), r.open, usd(r.unrealizedUsd), r.noFills
    )
}

@Composable
fun ScalpTabScreen(
    viewModel: OddsViewModel,
    onOpenScalpData: () -> Unit,
    onOpenScalpRules: () -> Unit
) {
    DisposableEffect(Unit) {
        viewModel.setScalpTabVisible(true)
        onDispose { viewModel.setScalpTabVisible(false) }
    }
    val st by viewModel.scalpTab.collectAsStateWithLifecycle()
    var qty by remember { mutableIntStateOf(PriceLadder.PRESET_SIZES[1]) }
    var buyMode by remember { mutableStateOf(true) }
    val colors = DipTheme.colors
    val best = ScalpModels.best(st.leaderAllTime)

    LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 12.dp)) {
        item(key = "hdr") {
            Column(Modifier.padding(top = 12.dp)) {
                Text(ScalpTabCopy.TITLE, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(ScalpTabCopy.PAPER_NOTE, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onOpenScalpData) { Text("Scalp Data") }
                    OutlinedButton(onClick = onOpenScalpRules) { Text("Rules & params") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf<String?>(null, "BTC", "ETH", "SOL").forEach { c ->
                        FilterChip(selected = st.coin == c, onClick = { viewModel.selectScalpCoin(c) }, label = { Text(c ?: "All") })
                    }
                }
                Text(String.format(Locale.US, "Bankroll slice per model: $%.2f", st.sliceUsd), style = MaterialTheme.typography.labelSmall)
            }
        }
        item(key = "lbw-h") { Section("Current window ${st.currentWindowKey ?: "—"}") }
        items(st.leaderWindow, key = { "w-" + it.model.code }) { r -> ModelRow(r, r.model == ScalpModels.best(st.leaderWindow)) }
        item(key = "lba-h") { Section("All-time leaderboard (P&L after fees)") }
        items(st.leaderAllTime, key = { "a-" + it.model.code }) { r -> ModelRow(r, r.model == best) }
        item(key = "coin-h") { Section("Per coin — best model") }
        items(st.leaderByCoin.entries.toList(), key = { "c-" + it.key }) { (coin, rows) ->
            val b = rows.filter { it.roundTrips > 0 }.maxByOrNull { it.netUsd }
            Text("$coin: " + (b?.let { "${it.model.label} ${ScalpTabCopy.usd(it.netUsd)} · ${it.roundTrips} RT" } ?: "no round trips yet") +
                "  ·  " + rows.joinToString("  ") { "${it.model.code} ${ScalpTabCopy.usd(it.netUsd)}" },
                style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
        item(key = "ladder-h") {
            Column {
                Section("Price ladder (paper)")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(st.markets.filter { st.coin == null || it.coin == st.coin }, key = { it.ticker }) { m ->
                        FilterChip(selected = st.selectedTicker == m.ticker, onClick = { viewModel.selectScalpMarket(m.ticker) }, label = { Text(m.ticker, fontSize = 11.sp) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = st.side == "YES", onClick = { viewModel.selectScalpSide("YES") }, label = { Text("UP (YES)") })
                    FilterChip(selected = st.side == "NO", onClick = { viewModel.selectScalpSide("NO") }, label = { Text("DOWN (NO)") })
                    FilterChip(selected = buyMode, onClick = { buyMode = true }, label = { Text("Tap = BUY") })
                    FilterChip(selected = !buyMode, onClick = { buyMode = false }, label = { Text("Tap = SELL") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PriceLadder.PRESET_SIZES.forEach { n -> FilterChip(selected = qty == n, onClick = { qty = n }, label = { Text("$n") }) }
                }
                Text(
                    "Held ${st.heldContracts} @ ${st.heldEntry?.let { String.format(Locale.US, "%.0f¢", it * 100) } ?: "—"} · " +
                        "book age ${st.bookAgeMs?.let { "${it}ms" } ?: "—"} · ${st.secondsLeft?.let { "${it / 60}:${String.format(Locale.US, "%02d", it % 60)} left" } ?: ""}",
                    style = MaterialTheme.typography.bodySmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Sell what I own at", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 10.dp))
                    PriceLadder.EXIT_PRESETS_CENTS.forEach { c ->
                        OutlinedButton(onClick = { viewModel.sellOwnedAt(c) }, enabled = st.heldContracts > 0) { Text("+${c}¢", fontSize = 11.sp) }
                    }
                }
                Text("Rows: bid size | price | ask size | my orders. Tap a row to place; tap a row with your order to cancel it; drag an order row up/down to reprice.",
                    style = MaterialTheme.typography.labelSmall)
            }
        }
        item(key = "ladder") {
            val listState = rememberLazyListState()
            val focus = PriceLadder.focusIndex(st.ladder)
            LaunchedEffect(st.selectedTicker, st.side) { if (st.ladder.isNotEmpty()) listState.scrollToItem((focus - 6).coerceAtLeast(0)) }
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(st.ladder, key = { it.cents }) { row ->
                    var drag by remember(row.cents) { mutableFloatStateOf(0f) }
                    val mine = row.myBuyQty + row.mySellQty > 0
                    Row(
                        Modifier.fillMaxWidth().height(28.dp)
                            .background(if (mine) colors.upContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.background)
                            .clickable {
                                if (mine) row.myOrderIds.forEach { viewModel.ladderCancel(it) }
                                else viewModel.ladderPlace(row.cents, buyMode, qty)
                            }
                            .pointerInput(row.cents, mine) {
                                if (!mine) return@pointerInput
                                detectVerticalDragGestures(
                                    onDragEnd = {
                                        val moved = (drag / 28.dp.toPx()).toInt()
                                        if (moved != 0) row.myOrderIds.forEach { id -> viewModel.ladderReprice(id, (row.cents - moved).coerceIn(1, 99)) }
                                        drag = 0f
                                    },
                                    onVerticalDrag = { _, dy -> drag += dy }
                                )
                            },
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(if (row.bidSize > 0) String.format(Locale.US, "%.0f", row.bidSize) else "", color = colors.up, modifier = Modifier.width(70.dp), fontFamily = FontFamily.Monospace)
                        Text("${row.cents}¢", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        Text(if (row.askSize > 0) String.format(Locale.US, "%.0f", row.askSize) else "", color = colors.down, modifier = Modifier.width(70.dp), fontFamily = FontFamily.Monospace)
                        Text(buildString {
                            if (row.myBuyQty > 0) append("B${row.myBuyQty} ")
                            if (row.mySellQty > 0) append("S${row.mySellQty}")
                        }, modifier = Modifier.width(70.dp), fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
        item(key = "win-h") { Section("Per window — which scalper did best") }
        items(st.windows, key = { "pw-" + it.windowKey }) { w ->
            Text(
                "${w.windowKey}: best ${w.best?.label ?: "—"} · " + ScalpModels.ALL.joinToString("  ") { m ->
                    "${m.code} ${w.netByModel[m]?.let { ScalpTabCopy.usd(it) } ?: "·"}"
                },
                style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace
            )
        }
        item(key = "end") { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

@Composable
private fun ModelRow(r: ScalpModels.Row, highlight: Boolean) {
    val c = DipTheme.colors
    Column(
        Modifier.fillMaxWidth()
            .background(if (highlight) c.upContainer.copy(alpha = 0.45f) else MaterialTheme.colorScheme.background)
            .padding(vertical = 4.dp, horizontal = 6.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text((if (highlight) "★ " else "") + r.model.label, fontWeight = FontWeight.SemiBold)
            Text(ScalpTabCopy.usd(r.netUsd), color = if (r.netUsd >= 0) c.up else c.down, fontWeight = FontWeight.Bold)
        }
        Text(ScalpTabCopy.line(r) + " · " + ScalpTabCopy.roi(r), style = MaterialTheme.typography.bodySmall)
    }
}
