package com.dirk.kalshiodds.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.dirk.kalshiodds.decision.ScalpTrade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.ui.charts.ChartGallery
import com.dirk.kalshiodds.ui.charts.StrategySideBars
import com.dirk.kalshiodds.ui.charts.WinRateHeatmap

/** Charts added on the Scalp Analysis tab. Existing cards stay where they are. */
@Composable
internal fun AnalysisGallery(trades: List<ScalpTrade>) {
    val groups = remember(trades) { ChartGallery.sideGroups(trades) }
    val heat = remember(trades) { ChartGallery.winRateByHourCoin(trades) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ArenaCard { StrategySideBars(groups) }
        ArenaCard { WinRateHeatmap(heat) }
    }
}
