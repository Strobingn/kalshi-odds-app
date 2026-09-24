package com.dirk.kalshiodds.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.dirk.kalshiodds.signal.model.LiveCall
import com.dirk.kalshiodds.signal.checklist.PreTradeChecklist
import com.dirk.kalshiodds.ui.theme.AccentBlue
import com.dirk.kalshiodds.ui.theme.AccentGreen
import com.dirk.kalshiodds.ui.theme.AccentOrange
import com.dirk.kalshiodds.ui.theme.AccentRed
import com.dirk.kalshiodds.ui.theme.Border
import com.dirk.kalshiodds.ui.theme.Surface
import com.dirk.kalshiodds.ui.theme.TextSecondary
import java.util.Locale
import kotlin.math.abs

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MarketCard(
    market: MarketUiModel,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    liveCall: LiveCall? = null
) {
    val alertBorder = if (market.edgeAlert) AccentGreen else Border
    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(if (market.edgeAlert) 2.dp else 1.dp, alertBorder, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Surface)
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
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    market.subtitle?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    StatusChip(market.status)
                    Text(
                        text = market.ticker,
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary,
                        modifier = Modifier.padding(top = 6.dp)
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

            Spacer(Modifier.height(10.dp))
            val upPct = liveCall?.upPct ?: market.aiYesPercent
            val downPct = liveCall?.downPct ?: market.aiNoPercent
            val dir = liveCall?.direction ?: upPct?.let { LiveCall.directionFromUp(it) }
            val upColor = if (dir == LiveCall.UP) AccentGreen else TextSecondary
            val downColor = if (dir == LiveCall.DOWN) AccentRed else TextSecondary
            Text(
                text = dir ?: "UP / DOWN",
                fontSize = if (compact) 28.sp else 36.sp,
                fontWeight = FontWeight.Black,
                color = if (dir == LiveCall.DOWN) AccentRed else AccentGreen,
                lineHeight = if (compact) 30.sp else 40.sp
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                OddsColumn(
                    label = "UP",
                    percent = upPct,
                    bid = null,
                    ask = null,
                    accent = upColor,
                    modifier = Modifier.weight(1f),
                    big = true
                )
                OddsColumn(
                    label = "DOWN",
                    percent = downPct,
                    bid = null,
                    ask = null,
                    accent = downColor,
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
                    else -> TextSecondary
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
                        color = TextSecondary
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
                                color = TextSecondary
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
                            color = if (market.uncertaintyPassed) TextSecondary else AccentOrange,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    market.ensembleNote?.let { note ->
                        Text(
                            text = note,
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondary,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    market.extendedNote?.let { note ->
                        Text(
                            text = note,
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondary,
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
                        color = TextSecondary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            ChecklistBlock(market)

            if (!compact) {
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "Kalshi market (reference)",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
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
                        accent = TextSecondary,
                        modifier = Modifier.weight(1f),
                        big = false
                    )
                    OddsColumn(
                        label = "Mkt NO",
                        percent = market.noProbabilityPercent,
                        bid = market.noBid,
                        ask = market.noAsk,
                        accent = TextSecondary,
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
                Metric("Closes", market.closeTimeLocal ?: "—")
            }
        }
    }
}

@Composable
private fun ChecklistBlock(market: MarketUiModel) {
    val items = PreTradeChecklist.items(market)
    val clipboard = LocalClipboardManager.current
    var copied by remember(market.ticker) { mutableStateOf(false) }
    Spacer(Modifier.height(12.dp))
    Text(
        text = "PRE-TRADE CHECKLIST",
        style = MaterialTheme.typography.labelMedium,
        color = AccentBlue,
        fontWeight = FontWeight.Bold
    )
    Spacer(Modifier.height(4.dp))
    items.forEach { item ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(item.label, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
            Text(
                item.value,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
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
    Column(
        modifier = modifier,
        horizontalAlignment = if (endAligned) Alignment.End else Alignment.Start
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
        Text(
            text = percent?.let { String.format(Locale.US, "%.1f%%", it) } ?: "—",
            fontSize = if (big) 36.sp else 22.sp,
            fontWeight = FontWeight.Bold,
            color = accent,
            lineHeight = if (big) 40.sp else 26.sp
        )
        if (bid != null || ask != null) {
            Text(
                text = "Bid ${formatCents(bid)} · Ask ${formatCents(ask)}",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
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
