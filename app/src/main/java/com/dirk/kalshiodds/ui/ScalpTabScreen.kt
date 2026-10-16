package com.dirk.kalshiodds.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dirk.kalshiodds.decision.PriceLadder
import com.dirk.kalshiodds.decision.ScalpModels
import com.dirk.kalshiodds.decision.ScalpTrade
import com.dirk.kalshiodds.ui.components.FieldCard
import com.dirk.kalshiodds.ui.theme.DipTheme
import java.util.Locale

/** Scalp tab copy (pure, tested). 0.3.51: plain cards; raw per-model detail lives in Scalp Data. */
object ScalpTabCopy {
    /** 0.3.61: "stop saved $x vs no-stop (n stops)" / "stop cost …"; "no stops hit yet" when none. */
    fun stopSaved(r: ScalpModels.Row?): String = if (r == null || r.stopsHit == 0) "no stops hit yet"
        else (if (r.stopSavedUsd >= 0) "stop saved " else "stop cost ") + money(kotlin.math.abs(r.stopSavedUsd)) + " vs no-stop (${r.stopsHit} stop" + (if (r.stopsHit == 1) ")" else "s)")
    const val TITLE = "Scalp"
    const val PAPER_NOTE = "Paper only. Models run as parallel experiments on 15-minute markets. Totals combine model results after fees. " +
        "Tap a ladder row to place a paper limit at that price; tap your order to cancel; drag it to reprice. " +
        "Limits fill at exactly your price when touched, capped by depth, and cancel at window close. " +
        "Real-money orders only from the Real Money tab (Approve + typed REAL MONEY). No LLM in any decision."

    fun usd(v: Double?): String = v?.takeIf { it.isFinite() }?.let { String.format(Locale.US, "%+.2f", it) } ?: "—"
    /** "−$2.84" / "+$1.20" with a real minus sign. */
    fun money(v: Double): String = (if (v < -0.004) "−" else "+") + String.format(Locale.US, "$%.2f", kotlin.math.abs(v))
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

    /** "BTC — leader: Extreme-reversion, −$2.84 today" (or no trades yet). */
    fun coinHeadline(coin: String, today: List<ScalpModels.Row>): String {
        val lead = today.filter { it.roundTrips > 0 }.maxByOrNull { it.netUsd }
            ?: return "$coin — no round trips today"
        return "$coin — leader: ${lead.model.label}, ${money(lead.netUsd)} today"
    }

    /** Full per-model detail (moved to Scalp Data in 0.3.51). */
    fun detailLines(trades: List<ScalpTrade>): List<String> = buildList {
        add("All coins, all-time:")
        ScalpModels.leaderboard(trades).forEach { add("  ${it.model.label}: ${usd(it.netUsd)} · ${line(it)} · ${roi(it)}") }
        com.dirk.kalshiodds.decision.ScalpParams.COINS.forEach { c ->
            add("$c all-time:")
            ScalpModels.leaderboard(trades, c).forEach { add("  ${it.model.code} ${usd(it.netUsd)} · ${it.roundTrips} RT · win ${pct(it.winRate)}") }
        }
        val w = ScalpModels.perWindow(trades, limit = 24)
        if (w.isNotEmpty()) add("Per window (best model):")
        w.forEach { r ->
            add("  ${r.windowKey}: ${r.best?.label ?: "—"} · " + ScalpModels.ALL.joinToString("  ") { m -> "${m.code} ${r.netByModel[m]?.let(::usd) ?: "·"}" })
        }
    }

    enum class Live { GREEN, AMBER, RED }
    fun live(bookAgeMs: Long?): Live = when {
        bookAgeMs == null -> Live.RED
        bookAgeMs <= 2_000L -> Live.GREEN
        bookAgeMs <= 10_000L -> Live.AMBER
        else -> Live.RED
    }
    fun timeLeft(s: Long?): String = s?.let { String.format(Locale.US, "%d:%02d left", it / 60, it % 60) } ?: "—"
    fun cents(p: Double?): String = p?.let { String.format(Locale.US, "%.0f¢", it * 100) } ?: "—"
}

@Composable
fun ScalpTabScreen(viewModel: OddsViewModel, onOpenScalpData: () -> Unit, onOpenScalpRules: () -> Unit, onOpenAlgo: (ScalpModels.Model) -> Unit = {}) {
    DisposableEffect(Unit) {
        viewModel.setScalpTabVisible(true)
        onDispose { viewModel.setScalpTabVisible(false) }
    }
    val st by viewModel.scalpTab.collectAsStateWithLifecycle()
    val book = com.dirk.kalshiodds.KalshiOddsApp.from(androidx.compose.ui.platform.LocalContext.current).container.scalp
    val controls by book.controls.state.collectAsStateWithLifecycle()
    ScalpTabContent(
        st = st,
        controlConfigs = controls,
        onApplyControl = { code, config -> book.applyControls(code, config) },
        onResetControl = book::resetControls,
        onOpenScalpData = onOpenScalpData,
        onOpenScalpRules = onOpenScalpRules,
        onSelectMarket = viewModel::selectScalpMarket,
        onSelectSide = viewModel::selectScalpSide,
        onPlace = viewModel::ladderPlace,
        onCancel = viewModel::ladderCancel,
        onReprice = viewModel::ladderReprice,
        onSellOwnedAt = viewModel::sellOwnedAt,
        onOpenAlgo = onOpenAlgo
    )
}

/** Stateless content (Paparazzi-tested at 1080×2340). */
@Composable
fun ScalpTabContent(
    st: ScalpTabState,
    onOpenScalpData: () -> Unit = {},
    onOpenScalpRules: () -> Unit = {},
    onSelectMarket: (String) -> Unit = {},
    onSelectSide: (String) -> Unit = {},
    onPlace: (cents: Int, buy: Boolean, qty: Int) -> Unit = { _, _, _ -> },
    onCancel: (String) -> Unit = {},
    onReprice: (String, Int) -> Unit = { _, _ -> },
    onSellOwnedAt: (Int) -> Unit = {},
    onOpenAlgo: (ScalpModels.Model) -> Unit = {},
    /** Previews only: hide the coin cards to render the ladder card in view. */
    showCoinCards: Boolean = true,
    controlConfigs: Map<String, com.dirk.kalshiodds.decision.ScalpControlSpec.Config> = emptyMap(),
    onApplyControl: (String, com.dirk.kalshiodds.decision.ScalpControlSpec.Config) -> Unit = { _, _ -> },
    onResetControl: (String) -> Unit = {},
    initialPage: ScalpLabUi.Page? = null
) {
    var qty by remember { mutableIntStateOf(PriceLadder.PRESET_SIZES[1]) }
    var buyMode by remember { mutableStateOf(true) }
    var info by remember { mutableStateOf(false) }
    val c = DipTheme.colors
    var pageName by rememberSaveable(showCoinCards) { mutableStateOf(initialPage?.name ?: if (showCoinCards) "LIVE" else "LADDER") }
    var filterName by rememberSaveable { mutableStateOf("ALL") }
    var todayOnly by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var fullBoard by rememberSaveable { mutableStateOf(false) }
    var compareV2 by rememberSaveable { mutableStateOf(false) }
    val page = ScalpLabUi.Page.valueOf(pageName)
    val filter = ScalpLabUi.Filter.valueOf(filterName)
    val rows = if (todayOnly) st.algoToday else st.algoAllTime
    var liveModel by rememberSaveable { mutableStateOf("*") }
    var chartSide by rememberSaveable { mutableStateOf("BOTH") }
    var slidersOpen by rememberSaveable { mutableStateOf(false) }
    var selectedTradeId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(st.window.ticker) { selectedTradeId = null }
    val liveTrades = remember(st.window.trades, liveModel, chartSide) {
        st.window.trades.filter { (liveModel == "*" || com.dirk.kalshiodds.decision.ScalpControlSpec.code(it) == liveModel) &&
            (chartSide == "BOTH" || it.side == chartSide) }
    }
    val models = remember(rows, st.algoAllTime, filter, query) { ScalpLabUi.ranked(rows, st.algoAllTime, filter, query) }

    if (info) {
        AlertDialog(
            onDismissRequest = { info = false },
            confirmButton = { TextButton(onClick = { info = false }) { Text("OK") } },
            title = { Text("How the Scalp tab works") },
            text = { Text(ScalpTabCopy.PAPER_NOTE) }
        )
    }

    LazyColumn(/* 0.3.67 stable keys throughout */ 
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
            st.migrateNote?.let { note -> item(key = "mig") { Text(note, style = MaterialTheme.typography.labelMedium, color = DipTheme.colors.textSecondary) } }
            if (st.debugTiming.isNotBlank()) item(key = "dbg") { Text(st.debugTiming, style = MaterialTheme.typography.labelSmall, color = DipTheme.colors.textSecondary) }

        item(key = "hdr") {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(ScalpTabCopy.TITLE, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = c.textPrimary)
                Spacer(Modifier.width(8.dp))
                Text(
                    "PAPER", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = c.textSecondary,
                    modifier = Modifier.border(1.dp, c.border, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp)
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onOpenScalpData) { Text("Data", maxLines = 1) }
                TextButton(onClick = onOpenScalpRules) { Text("Rules") }
                IconButton(onClick = { info = true }) { Icon(Icons.Outlined.Info, contentDescription = "How it works", tint = c.textSecondary) }
            }
        }
        item(key = "pages") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ScalpLabUi.Page.entries.toList(), key = { it.name }) { tab ->
                    FilterChip(selected = page == tab, onClick = { pageName = tab.name },
                        label = { Text(tab.label) })
                }
            }
        }
        if (page == ScalpLabUi.Page.MODELS || page == ScalpLabUi.Page.COMPARE) {
            item(key = "period") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !todayOnly, onClick = { todayOnly = false }, label = { Text("All-time") })
                    FilterChip(selected = todayOnly, onClick = { todayOnly = true }, label = { Text("Today (ET)") })
                }
            }
            item(key = "overview") { ScalpLabOverview(rows, st.algoAllTime, todayOnly) }
        }
        if (page == ScalpLabUi.Page.LIVE) {
            item(key = "algorithm-picker") {
                ScalpAlgorithmPicker(liveModel, onSelect = { liveModel = it; selectedTradeId = null }, onSliders = { slidersOpen = true })
                Text("All scalpers: 2¢ stop enabled · buy price ≤50¢", style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold, color = c.accentBlue)
            }
            if (slidersOpen && liveModel != "*") item(key = "live-sliders-$liveModel") {
                TextButton(onClick = { slidersOpen = false }) { Text("Hide sliders · return to chart") }
                ScalpControlsCard(liveModel, controlConfigs[liveModel], { onApplyControl(liveModel, it) }, { onResetControl(liveModel) })
                ScalpModels.modelByCode(liveModel)?.let { m -> ScalpModels.originalOf(m)?.let { original ->
                    TextButton(onClick = { liveModel = original.code }) { Text("Edit original signal settings: ${original.label}") }
                } }
            }
            item(key = "live-markets") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(st.markets, key = { it.ticker }) { m -> Pill(m.label, st.selectedTicker == m.ticker) { onSelectMarket(m.ticker) } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("BOTH" to "Both sides", "YES" to "UP", "NO" to "DOWN").forEach { (side, label) ->
                        FilterChip(selected = chartSide == side, onClick = { chartSide = side; selectedTradeId = null }, label = { Text(label) })
                    }
                }
            }
            item(key = "live-chart") { ScalpLiveWindow(st.window, liveTrades, selectedTradeId) { selectedTradeId = it } }
            item(key = "live-rules") {
                Text("A 2¢ stop triggers a sale at available bids. Fees, gaps and limited depth can make the final loss larger than 2¢ per contract.",
                    style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
            }
            liveTrades.firstOrNull { it.id == selectedTradeId }?.let { t ->
                item(key = "selected-execution") { ScalpWindowTradeCard(t, true, st.window.quote) { selectedTradeId = null } }
            }
            item(key = "live-activity") {
                Text("Window activity · ${liveTrades.size} trades", style = MaterialTheme.typography.titleMedium, color = c.textPrimary, fontWeight = FontWeight.SemiBold)
                Text("${liveTrades.count { it.entryAtMs != null }} filled · ${liveTrades.count { it.state == com.dirk.kalshiodds.decision.ScalpState.CLOSED }} closed · ${liveTrades.count { it.state == com.dirk.kalshiodds.decision.ScalpState.NO_FILL }} no-fill",
                    style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
                if (liveTrades.isEmpty()) Text("No trades for this selection yet. Choose All algorithms or another model to inspect its activity.", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                if (liveTrades.size > 80) Text("Latest 80 trades shown below. Filter by algorithm to inspect more.", style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
            }
            items(liveTrades.filter { it.id != selectedTradeId }.take(80), key = { "window-trade-" + it.id }) { t ->
                ScalpWindowTradeCard(t, false, st.window.quote) { selectedTradeId = t.id }
            }
        }
        if (page == ScalpLabUi.Page.MODELS) {
            item(key = "find-model") {
                OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Find a model") }, placeholder = { Text("Name, code or strategy") }, singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("Clear") } })
            }
            item(key = "filters") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(ScalpLabUi.Filter.entries.toList(), key = { it.name }) { f ->
                        FilterChip(selected = filter == f, onClick = { filterName = f.name }, label = { Text(f.label) })
                    }
                }
            }
            item(key = "algo-h") {
                Text("${models.size} models · ranked by net P&L", style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
            }
            if (models.isEmpty()) item(key = "empty") {
                ScalpLabCard { Text("No models match these filters.", color = c.textSecondary) }
            }
            items(models, key = { "algo-" + it.code }) { m ->
                ScalpLabModelCard(models.indexOf(m) + 1, m, rows[m], st.algoAllTime[m], controlConfigs[m.code]) { onOpenAlgo(m) }
            }
        }
        if (page == ScalpLabUi.Page.COMPARE) {
            item(key = "comparison") { ScalpLabComparison(rows, onOpenAlgo) }
            item(key = "board-toggle") {
                OutlinedButton(onClick = { fullBoard = !fullBoard }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (fullBoard) "Hide full leaderboard" else "Full leaderboard")
                }
            }
            if (fullBoard) item(key = "board") { CombinedBoard(ScalpModels.combinedBoard(rows), todayOnly) }
            item(key = "v2-toggle") {
                OutlinedButton(onClick = { compareV2 = !compareV2 }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (compareV2) "Hide V2 comparison" else "V2 vs original")
                }
            }
            if (compareV2) item(key = "v2cmp") {
                val cmp = remember(rows) { ScalpModels.v2Compare(rows) }
                V2CompareCard(cmp)
            }
        }
        if (page == ScalpLabUi.Page.LADDER) {
            item(key = "ladder-card") {
                ScalpLabCard {
                    Text("Trade the ladder · buys ≤50¢", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = c.textPrimary)
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(st.markets, key = { it.ticker }) { m ->
                            Pill(m.label, selected = st.selectedTicker == m.ticker) { onSelectMarket(m.ticker) }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Segmented(
                        left = "UP", right = "DOWN", leftSelected = st.side == "YES",
                        onLeft = { onSelectSide("YES") }, onRight = { onSelectSide("NO") }
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BigButton("Buy", c.upButton, selected = buyMode, modifier = Modifier.weight(1f)) { buyMode = true }
                        BigButton("Sell", c.downButton, selected = !buyMode, modifier = Modifier.weight(1f)) { buyMode = false }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PriceLadder.PRESET_SIZES.forEach { n -> Pill("$n", selected = qty == n, modifier = Modifier.weight(1f)) { qty = n } }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val dot = when (ScalpTabCopy.live(st.bookAgeMs)) {
                            ScalpTabCopy.Live.GREEN -> c.up
                            ScalpTabCopy.Live.AMBER -> c.accentOrange
                            ScalpTabCopy.Live.RED -> c.down
                        }
                        Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
                        Spacer(Modifier.width(6.dp))
                        Text(if (ScalpTabCopy.live(st.bookAgeMs) == ScalpTabCopy.Live.GREEN) "Live" else "Stale",
                            style = MaterialTheme.typography.labelMedium, color = c.textSecondary, maxLines = 1)
                        Spacer(Modifier.weight(1f))
                        Text(ScalpTabCopy.timeLeft(st.secondsLeft), style = MaterialTheme.typography.labelMedium, color = c.textSecondary, maxLines = 1)
                    }
                    if (st.heldContracts > 0) {
                        Spacer(Modifier.height(10.dp))
                        Text("You own ${st.heldContracts} @ ${ScalpTabCopy.cents(st.heldEntry)} — sell all at:",
                            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            PriceLadder.EXIT_PRESETS_CENTS.forEach { x -> Pill("+$x¢", selected = false, modifier = Modifier.weight(1f)) { onSellOwnedAt(x) } }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Ladder(st, buyMode, qty, onPlace, onCancel, onReprice)
                }
            }
        }
        item(key = "end") { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
internal fun AlgoCard(
    m: ScalpModels.Model, all: ScalpModels.Row?, today: ScalpModels.Row?, byCoin: Map<String, ScalpModels.Row>, onClick: () -> Unit
) {
    val c = DipTheme.colors
    val net = all?.netUsd ?: 0.0
    val traded = (all?.roundTrips ?: 0) > 0
    FieldCard(onClick = onClick, accentColor = if (!traded) null else if (net >= 0) c.up else c.down) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(m.label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val holding = all?.holding == true
            Text(
                if (holding) "Holding ${all!!.openContracts}" else "Running",
                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false,
                color = if (holding) c.accentBlue else c.textSecondary,
                modifier = Modifier.border(1.dp, if (holding) c.accentBlue else c.border, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
        if (ScalpModels.noEdge2s(m)) {
            Text("no edge at 2s delay", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = c.down, maxLines = 1,
                modifier = Modifier.border(1.dp, c.down, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
        }
        ScalpModels.tag(m)?.let { tg ->
            Text(tg, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("2¢ global stop · historical: " + ScalpTabCopy.stopSaved(all), style = MaterialTheme.typography.labelSmall, color = c.textSecondary,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        com.dirk.kalshiodds.decision.ShockFav.byVariantId(m.code)?.let { p ->
            Text(p.summary(), style = MaterialTheme.typography.labelSmall, color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("Backtest (test days): " + p.oos, style = MaterialTheme.typography.labelSmall, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (m == ScalpModels.Model.FORECAST_RIDE || m == ScalpModels.Model.FORECAST_FADE) {
            Text("Horizon: " + com.dirk.kalshiodds.decision.ForecastScalp.horizonNote(), style = MaterialTheme.typography.labelSmall,
                color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(if (traded) ScalpTabCopy.money(net) else "—", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace, color = if (!traded) c.textSecondary else if (net >= 0) c.up else c.down, maxLines = 1)
            Spacer(Modifier.width(10.dp))
            Text("all-time · today " + (today?.takeIf { it.roundTrips > 0 }?.let { ScalpTabCopy.money(it.netUsd) } ?: "—"),
                style = MaterialTheme.typography.labelMedium, color = c.textSecondary, maxLines = 1, modifier = Modifier.padding(bottom = 4.dp))
        }
        Spacer(Modifier.height(6.dp))
        val r = all
        StatPair("Won", r?.let { ScalpTabCopy.money(it.grossWinUsd) } ?: "—", "Lost", r?.let { ScalpTabCopy.money(it.grossLossUsd) } ?: "—")
        StatPair("Wins / losses", r?.let { "${it.wins} / ${it.losses}" } ?: "—", "Win rate", ScalpTabCopy.pct(r?.winRate))
        StatPair("Avg win", r?.avgWinUsd?.let { ScalpTabCopy.money(it) } ?: "—", "Avg loss", r?.avgLossUsd?.let { ScalpTabCopy.money(it) } ?: "—")
        StatPair("Best", r?.bestUsd?.let { ScalpTabCopy.money(it) } ?: "—", "Worst", r?.worstUsd?.let { ScalpTabCopy.money(it) } ?: "—")
        StatPair("$/trade", r?.perTradeCi?.let { ScalpTabCopy.money(it.mean) } ?: "—", "95% CI", r?.perTradeCi?.let { ScalpTabCopy.money(it.lo) + "…" + ScalpTabCopy.money(it.hi) } ?: "—")
        StatPair("Fees", r?.let { ScalpTabCopy.money(it.feesUsd) } ?: "—", "Today trades", today?.roundTrips?.toString() ?: "0")
        r?.exitReasons?.takeIf { it.isNotBlank() }?.let { Text("Exits: $it", style = MaterialTheme.typography.labelSmall, color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        StatPair("Open", r?.let { "${it.openContracts} ct" } ?: "—", "Unrealized", r?.takeIf { it.open > 0 }?.let { ScalpTabCopy.money(it.unrealizedUsd) } ?: "—")
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
internal fun StatPair(l1: String, v1: String, l2: String, v2: String) {
    val c = DipTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(l1, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 1)
        Text(v1, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = c.textPrimary, maxLines = 1, textAlign = TextAlign.End)
        Spacer(Modifier.width(16.dp))
        Text(l2, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 1)
        Text(v2, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = c.textPrimary, maxLines = 1, textAlign = TextAlign.End)
    }
}

/** 0.3.65: each v2 copy next to its original — trades, net after fees, $/trade with 95 % CI, plain status. */
@Composable
private fun V2CompareCard(rows: List<com.dirk.kalshiodds.decision.ScalpV2.Compare>) {
    val c = DipTheme.colors
    FieldCard {
        Text("v2 vs original · BTC 15m", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = c.textPrimary)
        Text("v2 keeps positive/inconclusive settings and tests bounded changes after sustained losses (30 trades, 3 completed windows). Protective stops always apply. Final-minute holds require strong fresh CF evidence; details are in each trade note. Under 30 v2 trades = not enough data.",
            style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
        Spacer(Modifier.height(6.dp))
        rows.forEach { cmp ->
            fun line(r: ScalpModels.Row): String {
                val ci = r.perTradeCi
                return "${r.roundTrips} tr · net ${if (r.roundTrips > 0) ScalpTabCopy.money(r.netUsd) else "—"} · " +
                    (ci?.let { "${ScalpTabCopy.money(it.mean)}/tr (${ScalpTabCopy.money(it.lo)}…${ScalpTabCopy.money(it.hi)})" } ?: "—/tr")
            }
            val col = when (cmp.status) { "beating original" -> c.up; "losing to original" -> c.down; else -> c.textSecondary }
            Text(cmp.original.label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("v2: " + line(cmp.v2Row), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("orig: " + line(cmp.origRow), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(cmp.status, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = col)
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun CombinedBoard(rows: List<ScalpModels.Row>, todayOnly: Boolean = false) {
    val c = DipTheme.colors
    FieldCard {
        Text("Leaderboard · " + (if (todayOnly) "today (ET)" else "all-time on this phone"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = c.textPrimary)
        // 0.3.68: totals from the scalp_stats rows behind this board (was 0 / 0 / $0 after 0.3.67 moved history to SQLite).
        val tn = rows.sumOf { it.roundTrips }; val tw = rows.sumOf { it.wins }; val tl = rows.sumOf { it.losses }; val tnet = rows.sumOf { it.netUsd }
        Text("$tn round trips · $tw wins / $tl losses · net ${ScalpTabCopy.money(tnet)} after fees", style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace, color = if (tn == 0) c.textSecondary else if (tnet >= 0) c.up else c.down)
        Spacer(Modifier.height(6.dp))
        rows.forEachIndexed { i, r ->
            val traded = r.roundTrips > 0
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}.", style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, color = c.textSecondary, modifier = Modifier.width(30.dp))
                Text(r.model.label + if (ScalpModels.noEdge2s(r.model)) " ·no edge" else "", style = MaterialTheme.typography.bodySmall,
                    color = if (ScalpModels.noEdge2s(r.model)) c.textSecondary else c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(r.stopLabel.removePrefix("stop ").substringBefore(" ") + " " + (if (r.stopsHit > 0) ScalpTabCopy.money(r.stopSavedUsd) else "·"), style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace, color = c.textSecondary, maxLines = 1, modifier = Modifier.padding(start = 6.dp))
                Text("${r.roundTrips}", style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, color = c.textSecondary, modifier = Modifier.padding(horizontal = 8.dp))
                Text(if (traded) ScalpTabCopy.money(r.netUsd) else "—", style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold, color = if (!traded) c.textSecondary else if (r.netUsd >= 0) c.up else c.down, maxLines = 1)
            }
        }
    }
}

@Composable
private fun CoinCard(coin: String, today: List<ScalpModels.Row>) {
    val c = DipTheme.colors
    val lead = today.filter { it.roundTrips > 0 }.maxByOrNull { it.netUsd }
    FieldCard(accentColor = lead?.let { if (it.netUsd >= 0) c.up else c.down }) {
        Text(ScalpTabCopy.coinHeadline(coin, today), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            Text("Model", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
            Text("P&L", Modifier.width(76.dp), style = MaterialTheme.typography.labelSmall, color = c.textSecondary, textAlign = TextAlign.End)
            Text("Win", Modifier.width(52.dp), style = MaterialTheme.typography.labelSmall, color = c.textSecondary, textAlign = TextAlign.End)
        }
        today.forEach { r ->
            val star = lead != null && r.model == lead.model
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text((if (star) "★ " else "") + r.model.label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium, fontWeight = if (star) FontWeight.SemiBold else FontWeight.Normal, color = c.textPrimary)
                Text(if (r.roundTrips == 0) "—" else ScalpTabCopy.money(r.netUsd), Modifier.width(76.dp), maxLines = 1, textAlign = TextAlign.End,
                    style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace,
                    color = when { r.roundTrips == 0 -> c.textSecondary; r.netUsd >= 0 -> c.up; else -> c.down })
                Text(ScalpTabCopy.pct(r.winRate), Modifier.width(52.dp), maxLines = 1, textAlign = TextAlign.End,
                    style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, color = c.textSecondary)
            }
        }
    }
}

@Composable
private fun Pill(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = DipTheme.colors
    Box(
        modifier.clip(RoundedCornerShape(10.dp))
            .background(if (selected) c.accentBlue.copy(alpha = 0.18f) else Color.Transparent)
            .border(1.dp, if (selected) c.accentBlue else c.border, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, color = c.textPrimary)
    }
}

@Composable
private fun Segmented(left: String, right: String, leftSelected: Boolean, onLeft: () -> Unit, onRight: () -> Unit) {
    val c = DipTheme.colors
    Row(Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, c.border, RoundedCornerShape(12.dp))) {
        listOf(Triple(left, leftSelected, onLeft), Triple(right, !leftSelected, onRight)).forEachIndexed { i, (label, sel, cb) ->
            val tint = if (i == 0) c.up else c.down
            Box(
                Modifier.weight(1f).fillMaxSize().background(if (sel) tint.copy(alpha = 0.22f) else Color.Transparent).clickable(onClick = cb),
                contentAlignment = Alignment.Center
            ) {
                Text(label, maxLines = 1, softWrap = false, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                    color = if (sel) tint else c.textSecondary, style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

@Composable
private fun BigButton(text: String, color: Color, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier.height(52.dp), shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White)) {
            Text(text, maxLines = 1, softWrap = false, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier.height(52.dp), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.5.dp, color)) {
            Text(text, maxLines = 1, softWrap = false, fontWeight = FontWeight.SemiBold, color = color, style = MaterialTheme.typography.titleMedium)
        }
    }
}

private const val ROW_DP = 34

@Composable
private fun Ladder(
    st: ScalpTabState, buyMode: Boolean, qty: Int,
    onPlace: (Int, Boolean, Int) -> Unit, onCancel: (String) -> Unit, onReprice: (String, Int) -> Unit
) {
    val c = DipTheme.colors
    val bestBid = st.ladder.filter { it.bidSize > 0 }.maxOfOrNull { it.cents }
    val bestAsk = st.ladder.filter { it.askSize > 0 }.minOfOrNull { it.cents }
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        LadderHeader("Bid size", Modifier.weight(1f), TextAlign.Start)
        LadderHeader("Price", Modifier.weight(1f), TextAlign.Center)
        LadderHeader("Ask size", Modifier.weight(1f), TextAlign.End)
        LadderHeader("Mine", Modifier.weight(0.8f), TextAlign.End)
    }
    if (bestBid != null && bestAsk != null) {
        Text("Best bid ${bestBid}¢ · best ask ${bestAsk}¢ · spread ${bestAsk - bestBid}¢",
            style = MaterialTheme.typography.labelMedium, color = c.textSecondary, maxLines = 1, modifier = Modifier.padding(bottom = 4.dp))
    }
    val listState = rememberLazyListState()
    val focus = PriceLadder.focusIndex(st.ladder)
    LaunchedEffect(st.selectedTicker, st.side, st.ladder.isNotEmpty()) {
        if (st.ladder.isNotEmpty()) listState.scrollToItem((focus - 6).coerceAtLeast(0))
    }
    if (st.ladder.isEmpty()) {
        Text("Waiting for the live book…", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
        return
    }
    LazyColumn(/* 0.3.67 stable keys throughout */ state = listState, modifier = Modifier.fillMaxWidth().height((ROW_DP * 13).dp)) {
        items(st.ladder, key = { it.cents }) { row ->
            var drag by remember(row.cents) { mutableFloatStateOf(0f) }
            val mine = row.myBuyQty + row.mySellQty > 0
            val bg = when {
                mine -> c.accentBlue.copy(alpha = 0.22f)
                row.cents == bestBid -> c.upContainer
                row.cents == bestAsk -> c.downContainer
                bestBid != null && bestAsk != null && row.cents in (bestBid + 1) until bestAsk -> c.surfaceAlt
                else -> Color.Transparent
            }
            Row(
                Modifier.fillMaxWidth().height(ROW_DP.dp).background(bg)
                    .clickable(enabled = mine || !buyMode || row.cents <= 50) { if (mine) row.myOrderIds.forEach(onCancel) else onPlace(row.cents, buyMode, qty) }
                    .pointerInput(row.cents, mine) {
                        if (!mine) return@pointerInput
                        detectVerticalDragGestures(
                            onDragEnd = {
                                val moved = (drag / ROW_DP.dp.toPx()).toInt()
                                if (moved != 0) row.myOrderIds.forEach { id -> onReprice(id, (row.cents - moved).coerceIn(1, 99)) }
                                drag = 0f
                            },
                            onVerticalDrag = { _, dy -> drag += dy }
                        )
                    }
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LadderCell(if (row.bidSize > 0) String.format(Locale.US, "%,.0f", row.bidSize) else "", Modifier.weight(1f), TextAlign.Start, c.up)
                LadderCell("${row.cents}¢", Modifier.weight(1f), TextAlign.Center, c.textPrimary, bold = row.cents == bestBid || row.cents == bestAsk)
                LadderCell(if (row.askSize > 0) String.format(Locale.US, "%,.0f", row.askSize) else "", Modifier.weight(1f), TextAlign.End, c.down)
                LadderCell(buildString {
                    if (row.myBuyQty > 0) append("B${row.myBuyQty}")
                    if (row.mySellQty > 0) { if (isNotEmpty()) append(' '); append("S${row.mySellQty}") }
                }, Modifier.weight(0.8f), TextAlign.End, c.accentBlue, bold = true)
            }
        }
    }
}

@Composable
private fun LadderHeader(t: String, m: Modifier, align: TextAlign) =
    Text(t, m, style = MaterialTheme.typography.labelSmall, color = DipTheme.colors.textSecondary, textAlign = align, maxLines = 1, softWrap = false)

@Composable
private fun LadderCell(t: String, m: Modifier, align: TextAlign, color: Color, bold: Boolean = false) =
    Text(t, m, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, color = color, textAlign = align,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, maxLines = 1, softWrap = false)

