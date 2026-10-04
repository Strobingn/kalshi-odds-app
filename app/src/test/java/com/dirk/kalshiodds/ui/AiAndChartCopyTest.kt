package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.signal.engine.ScoringEngine
import com.dirk.kalshiodds.signal.engine.TapeConflict
import com.dirk.kalshiodds.signal.model.AiDisplay
import com.dirk.kalshiodds.ui.components.CHART_SPOT_CAPTION
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiAndChartCopyTest {
    @Test
    fun zeroWeightOrNonBtcModelHidesBannerAndAiPercent() {
        assertEquals(0.0, ScoringEngine.W_AI, 0.0)
        assertFalse(AiDisplay.visible(ScoringEngine.W_AI, importedWeight = 0.0, trainedOnBtc = true))
        assertFalse(AiDisplay.visible(0.0, importedWeight = 0.35, trainedOnBtc = false))
        assertTrue(AiDisplay.visible(0.0, importedWeight = 0.35, trainedOnBtc = true))
        val hidden = HomeFixtures.screenshotPhoneBtc().copy(showAiPercent = false)
        assertEquals(HomeCopy.AI_EM_DASH, HomeCopy.tileAiUp(hidden))
        assertEquals(HomeCopy.AI_EM_DASH, HomeCopy.tileAiDown(hidden))
        assertEquals("—", HomeCardDetails.of(
            hidden,
            com.dirk.kalshiodds.signal.trade.BetCall.decide(hidden, com.dirk.kalshiodds.signal.config.SignalSettings()),
            HomeFixtures.NOW_MS
        ).aiYes)
        assertNull(HomeCardDetails.conflictLine(hidden))
        val banner = TapeConflict.evaluate(
            spotReturn1m = -0.01,
            spotReturn5m = -0.01,
            modelSide = "YES",
            yesAsk = 0.30,
            noAsk = 0.70,
            modelYesPercent = 80.0,
            priorStreak = 5,
            modelActive = false
        )
        assertNull(banner.banner)
        assertFalse(banner.conflict)
    }

    @Test
    fun chartCaptionMatchesTheGraySpotLine() {
        assertTrue(CHART_SPOT_CAPTION.startsWith("Gray ="))
        assertFalse(CHART_SPOT_CAPTION.contains("Orange"))
        assertTrue(CHART_SPOT_CAPTION.contains("Green/red"))
    }
}
