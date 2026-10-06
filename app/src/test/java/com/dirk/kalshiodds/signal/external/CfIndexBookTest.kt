package com.dirk.kalshiodds.signal.external

import com.dirk.kalshiodds.signal.ws.KalshiWsMessages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CfIndexBookTest {

    private val withFinal = """
        {"type":"cfbenchmarks_value","sid":3,"msg":{
          "index_id":"BRTI","received_at":1710000000200,
          "data":{"type":"value","id":"BRTI","value":"68001.50","time":1710000000123},
          "avg_60s_data":{"value":"68000.12000000","window_size":3,"window_start_ts_ms":1709999940123,"window_end_ts_exclusive":1710000000123},
          "last_60s_windowed_average_15min":{"value":"67990.25","window_size":40}
        }}
    """.trimIndent()

    @Test
    fun parsesIndexValueWithFinalMinuteAverage() {
        val p = KalshiWsMessages.parse(withFinal, 0L) as KalshiWsMessages.Parsed.IndexValue
        assertEquals("BRTI", p.indexId)
        assertEquals(68001.50, p.value, 1e-9)
        assertEquals(1710000000123L, p.sourceTsMs)
        assertEquals(1710000000200L, p.receivedAtMs)
        assertEquals(68000.12, p.avg60!!, 1e-9)
        assertEquals(3, p.avg60Ticks)
        assertEquals(67990.25, p.finalMinuteAvg!!, 1e-9)
        assertEquals(40, p.finalMinuteTicks)
    }

    @Test
    fun parsesDataSentAsAJsonString() {
        val raw = """{"type":"cfbenchmarks_value","msg":{"index_id":"ETHUSD_RTI","data":"{\"value\":\"3500.5\",\"time\":5}"}}"""
        val p = KalshiWsMessages.parse(raw, 0L) as KalshiWsMessages.Parsed.IndexValue
        assertEquals(3500.5, p.value, 1e-9)
        assertNull(p.finalMinuteAvg)
    }

    @Test
    fun badFramesAreIgnored() {
        assertTrue(KalshiWsMessages.parse("""{"type":"cfbenchmarks_value","msg":{"index_id":"BRTI","data":{}}}""", 0L) is KalshiWsMessages.Parsed.Other)
        assertTrue(KalshiWsMessages.parse("""{"type":"cfbenchmarks_value","msg":{"data":{"value":"1"}}}""", 0L) is KalshiWsMessages.Parsed.Other)
    }

    @Test
    fun subscribeCommandCarriesIndexIds() {
        val cmd = KalshiWsMessages.subscribeIndices(7, CfIndexBook.INDEX_IDS)
        assertTrue(cmd, cmd.contains("\"cfbenchmarks_value\""))
        assertTrue(cmd, cmd.contains("\"index_ids\":[\"BRTI\",\"ETHUSD_RTI\",\"SOLUSD_RTI\"]"))
        assertTrue(cmd, cmd.contains("\"cmd\":\"subscribe\""))
    }

    @Test
    fun subscribedAckKeepsChannel() {
        val p = KalshiWsMessages.parse("""{"id":2,"type":"subscribed","msg":{"channel":"cfbenchmarks_value","sid":9}}""", 0L)
            as KalshiWsMessages.Parsed.Subscribed
        assertEquals("cfbenchmarks_value", p.channel)
        assertEquals(9, p.sid)
    }

    @Test
    fun freshnessAndAssetMapping() {
        val book = CfIndexBook()
        val v = KalshiWsMessages.parse(withFinal, 0L) as KalshiWsMessages.Parsed.IndexValue
        book.onUpdate(v, nowMs = 1_000L)
        assertNotNull(book.fresh("BTC", 3_000L))
        assertNull(book.fresh("BTC", 1_000L + CfIndexBook.MAX_AGE_MS + 1))
        assertNull(book.fresh("ETH", 1_000L))
    }

    @Test
    fun finalMinuteAverageOnlyInsideThatMinute() {
        val book = CfIndexBook()
        val close = 900_000L
        val v = KalshiWsMessages.parse(withFinal, 0L) as KalshiWsMessages.Parsed.IndexValue
        book.onUpdate(v, nowMs = close - 20_000L)
        assertEquals(67990.25, book.finalMinuteAverage("BTC", close, close - 19_000L)!!, 1e-9)
        // Before the final minute, or for the next window, it is not used.
        assertNull(book.finalMinuteAverage("BTC", close, close - 61_000L))
        assertNull(book.finalMinuteAverage("BTC", close + 900_000L, close + 900_000L - 30_000L))
        // An update without the final-minute block keeps the last average but
        // goes stale once the final-minute block stops arriving.
        val plain = v.copy(finalMinuteAvg = null, finalMinuteTicks = null)
        book.onUpdate(plain, nowMs = close - 10_000L)
        assertNull(book.finalMinuteAverage("BTC", close, close - 10_000L + 1))
    }
}
