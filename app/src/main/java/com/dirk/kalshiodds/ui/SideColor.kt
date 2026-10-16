package com.dirk.kalshiodds.ui

import androidx.compose.ui.graphics.Color
import com.dirk.kalshiodds.signal.trade.BetCall
import com.dirk.kalshiodds.ui.theme.DipPalette

/**
 * Kalshi-style side colors: green = buying UP, red = buying DOWN.
 * Everything else stays neutral. Tokens live on [DipPalette].
 */
object SideColor {
    fun of(headline: BetCall.Headline, colors: DipPalette): Color = when (headline) {
        BetCall.Headline.BET_UP -> colors.up
        BetCall.Headline.BET_DOWN -> colors.down
        BetCall.Headline.NO_BET -> colors.textSecondary
    }

    fun on(headline: BetCall.Headline, colors: DipPalette): Color = when (headline) {
        BetCall.Headline.BET_UP -> colors.onUp
        BetCall.Headline.BET_DOWN -> colors.onDown
        BetCall.Headline.NO_BET -> colors.textPrimary
    }

    /** Solid fill for a side button or card border. Fills are deeper than the text colors. */
    fun fill(headline: BetCall.Headline, colors: DipPalette): Color = when (headline) {
        BetCall.Headline.BET_UP -> colors.upButton
        BetCall.Headline.BET_DOWN -> colors.downButton
        BetCall.Headline.NO_BET -> colors.textSecondary
    }

    fun container(headline: BetCall.Headline, colors: DipPalette): Color = when (headline) {
        BetCall.Headline.BET_UP -> colors.upContainer
        BetCall.Headline.BET_DOWN -> colors.downContainer
        BetCall.Headline.NO_BET -> colors.surfaceAlt
    }

    fun ofTicketSide(side: String?, colors: DipPalette): Color =
        of(headlineForSide(side), colors)

    fun onTicketSide(side: String?, colors: DipPalette): Color =
        on(headlineForSide(side), colors)

    fun headlineForSide(side: String?): BetCall.Headline = when {
        side.equals("NO", true) || side.equals("DOWN", true) -> BetCall.Headline.BET_DOWN
        side.equals("YES", true) || side.equals("UP", true) -> BetCall.Headline.BET_UP
        else -> BetCall.Headline.BET_UP
    }
}
