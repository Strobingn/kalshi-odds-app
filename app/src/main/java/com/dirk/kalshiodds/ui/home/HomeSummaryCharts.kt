package com.dirk.kalshiodds.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dirk.kalshiodds.ui.charts.NetBar
import com.dirk.kalshiodds.ui.charts.RankedNetBars
import com.dirk.kalshiodds.ui.charts.WinRateGauge

@Composable
internal fun HomeBestScalperChart(best24: RankedScalper?, best7: RankedScalper?) =
    RankedNetBars(
        listOfNotNull(
            best24?.let { NetBar("24h · ${it.label}", it.netUsd) },
            best7?.let { NetBar("7d · ${it.label}", it.netUsd) }
        ),
        Modifier.fillMaxWidth().padding(top = 4.dp),
        ranked = false,
        labelWidthDp = 120
    )

@Composable
internal fun HomeTodayCharts(summary: DailySummary) {
    if (summary.trades <= 0) return
    Column(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        WinRateGauge(summary.winRate, summary.trades, size = 88.dp, unit = "trades")
        RankedNetBars(
            listOfNotNull(
                summary.best?.let { NetBar("Best · ${it.label}", it.netUsd) },
                summary.worst?.takeIf { it.scalperId != summary.best?.scalperId }?.let { NetBar("Worst · ${it.label}", it.netUsd) }
            ),
            Modifier.fillMaxWidth(),
            ranked = false,
            labelWidthDp = 120
        )
    }
}
