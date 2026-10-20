package com.dirk.kalshiodds.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.dirk.kalshiodds.decision.ScalpTrade
import com.dirk.kalshiodds.ui.charts.ChartGallery
import com.dirk.kalshiodds.ui.charts.StrategySideBars

/** Charts added on the Scalp Analysis tab. Existing cards stay where they are. */
@Composable
internal fun AnalysisGallery(trades: List<ScalpTrade>) {
    val groups = remember(trades) { ChartGallery.sideGroups(trades) }
    ArenaCard { StrategySideBars(groups) }
}
