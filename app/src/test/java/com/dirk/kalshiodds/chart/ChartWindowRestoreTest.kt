package com.dirk.kalshiodds.chart

import com.dirk.kalshiodds.data.backfill.HistoryTransport
import com.dirk.kalshiodds.data.backfill.KalshiBackfillEngine
import com.dirk.kalshiodds.data.backfill.LiveWindowBackfill
import com.dirk.kalshiodds.data.local.archive.ChartTickRow
import com.dirk.kalshiodds.data.local.results.InMemoryResultsStore
import com.dirk.kalshiodds.domain.MarketQuoteView
import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.signal.engine.TickBook
import com.dirk.kalshiodds.signal.model.MarketTick
import com.dirk.kalshiodds.signal.model.TickSource
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartWindowRestoreTest {

    private val ticker = "KXBTC15M-26SEP251045-45"
    private val windowStart = 1_700_000_000_000L
    private val windowEnd = windowStart + 900_000L

    @Test
    fun persistAndRestoreTicksAcrossNewRepository() {
        val store = InMemoryResultsStore()
        val book1 = TickBook()
        val repo1 = ChartWindowService(store, book1)
        val live = (0 until 90).map { i ->
            BidPoint(
                tMs = windowStart + i * 10_000L,
                upBidCents = 40f + i * 0.4f,
                downBidCents = 55f - i * 0.3f,
                spotUsd = 83_700.0 + i
            )
        }
        repo1.persistPoints(ticker, live, ChartTickRow.SOURCE_LIVE)
        book1.seedBids(ticker, live)

        val book2 = TickBook()
        val repo2 = ChartWindowService(store, book2)
        val restored = repo2.restoreWindow(ticker, windowStart, windowEnd)
        assertTrue(restored.size >= 80)
        assertEquals(live.first().tMs, restored.first().tMs)
        assertEquals(live.last().upBidCents!!, restored.last().upBidCents!!, 0.2f)
        assertTrue(ChartSeriesMerge.coversWindow(restored, windowStart, windowEnd))
        assertEquals(restored.size, book2.bidHistory(ticker).size)
    }

    @Test
    fun mergeBackfillAndLiveHasNoDuplicatesAndCorrectOrder() {
        val stored = listOf(
            BidPoint(windowStart + 60_000L, 40f, 55f),
            BidPoint(windowStart + 120_000L, 41f, 54f)
        )
        val backfill = listOf(
            BidPoint(windowStart + 60_000L, 40.2f, 54.8f, 83_700.0),
            BidPoint(windowStart + 180_000L, 42f, 53f, 83_710.0)
        )
        val live = listOf(
            BidPoint(windowStart + 180_000L, 43f, 52f),
            BidPoint(windowStart + 240_000L, 44f, 51f)
        )
        val merged = ChartSeriesMerge.merge(stored, backfill, live)
        assertEquals(listOf(60_000L, 120_000L, 180_000L, 240_000L).map { windowStart + it }, merged.map { it.tMs })
        assertEquals(43f, merged[2].upBidCents!!, 0.01f)
        assertEquals(83_710.0, merged[2].spotUsd!!, 1e-6)
        assertEquals(4, merged.size)
    }

    @Test
    fun parseDocAccurateCandlestickSample() {
        val body = docSample()
        val prints = LiveWindowBackfill.parseResponse(body)
        assertEquals(1, prints.size)
        val p = prints.single()
        assertEquals(1_727_282_700L, p.endTs)
        assertEquals(0.42, p.yesBid!!, 1e-9)
        assertEquals(0.56, p.noBid!!, 1e-9)
        assertEquals(0.43, p.mid!!, 1e-9)
        val point = p.toBidPoint()
        assertEquals(1_727_282_700_000L, point.tMs)
        assertEquals(42f, point.upBidCents!!, 0.01f)
        assertEquals(56f, point.downBidCents!!, 0.01f)
    }

    @Test
    fun liveBackfillUsesOfficialPathAndParams() {
        val calls = ArrayList<String>()
        val transport = HistoryTransport { path, query ->
            calls.add(path + "?" + query.entries.joinToString("&") { "${it.key}=${it.value}" })
            docSample()
        }
        val engine = LiveWindowBackfill(transport, sleep = {}, maxRetries = 1)
        val pts = engine.candles("KXBTC15M", ticker, windowStart, windowEnd)
        assertEquals(1, pts.size)
        assertTrue(calls.single().startsWith("/series/KXBTC15M/markets/$ticker/candlesticks"))
        assertTrue(calls.single().contains("period_interval=1"))
        assertTrue(calls.single().contains("start_ts="))
        assertTrue(calls.single().contains("end_ts="))
    }

    @Test
    fun processRestartThenBackfillFillsTheGap() {
        val store = InMemoryResultsStore()
        val lateOnly = listOf(
            BidPoint(windowEnd - 20_000L, 99f, 1f, 83_733.0),
            BidPoint(windowEnd - 10_000L, 100f, null, 83_733.0)
        )
        ChartWindowService(store, TickBook()).persistPoints(ticker, lateOnly)

        val candles = JSONObject().put(
            "candlesticks",
            JSONArray().apply {
                for (i in 0 until 15) {
                    put(
                        JSONObject()
                            .put("end_period_ts", (windowStart / 1000L) + (i + 1) * 60)
                            .put("yes_bid", JSONObject().put("close_dollars", "0.4${i % 10}00"))
                            .put("yes_ask", JSONObject().put("close_dollars", "0.5${i % 10}00"))
                            .put("price", JSONObject().put("close_dollars", "0.4500"))
                    )
                }
            }
        )
        val transport = HistoryTransport { _, _ -> candles.toString() }
        val book2 = TickBook()
        val repo2 = ChartWindowService(
            store,
            book2,
            backfill = LiveWindowBackfill(transport, sleep = {}, maxRetries = 1)
        )
        assertTrue(repo2.needsBackfill(ticker, windowStart, windowEnd))
        val filled = repo2.backfillWindow(ticker, "KXBTC15M", windowStart, windowEnd)
        assertTrue(ChartSeriesMerge.coversWindow(filled, windowStart, windowEnd))
        assertTrue(filled.size >= 15)
        assertTrue(filled.first().tMs <= windowStart + 90_000L)
        assertTrue(filled.any { it.tMs >= windowEnd - 20_000L })
    }

    @Test
    fun parseCandleUsesFixedPointDollarsNotCents() {
        val c = JSONObject()
            .put("end_period_ts", 1_700_000_060L)
            .put("yes_bid", JSONObject().put("close_dollars", "0.0060"))
            .put("yes_ask", JSONObject().put("close_dollars", "0.0100"))
            .put("price", JSONObject().put("close_dollars", "0.0080"))
        val print = KalshiBackfillEngine.parseCandle(c)
        assertEquals(0.006, print.yesBid!!, 1e-9)
        assertEquals(0.99, print.noBid!!, 1e-9)
    }

    @Test
    fun labelsHeaderButtonsShareOneQuoteSource() {
        val market = sampleMarket(yesBid = 1.0, yesAsk = null, noBid = null, noAsk = 0.01)
        val q = MarketQuoteView.of(market)
        assertEquals("100¢", q.yesBidLabel)
        assertEquals("—", q.yesAskLabel)
        assertEquals("—", q.noBidLabel)
        assertEquals("1¢", q.noAskLabel)
        assertEquals(q.upHeader, "UP bid 100¢  ask —")
        assertEquals(q.downHeader, "DOWN bid —  ask 1¢")
        assertEquals(q.upChartLabel, "UP bid 100¢")
        assertEquals(q.downChartLabel, "DOWN bid —")
        assertEquals("Buy UP", q.upButton)
        assertTrue(q.downButton.startsWith("Down 1¢"))
        assertEquals(q.downMultiple, com.dirk.kalshiodds.domain.KalshiQuoteDisplay.multiplier(0.01))
        assertNull(q.upMultiple)
    }

    @Test
    fun seriesForCardUsesLiveHeadNotStaleMid() {
        val store = InMemoryResultsStore()
        val book = TickBook()
        val repo = ChartWindowService(store, book)
        repo.persistPoints(
            ticker,
            listOf(BidPoint(windowStart + 30_000L, 71f, 29f))
        )
        val market = sampleMarket(yesBid = 1.0, yesAsk = null, noBid = null, noAsk = 0.01)
            .copy(closeTimeEpochMs = windowEnd, bidHistory = emptyList(), oddsHistory = listOf(71f))
        val series = repo.seriesForCard(market, nowMs = windowEnd - 60_000L)
        assertTrue(series.isNotEmpty())
        assertEquals(100f, series.last().upBidCents!!, 0.01f)
    }

    private fun sampleMarket(
        yesBid: Double?,
        yesAsk: Double?,
        noBid: Double?,
        noAsk: Double?
    ) = MarketUiModel(
        ticker = ticker,
        title = "BTC price up in next 15 mins?",
        subtitle = null,
        floorStrike = 83_661.01,
        yesBid = yesBid,
        yesAsk = yesAsk,
        noBid = noBid,
        noAsk = noAsk,
        lastPrice = yesBid,
        yesProbabilityPercent = yesBid?.times(100.0),
        noProbabilityPercent = noBid?.times(100.0),
        volume = 1.0,
        volume24h = 1.0,
        openInterest = 1.0,
        liquidityDollars = 1.0,
        closeTimeLocal = null,
        closeTimeEpochMs = windowEnd,
        status = "active",
        seriesLabel = "Bitcoin"
    )

    private fun docSample(): String = JSONObject()
        .put("ticker", ticker)
        .put(
            "candlesticks",
            JSONArray().put(
                JSONObject()
                    .put("end_period_ts", 1_727_282_700L)
                    .put(
                        "yes_bid",
                        JSONObject()
                            .put("open_dollars", "0.4000")
                            .put("low_dollars", "0.3900")
                            .put("high_dollars", "0.4500")
                            .put("close_dollars", "0.4200")
                    )
                    .put(
                        "yes_ask",
                        JSONObject()
                            .put("open_dollars", "0.4200")
                            .put("low_dollars", "0.4100")
                            .put("high_dollars", "0.4700")
                            .put("close_dollars", "0.4400")
                    )
                    .put(
                        "price",
                        JSONObject()
                            .put("close_dollars", "0.4300")
                    )
                    .put("volume_fp", "10.00")
                    .put("open_interest_fp", "100.00")
            )
        )
        .toString()
}

class ChartTickBookSeedTest {
    @Test
    fun seedBidsRestoresOneSidedHundredCentPrint() {
        val book = TickBook()
        val ticker = "KXBTC15M-SEED"
        book.seedBids(
            ticker,
            listOf(
                BidPoint(1_000L, 64f, 35f, 83_700.0),
                BidPoint(2_000L, 100f, null, 83_733.0)
            )
        )
        val hist = book.bidHistory(ticker)
        assertEquals(2, hist.size)
        assertEquals(100f, hist.last().upBidCents!!, 0.01f)
        assertEquals(83_733.0, hist.last().spotUsd!!, 1e-6)
    }

    @Test
    fun liveHundredCentBidIsRecorded() {
        val book = TickBook()
        val ticker = "KXBTC15M-HUNDRED"
        book.push(
            MarketTick(
                ticker = ticker,
                series = "KXBTC15M",
                yesBid = 1.0,
                yesAsk = null,
                lastPrice = 0.99,
                volume = 1.0,
                openInterest = 1.0,
                closeTimeEpochMs = 9_000L,
                source = TickSource.REST,
                receiveElapsedNanos = 1L,
                noBid = null,
                noAsk = 0.01
            ),
            nowMs = 5_000L
        )
        val hist = book.bidHistory(ticker)
        assertEquals(1, hist.size)
        assertEquals(100f, hist.single().upBidCents!!, 0.01f)
    }
}
