package com.dirk.kalshiodds.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dirk.kalshiodds.domain.EDGE_ALERT_THRESHOLD_PP
import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.signal.checklist.PreTradeChecklist
import com.dirk.kalshiodds.ui.theme.AccentBlue
import com.dirk.kalshiodds.ui.theme.AccentGreen
import com.dirk.kalshiodds.ui.theme.AccentOrange
import com.dirk.kalshiodds.ui.theme.Contrast
import com.dirk.kalshiodds.ui.theme.checklistLabelColor
import com.dirk.kalshiodds.ui.theme.checklistValueColor
import java.util.Locale
import kotlin.math.abs

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MarketCard(
    market: MarketUiModel,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onBuyYes: (() -> Unit)? = null,
    onBuyNo: (() -> Unit)? = null,
    onSell: (() -> Unit)? = null,
    onOpenChart: (() -> Unit)? = null
) {
    val scheme = MaterialTheme.colorScheme
    val cardBg = scheme.surface
    val labelColor = checklistLabelColor(cardBg)
    val valueColor = checklistValueColor(cardBg)
    val alertBorder = if (market.edgeAlert) AccentGreen else scheme.outline
    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(if (market.edgeAlert) 2.dp else 1.dp, alertBorder, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = cardBg,
            contentColor = valueColor
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = market.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = valueColor
                    )
                    market.subtitle?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = labelColor,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    StatusChip(market.status)
                    TimeLeftLabel(market.closeTimeEpochMs, compact = true)
                    Text(
                        text = market.ticker,
                        style = MaterialTheme.typography.labelMedium,
                        color = labelColor,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            val chips = listOfNotNull(
                market.regimeTag,
                market.tteRegimeLabel,
                if (market.calibrated) "Calibrated" else null,
                if (market.adapterReady) "Adapter" else null,
                if (market.heavyMl) "Heavy ML" else null,
                if (market.uncertainty != null && !market.uncertaintyPassed) "Unc gated" else null,
                market.sessionTag?.let { "Sess $it" },
                if (market.newsShock) "News shock" else null,
                market.anomalyNote,
                if (market.conformalAmbiguous) "Conformal ?" else market.conformalSet,
                market.flowNote?.takeIf { it == "smart-flow" },
                if (market.muted) "Muted" else null,
                market.suggestedContracts?.let { "$it contracts max" }
            )
            if (chips.isNotEmpty() || market.edgeAlert) {
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (market.edgeAlert) {
                        Text(
                            text = "⚡ Edge",
                            style = MaterialTheme.typography.labelMedium,
                            color = AccentGreen,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .background(AccentGreen.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                    chips.forEach { chip ->
                        val mutedChip = chip == "Muted"
                        val color = if (mutedChip) AccentOrange else AccentBlue
                        Text(
                            text = chip,
                            style = MaterialTheme.typography.labelMedium,
                            color = color,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .background(color.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }
            if (market.muted && market.muteReason != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = market.muteReason.orEmpty(),
                    style = MaterialTheme.typography.labelMedium,
                    color = AccentOrange
                )
            } else if (!market.passedFilter && market.skipReason != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Filtered · ${market.skipReason}",
                    style = MaterialTheme.typography.labelMedium,
                    color = AccentOrange
                )
            }

            if (market.tapeConflict && market.tapeConflictNote != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = market.tapeConflictNote.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AccentOrange,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AccentOrange.copy(alpha = 0.14f), RoundedCornerShape(10.dp))
                        .padding(10.dp)
                )
                market.modelLeanSide?.let { lean ->
                    Text(
                        text = "Model lean ${if (lean == "NO") "DOWN / NO" else "UP / YES"} · primary follows live tape",
                        style = MaterialTheme.typography.labelMedium,
                        color = AccentOrange,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                text = "LIVE BOOK · UP / DOWN",
                style = MaterialTheme.typography.labelMedium,
                color = AccentBlue,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SideQuote(
                    title = "UP  YES",
                    bid = market.yesBid,
                    ask = market.yesAsk,
                    accent = AccentGreen,
                    modifier = Modifier.weight(1f)
                )
                SideQuote(
                    title = "DOWN  NO",
                    bid = market.noBid,
                    ask = market.noAsk,
                    accent = AccentOrange,
                    modifier = Modifier.weight(1f)
                )
            }
            market.digitalFairPp?.let { fv ->
                Text(
                    text = String.format(
                        Locale.US,
                        "Fair value %.0f¢  ·  model %s",
                        fv,
                        market.importedModelPp?.let { String.format(Locale.US, "%.0f¢", it) } ?: "baseline"
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AccentBlue,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            BidChart(
                points = market.bidHistory.ifEmpty {
                    market.oddsHistory.mapIndexed { i, mid ->
                        com.dirk.kalshiodds.chart.BidPoint(
                            tMs = (market.closeTimeEpochMs ?: 0L) - (market.oddsHistory.size - 1 - i) * 2_000L,
                            upBidCents = mid,
                            downBidCents = 100f - mid
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (onOpenChart != null) Modifier.clickable(onClick = onOpenChart) else Modifier),
                heightDp = if (compact) 72 else 110,
                windowStartMs = market.closeTimeEpochMs?.minus(900_000L),
                windowEndMs = market.closeTimeEpochMs,
                strikeLabel = market.floorStrike?.let { String.format(Locale.US, "Strike $%,.0f", it) }
            )
            if (onOpenChart != null) {
                Text(
                    "Tap chart for full-screen scrub",
                    style = MaterialTheme.typography.labelMedium,
                    color = labelColor,
                    modifier = Modifier
                        .clickable(onClick = onOpenChart)
                        .padding(top = 2.dp)
                )
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text = "DIP HUNTER AI",
                style = MaterialTheme.typography.labelMedium,
                color = AccentBlue,
                fontWeight = FontWeight.Bold
            )
            market.aiNote?.let {
                Text(
                    text = it + market.aiConfidence?.let { c ->
                        String.format(Locale.US, " · conf %.0f%%", c * 100)
                    }.orEmpty(),
                    style = MaterialTheme.typography.labelMedium,
                    color = labelColor,
                    modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                OddsColumn(
                    label = if (market.calibrated) "FV YES" else "AI YES",
                    percent = market.aiYesPercent,
                    bid = null,
                    ask = null,
                    accent = AccentGreen,
                    modifier = Modifier.weight(1f),
                    big = true
                )
                OddsColumn(
                    label = if (market.calibrated) "FV NO" else "AI NO",
                    percent = market.aiNoPercent,
                    bid = null,
                    ask = null,
                    accent = AccentOrange,
                    modifier = Modifier.weight(1f),
                    endAligned = true,
                    big = true
                )
            }

            // Edge panel
            market.edgePp?.let { edge ->
                Spacer(Modifier.height(12.dp))
                val edgeColor = when {
                    abs(edge) >= EDGE_ALERT_THRESHOLD_PP -> AccentGreen
                    abs(edge) >= 2.0 -> AccentBlue
                    else -> labelColor
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(edgeColor.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        text = "Dip Hunter edge",
                        style = MaterialTheme.typography.labelMedium,
                        color = labelColor
                    )
                    Text(
                        text = String.format(Locale.US, "%+.1f pp", edge),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = edgeColor,
                        lineHeight = 32.sp
                    )
                    market.netEdgePp?.let { net ->
                        Text(
                            text = String.format(
                                Locale.US,
                                "Net EV %+.1f pp  ·  %+.3f $/ct  ·  fee %.1f¢",
                                net,
                                market.netEvDollars ?: 0.0,
                                (market.feePerContract ?: 0.0) * 100.0
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = edgeColor,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    market.suggestedContracts?.let { n ->
                        Text(
                            text = "$n contracts max",
                            style = MaterialTheme.typography.titleMedium,
                            color = AccentGreen,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        market.sizingNote?.let { note ->
                            Text(
                                text = note,
                                style = MaterialTheme.typography.labelMedium,
                                color = labelColor
                            )
                        }
                    }
                    if (market.timeToMoveSec != null || market.pFill != null || market.uncertainty != null) {
                        Text(
                            text = listOfNotNull(
                                market.timeToMoveSec?.let { String.format(Locale.US, "TTM %.0fs", it) },
                                market.midVolPp?.let { String.format(Locale.US, "vol %.1fpp", it) },
                                market.pFill?.let { String.format(Locale.US, "P(fill) %.0f%%", it * 100.0) },
                                market.uncertainty?.let { String.format(Locale.US, "unc %.2f", it) }
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (market.uncertaintyPassed) labelColor else AccentOrange,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    market.ensembleNote?.let { note ->
                        Text(
                            text = note,
                            style = MaterialTheme.typography.labelMedium,
                            color = labelColor,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    market.extendedNote?.let { note ->
                        Text(
                            text = note,
                            style = MaterialTheme.typography.labelMedium,
                            color = labelColor,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    market.rlNote?.let { note ->
                        Text(
                            text = note,
                            style = MaterialTheme.typography.labelMedium,
                            color = AccentBlue,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    market.stance?.let { s ->
                        Text(
                            text = s,
                            style = MaterialTheme.typography.bodyMedium,
                            color = edgeColor,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    Text(
                        text = "Fair − market (pp). Net EV subtracts Kalshi-style fee + half-spread. Analysis stays advisory; tickets need a separate Approve.",
                        style = MaterialTheme.typography.labelMedium,
                        color = labelColor,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            if (onBuyYes != null || onBuyNo != null || onSell != null) {
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (onBuyYes != null) {
                        Button(
                            onClick = onBuyYes,
                            modifier = Modifier.weight(1f).height(52.dp)
                        ) { Text(com.dirk.kalshiodds.domain.KalshiQuoteDisplay.buttonLabel(true, market.yesAsk)) }
                    }
                    if (onBuyNo != null) {
                        OutlinedButton(
                            onClick = onBuyNo,
                            modifier = Modifier.weight(1f).height(52.dp)
                        ) { Text(com.dirk.kalshiodds.domain.KalshiQuoteDisplay.buttonLabel(false, market.noAsk)) }
                    }
                    if (onSell != null) {
                        OutlinedButton(
                            onClick = onSell,
                            modifier = Modifier.weight(1f).height(52.dp)
                        ) { Text("Sell") }
                    }
                }
                Text(
                    "Opens an approve-gated limit. Nothing is sent until you tap Approve.",
                    style = MaterialTheme.typography.labelMedium,
                    color = labelColor,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            ChecklistBlock(market)

            if (!compact) {
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "Kalshi market (reference)",
                    style = MaterialTheme.typography.labelMedium,
                    color = labelColor
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    OddsColumn(
                        label = "Mkt YES",
                        percent = market.yesProbabilityPercent,
                        bid = market.yesBid,
                        ask = market.yesAsk,
                        accent = valueColor,
                        modifier = Modifier.weight(1f),
                        big = false
                    )
                    OddsColumn(
                        label = "Mkt NO",
                        percent = market.noProbabilityPercent,
                        bid = market.noBid,
                        ask = market.noAsk,
                        accent = valueColor,
                        modifier = Modifier.weight(1f),
                        endAligned = true,
                        big = false
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Metric("Spread", market.spreadDollars?.let { String.format(Locale.US, "%.1f¢", it * 100) } ?: "—")
                Metric("Volume", formatCompact(market.volume))
                Metric("OI", formatCompact(market.openInterest))
            }
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Metric("24h vol", formatCompact(market.volume24h))
                Metric("Liquidity", market.liquidityDollars?.let { formatCompact(it) } ?: "—")
                Column {
                    Text("Time left", style = MaterialTheme.typography.labelMedium, color = labelColor)
                    TimeLeftLabel(market.closeTimeEpochMs)
                    market.closeTimeLocal?.let {
                        Text(it, style = MaterialTheme.typography.labelMedium, color = labelColor)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChecklistBlock(market: MarketUiModel) {
    val items = PreTradeChecklist.items(market)
    val clipboard = LocalClipboardManager.current
    var copied by remember(market.ticker) { mutableStateOf(false) }
    val bg = MaterialTheme.colorScheme.surface
    val labelColor = checklistLabelColor(bg)
    val valueColor = checklistValueColor(bg)
    Spacer(Modifier.height(12.dp))
    Text(
        text = "PRE-TRADE CHECKLIST",
        style = MaterialTheme.typography.labelMedium,
        color = Contrast.readable(AccentBlue, bg, minRatio = Contrast.AA_LARGE),
        fontWeight = FontWeight.Bold
    )
    Spacer(Modifier.height(4.dp))
    items.forEach { item ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(item.label, style = MaterialTheme.typography.labelMedium, color = labelColor)
            Text(
                Contrast.display(item.value),
                style = MaterialTheme.typography.labelMedium,
                color = valueColor,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
    OutlinedButton(
        onClick = {
            clipboard.setText(AnnotatedString(PreTradeChecklist.copyText(market)))
            copied = true
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
    ) {
        Icon(Icons.Outlined.ContentCopy, contentDescription = null)
        Text(
            if (copied) "Copied" else "Copy checklist",
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

@Composable
private fun SideQuote(
    title: String,
    bid: Double?,
    ask: Double?,
    accent: Color,
    modifier: Modifier = Modifier
) {
    val bg = MaterialTheme.colorScheme.surface
    val labelColor = checklistLabelColor(bg)
    val valueColor = Contrast.readable(accent, bg, minRatio = Contrast.AA_LARGE)
    Column(
        modifier
            .background(accent.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = labelColor, fontWeight = FontWeight.Bold)
        Text(
            formatCents(bid),
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = valueColor,
            lineHeight = 36.sp
        )
        Text(
            "bid  ·  ask ${formatCents(ask)}",
            style = MaterialTheme.typography.bodyMedium,
            color = labelColor,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun OddsColumn(
    label: String,
    percent: Double?,
    bid: Double?,
    ask: Double?,
    accent: Color,
    modifier: Modifier = Modifier,
    endAligned: Boolean = false,
    big: Boolean = true
) {
    val bg = MaterialTheme.colorScheme.surface
    val labelColor = checklistLabelColor(bg)
    val valueColor = Contrast.readable(accent, bg, minRatio = Contrast.AA_LARGE)
    Column(
        modifier = modifier,
        horizontalAlignment = if (endAligned) Alignment.End else Alignment.Start
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = labelColor)
        Text(
            text = percent?.let { String.format(Locale.US, "%.1f%%", it) } ?: "—",
            fontSize = if (big) 36.sp else 22.sp,
            fontWeight = FontWeight.Bold,
            color = valueColor,
            lineHeight = if (big) 40.sp else 26.sp
        )
        if (bid != null || ask != null) {
            Text(
                text = "Bid ${formatCents(bid)} · Ask ${formatCents(ask)}",
                style = MaterialTheme.typography.labelMedium,
                color = labelColor,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    val bg = MaterialTheme.colorScheme.surface
    val labelColor = checklistLabelColor(bg)
    val valueColor = checklistValueColor(bg)
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = labelColor)
        Text(
            Contrast.display(value),
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun StatusChip(status: String?) {
    val label = status?.ifBlank { null } ?: "unknown"
    val color = when (label.lowercase(Locale.US)) {
        "active", "open" -> AccentGreen
        "closed", "determined" -> AccentOrange
        else -> AccentBlue
    }
    Text(
        text = label.uppercase(Locale.US),
        style = MaterialTheme.typography.labelMedium,
        color = color,
        fontWeight = FontWeight.Bold
    )
}

private fun formatCents(dollars: Double?): String =
    dollars?.let { String.format(Locale.US, "%.0f¢", it * 100) } ?: "—"

private fun formatCompact(value: Double?): String {
    if (value == null) return "—"
    return when {
        value >= 1_000_000 -> String.format(Locale.US, "%.2fM", value / 1_000_000)
        value >= 1_000 -> String.format(Locale.US, "%.1fK", value / 1_000)
        else -> String.format(Locale.US, "%.0f", value)
    }
}
