package com.dirk.kalshiodds

import com.dirk.kalshiodds.data.dto.MarketDto
import com.dirk.kalshiodds.domain.CryptoMarkets
import com.dirk.kalshiodds.domain.SeriesKind
import com.dirk.kalshiodds.domain.toUiModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
            ticker = "KXETH15M-TEST",
            title = "ETH up?",
            yesBidDollars = null,
            yesAskDollars = "0.5000",
            lastPriceDollars = "0.3300"
        )
        val ui = dto.toUiModel(SeriesKind.ETH)
        assertEquals(33.0, ui.yesProbabilityPercent!!, 0.001)
        assertEquals(67.0, ui.noProbabilityPercent!!, 0.001)
    }
}

class CryptoMarketsTest {
    @Test
    fun acceptsDefaultCryptoSeries() {
        assertTrue(CryptoMarkets.isCryptoTicker("KXBTC15M-26SEP231600-00"))
        assertTrue(CryptoMarkets.isCryptoTicker("KXETH15M-26SEP231645-45"))
        assertTrue(CryptoMarkets.isCryptoTicker("KXSOL15M-26SEP231645-45"))
        assertTrue(CryptoMarkets.isCryptoTicker("KXXRP15M-FOO"))
    }

    @Test
    fun rejectsWtiAndOil() {
        assertFalse(CryptoMarkets.isCryptoTicker("KXWTI15M-26SEP231600-00"))
        assertFalse(CryptoMarkets.isCryptoTicker("CRUDE-OIL"))
        assertTrue(CryptoMarkets.filterCrypto(listOf("KXBTC15M-A", "KXWTI15M-B", "KXETH15M-C"))
            == listOf("KXBTC15M-A", "KXETH15M-C"))
    }

    @Test
    fun inferSeries() {
        assertEquals("KXBTC15M", CryptoMarkets.inferSeries("KXBTC15M-X"))
        assertEquals("KXETH15M", CryptoMarkets.inferSeries("KXETH15M-X"))
        assertEquals("KXSOL15M", CryptoMarkets.inferSeries("KXSOL15M-X"))
        assertEquals(SeriesKind.ETH, CryptoMarkets.kindFor("KXETH15M-X"))
    }
}
