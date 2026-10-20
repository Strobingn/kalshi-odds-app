package com.dirk.kalshiodds.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.chart.BidPoint
import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.ui.charts.CalibrationPlot
import com.dirk.kalshiodds.ui.charts.ChartBlock
import com.dirk.kalshiodds.ui.charts.ChartGallery
import com.dirk.kalshiodds.ui.charts.ChartShapes
import com.dirk.kalshiodds.ui.charts.CumulativeDepthChart
import com.dirk.kalshiodds.ui.charts.FeeWaterfall
import com.dirk.kalshiodds.ui.charts.KnobTimeline
import com.dirk.kalshiodds.ui.charts.LineSeries
import com.dirk.kalshiodds.ui.charts.MultiLineChart
import java.util.Locale
import kotlin.math.roundToInt
import com.dirk.kalshiodds.ui.components.AiFairLabel
import com.dirk.kalshiodds.ui.components.BidChart
import com.dirk.kalshiodds.ui.components.MarketAskHero
import com.dirk.kalshiodds.ui.components.PastSettlementsRow
import com.dirk.kalshiodds.ui.components.TapeConflictBanner
import com.dirk.kalshiodds.ui.components.TargetNowLine
import com.dirk.kalshiodds.ui.components.TimeLeftLabel
import com.dirk.kalshiodds.ui.components.UpDownBuyButtons
import com.dirk.kalshiodds.ui.theme.DipTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChartDetailScreen(
    market: MarketUiModel,
    points: List<BidPoint>,
    onBack: () -> Unit,
    onBuyYes: (MarketUiModel) -> Unit,
    onBuyNo: (MarketUiModel) -> Unit,
    book: com.dirk.kalshiodds.signal.engine.BookLevelSnapshot? = null,
    calibration: List<Pair<Double, Boolean>> = emptyList(),
    windowFees: ChartGallery.FeeLeg? = null,
    dayFees: ChartGallery.FeeLeg? = null,
    knobs: List<ChartGallery.KnobTick> = emptyList()
) {
    val colors = DipTheme.colors
    Scaffold(
        containerColor = colors.bg,
        topBar = {
            TopAppBar(
                title = { Text(com.dirk.kalshiodds.ui.WindowLabel.of(market.ticker, market.closeTimeEpochMs)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TimeLeftLabel(market.closeTimeEpochMs, pill = true)
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.bg,
                    titleContentColor = colors.textPrimary,
                    navigationIconContentColor = colors.textPrimary,
                    actionIconContentColor = colors.textSecondary
                )
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Details · ${market.ticker}",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textSecondary
                )
                Spacer(Modifier.weight(1f))
            }
            TargetNowLine(market, Modifier.padding(top = 8.dp))
            TapeConflictBanner(market, Modifier.padding(top = 8.dp))
            MarketAskHero(market, Modifier.padding(top = 12.dp))
            AiFairLabel(market, Modifier.padding(top = 8.dp))
            PastSettlementsRow(market.pastSettlements, Modifier.padding(top = 8.dp, bottom = 12.dp))
            val quotes = com.dirk.kalshiodds.domain.MarketQuoteView.of(market)
            BidChart(
                points = points,
                modifier = Modifier.fillMaxWidth(),
                heightDp = 200,
                scrub = true,
                windowStartMs = market.closeTimeEpochMs?.minus(900_000L),
                windowEndMs = market.closeTimeEpochMs,
                strikeLabel = null,
                spotUsd = market.spotUsd,
                strikeUsd = market.floorStrike,
                spotHeightDp = 180,
                liveUpLabel = quotes.upChartLabel,
                liveDownLabel = quotes.downChartLabel
            )
            Text(
                "Orange = Coinbase/Binance spot with dashed TARGET. Green/red = UP/DOWN best bids. Drag to scrub.",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = 12.dp)
            )
            ChartBlock(
                "Bitcoin · this 15-minute window",
                "Recorded spot with the strike. No prints yet stays empty."
            ) {
                MultiLineChart(
                    series = listOf(LineSeries("BTC", colors.spot, ChartShapes.spotSamples(points), strokeDp = 3f)),
                    yFormat = { String.format(Locale.US, "$%,.0f", it) },
                    fixedRange = null,
                    guideY = market.floorStrike?.takeIf { it.isFinite() && it > 0.0 },
                    guideLabel = "Strike",
                    height = 240.dp,
                    contentLabel = "Bitcoin price"
                )
            }
            ChartBlock("UP and DOWN together", "Both bids on one scale, with the 50¢ line. Drag to read either side.") {
                MultiLineChart(
                    series = listOf(
                        LineSeries("UP", colors.upButton, ChartShapes.bidSamples(points, true), maxGapMs = 15_000),
                        LineSeries("DOWN", colors.downButton, ChartShapes.bidSamples(points, false), maxGapMs = 15_000)
                    ),
                    yFormat = { "${it.roundToInt()}¢" },
                    fixedRange = 0.0 to 100.0,
                    guideY = 50.0,
                    guideLabel = "50¢",
                    height = 240.dp,
                    contentLabel = "UP and DOWN bids"
                )
            }
            var aiSamples by remember(market.ticker) { mutableStateOf(emptyList<ChartShapes.Sample>()) }
            val aiNow = market.aiYesPercent?.takeIf { it.isFinite() && it in 0.0..100.0 }
                ?: market.importedModelPp?.takeIf { it.isFinite() && it in 0.0..100.0 }
            val stamp = points.lastOrNull()?.tMs
            LaunchedEffect(market.ticker, aiNow, stamp) {
                if (aiNow != null) {
                    val t = stamp ?: System.currentTimeMillis()
                    val last = aiSamples.lastOrNull()
                    if (last == null || last.tMs != t || last.value != aiNow) {
                        aiSamples = (aiSamples + ChartShapes.Sample(t, aiNow)).takeLast(360)
                    }
                }
            }
            ChartBlock(
                    "AI UP chance vs market UP",
                    "Market is the recorded UP bid. AI is marked as this screen sees it — older AI samples are not stored."
                ) {
                    MultiLineChart(
                        series = listOf(
                            LineSeries("Market UP", colors.upButton, ChartShapes.bidSamples(points, true), maxGapMs = 15_000),
                            LineSeries("AI UP", colors.textPrimary, aiSamples, dashed = true)
                        ),
                        yFormat = { "${it.roundToInt()}" },
                        fixedRange = 0.0 to 100.0,
                        guideY = 50.0,
                        guideLabel = "50",
                        height = 240.dp,
                        contentLabel = "AI UP chance versus market"
                    )
                }
            OrderBookCard(book)
            CumulativeDepthChart(ChartGallery.cumulativeDepth(book?.yes.orEmpty(), book?.no.orEmpty()))
            CalibrationPlot(ChartGallery.calibration(calibration))
            FeeWaterfall(windowFees, dayFees)
            KnobTimeline(knobs)
            UpDownBuyButtons(
                market = market,
                onBuyYes = { onBuyYes(market) },
                onBuyNo = { onBuyNo(market) },
                modifier = Modifier.padding(top = 16.dp)
            )
            Text(
                "Approve on the ticket is the only path that can place a real V2 order.",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
