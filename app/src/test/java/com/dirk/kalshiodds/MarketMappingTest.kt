package com.dirk.kalshiodds

import com.dirk.kalshiodds.data.dto.MarketDto
import com.dirk.kalshiodds.domain.SeriesKind
import com.dirk.kalshiodds.domain.toUiModel
import org.junit.Assert.assertEquals
import org.junit.Test

class MarketMappingTest {

    @Test
    fun midWhenBidAndAskPresent() {
        val dto = MarketDto(
            ticker = "KXBTC15M-TEST",
            title = "BTC up?",
            yesBidDollars = "0.4000",
            yesAskDollars = "0.5000",
            noBidDollars = "0.5000",
            noAskDollars = "0.6000",
            lastPriceDollars = "0.1000"
        )
        val ui = dto.toUiModel(SeriesKind.BTC)
        assertEquals(45.0, ui.yesProbabilityPercent!!, 0.001)
        assertEquals(55.0, ui.noProbabilityPercent!!, 0.001)
    }

    @Test
    fun lastWhenBidMissing() {
        val dto = MarketDto(
            ticker = "KXWTI15M-TEST",
            title = "WTI up?",
            yesBidDollars = null,
            yesAskDollars = "0.5000",
            lastPriceDollars = "0.3300"
        )
        val ui = dto.toUiModel(SeriesKind.WTI)
        assertEquals(33.0, ui.yesProbabilityPercent!!, 0.001)
        assertEquals(67.0, ui.noProbabilityPercent!!, 0.001)
    }
}
