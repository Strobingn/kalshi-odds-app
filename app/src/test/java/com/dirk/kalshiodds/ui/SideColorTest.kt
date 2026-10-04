package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.signal.trade.BetCall
import com.dirk.kalshiodds.ui.theme.DarkPalette
import com.dirk.kalshiodds.ui.theme.DownColor
import com.dirk.kalshiodds.ui.theme.LightDownColor
import com.dirk.kalshiodds.ui.theme.LightPalette
import com.dirk.kalshiodds.ui.theme.LightUpColor
import com.dirk.kalshiodds.ui.theme.UpColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SideColorTest {

    @Test
    fun upMapsToGreenTokenDownMapsToRedTokenNoBetIsNeutral() {
        for (p in listOf(LightPalette, DarkPalette)) {
            assertEquals(p.up, SideColor.of(BetCall.Headline.BET_UP, p))
            assertEquals(p.down, SideColor.of(BetCall.Headline.BET_DOWN, p))
            assertEquals(p.textSecondary, SideColor.of(BetCall.Headline.NO_BET, p))
            assertEquals(p.onUp, SideColor.on(BetCall.Headline.BET_UP, p))
            assertEquals(p.onDown, SideColor.on(BetCall.Headline.BET_DOWN, p))
            assertEquals(p.downButton, SideColor.fill(BetCall.Headline.BET_DOWN, p))
            assertEquals(p.up, SideColor.fill(BetCall.Headline.BET_UP, p))
            assertEquals(p.upContainer, SideColor.container(BetCall.Headline.BET_UP, p))
            assertEquals(p.downContainer, SideColor.container(BetCall.Headline.BET_DOWN, p))
        }
        assertEquals(UpColor, DarkPalette.up)
        assertEquals(DownColor, DarkPalette.down)
        assertEquals(LightUpColor, LightPalette.up)
        assertEquals(LightDownColor, LightPalette.down)
        assertTrue("UP token is green-dominant", greenDominant(UpColor) && greenDominant(LightUpColor))
        assertTrue("DOWN token is red-dominant", redDominant(DownColor) && redDominant(LightDownColor))
        assertEquals(DarkPalette.up, SideColor.ofTicketSide("YES", DarkPalette))
        assertEquals(DarkPalette.down, SideColor.ofTicketSide("NO", DarkPalette))
    }

    private fun greenDominant(c: androidx.compose.ui.graphics.Color): Boolean =
        c.green > c.red && c.green > c.blue

    private fun redDominant(c: androidx.compose.ui.graphics.Color): Boolean =
        c.red > c.green && c.red > c.blue
}
