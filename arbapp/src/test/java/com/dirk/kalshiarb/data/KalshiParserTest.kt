package com.dirk.kalshiarb.data

import com.dirk.kalshiarb.scan.ArbType
import com.dirk.kalshiarb.scan.Level
import com.dirk.kalshiarb.scan.MarketBook
import com.dirk.kalshiarb.scan.Scanner
import com.dirk.kalshiarb.scan.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KalshiParserTest {

    private fun resource(name: String): String =
        requireNotNull(javaClass.classLoader?.getResource(name)) { "missing test resource $name" }.readText()

    @Test
    fun parsesEventsPageWithNestedMarkets() {
        val page = KalshiParser.parseEventsPage(resource("events_sample.json"))
        assertEquals("CgsIgICAgICAgICAARIHS1hIRUxMTw", page.cursor)
        assertEquals(3, page.events.size)

        val temp = page.events[0]
        assertEquals("KXHIGHNY-26SEP30", temp.eventTicker)
        assertEquals("KXHIGHNY", temp.seriesTicker)
        assertTrue(temp.mutuallyExclusive)
        assertEquals(5, temp.markets.size)
        val low = temp.markets[0]
        assertEquals("less", low.strikeType)
        assertEquals(58.0, low.capStrike!!, 0.0)
        assertNull(low.floorStrike)
        assertEquals(500, low.yesAskE4)
        assertEquals(400, low.yesBidE4)
        assertEquals(9_600, low.noAskE4)
        assertEquals("57° or below", low.label)
        assertTrue(low.isActive)

        val btc = page.events[1]
        assertFalse(btc.mutuallyExclusive)
        assertEquals("KXBTCD-26SEP3017", btc.markets[0].eventTicker) // inherited from the event
        assertEquals(107_999.99, btc.markets[0].floorStrike!!, 1e-9)
        // No NO quotes listed: the NO ask falls back to 1 − YES bid.
        assertEquals(3_900, btc.markets[0].askE4(Side.NO))

        val legacy = page.events[2].markets.single()
        assertEquals(4_500, legacy.yesAskE4)
        assertEquals(4_400, legacy.yesBidE4)
        assertEquals(5_600, legacy.noAskE4)
        assertEquals("Legacy A", legacy.label)
        assertNull(legacy.floorStrike)
    }

    @Test
    fun lastPageHasNoCursor() {
        val page = KalshiParser.parseEventsPage("""{"events": [], "cursor": ""}""")
        assertTrue(page.events.isEmpty())
        assertNull(page.cursor)
    }

    @Test
    fun parsesFixedPointOrderbookIntoAsks() {
        val b = KalshiParser.parseOrderbook("T", resource("orderbook_fp_sample.json"))
        // YES asks come from NO bids (1 − p); fractional sizes are floored.
        assertEquals(listOf(Level(600, 75), Level(700, 200)), b.yesAsks)
        assertEquals(listOf(Level(9_550, 30), Level(9_600, 120), Level(9_900, 500)), b.noAsks)
    }

    @Test
    fun parsesLegacyCentsOrderbook() {
        val b = KalshiParser.parseOrderbook("T", resource("orderbook_legacy_sample.json"))
        assertEquals(listOf(Level(4_300, 10), Level(4_500, 60)), b.yesAsks)
        assertEquals(listOf(Level(5_800, 25), Level(6_000, 100)), b.noAsks)
    }

    @Test
    fun emptyOrNullBookIsEmpty() {
        val b = KalshiParser.parseOrderbook("T", """{"orderbook": {"yes": null, "no": null}}""")
        assertTrue(b.yesAsks.isEmpty())
        assertTrue(b.noAsks.isEmpty())
    }

    @Test
    fun sampleTemperatureEventIsAnExhaustiveArb() {
        val events = KalshiParser.parseEventsPage(resource("events_sample.json")).events
        val plan = Scanner.plan(events)
        val books = events.flatMap { it.markets }.associate { it.ticker to MarketBook.topOfBook(it, 100) }
        val r = Scanner.evaluate(plan, books)
        val o = r.opportunities.single()
        assertEquals(ArbType.ALL_YES, o.structure.type)
        assertEquals("KXHIGHNY-26SEP30", o.structure.eventTicker)
        assertTrue(o.structure.verified)
        assertEquals(100L, o.sets)
        assertEquals(5, o.legs.size)
        assertTrue(o.profitCents > 0)
    }
}
