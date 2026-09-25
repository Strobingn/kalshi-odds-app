package com.dirk.kalshiodds.domain

import com.dirk.kalshiodds.data.dto.MarketDto
import com.dirk.kalshiodds.signal.trade.KalshiFee
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketQuoteViewTest {

    @Test
    fun oneCentAtFiveDollarsIsHandComputed93_46x() {
        // C = floor(5 / 0.01) = 500
        // model = 0.07 × 500 × 0.01 × 0.99 = 0.3465
        // trade = ceil_6dp(0.3465) = 0.346500
        // debit = ceil_cent(5.00 + 0.346500) = 5.35
        // multiple = 500 / 5.35
        assertEquals(500, KalshiFee.contractsForStake(5.0, 0.01))
        assertEquals(0.3465, KalshiFee.raw(500, 0.01), 1e-12)
        assertEquals(0.346500, KalshiFee.ceil6dp(0.3465), 1e-12)
        assertEquals(0.35, KalshiFee.total(500, 0.01), 1e-9)
        assertEquals(5.35, KalshiFee.totalCost(500, 0.01), 1e-9)
        val one = KalshiQuoteDisplay.multiplier(0.01)!!
        assertEquals(500.0 / 5.35, one, 1e-9)
        assertEquals(93.45794392523364, one, 1e-9)
        assertTrue("1¢ multiple must not be 99x", kotlin.math.abs(one - 99.0) > 1.0)
        assertTrue(one < 94.0)
        assertTrue(one > 93.0)
    }

    @Test
    fun fiftyCentsAtFiveDollarsIsHandComputed1_93x() {
        // C = 10, model = 0.07 × 10 × 0.50 × 0.50 = 0.175
        // debit = ceil_cent(5.00 + 0.175) = 5.18
        assertEquals(10, KalshiFee.contractsForStake(5.0, 0.50))
        assertEquals(0.175, KalshiFee.raw(10, 0.50), 1e-12)
        assertEquals(0.18, KalshiFee.total(10, 0.50), 1e-9)
        val m = KalshiQuoteDisplay.multiplier(0.50)!!
        assertEquals(10.0 / 5.18, m, 1e-9)
        assertEquals(1.9305019305019305, m, 1e-9)
    }

    @Test
    fun ninetyNineCentsAtFiveDollarsIsHandComputed1_01x() {
        // C = floor(5 / 0.99) = 5, position = 4.95
        // model = 0.07 × 5 × 0.99 × 0.01 = 0.003465
        // debit = ceil_cent(4.953465) = 4.96
        assertEquals(5, KalshiFee.contractsForStake(5.0, 0.99))
        assertEquals(0.003465, KalshiFee.raw(5, 0.99), 1e-12)
        assertEquals(0.01, KalshiFee.total(5, 0.99), 1e-9)
        val m = KalshiQuoteDisplay.multiplier(0.99)!!
        assertEquals(5.0 / 4.96, m, 1e-9)
        assertEquals(1.0080645161290323, m, 1e-9)
    }

    @Test
    fun deciCentAtFiveDollarsIsLegitimateAndHandComputed() {
        // 0.1¢ is a real tapered_deci_cent / deci_cent tick.
        // C = floor(5 / 0.001) = 5000
        // model = 0.07 × 5000 × 0.001 × 0.999 = 0.34965
        // debit = ceil_cent(5.00 + 0.349650) = 5.35
        // multiple = 5000 / 5.35 ≈ 934.58 — NOT the 0.3.8 934.64 from 1/(P+raw)
        assertEquals(5000, KalshiFee.contractsForStake(5.0, 0.001))
        assertEquals(0.34965, KalshiFee.raw(5000, 0.001), 1e-12)
        assertEquals(0.35, KalshiFee.total(5000, 0.001), 1e-9)
        val m = KalshiQuoteDisplay.multiplier(0.001)!!
        assertEquals(5000.0 / 5.35, m, 1e-9)
        assertEquals(934.5794392523364, m, 1e-9)
        val oldWrong = 1.0 / (0.001 + 0.07 * 0.001 * 0.999)
        assertTrue(kotlin.math.abs(oldWrong - 934.64) < 0.02)
        assertTrue(kotlin.math.abs(m - oldWrong) > 0.05)
        assertEquals("0.1¢", KalshiQuoteDisplay.formatAsk(0.001))
        assertEquals("Down 0.1¢ · 934.58x", KalshiQuoteDisplay.buttonLabel(false, 0.001))
        val q = MarketQuoteView.of(
            yesBid = null,
            yesAsk = null,
            noBid = null,
            noAsk = 0.001
        )
        assertEquals("0.1¢", q.downHero)
        assertEquals("0.1¢", q.noAskLabel)
        assertEquals(m, q.downMultiple!!, 1e-9)
        assertEquals("Down 0.1¢ · 934.58x", q.downButton)
        assertTrue(q.downHeader.contains("ask 0.1¢"))
    }

    @Test
    fun zeroCentAndNoAskNeverDivide() {
        assertNull(KalshiQuoteDisplay.multiplier(0.0))
        assertNull(KalshiQuoteDisplay.multiplier(null))
        assertNull(KalshiQuoteDisplay.cents(0.0))
        assertNull(KalshiQuoteDisplay.cents(null))
        assertEquals("—", KalshiQuoteDisplay.formatAsk(0.0))
        assertEquals("—", KalshiQuoteDisplay.formatAsk(null))
        assertEquals("Buy DOWN", KalshiQuoteDisplay.buttonLabel(false, 0.0))
        assertEquals("Buy UP", KalshiQuoteDisplay.buttonLabel(true, null))
        val q = MarketQuoteView.of(yesBid = 1.0, yesAsk = null, noBid = null, noAsk = null)
        assertNull(q.upMultiple)
        assertNull(q.downMultiple)
        assertEquals("no ask", q.upMultipleLabel)
        assertEquals("no ask", q.downMultipleLabel)
        assertEquals("Buy UP", q.upButton)
        assertEquals("Buy DOWN", q.downButton)
        assertEquals("no ask", KalshiQuoteDisplay.multipleLabel(null))
        assertEquals("no ask", KalshiQuoteDisplay.multipleLabel(0.0))
    }

    @Test
    fun oneCentIsNotNinetyNineX() {
        val label = KalshiQuoteDisplay.buttonLabel(false, 0.01)
        assertEquals("Down 1¢ · 93.46x", label)
        assertTrue(KalshiQuoteDisplay.multiplier(0.01)!! < 94.0)
        assertTrue(kotlin.math.abs(KalshiQuoteDisplay.multiplier(0.01)!! - 99.0) > 1.0)
    }

    @Test
    fun headerAndButtonStayConsistent() {
        val q = MarketQuoteView.of(
            yesBid = 1.0,
            yesAsk = null,
            noBid = null,
            noAsk = 0.01
        )
        assertEquals("—", q.upHero)
        assertEquals("1¢", q.downHero)
        assertEquals(500.0 / 5.35, q.downMultiple!!, 1e-9)
        assertEquals("Buy UP", q.upButton)
        assertEquals("Down 1¢ · 93.46x", q.downButton)
        assertEquals(q.downHero, "1¢")
        assertTrue(q.downButton.contains(q.downHero))
        assertTrue(q.downHeader.contains("ask 1¢"))
        assertTrue(q.upHeader.contains("ask —"))
    }

    @Test
    fun hundredCentBidIsDisplayedNotDropped() {
        assertEquals("100¢", KalshiQuoteDisplay.formatBid(1.0))
        assertEquals("—", KalshiQuoteDisplay.formatBid(0.0))
        assertEquals("71¢", KalshiQuoteDisplay.formatBid(0.71))
    }

    @Test
    fun handComputedMultiplesAtFiveDollarStakeMatchDisplay() {
        val cases = listOf(
            Triple(0.001, "0.1¢", 5000.0 / 5.35),
            Triple(0.01, "1¢", 500.0 / 5.35),
            Triple(0.015, "1.5¢", 333.0 / 5.34),
            Triple(0.018, "1.8¢", 277.0 / 5.33),
            Triple(0.099, "9.9¢", 50.0 / 5.27),
            Triple(0.10, "10¢", 50.0 / 5.32),
            Triple(0.105, "10.5¢", 47.0 / 5.25),
            Triple(0.25, "25¢", 20.0 / 5.27),
            Triple(0.50, "50¢", 10.0 / 5.18),
            Triple(0.905, "90.5¢", 5.0 / 4.56),
            Triple(0.99, "99¢", 5.0 / 4.96)
        )
        for ((ask, label, expected) in cases) {
            val q = MarketQuoteView.of(yesBid = null, yesAsk = ask, noBid = null, noAsk = ask)
            assertEquals("hero $ask", label, q.upHero)
            assertEquals("ask label $ask", label, q.yesAskLabel)
            assertEquals("button $ask", label, q.upButton.substringAfter("Up ").substringBefore(" ·"))
            assertEquals("multiple $ask", expected, q.upMultiple!!, 1e-9)
            assertEquals("same source $ask", KalshiQuoteDisplay.multiplier(ask)!!, q.upMultiple!!, 1e-9)
            assertTrue(q.upButton.contains(q.upHero))
            assertTrue(q.upHeader.contains("ask $label"))
            assertTrue("hero $ask never blank multiple", q.upMultipleLabel.isNotBlank())
            assertFalse("hero $ask not a silent hide", q.upMultipleLabel.isEmpty())
            if (ask < 0.10) {
                assertTrue("sub-10c $ask must not exceed 1/P", q.upMultiple!! <= 1.0 / ask + 1e-9)
                assertTrue("sub-10c $ask must not be the 0.1c 934x unless it is 0.1c", ask < 0.0015 || q.upMultiple!! < 200.0)
            } else {
                assertNotNull("10c+ $ask must show a multiple", q.upMultiple)
                assertTrue("10c+ $ask multiple", q.upMultiple!! > 1.0)
                assertTrue("10c+ $ask label", q.upMultipleLabel.endsWith("x"))
            }
        }
        assertEquals("Down 1.5¢ · 62.36x", KalshiQuoteDisplay.buttonLabel(false, 0.015))
        assertEquals("Down 10¢ · 9.40x", KalshiQuoteDisplay.buttonLabel(false, 0.10))
        assertEquals("Down 25¢ · 3.80x", KalshiQuoteDisplay.buttonLabel(false, 0.25))
    }

    @Test
    fun restPayloadSubPennyAndWholeCentShareOneQuote() {
        val sub = com.dirk.kalshiodds.data.dto.MarketDto(
            ticker = "KXBTC15M-26SEP251600-00",
            yesAskDollars = "0.0150",
            noAskDollars = "0.2500",
            yesBidDollars = "0.0140",
            noBidDollars = "0.2480"
        ).toUiModel(SeriesKind.BTC)
        assertEquals(0.015, sub.yesAsk!!, 1e-12)
        assertEquals(0.25, sub.noAsk!!, 1e-12)
        val q = MarketQuoteView.of(sub)
        assertEquals("1.5¢", q.upHero)
        // REST fixture is mixed (1.5¢ YES vs 25¢ NO). Display derives NO from the same YES print.
        assertEquals("98.6¢", q.downHero)
        assertEquals(333.0 / 5.34, q.upMultiple!!, 1e-9)
        assertEquals(KalshiQuoteDisplay.multiplier(0.986)!!, q.downMultiple!!, 1e-9)
        assertTrue(q.upButton.contains("1.5¢"))
        assertTrue(q.downButton.contains("98.6¢"))
        assertFalse(q.downButton.contains("934"))
        assertFalse(q.downButton.contains("25¢"))
    }

    @Test
    fun liveRestPayloadOverTenCentsShowsMultiple() {
        // Captured 2026-09-25 GET /markets?series_ticker=KXBTC15M — no integer fields.
        val raw = """
            {
              "ticker": "KXBTC15M-26SEP251200-00",
              "yes_ask_dollars": "0.4400",
              "yes_bid_dollars": "0.4300",
              "no_ask_dollars": "0.5700",
              "no_bid_dollars": "0.5600",
              "last_price_dollars": "0.4300",
              "price_level_structure": "tapered_deci_cent",
              "price_ranges": [
                {"start": "0.0000", "end": "0.1000", "step": "0.0010"},
                {"start": "0.1000", "end": "0.9000", "step": "0.0100"},
                {"start": "0.9000", "end": "1.0000", "step": "0.0010"}
              ]
            }
        """.trimIndent()
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
        val dto = json.decodeFromString<com.dirk.kalshiodds.data.dto.MarketDto>(raw)
        assertEquals("tapered_deci_cent", dto.priceLevelStructure)
        assertEquals(3, dto.priceRanges.size)
        assertEquals("0.0010", dto.priceRanges[0].step)
        assertEquals("0.0100", dto.priceRanges[1].step)
        val ui = dto.toUiModel(SeriesKind.BTC)
        assertEquals(0.44, ui.yesAsk!!, 1e-12)
        val q = MarketQuoteView.of(ui)
        assertEquals("44¢", q.upHero)
        assertNotNull(q.upMultiple)
        assertTrue(q.upMultiple!! > 1.0)
        assertTrue(q.upMultipleLabel.endsWith("x"))
        assertFalse(q.upMultipleLabel == "no ask")
    }

    @Test
    fun liveRestNumberTypedDollarsStillParse() {
        val raw = """
            {
              "ticker": "KXETH15M-26SEP251200-00",
              "yes_ask_dollars": 0.015,
              "no_ask_dollars": 0.25
            }
        """.trimIndent()
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
        val dto = json.decodeFromString<com.dirk.kalshiodds.data.dto.MarketDto>(raw)
        val ui = dto.toUiModel(SeriesKind.ETH)
        assertEquals(0.015, ui.yesAsk!!, 1e-12)
        assertEquals(0.25, ui.noAsk!!, 1e-12)
        val q = MarketQuoteView.of(ui)
        assertEquals("1.5¢", q.upHero)
        assertEquals(333.0 / 5.34, q.upMultiple!!, 1e-9)
        assertTrue(q.upMultiple!! < 1.0 / 0.015 + 1e-9)
        // YES-only parse: do not keep the mixed 25¢ NO ask. Complement of 1.5¢ ask is 98.5¢ bid.
        assertEquals("—", q.downHero)
        assertEquals("98.5¢", q.noBidLabel)
        assertTrue(q.downMultiple == null || q.downMultipleLabel.endsWith("x"))
    }

    @Test
    fun centsWithDecimalWireDoesNotHideTenCentsPlusOrInflateSubTen() {
        val oneFive = MarketQuoteView.of(yesBid = null, yesAsk = KalshiPrice.parseDollars("1.5"), noBid = null, noAsk = null)
        assertEquals("1.5¢", oneFive.upHero)
        assertEquals(333.0 / 5.34, oneFive.upMultiple!!, 1e-9)
        assertTrue(oneFive.upMultiple!! < 200.0)
        val twentyFive = MarketQuoteView.of(yesBid = null, yesAsk = KalshiPrice.parseDollars("25.00"), noBid = null, noAsk = null)
        assertEquals("25¢", twentyFive.upHero)
        assertEquals(20.0 / 5.27, twentyFive.upMultiple!!, 1e-9)
        val deci = MarketQuoteView.of(yesBid = null, yesAsk = KalshiPrice.parseDollars("440"), noBid = null, noAsk = null)
        assertEquals("44¢", deci.upHero)
        assertNotNull(deci.upMultiple)
    }

    @Test
    fun liveTickerAskOverlaysStaleRestWithoutSplittingDisplayAndMath() {
        val rest = MarketDto(
            ticker = "KXBTC15M-26SEP251200-00",
            yesAskDollars = "0.0010",
            noAskDollars = "0.2500"
        ).toUiModel(SeriesKind.BTC)
        val stale = MarketQuoteView.of(rest)
        assertEquals("0.1¢", stale.upHero)
        assertEquals(5000.0 / 5.35, stale.upMultiple!!, 1e-9)
        val tick = com.dirk.kalshiodds.signal.model.MarketTick(
            ticker = "KXBTC15M-26SEP251200-00",
            series = "KXBTC15M",
            yesBid = 0.014,
            yesAsk = 0.015,
            lastPrice = 0.25,
            volume = null,
            openInterest = null,
            closeTimeEpochMs = null,
            source = com.dirk.kalshiodds.signal.model.TickSource.WS_TICKER,
            receiveElapsedNanos = 1L,
            noAsk = 0.25
        )
        val live = rest.withLiveQuote(tick)
        val q = MarketQuoteView.of(live)
        assertEquals(0.015, live.yesAsk!!, 1e-12)
        assertEquals("1.5¢", q.upHero)
        assertEquals(333.0 / 5.34, q.upMultiple!!, 1e-9)
        assertEquals(q.upMultiple, KalshiQuoteDisplay.multiplier(live.yesAsk)!!, 1e-9)
        assertFalse(q.upButton.contains("934"))
        // Tick YES is source of truth; NO is derived (1 − yes), never stale REST 25¢.
        assertEquals(0.985, live.noBid!!, 1e-12)
        assertEquals(0.986, live.noAsk!!, 1e-12)
        assertEquals("98.5¢", q.noBidLabel)
    }

    @Test
    fun screenshotKxeth15mMixedYesAndNoIsRejectedAndRepaired() {
        // User phone 0.3.9: LIVE BOOK UP 55/63 + DOWN 37/50. 55+50=105 is impossible.
        // REST NO ask 50¢ left over; WS ticker only sent yes_bid/yes_ask 55/63.
        val rest = MarketUiModel(
            ticker = "KXETH15M-26SEP251230-30",
            title = "ETH price up in next 15 mins?",
            subtitle = null,
            floorStrike = 2691.74,
            yesBid = 0.55,
            yesAsk = 0.63,
            noBid = 0.37,
            noAsk = 0.50,
            lastPrice = 0.55,
            yesProbabilityPercent = 59.0,
            noProbabilityPercent = 41.0,
            volume = null,
            volume24h = null,
            closeTimeLocal = null,
            closeTimeEpochMs = null,
            status = "active",
            seriesLabel = "Ethereum"
        )
        val tick = com.dirk.kalshiodds.signal.model.MarketTick(
            ticker = "KXETH15M-26SEP251230-30",
            series = "KXETH15M",
            yesBid = 0.55,
            yesAsk = 0.63,
            lastPrice = 0.55,
            volume = null,
            openInterest = null,
            closeTimeEpochMs = null,
            source = com.dirk.kalshiodds.signal.model.TickSource.WS_TICKER,
            receiveElapsedNanos = 1L
        )
        val mixedView = MarketQuoteView.of(0.55, 0.63, 0.37, 0.50)
        assertEquals(0.55, mixedView.yesBid!!, 1e-12)
        assertEquals(0.45, mixedView.noAsk!!, 1e-12)
        assertEquals(0.37, mixedView.noBid!!, 1e-12)
        assertTrue(kotlin.math.abs(mixedView.yesBid!! + mixedView.noAsk!! - 1.0) < 0.0015)

        val live = rest.withLiveQuote(tick)
        assertEquals(0.55, live.yesBid!!, 1e-12)
        assertEquals(0.63, live.yesAsk!!, 1e-12)
        assertEquals(0.37, live.noBid!!, 1e-12)
        assertEquals(0.45, live.noAsk!!, 1e-12)
        assertTrue(kotlin.math.abs(live.yesBid!! + live.noAsk!! - 1.0) < 1e-12)
        assertTrue(kotlin.math.abs(live.noBid!! + live.yesAsk!! - 1.0) < 1e-12)
        val q = MarketQuoteView.of(live)
        assertEquals("55¢", q.yesBidLabel)
        assertEquals("63¢", q.yesAskLabel)
        assertEquals("37¢", q.noBidLabel)
        assertEquals("45¢", q.noAskLabel)
        assertFalse(q.downHeader.contains("ask 50¢"))
    }
}
