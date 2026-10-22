package com.dirk.kalshiodds.decision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScalpVariantBoardTest {
    @Test fun eachWinnerGetsThreeDistinctPaperVariants() {
        val parents = ScalpModels.WINNERS.map { it.code } + listOf("sf43", "g16-t14-s08-d15-w300", "dip-g05-t08-s06-d10-w180-mk")
        assertTrue(ScalpVariants.ALL.size in 36..48)
        parents.forEach { parent ->
            val kids = ScalpVariants.ALL.filter { it.parentId == parent }
            assertEquals(parent, 3, kids.size)
            kids.forEach { spec ->
                assertTrue(spec.id, spec.id == "$parent-v1" || spec.id == "$parent-v2" || spec.id == "$parent-v3")
                assertTrue(spec.id, spec.differsFromParent())
                assertFalse(spec.id, spec.id in KeptScalpers.IDS)
                val card = ScalpModels.modelByCode(spec.id)
                assertEquals(spec.id, card?.code)
                assertEquals(spec.label, card?.label)
                assertFalse(ScalpModels.isRemoved(spec.id))
            }
        }
        assertTrue(ScalpVariants.ALL.all { it.blurb.contains("Paper") })
    }

    @Test fun renamedMakerFairGapStillCountsOnTheV2Card() {
        val board = ScalpModels.leaderboardFromStats(listOf(
            ScalpStat("g10-t12-s08-d15-w300-mk", n = 34, wins = 20, netUsd = 12.5)
        ))
        val card = board.first { it.model.code == KeptScalpers.V2_FAIR_ID }
        assertEquals(34, card.roundTrips)
        assertEquals(12.5, card.netUsd, 1e-9)
    }
}
