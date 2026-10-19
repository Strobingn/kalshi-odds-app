package com.dirk.kalshiodds.prediction

import com.dirk.kalshiodds.decision.LeadForecaster
import com.dirk.kalshiodds.decision.ShockFav
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class BiasSymmetryTest {
    @Test fun shrinkIsMirrorSymmetricAndPullsTowardMarket() {
        for ((m, mk) in listOf(0.5 to 0.2, 0.7 to 0.4, 0.1 to 0.9, 0.55 to 0.08)) {
            val a = AiBias.shrinkToMarket(m, mk)
            val b = AiBias.shrinkToMarket(1 - m, 1 - mk)
            assertEquals(1.0, a + b, 1e-9)
            assertTrue(abs(a - mk) < abs(m - mk))
        }
        // ~50% model on a cheap 20¢ contract no longer reads as a big UP edge
        assertTrue(AiBias.shrinkToMarket(0.5, 0.2) < 0.28)
    }

    @Test fun edgeSideMirrors() {
        assertEquals("YES", AiBias.edgeSide(0.70, 0.55, 0.47, 0.05))
        assertEquals("NO", AiBias.edgeSide(0.30, 0.47, 0.55, 0.05))
        assertNull(AiBias.edgeSide(0.50, 0.50, 0.50, 0.05))
    }

    @Test fun equalDipsDoNotDefaultToUp() {
        val p = ShockFav.v("sf41")
        assertNull(ShockFav.simpleDipSide(p, 0.40, 0.50, 0.40, 0.50))
        assertEquals("NO", ShockFav.simpleDipSide(p, 0.40, 0.50, 0.30, 0.50))
        assertEquals("YES", ShockFav.simpleDipSide(p, 0.30, 0.50, 0.40, 0.50))
    }

    @Test fun zeroFeatureForecastIsZero() {
        val x = DoubleArray(11).also { it[0] = 1.0 }
        assertEquals(0.0, LeadForecaster.predict("BTC", 600, x)!!, 1e-12)
    }
}
