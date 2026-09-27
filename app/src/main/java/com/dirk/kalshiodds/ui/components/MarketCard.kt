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
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.unit.sp
import com.dirk.kalshiodds.domain.MarketQuoteView
import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.signal.checklist.PreTradeChecklist
import com.dirk.kalshiodds.ui.DisagreementLabel
import com.dirk.kalshiodds.ui.HomeCardDetails
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
    onOpenChart: (() -> Unit)? = null,
    detailsInitiallyOpen: Boolean = true
) {
    val colors = DipTheme.colors
    val scheme = MaterialTheme.colorScheme
    val cardBg = scheme.surface
    val labelColor = checklistLabelColor(cardBg)
    val valueColor = checklistValueColor(cardBg)
    val clock = nowMs ?: System.currentTimeMillis()
    val call = decision ?: BetCall.decide(market, settings, clock)
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
                points = com.dirk.kalshiodds.chart.ChartSeriesBuilder.sparklineMidsPp(market.oddsHistory),
                modifier = Modifier.fillMaxWidth(),
                color = colors.textSecondary
            )

            LastMinutePlayBox(market = market, call = call, nowMs = clock)
            Text(
                text = when {
                    market.lastMinute != null ->
                        com.dirk.kalshiodds.signal.lastminute.LastMinuteCopy.headline(market.lastMinute)
                    call.headline != BetCall.Headline.NO_BET -> call.label
                    !call.noBetReason.isNullOrBlank() -> call.noBetReason!!
                    else -> call.label
                },
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
                } else if (disagreement == null && market.lastMinute == null) {
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
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Text(
                        HomeCopy.PAPER_UP,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Clip
                    )
                }
                Button(
                    onClick = { onPaperDown?.invoke() },
                    enabled = onPaperDown != null && paperDownOk,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.down,
                        contentColor = colors.onDown,
                        disabledContainerColor = colors.down.copy(alpha = 0.38f),
                        disabledContentColor = colors.onDown.copy(alpha = 0.70f)
                    ),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Text(
                        HomeCopy.PAPER_DOWN,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Clip
                    )
                }
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

            var detailsOpen by remember(market.ticker) { mutableStateOf(detailsInitiallyOpen) }
            Text(
                text = HomeCardDetails.detailsToggleLabel(detailsOpen),
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
                    call = call,
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
    call: BetCall.Decision,
    compact: Boolean,
    labelColor: Color,
    valueColor: Color,
    nowMs: Long?,
    onOpenChart: (() -> Unit)?
) {
    val colors = DipTheme.colors
    val details = HomeCardDetails.of(market, call, nowMs ?: System.currentTimeMillis())
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
        details.conflict?.let { conflict ->
            Text(
                text = conflict,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.accentOrange,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.accentOrange.copy(alpha = 0.14f), RoundedCornerShape(10.dp))
                    .padding(10.dp)
            )
            details.modelLean?.let { lean ->
                Text(
                    text = lean,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.accentOrange
                )
            }
        }
        details.value?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = valueColor,
                fontWeight = FontWeight.SemiBold
            )
        }

        Text(
            text = HomeCardDetails.LIVE_BOOK,
            style = MaterialTheme.typography.labelMedium,
            color = colors.accentBlue,
            fontWeight = FontWeight.Bold
        )
        details.targetVsSpot?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = labelColor)
        }
        details.spotMove?.let {
            if (details.targetVsSpot == null || !details.targetVsSpot.contains(it)) {
                Text("Spot move $it", style = MaterialTheme.typography.labelMedium, color = labelColor)
            }
        }

        Text(
            text = HomeCardDetails.SECTION,
            style = MaterialTheme.typography.labelMedium,
            color = colors.accentBlue,
            fontWeight = FontWeight.Bold
        )
        details.reasons?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = labelColor)
        }
        details.confidence?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = valueColor, fontWeight = FontWeight.SemiBold)
        }
        details.signalStrength?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = valueColor, fontWeight = FontWeight.SemiBold)
        }
        details.drivers?.let {
            Text(
                text = "Drivers  $it",
                style = MaterialTheme.typography.labelMedium,
                color = labelColor
            )
        }
        Text(
            text = details.modelVsMarket,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
            fontWeight = FontWeight.SemiBold
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            OddsColumn(
                label = details.aiYesLabel,
                percentLabel = details.aiYes,
                accent = colors.up,
                modifier = Modifier.weight(1f),
                big = true
            )
            OddsColumn(
                label = details.aiNoLabel,
                percentLabel = details.aiNo,
                accent = colors.down,
                modifier = Modifier.weight(1f),
                endAligned = true,
                big = true
            )
        }

        details.fairValue?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = valueColor,
                fontWeight = FontWeight.SemiBold
            )
        }
        details.payout?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = labelColor)
        }

        details.edge?.let { edgeLine ->
            val edge = market.edgePp ?: 0.0
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
                Text(
                    HomeCardDetails.EDGE_TITLE,
                    style = MaterialTheme.typography.labelMedium,
                    color = labelColor
                )
                Text(
                    text = edgeLine,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = edgeColor,
                    lineHeight = 32.sp
                )
                details.netEv?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = edgeColor,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                details.sizing?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = valueColor, fontWeight = FontWeight.SemiBold)
                }
                details.microstructure?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (market.uncertaintyPassed) labelColor else colors.accentOrange
                    )
                }
                details.notes?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = labelColor) }
                details.stance?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = edgeColor, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    text = HomeCardDetails.FAIR_NOTE,
                    style = MaterialTheme.typography.labelMedium,
                    color = labelColor
                )
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

        Text(
            text = HomeCardDetails.MARKET_REF,
            style = MaterialTheme.typography.labelMedium,
            color = labelColor,
            fontWeight = FontWeight.Bold
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            OddsColumn(
                label = details.mktYesLabel,
                percentLabel = details.mktYes,
                bidLabel = quotes.yesBidLabel,
                askLabel = quotes.yesAskLabel,
                accent = valueColor,
                modifier = Modifier.weight(1f),
                big = false
            )
            OddsColumn(
                label = details.mktNoLabel,
                percentLabel = details.mktNo,
                bidLabel = quotes.noBidLabel,
                askLabel = quotes.noAskLabel,
                accent = valueColor,
                modifier = Modifier.weight(1f),
                endAligned = true,
                big = false
            )
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Metric("Spread", details.spread)
            Metric("Volume", details.volume)
            Metric("OI", details.openInterest)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Metric("24h vol", details.volume24h)
            Metric("Liquidity", details.liquidity)
            Column {
                Text("Time left", style = MaterialTheme.typography.labelMedium, color = labelColor)
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
private fun OddsColumn(
    label: String,
    percentLabel: String,
    accent: Color,
    modifier: Modifier = Modifier,
    bidLabel: String? = null,
    askLabel: String? = null,
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
            text = percentLabel,
            fontSize = if (big) 36.sp else 22.sp,
            fontWeight = FontWeight.Bold,
            color = valueColor,
            lineHeight = if (big) 40.sp else 26.sp
        )
        if (bidLabel != null || askLabel != null) {
            Text(
                text = "Bid ${bidLabel ?: "—"} · Ask ${askLabel ?: "—"}",
                style = MaterialTheme.typography.labelMedium,
                color = labelColor,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
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

private fun lastMinuteFallback(
    market: MarketUiModel,
    nowMs: Long
): com.dirk.kalshiodds.signal.lastminute.LastMinuteSnapshot {
    val close = market.closeTimeEpochMs
    val tau = if (close != null) ((close - nowMs) / 1000L).toInt() else 900
    return when {
        tau <= 0 -> com.dirk.kalshiodds.signal.lastminute.LastMinuteSnapshot(
            phase = com.dirk.kalshiodds.signal.lastminute.LastMinutePhase.NO_PLAY,
            tauSec = 0,
            startsInMs = null
        )
        tau > 60 -> com.dirk.kalshiodds.signal.lastminute.LastMinuteSnapshot(
            phase = com.dirk.kalshiodds.signal.lastminute.LastMinutePhase.WAITING,
            tauSec = tau,
            startsInMs = (tau - 60).toLong() * 1000L
        )
        else -> com.dirk.kalshiodds.signal.lastminute.LastMinuteSnapshot(
            phase = com.dirk.kalshiodds.signal.lastminute.LastMinutePhase.LIVE,
            tauSec = tau,
            startsInMs = null
        )
    }
}

@Composable
internal fun LastMinutePlayBox(
    market: MarketUiModel,
    call: BetCall.Decision,
    nowMs: Long
) {
    val snap = market.lastMinute ?: lastMinuteFallback(market, nowMs)
    val colors = DipTheme.colors
    val fired = snap.phase == com.dirk.kalshiodds.signal.lastminute.LastMinutePhase.FIRED
    val border = when {
        fired && call.headline == BetCall.Headline.BET_DOWN -> colors.down
        fired && call.headline == BetCall.Headline.BET_UP -> colors.up
        else -> MaterialTheme.colorScheme.outline
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(if (fired) 2.dp else 1.dp, border, RoundedCornerShape(12.dp))
            .background(
                if (fired && call.headline == BetCall.Headline.BET_DOWN) colors.downContainer
                else if (fired) colors.upContainer.copy(alpha = if (call.headline == BetCall.Headline.BET_UP) 1f else 0.35f)
                else Color.Transparent,
                RoundedCornerShape(12.dp)
            )
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            com.dirk.kalshiodds.signal.lastminute.LastMinuteCopy.TITLE,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = colors.textSecondary
        )
        Text(
            com.dirk.kalshiodds.signal.lastminute.LastMinuteCopy.UNPROVEN_SUBTITLE,
            style = MaterialTheme.typography.labelMedium,
            color = colors.accentOrange
        )
        when (snap.phase) {
            com.dirk.kalshiodds.signal.lastminute.LastMinutePhase.WAITING -> {
                Text(
                    com.dirk.kalshiodds.signal.lastminute.LastMinuteCopy.waiting(snap.startsInMs),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            com.dirk.kalshiodds.signal.lastminute.LastMinutePhase.LIVE -> {
                Text(
                    com.dirk.kalshiodds.signal.lastminute.LastMinuteCopy.sideLiveLine(snap.up),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.up,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    com.dirk.kalshiodds.signal.lastminute.LastMinuteCopy.sideLiveLine(snap.down),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.down,
                    fontWeight = FontWeight.SemiBold
                )
            }
            com.dirk.kalshiodds.signal.lastminute.LastMinutePhase.FIRED -> {
                snap.fired?.let {
                    Text(
                        com.dirk.kalshiodds.signal.lastminute.LastMinuteCopy.buyLine(it),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (it.side.equals("NO", true)) colors.down else colors.up
                    )
                    Text(
                        com.dirk.kalshiodds.signal.lastminute.LastMinuteCopy.modelEvLine(it.evPerDollar),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textSecondary
                    )
                    if (it.depthLimited) {
                        Text(
                            com.dirk.kalshiodds.signal.lastminute.LastMinuteCopy.DEPTH_LIMITED,
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.accentOrange
                        )
                    }
                }
            }
            com.dirk.kalshiodds.signal.lastminute.LastMinutePhase.NO_PLAY -> {
                Text(
                    com.dirk.kalshiodds.signal.lastminute.LastMinuteCopy.NO_PLAY,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        Text(
            com.dirk.kalshiodds.signal.lastminute.LastMinuteCopy.spotSourceLine(snap),
            style = MaterialTheme.typography.labelMedium,
            color = colors.textSecondary
        )
    }
}

private fun formatCompact(value: Double?): String {
    if (value == null) return "—"
    return when {
        value >= 1_000_000 -> String.format(Locale.US, "%.2fM", value / 1_000_000)
        value >= 1_000 -> String.format(Locale.US, "%.1fK", value / 1_000)
        else -> String.format(Locale.US, "%.0f", value)
    }
}
