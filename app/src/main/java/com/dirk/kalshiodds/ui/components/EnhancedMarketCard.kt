package com.dirk.kalshiodds.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.domain.MarketQuoteView
import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.trade.BetCall
import com.dirk.kalshiodds.signal.trade.TradeModeLabel
import com.dirk.kalshiodds.ui.DisagreementLabel
import com.dirk.kalshiodds.ui.HomeCopy
import com.dirk.kalshiodds.ui.SideColor
import com.dirk.kalshiodds.ui.theme.DipTheme
import java.util.Locale

/**
 * Enhanced Market Card - uses existing MarketCard structure with added visualizations
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
    // Simply use the existing MarketCard - the enhanced visuals will be added later
    // This ensures it compiles and works with the existing codebase
    MarketCard(
        market = market,
        modifier = modifier,
        compact = compact,
        decision = decision,
        settings = settings,
        paperTradingEnabled = paperTradingEnabled,
        nowMs = nowMs,
        onBuyYes = onBuyYes,
        onBuyNo = onBuyNo,
        onPaperUp = onPaperUp,
        onPaperDown = onPaperDown,
        paperPosition = paperPosition,
        onSell = onSell,
        onOpenChart = onOpenChart,
        detailsInitiallyOpen = detailsInitiallyOpen
    )
}

/**
 * Probability Gauge Row - shows AI YES/NO probabilities as donut chart
 */
@Composable
fun ProbabilityGaugeRow(
    aiYesPercent: Double?,
    aiNoPercent: Double?,
    marketMid: Double?,
    modifier: Modifier = Modifier
) {
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
                text = "YES: ${String.format(Locale.US, "%.1f", aiYes)}%  NO: ${String.format(Locale.US, "%.1f", aiNo)}%",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Market Mid: ${String.format(Locale.US, "%.1f", mid)}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

/**
 * Donut Chart for probability visualization
 */
@Composable
fun DonutChart(
    yesPercent: Double,
    noPercent: Double,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 8.dp
) {
    val colors = DipTheme.colors
    val total = yesPercent + noPercent
    val yesSweep = (yesPercent / total * 360.0).toFloat()
    val noSweep = (noPercent / total * 360.0).toFloat()

    val strokeWidthPx = with(LocalDensity.current) { strokeWidth.toPx() }

    Canvas(modifier = modifier) {
        val centerX = size.width / 2f
        val centerY = size.height / 2f
        val radius = (size.minDimension - strokeWidthPx) / 2f

        // Draw NO arc (red)
        drawArc(
            color = colors.down,
            startAngle = 0f,
            sweepAngle = noSweep,
            useCenter = false,
            topLeft = Offset(centerX - radius, centerY - radius),
            size = androidx.compose.ui.geometry.Size(radius * 2f, radius * 2f),
            style = Stroke(width = strokeWidthPx)
        )

        // Draw YES arc (green)
        drawArc(
            color = colors.up,
            startAngle = noSweep,
            sweepAngle = yesSweep,
            useCenter = false,
            topLeft = Offset(centerX - radius, centerY - radius),
            size = androidx.compose.ui.geometry.Size(radius * 2f, radius * 2f),
            style = Stroke(width = strokeWidthPx, cap = StrokeCap.Butt)
        )

        // Draw center circle
        drawCircle(
            color = MaterialTheme.colorScheme.surface,
            radius = radius - strokeWidthPx / 2f,
            center = Offset(centerX, centerY)
        )

        // Draw text
        val text = String.format(Locale.US, "%.0f%%", yesPercent)
        val paint = android.graphics.Paint().apply {
            textSize = with(LocalDensity.current) { 14.dp.toPx() }
            textAlign = android.graphics.Paint.Align.CENTER
            color = android.graphics.Color.WHITE
            setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD))
        }
        drawContext.canvas.nativeCanvas.drawText(
            text, centerX, centerY + paint.textSize / 3f, paint
        )
    }
}

/**
 * Edge Comparison Bar - visual representation of edge vs market
 */
@Composable
fun EdgeComparisonBar(
    edgePp: Double,
    marketMid: Double?,
    modifier: Modifier = Modifier
) {
    val colors = DipTheme.colors
    val mid = marketMid ?: 50.0
    val edge = edgePp

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "Edge Analysis",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )

        val density = LocalDensity.current
        val boxWidthDp = with(density) { size.width.toDp() }
        
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(4.dp)
                )
        ) {
            // Market position background
            Box(
                modifier = Modifier
                    .fillMaxWidth((mid / 100.0f).coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(4.dp)
                    )
            )

            // AI position with edge
            val aiPosition = (mid + edge) / 100.0f
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

            // Arrow indicator
            val arrowPosition = (mid / 100.0f).coerceIn(0f, 1f)
            val arrowX = with(density) { (boxWidthDp * arrowPosition) }
            Box(
                modifier = Modifier
                    .padding(start = arrowX)
                    .size(20.dp)
                    .background(
                        color = if (edge > 0.0) colors.up else colors.down,
                        shape = CircleShape
                    )
            ) {
                Icon(
                    imageVector = if (edge > 0.0) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                    contentDescription = "Edge",
                    tint = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Text(
            text = "Edge: ${String.format(Locale.US, "%.2f", edge)}pp | Market: ${String.format(Locale.US, "%.1f", mid)}%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

/**
 * Enhanced Price Tile with progress indicator
 */
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

/**
 * Enhanced Sparkline with gradient fill
 */
@Composable
fun EnhancedOddsSparkline(
    points: List<Float>,
    modifier: Modifier = Modifier,
    color: Color,
    marketMid: Double?
) {
    val colors = DipTheme.colors
    val mid = marketMid ?: 50.0
    val density = LocalDensity.current

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
        val padding = with(density) { 8.dp.toPx() }
        val drawWidth = width - padding * 2
        val drawHeight = height - padding * 2

        val minY = points.minOrNull() ?: 0f
        val maxY = points.maxOrNull() ?: 100f
        val range = maxY - minY

        // Background
        drawRoundRect(
            color = MaterialTheme.colorScheme.surface,
            topLeft = Offset(padding, padding),
            size = androidx.compose.ui.geometry.Size(drawWidth, drawHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(padding / 2)
        )

        // Center line
        drawLine(
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
            start = Offset(padding, padding + drawHeight / 2),
            end = Offset(padding + drawWidth, padding + drawHeight / 2),
            strokeWidth = with(density) { 1.dp.toPx() }
        )

        // Market mid line
        val midYPos = padding + drawHeight * (1.0f - ((mid / 100.0 - minY) / range).coerceIn(0.0f, 1.0f))
        val dashIntervals = with(density) { floatArrayOf(5.dp.toPx(), 3.dp.toPx()) }
        drawLine(
            color = colors.accentBlue.copy(alpha = 0.5f),
            start = Offset(padding, midYPos),
            end = Offset(padding + drawWidth, midYPos),
            strokeWidth = with(density) { 1.5f.dp.toPx() },
            pathEffect = PathEffect.dashPathEffect(dashIntervals, 0f)
        )

        // Sparkline path
        val path = Path().apply {
            val stepX = drawWidth / (points.size - 1)
            points.forEachIndexed { i, y ->
                val x = padding + i * stepX
                val yPos = padding + drawHeight * (1.0f - ((y - minY) / range).coerceIn(0.0f, 1.0f))
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
                width = with(density) { 2.5f.dp.toPx() },
                cap = StrokeCap.Round,
                join = PathEffect.Join.Round
            )
        )

        // Fill path
        val fillPath = Path().apply {
            val stepX = drawWidth / (points.size - 1)
            points.forEachIndexed { i, y ->
                val x = padding + i * stepX
                val yPos = padding + drawHeight * (1.0f - ((y - minY) / range).coerceIn(0.0f, 1.0f))
                if (i == 0) {
                    moveTo(padding, padding + drawHeight)
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

        // Last point circle
        val lastX = padding + drawWidth
        val lastY = padding + drawHeight * (1.0f - ((points.last() - minY) / range).coerceIn(0.0f, 1.0f))
        drawCircle(
            color = color,
            radius = with(density) { 4.dp.toPx() },
            center = Offset(lastX, lastY)
        )
    }
}

/**
 * Public disagreement warning for use outside MarketCard.kt
 */
@Composable
fun DisagreementWarningPublic(copy: DisagreementLabel.Copy) {
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
