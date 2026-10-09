package com.dirk.kalshiodds.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DisagreementLabelTest {

    @Test
    fun modelUpVsMarketSpotDown() {
        val copy = DisagreementLabel.of(
            tapeConflict = true,
            modelLeanSide = "YES",
            primaryHeroSide = "NO",
            modelYesPercent = 58.0
        )
        assertEquals(DisagreementLabel.TITLE, copy!!.title)
        assertEquals("Model: UP 58%  ·  Market + spot: DOWN", copy.detail)
    }

    @Test
    fun modelDownVsMarketSpotUp() {
        val copy = DisagreementLabel.of(
            tapeConflict = true,
            modelLeanSide = "NO",
            primaryHeroSide = "YES",
            modelYesPercent = 42.0
        )
        assertEquals("Model disagrees with market", copy!!.title)
        assertEquals("Model: DOWN 58%  ·  Market + spot: UP", copy.detail)
    }

    @Test
    fun modelUpAndMarketUpIsNull() {
        assertNull(
            DisagreementLabel.of(
                tapeConflict = true,
                modelLeanSide = "YES",
                primaryHeroSide = "YES",
                modelYesPercent = 62.0,
                yesAsk = 0.58,
                noAsk = 0.43,
                yesBid = 0.57,
                noBid = 0.42
            )
        )
    }

    @Test
    fun screenshotModelDownMarketDownIsNullEvenWhenLeanSaysUp() {
        // Phone 0.3.14: AI UP 39% / DOWN 61%, DOWN ask 66¢. They agree.
        val copy = DisagreementLabel.of(
            tapeConflict = true,
            modelLeanSide = "YES",
            primaryHeroSide = "NO",
            predictedSide = "YES",
            modelYesPercent = 39.0,
            marketYesPercent = 34.0,
            yesAsk = 0.34,
            noAsk = 0.66,
            yesBid = 0.33,
            noBid = 0.66
        )
        assertNull(copy)
        assertEquals("DOWN", DisagreementLabel.modelFavoredSide(39.0))
        assertEquals(
            "DOWN",
            DisagreementLabel.marketFavoredSide(
                primaryHeroSide = "NO",
                marketYesPercent = 34.0,
                yesAsk = 0.34,
                noAsk = 0.66,
                yesBid = 0.33,
                noBid = 0.66
            )
        )
    }

    @Test
    fun modelDownAndMarketDownIsNull() {
        assertNull(
            DisagreementLabel.of(
                tapeConflict = true,
                modelLeanSide = "NO",
                primaryHeroSide = "NO",
                modelYesPercent = 39.0,
                yesAsk = 0.34,
                noAsk = 0.67,
                yesBid = 0.33,
                noBid = 0.66
            )
        )
    }

    @Test
    fun realDisagreementModelUpMarketDown() {
        val copy = DisagreementLabel.of(
            tapeConflict = false,
            modelLeanSide = null,
            primaryHeroSide = "NO",
            modelYesPercent = 61.0,
            yesAsk = 0.34,
            noAsk = 0.67,
            yesBid = 0.33,
            noBid = 0.66
        )
        assertEquals("Model disagrees with market", copy!!.title)
        assertEquals("Model: UP 61%  ·  Market + spot: DOWN", copy.detail)
    }

    @Test
    fun realDisagreementModelDownMarketUp() {
        val copy = DisagreementLabel.of(
            tapeConflict = false,
            modelLeanSide = "YES",
            primaryHeroSide = "YES",
            modelYesPercent = 39.0,
            yesAsk = 0.62,
            noAsk = 0.39,
            yesBid = 0.61,
            noBid = 0.38
        )
        assertEquals("Model: DOWN 61%  ·  Market + spot: UP", copy!!.detail)
    }

    @Test
    fun agreementIsNull() {
        assertNull(
            DisagreementLabel.of(
                tapeConflict = false,
                modelLeanSide = "YES",
                primaryHeroSide = "YES",
                modelYesPercent = 62.0
            )
        )
        assertNull(
            DisagreementLabel.of(
                tapeConflict = true,
                modelLeanSide = "YES",
                primaryHeroSide = "YES",
                modelYesPercent = 62.0
            )
        )
    }

    @Test
    fun missingDataIsNull() {
        assertNull(DisagreementLabel.of(tapeConflict = true))
        assertNull(
            DisagreementLabel.of(
                tapeConflict = true,
                modelLeanSide = "YES",
                primaryHeroSide = null,
                modelYesPercent = 58.0
            )
        )
        assertNull(
            DisagreementLabel.of(
                tapeConflict = false,
                modelLeanSide = null,
                primaryHeroSide = null,
                modelYesPercent = null
            )
        )
    }

    @Test
    fun neverSaysYesOrNo() {
        val copy = DisagreementLabel.of(
            tapeConflict = true,
            modelLeanSide = "NO",
            primaryHeroSide = "YES",
            predictedSide = "NO",
            modelYesPercent = 40.0
        )!!
        assertEquals(false, copy.detail.contains("YES"))
        assertEquals(false, copy.detail.contains("NO"))
        assertEquals(true, copy.detail.contains("UP"))
        assertEquals(true, copy.detail.contains("DOWN"))
    }

    @Test
    fun screenshotMarketUiModelDoesNotWarn() {
        val market = HomeFixtures.screenshotPhoneBtc()
        assertNull(DisagreementLabel.of(market))
        assertTrue(market.importedModelPp!! < 50.0)
        assertEquals("DOWN", DisagreementLabel.modelFavoredSide(market.importedModelPp))
    }
}
