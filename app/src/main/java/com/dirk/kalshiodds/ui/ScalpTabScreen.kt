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
    const val TITLE = "Scalp"
    const val PAPER_NOTE = "Paper only. Six models run on every BTC/ETH/SOL 15-min window, each with its own bankroll slice. " +
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
fun ScalpTabScreen(viewModel: OddsViewModel, onOpenScalpData: () -> Unit, onOpenScalpRules: () -> Unit) {
    DisposableEffect(Unit) {
        viewModel.setScalpTabVisible(true)
        onDispose { viewModel.setScalpTabVisible(false) }
    }
    val st by viewModel.scalpTab.collectAsStateWithLifecycle()
    ScalpTabContent(
        st = st,
        onOpenScalpData = onOpenScalpData,
        onOpenScalpRules = onOpenScalpRules,
        onSelectMarket = viewModel::selectScalpMarket,
        onSelectSide = viewModel::selectScalpSide,
        onPlace = viewModel::ladderPlace,
        onCancel = viewModel::ladderCancel,
        onReprice = viewModel::ladderReprice,
        onSellOwnedAt = viewModel::sellOwnedAt
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
    /** Previews only: hide the coin cards to render the ladder card in view. */
    showCoinCards: Boolean = true
) {
    var qty by remember { mutableIntStateOf(PriceLadder.PRESET_SIZES[1]) }
    var buyMode by remember { mutableStateOf(true) }
    var info by remember { mutableStateOf(false) }
    val c = DipTheme.colors

    if (info) {
        AlertDialog(
            onDismissRequest = { info = false },
            confirmButton = { TextButton(onClick = { info = false }) { Text("OK") } },
            title = { Text("How the Scalp tab works") },
            text = { Text(ScalpTabCopy.PAPER_NOTE) }
        )
    }

    LazyColumn(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "hdr") {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(ScalpTabCopy.TITLE, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = c.textPrimary)
                Spacer(Modifier.width(8.dp))
                Text(
                    "PAPER", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = c.textSecondary,
                    modifier = Modifier.border(1.dp, c.border, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp)
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onOpenScalpData) { Text("Scalp Data", maxLines = 1) }
                IconButton(onClick = { info = true }) { Icon(Icons.Outlined.Info, contentDescription = "How it works", tint = c.textSecondary) }
            }
        }
        if (showCoinCards) items(com.dirk.kalshiodds.decision.ScalpParams.COINS, key = { "coin-$it" }) { coin ->
            CoinCard(coin, st.leaderTodayByCoin[coin].orEmpty())
        }
        item(key = "ladder-card") {
            FieldCard {
                Text("Trade the ladder", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = c.textPrimary)
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
        item(key = "end") { Spacer(Modifier.height(16.dp)) }
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
    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().height((ROW_DP * 13).dp)) {
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
                    .clickable { if (mine) row.myOrderIds.forEach(onCancel) else onPlace(row.cents, buyMode, qty) }
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
