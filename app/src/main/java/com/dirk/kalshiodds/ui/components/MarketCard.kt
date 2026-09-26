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
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.trade.BetCall
import com.dirk.kalshiodds.signal.trade.TradeModeLabel
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.domain.MarketQuoteView
import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.signal.checklist.PreTradeChecklist
import com.dirk.kalshiodds.ui.DisagreementLabel
import com.dirk.kalshiodds.ui.HomeCopy
import com.dirk.kalshiodds.ui.HomeMarkets
import com.dirk.kalshiodds.ui.SideColor
import com.dirk.kalshiodds.ui.theme.Contrast
import com.dirk.kalshiodds.ui.theme.checklistLabelColor
import com.dirk.kalshiodds.ui.theme.checklistValueColor
import java.util.Locale
import kotlin.math.abs
import com.dirk.kalshiodds.ui.theme.DipTheme

@Composable
fun NextWindowLoadingCard(
    series: String,
    modifier: Modifier = Modifier
) {
    val colors = DipTheme.colors
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, scheme.outline, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = scheme.surface,
            contentColor = scheme.onSurface
        )
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = HomeCopy.coinShort(series),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        HomeCopy.WINDOW_LENGTH,
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textSecondary
                    )
                }
            }
            Text(
                HomeMarkets.NEXT_WINDOW_LOADING,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MarketCard(
    market: MarketUiModel,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    decision: BetCall.Decision? = null,
    settings: SignalSettings = SignalSettings(),
    paperTradingEnabled: Boolean = false,
    nowMs: Long? = null,
    onBuyYes: (() -> Unit)? = null,
    onBuyNo: (() -> Unit)? = null,
    onPaperUp: (() -> Unit)? = null,
    onPaperDown: (() -> Unit)? = null,
    paperPosition: String? = null,
    onSell: (() -> Unit)? = null,
    onOpenChart: (() -> Unit)? = null
) {
    val colors = DipTheme.colors
    val scheme = MaterialTheme.colorScheme
    val cardBg = scheme.surface
    val labelColor = checklistLabelColor(cardBg)
    val valueColor = checklistValueColor(cardBg)
    val call = decision ?: BetCall.decide(market, settings)
    val headlineColor = SideColor.of(call.headline, colors)
    val alertBorder = if (call.isActionable) headlineColor else scheme.outline
    val quotes = MarketQuoteView.of(market)
    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(if (call.isActionable) 2.dp else 1.dp, alertBorder, RoundedCornerShape(16.dp))
            .then(if (onOpenChart != null) Modifier.clickable(onClick = onOpenChart) else Modifier),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = cardBg,
            contentColor = valueColor
        )
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = HomeCopy.coinShort(market),
                        style = MaterialTheme.typography.titleLarge,
                        color = valueColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        HomeCopy.WINDOW_LENGTH,
                        style = MaterialTheme.typography.labelMedium,
                        color = labelColor
                    )
                }
                TimeLeftLabel(market.closeTimeEpochMs, compact = true, nowMs = nowMs)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HomeCopy.targetText(market)?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = labelColor)
                }
                HomeSpotDelta(market)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PriceTile(
                    title = "UP",
                    askLabel = quotes.yesAskLabel,
                    bidLabel = quotes.yesBidLabel,
                    aiLabel = HomeCopy.tileAiUp(market),
                    profitLabel = HomeCopy.tileTenDollarUp(market),
                    accent = colors.up,
                    container = colors.upContainer,
                    highlighted = call.headline == BetCall.Headline.BET_UP,
                    modifier = Modifier.weight(1f)
                )
                PriceTile(
                    title = "DOWN",
                    askLabel = quotes.noAskLabel,
                    bidLabel = quotes.noBidLabel,
                    aiLabel = HomeCopy.tileAiDown(market),
                    profitLabel = HomeCopy.tileTenDollarDown(market),
                    accent = colors.down,
                    container = colors.downContainer,
                    highlighted = call.headline == BetCall.Headline.BET_DOWN,
                    modifier = Modifier.weight(1f)
                )
            }

            OddsSparkline(
                points = market.oddsHistory,
                modifier = Modifier.fillMaxWidth(),
                color = colors.textSecondary
            )

            Text(
                text = call.label,
                style = MaterialTheme.typography.titleMedium,
                color = headlineColor,
                fontWeight = FontWeight.Bold
            )
            val disagreement = DisagreementLabel.of(market)
            if (disagreement != null && call.headline != BetCall.Headline.NO_BET) {
                DisagreementWarning(disagreement)
            } else if (disagreement == null) {
                Text(
                    text = HomeCopy.modelVsMarket(market, call),
                    style = MaterialTheme.typography.bodyMedium,
                    color = valueColor
                )
            }

            if (call.isActionable) {
                HomeCopy.allInProfit(call)?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                val mode = TradeModeLabel.forApprove(settings, call.ticket)
                val buy = if (call.headline == BetCall.Headline.BET_DOWN) onBuyNo else onBuyYes
                if (buy != null) {
                    Button(
                        onClick = buy,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = headlineColor,
                            contentColor = SideColor.on(call.headline, colors)
                        ),
                        modifier = Modifier.fillMaxWidth().height(56.dp)
                    ) {
                        Text(HomeCopy.primaryButtonLabel(mode, call), fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                if (disagreement != null && call.headline == BetCall.Headline.NO_BET) {
                    DisagreementWarning(disagreement)
                } else if (disagreement == null) {
                    call.noBetReason?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textSecondary
                        )
                    }
                }
                val anywaySide = HomeCopy.buyAnywaySide(market, call)
                val anyway = if (anywaySide == "NO") onBuyNo else onBuyYes
                if (anyway != null) {
                    OutlinedButton(
                        onClick = anyway,
                        modifier = Modifier.fillMaxWidth().height(40.dp)
                    ) { Text(HomeCopy.BUY_ANYWAY) }
                }
            }
            paperPosition?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textPrimary,
                    fontWeight = FontWeight.SemiBold
                )
            }
            val paperUpOk = HomeCopy.paperUpEnabled(market)
            val paperDownOk = HomeCopy.paperDownEnabled(market)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { onPaperUp?.invoke() },
                    enabled = onPaperUp != null && paperUpOk,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.up,
                        contentColor = colors.onUp,
                        disabledContainerColor = colors.up.copy(alpha = 0.38f),
                        disabledContentColor = colors.onUp.copy(alpha = 0.70f)
                    ),
                    modifier = Modifier.weight(1f).height(48.dp)
                ) { Text(HomeCopy.PAPER_UP, fontWeight = FontWeight.Bold, maxLines = 1) }
                Button(
                    onClick = { onPaperDown?.invoke() },
                    enabled = onPaperDown != null && paperDownOk,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.down,
                        contentColor = colors.onDown,
                        disabledContainerColor = colors.down.copy(alpha = 0.38f),
                        disabledContentColor = colors.onDown.copy(alpha = 0.70f)
                    ),
                    modifier = Modifier.weight(1f).height(48.dp)
                ) { Text(HomeCopy.PAPER_DOWN, fontWeight = FontWeight.Bold, maxLines = 1) }
            }
            if (!paperUpOk || !paperDownOk) {
                val reason = listOfNotNull(
                    HomeCopy.paperDisabledReason(market, "YES").takeIf { !paperUpOk },
                    HomeCopy.paperDisabledReason(market, "NO").takeIf { !paperDownOk }
                ).distinct().joinToString(" · ")
                if (reason.isNotBlank()) {
                    Text(
                        reason,
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textSecondary
                    )
                }
            }
            if (onSell != null) {
                OutlinedButton(
                    onClick = onSell,
                    modifier = Modifier.fillMaxWidth().height(40.dp)
                ) { Text("Sell") }
            }

            var detailsOpen by remember(market.ticker) { mutableStateOf(false) }
            Text(
                text = if (detailsOpen) "Hide details" else "Details",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .padding(top = 0.dp)
                    .clickable { detailsOpen = !detailsOpen }
            )
            if (detailsOpen) {
                DetailsBlock(
                    market = market,
                    quotes = quotes,
                    compact = compact,
                    labelColor = labelColor,
                    valueColor = valueColor,
                    nowMs = nowMs,
                    onOpenChart = onOpenChart
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailsBlock(
    market: MarketUiModel,
    quotes: MarketQuoteView,
    compact: Boolean,
    labelColor: Color,
    valueColor: Color,
    nowMs: Long?,
    onOpenChart: (() -> Unit)?
) {
    val colors = DipTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (market.edgeAlert) {
                    Text(
                        text = "⚡ Edge",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textPrimary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .background(colors.textSecondary.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
                chips.forEach { chip ->
                    val mutedChip = chip == "Muted"
                    val color = if (mutedChip) colors.accentOrange else colors.textSecondary
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
            Text(market.muteReason.orEmpty(), style = MaterialTheme.typography.labelMedium, color = colors.accentOrange)
        } else if (!market.passedFilter && market.skipReason != null) {
            Text("Filtered · ${market.skipReason}", style = MaterialTheme.typography.labelMedium, color = colors.accentOrange)
        }
        if (market.tapeConflict && market.tapeConflictNote != null) {
            Text(
                text = market.tapeConflictNote.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.accentOrange,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.accentOrange.copy(alpha = 0.14f), RoundedCornerShape(10.dp))
                    .padding(10.dp)
            )
            market.modelLeanSide?.let { lean ->
                Text(
                    text = "Model lean ${if (lean == "NO") "DOWN / NO" else "UP / YES"} · primary follows live tape",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.accentOrange
                )
            }
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
                color = valueColor,
                fontWeight = FontWeight.SemiBold
            )
        }
        Text("Payout  UP ${quotes.upMultipleLabel}  ·  DOWN ${quotes.downMultipleLabel}", style = MaterialTheme.typography.labelMedium, color = labelColor)
        market.aiNote?.let {
            Text(
                text = it + market.aiConfidence?.let { c ->
                    String.format(Locale.US, " · conf %.0f%%", c * 100)
                }.orEmpty(),
                style = MaterialTheme.typography.labelMedium,
                color = labelColor
            )
        }

        market.edgePp?.let { edge ->
            val edgeColor = when {
                abs(edge) >= 2.0 -> valueColor
                else -> labelColor
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(edgeColor.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Text(String.format(Locale.US, "Edge %+.1f pp", edge), style = MaterialTheme.typography.bodyMedium, color = edgeColor, fontWeight = FontWeight.Bold)
                market.netEdgePp?.let { net ->
                    Text(
                        text = String.format(
                            Locale.US,
                            "Net EV %+.1f pp  ·  %+.3f $/ct  ·  fee %.1f¢",
                            net,
                            market.netEvDollars ?: 0.0,
                            (market.feePerContract ?: 0.0) * 100.0
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = edgeColor
                    )
                }
                market.suggestedContracts?.let { n ->
                    Text("$n contracts max", style = MaterialTheme.typography.labelMedium, color = valueColor, fontWeight = FontWeight.SemiBold)
                    market.sizingNote?.let { note ->
                        Text(note, style = MaterialTheme.typography.labelMedium, color = labelColor)
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
                        color = if (market.uncertaintyPassed) labelColor else colors.accentOrange
                    )
                }
                market.ensembleNote?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = labelColor) }
                market.extendedNote?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = labelColor) }
                market.rlNote?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = labelColor) }
                market.stance?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = edgeColor, fontWeight = FontWeight.SemiBold) }
            }
        }

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
            heightDp = if (compact) 56 else 72,
            windowStartMs = market.closeTimeEpochMs?.minus(900_000L),
            windowEndMs = market.closeTimeEpochMs,
            strikeLabel = market.floorStrike?.let { String.format(Locale.US, "Strike $%,.0f", it) },
            spotUsd = market.spotUsd,
            strikeUsd = market.floorStrike,
            spotHeightDp = if (compact) 40 else 48,
            liveUpLabel = quotes.upChartLabel,
            liveDownLabel = quotes.downChartLabel
        )

        ChecklistBlock(market)

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Metric("Spread", market.spreadDollars?.let { String.format(Locale.US, "%.1f¢", it * 100) } ?: "—")
            Metric("Volume", formatCompact(market.volume))
            Metric("OI", formatCompact(market.openInterest))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Metric("24h vol", formatCompact(market.volume24h))
            Metric("Liquidity", market.liquidityDollars?.let { formatCompact(it) } ?: "—")
            Column {
                Text("Closes", style = MaterialTheme.typography.labelMedium, color = labelColor)
                TimeLeftLabel(market.closeTimeEpochMs, nowMs = nowMs)
                market.closeTimeLocal?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = labelColor)
                }
            }
        }
        Text(market.ticker, style = MaterialTheme.typography.labelMedium, color = labelColor)
        StatusChip(market.status)
    }
}

@Composable
private fun DisagreementWarning(copy: DisagreementLabel.Copy) {
    val colors = DipTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = copy.title,
                tint = colors.accentOrange
            )
            Text(
                copy.title,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.accentOrange,
                fontWeight = FontWeight.SemiBold
            )
        }
        Text(
            copy.detail,
            style = MaterialTheme.typography.labelMedium,
            color = colors.accentOrange
        )
    }
}

@Composable
private fun PriceTile(
    title: String,
    askLabel: String,
    bidLabel: String,
    aiLabel: String,
    profitLabel: String,
    accent: Color,
    container: Color,
    highlighted: Boolean,
    modifier: Modifier = Modifier
) {
    val labelColor = Contrast.readable(
        MaterialTheme.colorScheme.onSurfaceVariant,
        container,
        minRatio = Contrast.AA_LARGE
    )
    val valueColor = Contrast.readable(accent, container, minRatio = Contrast.AA)
    Column(
        modifier
            .then(
                if (highlighted) Modifier.border(2.dp, accent, RoundedCornerShape(12.dp))
                else Modifier.border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            )
            .background(container, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = labelColor,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            askLabel,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            "bid $bidLabel",
            style = MaterialTheme.typography.labelSmall,
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            aiLabel,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            profitLabel,
            style = MaterialTheme.typography.labelMedium,
            color = labelColor,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ChecklistBlock(market: MarketUiModel) {
    val colors = DipTheme.colors
    val items = PreTradeChecklist.items(market)
    val clipboard = LocalClipboardManager.current
    var copied by remember(market.ticker) { mutableStateOf(false) }
    val bg = MaterialTheme.colorScheme.surface
    val labelColor = checklistLabelColor(bg)
    val valueColor = checklistValueColor(bg)
    Spacer(Modifier.height(4.dp))
    Text(
        text = "PRE-TRADE CHECKLIST",
        style = MaterialTheme.typography.labelMedium,
        color = Contrast.readable(colors.textSecondary, bg, minRatio = Contrast.AA_LARGE),
        fontWeight = FontWeight.Bold
    )
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
    val colors = DipTheme.colors
    val label = status?.ifBlank { null } ?: "unknown"
    val color = when (label.lowercase(Locale.US)) {
        "active", "open" -> colors.textSecondary
        "closed", "determined" -> colors.accentOrange
        else -> colors.textSecondary
    }
    Text(
        text = label.uppercase(Locale.US),
        style = MaterialTheme.typography.labelMedium,
        color = color,
        fontWeight = FontWeight.Bold
    )
}

private fun formatCompact(value: Double?): String {
    if (value == null) return "—"
    return when {
        value >= 1_000_000 -> String.format(Locale.US, "%.2fM", value / 1_000_000)
        value >= 1_000 -> String.format(Locale.US, "%.1fK", value / 1_000)
        else -> String.format(Locale.US, "%.0f", value)
    }
}
