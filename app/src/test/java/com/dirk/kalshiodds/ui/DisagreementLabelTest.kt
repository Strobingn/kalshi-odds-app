package com.dirk.kalshiodds.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
