package com.dirk.kalshiodds.data.backfill

import com.dirk.kalshiodds.data.local.archive.BackfillCursorRow
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KalshiBackfillEngineTest {
    @Test
    fun pagesAndResumesFromLastTicker() {
        val pages = ArrayDeque(
            listOf(
                marketPage(
                    listOf(
                        market("KXBTC15M-A", "yes", "2026-09-20T16:00:00Z"),
                        market("KXBTC15M-B", "no", "2026-09-20T16:15:00Z")
                    ),
                    cursor = "c2"
                ),
                marketPage(
                    listOf(market("KXBTC15M-C", "yes", "2026-09-20T16:30:00Z")),
                    cursor = null
                )
            )
        )
        val candles = JSONObject().put(
            "candlesticks",
            JSONArray().put(
                JSONObject()
                    .put("end_period_ts", 1_700_000_000L)
                    .put("yes_bid", JSONObject().put("close_dollars", "0.4000"))
                    .put("yes_ask", JSONObject().put("close_dollars", "0.4200"))
                    .put("price", JSONObject().put("close_dollars", "0.4100"))
            )
        )
        val calls = ArrayList<String>()
        val transport = HistoryTransport { path, query ->
            calls.add(path + "?" + query.entries.joinToString("&") { "${it.key}=${it.value}" })
            when {
                path.contains("candlesticks") -> candles.toString()
                path == "/markets" -> pages.removeFirst().toString()
                else -> null
            }
        }
        val engine = KalshiBackfillEngine(transport, nowMs = { 9L }, sleep = {}, pagePauseMs = 0L)
        val first = engine.step(
            series = "KXBTC15M",
            cursor = BackfillCursorRow(job = "t", series = "KXBTC15M"),
            minCloseMs = 0L,
            historical = false
        )
        assertEquals(2, first.settled.size)
        assertEquals("c2", first.cursor.cursor)
        assertEquals("KXBTC15M-B", first.cursor.lastTicker)
        assertTrue(first.path.isNotEmpty())

        val second = engine.step(
            series = "KXBTC15M",
            cursor = first.cursor,
            minCloseMs = 0L,
            historical = false
        )
        // resume skips A/B (already on the previous page's lastTicker) and takes C
        assertEquals(listOf("KXBTC15M-C"), second.settled.map { it.ticker })
        assertTrue(second.progress.done)
        assertNull(second.cursor.cursor)
        assertTrue(calls.any { it.startsWith("/markets") && it.contains("cursor=c2") })
    }

    @Test
    fun cancelStops() {
        val transport = HistoryTransport { _, _ ->
            marketPage(listOf(market("KXBTC15M-A", "yes", "2026-09-20T16:00:00Z")), null).toString()
        }
        val engine = KalshiBackfillEngine(transport, sleep = {}, pagePauseMs = 0L)
        val out = engine.step(
            series = "KXBTC15M",
            cursor = BackfillCursorRow(job = "t", series = "KXBTC15M"),
            minCloseMs = 0L,
            historical = false,
            cancel = { true }
        )
        assertTrue(out.progress.cancelled)
        assertEquals("cancelled", out.cursor.status)
    }

    private fun market(ticker: String, result: String, close: String) = JSONObject()
        .put("ticker", ticker)
        .put("result", result)
        .put("close_time", close)
        .put("open_time", "2026-09-20T15:45:00Z")
        .put("floor_strike", 111000.0)

    private fun marketPage(markets: List<JSONObject>, cursor: String?): JSONObject {
        val arr = JSONArray()
        markets.forEach { arr.put(it) }
        return JSONObject().put("markets", arr).put("cursor", cursor ?: "")
    }
}
