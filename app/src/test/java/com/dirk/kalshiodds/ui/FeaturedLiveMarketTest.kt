package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.data.dto.MarketDto
import com.dirk.kalshiodds.domain.SeriesKind
import com.dirk.kalshiodds.domain.toUiModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FeaturedLiveMarketTest {

    @Test
    fun emptyIsNull() {
        assertNull(featuredLiveMarket(emptyList()))
    }

    @Test
    fun prefersAtmBitcoin() {
        val far = MarketDto(
            ticker = "KXBTC15M-FAR",
            title = "far",
            yesBidDollars = "0.10",
            yesAskDollars = "0.12"
        ).toUiModel(SeriesKind.BTC)
        val atm = MarketDto(
            ticker = "KXBTC15M-ATM",
            title = "atm",
            yesBidDollars = "0.49",
            yesAskDollars = "0.51"
        ).toUiModel(SeriesKind.BTC)
        val eth = MarketDto(
            ticker = "KXETH15M-ATM",
            title = "eth",
            yesBidDollars = "0.50",
            yesAskDollars = "0.50"
        ).toUiModel(SeriesKind.ETH)
        assertEquals("KXBTC15M-ATM", featuredLiveMarket(listOf(far, eth, atm))?.ticker)
    }

    @Test
    fun skipsExpiredWindowForCurrentLive() {
        val now = 2_000_000L
        val dead = MarketDto(
            ticker = "KXBTC15M-26SEP241645-45",
            title = "dead atm",
            yesBidDollars = "0.49",
            yesAskDollars = "0.51",
            status = "closed"
        ).toUiModel(SeriesKind.BTC).copy(closeTimeEpochMs = now - 1L)
        val live = MarketDto(
            ticker = "KXBTC15M-26SEP241700-00",
            title = "live",
            yesBidDollars = "0.10",
            yesAskDollars = "0.12",
            status = "active"
        ).toUiModel(SeriesKind.BTC).copy(closeTimeEpochMs = now + 600_000L)
        assertEquals("KXBTC15M-26SEP241700-00", featuredLiveMarket(listOf(dead, live), now)?.ticker)
    }
}
