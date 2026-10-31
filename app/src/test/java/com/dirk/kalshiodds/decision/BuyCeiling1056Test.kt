package com.dirk.kalshiodds.decision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.0.56: the owner ceiling is 75¢. Factory bands stay 15–55¢.
 * Late window's factory max and ceiling are 95¢.
 */
class BuyCeiling1056Test {
    private fun hand(max: Int) = ScalpStrategySettings(
        minEntryCents = PaperFleetDefaults.BUY_MIN,
        maxEntryCents = max,
        takeProfitCents = PaperFleetDefaults.PROFIT_CENTS,
        maxOpenPerSide = PaperFleetDefaults.OPEN_AT_ONCE
    )

    @Test fun factoryBandStaysFifteenToFiftyFive() {
        val factory = PaperFleetDefaults.factory()
        assertEquals(15, factory.minEntryCents)
        assertEquals(55, factory.maxEntryCents)
        assertEquals(25, factory.takeProfitCents)
        assertEquals(0, factory.maxOpenPerSide)
        assertEquals(75, PaperFleetDefaults.BUY_CEILING)
        assertEquals(55, PaperFleetDefaults.BUY_MAX)
        val fresh = ScalpControls().settings("sf42")
        assertEquals(15, fresh.minEntryCents)
        assertEquals(55, fresh.maxEntryCents)
        assertEquals(25, fresh.takeProfitCents)
    }

    @Test fun userMaxOfSeventyFiveSurvivesHealLearnAndAdvice() {
        val row = hand(75)
        val stored = ScalpParamSanity.settings("sf42", row)
        assertEquals(15, stored.minEntryCents)
        assertEquals(75, stored.maxEntryCents)
        val repaired = AiLearnBounds.repair("sf42", stored.copy(aiN = 3, aiNote = "AI kept settings (winner +1.00)"))
        assertEquals(75, repaired.maxEntryCents)
        val advised = AdviceApply.repairControls(ScalpControls(strategySettings = mapOf("sf42" to row)))
        assertEquals(75, advised.settings("sf42").maxEntryCents)
        var learned = row.copy(aiLearns = true, aiN = 12, aiNet = 4.0)
        repeat(3) { learned = Research.learn("sf42", learned, 0.40, 55) }
        assertEquals(75, learned.maxEntryCents)
        assertTrue(learned.maxEntryCents!! <= PaperFleetDefaults.BUY_CEILING)
    }

    @Test fun aboveSeventyFiveClampsAndAiDoesNotRaiseTheFactoryMax() {
        assertEquals(75, ScalpParamSanity.settings("sf42", hand(76)).maxEntryCents)
        assertEquals(75, ScalpParamSanity.settings("sf42", hand(99)).maxEntryCents)
        assertEquals(75, ScalpParamSanity.settings("v2-sf41", hand(80)).maxEntryCents)
        var walked = ScalpControls().settings("sf42").copy(aiLearns = true, aiN = 12, aiNet = 4.0)
        repeat(5) { walked = Research.learn("sf42", walked, 0.80, 55) }
        assertEquals(55, walked.maxEntryCents)
        assertFalse(ScalpRule.buyPriceOk(0.72))
        assertTrue(ScalpRule.buyPriceOk(0.72, PaperFleetDefaults.BUY_CEILING / 100.0))
        assertTrue(ScalpRule.buyPriceOk(0.75, PaperFleetDefaults.BUY_CEILING / 100.0))
        assertFalse(ScalpRule.buyPriceOk(0.76, PaperFleetDefaults.BUY_CEILING / 100.0))
        assertFalse(ScalpV2.entryOk(0.56))
        assertTrue(ScalpV2.entryOk(0.75, 0.75))
        assertFalse(ScalpV2.entryOk(0.76, 0.99))
    }

    @Test fun lateWindowDefaultsToNinetyFiveAndCapsTakeProfitAtNinetyNine() {
        val fresh = ScalpControls().settings(LateWindowScalper.CODE)
        assertEquals(15, fresh.minEntryCents)
        assertEquals(95, fresh.maxEntryCents)
        assertEquals(25, fresh.takeProfitCents)
        assertEquals(95, PaperFleetDefaults.factory(LateWindowScalper.CODE).maxEntryCents)
        assertTrue(LateWindowScalper.askOk(0.95))
        assertFalse(LateWindowScalper.askOk(0.96))
        assertTrue(LateWindowScalper.askOk(0.70, 70))
        assertFalse(LateWindowScalper.askOk(0.80, 70))
        assertEquals(95, ScalpParamSanity.settings(LateWindowScalper.CODE, hand(99)).maxEntryCents)
        assertEquals(0.99, LateWindowScalper.takeProfitBid(0.95), 1e-9)
        assertEquals(0.65, LateWindowScalper.takeProfitBid(0.40), 1e-9)
        assertTrue(ScalpModels.stopLabel(ScalpModels.Model.LATE_WINDOW).startsWith("stop "))
    }

    @Test fun lateWindowMigratesOnceAndKeepsAHandMaxAlreadyAboveFiftyFive() {
        val atFiftyFive = ScalpControls(strategySettings = mapOf(LateWindowScalper.CODE to hand(55)))
        assertEquals(95, ScalpAutoBenchMigration.migrateLateWindow(atFiftyFive).settings(LateWindowScalper.CODE).maxEntryCents)
        val atSeventy = ScalpControls(strategySettings = mapOf(LateWindowScalper.CODE to hand(70)))
        assertEquals(70, ScalpAutoBenchMigration.migrateLateWindow(atSeventy).settings(LateWindowScalper.CODE).maxEntryCents)
        val over = ScalpControls(strategySettings = mapOf(LateWindowScalper.CODE to hand(96)))
        assertEquals(95, ScalpAutoBenchMigration.migrateLateWindow(over).settings(LateWindowScalper.CODE).maxEntryCents)
        val missing = ScalpAutoBenchMigration.migrateLateWindow(ScalpControls())
        assertEquals(95, missing.settings(LateWindowScalper.CODE).maxEntryCents)
        assertEquals(15, missing.settings(LateWindowScalper.CODE).minEntryCents)
        val learned = Research.learn(LateWindowScalper.CODE, freshLate(), 1.0, 95)
        assertEquals(95, learned.maxEntryCents)
    }

    private fun freshLate() = ScalpControls().settings(LateWindowScalper.CODE).copy(aiLearns = true)
}
