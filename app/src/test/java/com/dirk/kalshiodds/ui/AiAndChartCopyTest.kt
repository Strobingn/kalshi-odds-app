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
        val hiddenEdge = hidden.copy(
            importedModelPp = 90.0,
            aiYesPercent = 80.0,
            digitalFairPp = 42.0,
            fairValuePp = 61.0,
            yesAsk = 0.40,
            yesProbabilityPercent = 40.0
        )
        val card = HomeCardDetails.modelYesPercent(hiddenEdge)
        val ticket = com.dirk.kalshiodds.signal.trade.TicketBuilder.modelProb(hiddenEdge, "YES")
        assertEquals(61.0, card!!, 1e-9)
        assertEquals(0.61, ticket!!, 1e-9)
        assertEquals(
            com.dirk.kalshiodds.domain.FairValue.edgeVsAskPp(hiddenEdge, "YES")!!,
            card - hiddenEdge.yesAsk!! * 100.0,
            1e-6
        )
        assertEquals(card - hiddenEdge.yesAsk!! * 100.0, ticket * 100.0 - hiddenEdge.yesAsk!! * 100.0, 1e-6)
        val noScore = hidden.copy(aiYesPercent = null, importedModelPp = null, digitalFairPp = 55.0, fairValuePp = null, yesAsk = 0.30)
        assertEquals(55.0, HomeCardDetails.modelYesPercent(noScore)!!, 1e-9)
        assertEquals(0.55, com.dirk.kalshiodds.signal.trade.TicketBuilder.modelProb(noScore, "YES")!!, 1e-9)
    }

    @Test
    fun chartCaptionMatchesTheGraySpotLine() {
        assertTrue(CHART_SPOT_CAPTION.startsWith("Gray ="))
        assertFalse(CHART_SPOT_CAPTION.contains("Orange"))
        assertTrue(CHART_SPOT_CAPTION.contains("Green/red"))
    }
}
