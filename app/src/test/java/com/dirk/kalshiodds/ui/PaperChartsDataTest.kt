package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.llm.PaperMarketFeed
import com.dirk.kalshiodds.signal.paper.PaperFill
import com.dirk.kalshiodds.signal.trade.ScalpSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaperChartsDataTest {
    private fun sample(age: Long?) = PaperMarketFeed.Sample(
        ticker = "KXBTC15M-TEST", observedAtMs = 1_000L, closeAtMs = 900_000L,
        yesBid = 0.4, yesAsk = 0.6, noBid = 0.35, noAsk = 0.45,
        spot = 100_010.0, target = 100_000.0, fairPercent = 55.0,
        quoteAgeMs = age, openPaper = 2, realizedPaper = -3.0
    )

    @Test fun quotesSpreadsAndMidpointUseFreshValuesOnly() {
        val fresh = PaperChartsData.market(listOf(sample(1_000L)))
        assertEquals(40.0, fresh[0].points[0].values[0]!!, 1e-9)
        assertEquals(20.0, fresh[2].points[0].values[0]!!, 1e-9)
        assertEquals(50.0, fresh[3].points[0].values[1]!!, 1e-9)
        assertEquals(5.0, fresh[4].points[0].values[0]!!, 1e-9)
        assertEquals(105.0, fresh[5].points[0].values[0]!!, 1e-9)
        assertEquals(10.0, fresh[6].points[0].values[0]!!, 1e-9)
        val stale = PaperChartsData.market(listOf(sample(10_001L)))
        assertNull(stale[0].points[0].values[0])
        assertNull(stale[2].points[0].values[0])
        assertNull(stale[3].points[0].values[1])
        assertEquals(55.0, stale[3].points[0].values[0]!!, 1e-9)
    }

    @Test fun scorecardSeparatesOpenAndNonVoidSettledWithoutDeductingFeesTwice() {
        val track = ScalpSignal.Track.values().first()
        val fill = PaperFill(id = "1", ticker = "KXBTC15M-TEST", side = "YES", stakeUsd = 5.0, contracts = 10, limitPrice = 0.5, source = "AI scalp", createdAtMs = 1L, strategyVersion = track.formulaVersion, note = "")
        val rows = listOf(fill, fill.copy(id = "2", settled = true, pnlUsd = -1.0, feeUsd = 0.2, exitFeeUsd = 0.1), fill.copy(id = "3", settled = true, outcome = "void", pnlUsd = 20.0))
        val result = PaperChartsData.tracks(rows).first()
        assertEquals(1, result.open)
        assertEquals(1, result.settled)
        assertEquals(-1.0, result.pnl!!, 1e-9)
        assertEquals(0.3, result.fees, 1e-9)
        assertEquals(1, result.windows)
        assertEquals(-0.1, result.equalWindowPerContract!!, 1e-9)
        assertEquals(1, result.losses)
        assertEquals(20, PaperChartsData.tracks(rows).size)
    }
}
