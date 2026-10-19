package com.dirk.kalshiodds.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dirk.kalshiodds.domain.MarketQuoteView
import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.trade.BetCall
import com.dirk.kalshiodds.ui.DisagreementLabel
import com.dirk.kalshiodds.ui.HomeCardDetails
import com.dirk.kalshiodds.ui.HomeCopy
import com.dirk.kalshiodds.ui.SideColor
import com.dirk.kalshiodds.ui.theme.DipTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Enhanced Market Card with improved visualizations:
 * - Probability gauge (donut chart)
 * - Edge vs Market comparison bar
 * - Enhanced sparkline with gradient
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EnhancedMarketCard(
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
    val labelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    val valueColor = MaterialTheme.colorScheme.onSurface
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
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header with coin name and time
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

            // Probability Gauge Row
            ProbabilityGaugeRow(
                aiYesPercent = market.aiYesPercent,
                aiNoPercent = market.aiNoPercent,
                marketMid = market.yesProbabilityPercent,
                modifier = Modifier.fillMaxWidth()
            )

            // Edge vs Market Comparison
            EdgeComparisonBar(
                edgePercent = market.edgePercent ?: 0.0,
                marketMid = market.yesProbabilityPercent,
                modifier = Modifier.fillMaxWidth()
            )

            // Target and Spot Delta
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

            // Price Tiles with Enhanced Styling
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                EnhancedPriceTile(
                    title = "UP",
                    askLabel = quotes.yesAskLabel,
                    bidLabel = quotes.yesBidLabel,
                    aiLabel = HomeCopy.tileAiUp(market),
                    profitLabel = HomeCopy.tileTenDollarUp(market),
                    accent = colors.up,
                    container = colors.upContainer,
                    highlighted = call.headline == BetCall.Headline.BET_UP,
                    modifier = Modifier.weight(1f),
                    progress = (market.aiYesPercent ?: 50.0) / 100.0f
                )
                EnhancedPriceTile(
                    title = "DOWN",
                    askLabel = quotes.noAskLabel,
                    bidLabel = quotes.noBidLabel,
                    aiLabel = HomeCopy.tileAiDown(market),
                    profitLabel = HomeCopy.tileTenDollarDown(market),
                    accent = colors.down,
                    container = colors.downContainer,
                    highlighted = call.headline == BetCall.Headline.BET_DOWN,
                    modifier = Modifier.weight(1f),
                    progress = (market.aiNoPercent ?: 50.0) / 100.0f
                )
            }

            // Enhanced Sparkline with Gradient
            EnhancedOddsSparkline(
                points = com.dirk.kalshiodds.chart.ChartSeriesBuilder.sparklineMidsPp(market.oddsHistory),
                modifier = Modifier.fillMaxWidth().height(64.dp),
                color = headlineColor,
                marketMid = market.yesProbabilityPercent
            )

            // Decision Label with Icon
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when (call.headline) {
                    BetCall.Headline.BET_UP -> Icon(
                        Icons.Default.ArrowDropUp,
                        contentDescription = "Bet UP",
                        tint = colors.up,
                        modifier = Modifier.size(24.dp)
                    )
                    BetCall.Headline.BET_DOWN -> Icon(
                        Icons.Default.ArrowDropDown,
                        contentDescription = "Bet DOWN",
                        tint = colors.down,
                        modifier = Modifier.size(24.dp)
                    )
                    else -> Icon(
                        Icons.Default.Warning,
                        contentDescription = "No Bet",
                        tint = colors.textSecondary,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Text(
                    text = call.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = headlineColor,
                    fontWeight = FontWeight.Bold
                )
            }

            // Model vs Market Text
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

            // Action Buttons
            if (call.isActionable) {
                HomeCopy.allInProfit(call)?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                val mode = com.dirk.kalshiodds.signal.trade.TradeModeLabel.forApprove(settings, call.ticket)
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
                ).distinct().joinToString(" - ")
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
                ) { Text(HomeCopy.SELL) }
            }
        }
    }
}

@Composable
fun ProbabilityGaugeRow(
    aiYesPercent: Double?,
    aiNoPercent: Double?,
    marketMid: Double?,
    modifier: Modifier = Modifier
) {
    val colors = DipTheme.colors
    val aiYes = aiYesPercent ?: 50.0
    val aiNo = aiNoPercent ?: 50.0
    val mid = marketMid ?: 50.0

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DonutChart(
            yesPercent = aiYes,
            noPercent = aiNo,
            modifier = Modifier.size(80.dp),
            strokeWidth = 8.dp
        )

        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "AI Prediction",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Text(
                text = "YES: ${String.format("%.1f", aiYes)}%  NO: ${String.format("%.1f", aiNo)}%",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Market Mid: ${String.format("%.1f", mid)}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
fun DonutChart(
    yesPercent: Double,
    noPercent: Double,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 8.dp
) {
    val colors = DipTheme.colors
    val total = yesPercent + noPercent
    val yesSweep = (yesPercent / total * 360f).toFloat()
    val noSweep = (noPercent / total * 360f).toFloat()

    val strokeWidthPx = with(LocalDensity.current) { strokeWidth.toPx() }

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2, size.height / 2)
        val radius = (size.minDimension - strokeWidthPx) / 2

        drawArc(
            color = colors.down,
            startAngle = 0f,
            sweepAngle = noSweep,
            useCenter = false,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
            style = Stroke(width = strokeWidthPx)
        )

        drawArc(
            color = colors.up,
            startAngle = noSweep,
            sweepAngle = yesSweep,
            useCenter = false,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
            style = Stroke(width = strokeWidthPx, cap = StrokeCap.Butt)
        )

        drawCircle(
            color = MaterialTheme.colorScheme.surface,
            radius = radius - strokeWidthPx / 2,
            center = center
        )

        drawContext.canvas.nativeCanvas.apply {
            val text = String.format("%.0f%%", yesPercent)
            val paint = android.graphics.Paint().apply {
                textSize = 14.sp.toPx()
                textAlign = android.graphics.Paint.Align.CENTER
                color = android.graphics.Color.WHITE
                setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD))
            }
            drawText(text, center.x, center.y + paint.textSize / 3, paint)
        }
    }
}

@Composable
fun EdgeComparisonBar(
    edgePercent: Double,
    marketMid: Double?,
    modifier: Modifier = Modifier
) {
    val colors = DipTheme.colors
    val mid = marketMid ?: 50.0
    val edge = edgePercent

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "Edge Analysis",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(4.dp)
                )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth((mid / 100f).coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(4.dp)
                    )
            )

            val aiPosition = (mid + edge) / 100f
            Box(
                modifier = Modifier
                    .fillMaxWidth(aiPosition.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(colors.up, colors.down)
                        ),
                        shape = RoundedCornerShape(4.dp)
                    )
            )

            val arrowPosition = (mid / 100f).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .padding(start = (size.width * arrowPosition).dp)
                    .size(20.dp)
                    .background(
                        color = if (edge > 0) colors.up else colors.down,
                        shape = CircleShape
                    )
            ) {
                Icon(
                    imageVector = if (edge > 0) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                    contentDescription = "Edge",
                    tint = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Text(
            text = "Edge: ${String.format("%.2f", edge)}pp | Market: ${String.format("%.1f", mid)}%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

@Composable
fun EnhancedPriceTile(
    title: String,
    askLabel: String,
    bidLabel: String,
    aiLabel: String?,
    profitLabel: String?,
    accent: Color,
    container: Color,
    highlighted: Boolean,
    modifier: Modifier = Modifier,
    progress: Float = 0.5f
) {
    val colors = DipTheme.colors

    Column(
        modifier = modifier
            .background(
                color = if (highlighted) container else container.copy(alpha = 0.3f),
                shape = RoundedCornerShape(12.dp)
            )
            .border(
                width = if (highlighted) 2.dp else 1.dp,
                color = if (highlighted) accent else accent.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp)
            )
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            fontWeight = FontWeight.Bold
        )

        Text(
            text = aiLabel ?: "--",
            style = MaterialTheme.typography.titleLarge,
            color = accent,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = "Ask: $askLabel",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )

        Text(
            text = "Bid: $bidLabel",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )

        profitLabel?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = colors.textPrimary,
                fontWeight = FontWeight.SemiBold
            )
        }

        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(4.dp),
            color = accent,
            trackColor = accent.copy(alpha = 0.3f)
        )
    }
}

@Composable
fun EnhancedOddsSparkline(
    points: List<Float>,
    modifier: Modifier = Modifier,
    color: Color,
    marketMid: Double?
) {
    val colors = DipTheme.colors
    val mid = marketMid ?: 50.0
    val midY = (mid / 100f).coerceIn(0f, 1f)

    if (points.size < 2) {
        Box(
            modifier = modifier
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (points.isEmpty()) "Chart warming up" else "Need 2 prints",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        return
    }

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val padding = 8.dp.toPx()
        val drawWidth = width - padding * 2
        val drawHeight = height - padding * 2

        val minY = points.minOrNull() ?: 0f
        val maxY = points.maxOrNull() ?: 100f
        val range = maxY - minY

        drawRoundRect(
            color = MaterialTheme.colorScheme.surface,
            topLeft = Offset(padding, padding),
            size = androidx.compose.ui.geometry.Size(drawWidth, drawHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx())
        )

        drawLine(
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
            start = Offset(padding, padding + drawHeight / 2),
            end = Offset(padding + drawWidth, padding + drawHeight / 2),
            strokeWidth = 1.dp.toPx()
        )

        val midYPos = padding + drawHeight * (1 - ((mid / 100f - minY) / range).coerceIn(0f, 1f))
        drawLine(
            color = colors.accentBlue.copy(alpha = 0.5f),
            start = Offset(padding, midYPos),
            end = Offset(padding + drawWidth, midYPos),
            strokeWidth = 1.5.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 3.dp.toPx()), 0f)
        )

        val path = Path().apply {
            val stepX = drawWidth / (points.size - 1)
            points.forEachIndexed { i, y ->
                val x = padding + i * stepX
                val yPos = padding + drawHeight * (1 - ((y - minY) / range).coerceIn(0f, 1f))
                if (i == 0) {
                    moveTo(x, yPos)
                } else {
                    lineTo(x, yPos)
                }
            }
        }

        drawPath(
            path = path,
            color = color,
            style = Stroke(
                width = 2.5.dp.toPx(),
                cap = StrokeCap.Round,
                join = androidx.compose.ui.graphics.PathEffect.Join.Round
            )
        )

        val fillPath = Path().apply {
            val stepX = drawWidth / (points.size - 1)
            points.forEachIndexed { i, y ->
                val x = padding + i * stepX
                val yPos = padding + drawHeight * (1 - ((y - minY) / range).coerceIn(0f, 1f))
                if (i == 0) {
                    moveTo(x, padding + drawHeight)
                    lineTo(x, yPos)
                } else {
                    lineTo(x, yPos)
                }
            }
            lineTo(padding + drawWidth, padding + drawHeight)
            lineTo(padding, padding + drawHeight)
            close()
        }

        drawPath(
            path = fillPath,
            color = color.copy(alpha = 0.15f)
        )

        val lastX = padding + drawWidth
        val lastY = padding + drawHeight * (1 - ((points.last() - minY) / range).coerceIn(0f, 1f))
        drawCircle(
            color = color,
            radius = 4.dp.toPx(),
            center = Offset(lastX, lastY)
        )
    }
}
