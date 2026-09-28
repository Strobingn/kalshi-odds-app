package com.dirk.kalshiodds.data.local.recording

import com.dirk.kalshiodds.signal.engine.LocalOrderBook
import com.dirk.kalshiodds.signal.engine.TickBook
import com.dirk.kalshiodds.signal.engine.TopOfBook
import com.dirk.kalshiodds.signal.model.MarketTick
import com.dirk.kalshiodds.signal.model.TickSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MarketDataRecorderTest {
    private lateinit var dir: File

    // 2026-09-28T12:00:00Z
    private val t0 = 1_790_596_800_000L

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("recordings").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun gunzipLines(f: File): List<String> =
        GZIPInputStream(f.inputStream()).bufferedReader().readLines()

    @Test
    fun formatRowsMatchTheResearchContract() {
        assertEquals("2026-09-28", RecordingFormat.utcDay(t0))
        assertEquals("2026-09-27", RecordingFormat.utcDay(t0 - 12 * 3_600_000L - 1))
        assertEquals("spot_2026-09-28.csv.gz", RecordingFormat.fileName("spot", "2026-09-28"))
        assertEquals("settle_2026-09-28.csv", RecordingFormat.fileName("settle", "2026-09-28"))
        assertEquals("book" to "2026-09-28", RecordingFormat.parseFileName("book_2026-09-28.csv.gz"))
        assertNull(RecordingFormat.parseFileName("settle_2026-09-28.csv.gz"))
        assertNull(RecordingFormat.parseFileName("pending_settle.csv"))
        assertEquals("0.55", RecordingFormat.num(0.55))
        assertEquals("65000.12", RecordingFormat.num(65000.12))
        assertEquals("", RecordingFormat.num(null))
        assertEquals("", RecordingFormat.num(Double.NaN))
        assertEquals("0", RecordingFormat.num(0.0))
        assertEquals("$t0,BTC-USD,65000.5", RecordingFormat.spotRow(t0, "BTC-USD", 65000.5))
        val top = TopOfBook(0.55, 10.0, 0.57, 4.0, 0.43, 4.0, 0.45, 10.0)
        assertEquals(
            "$t0,KXBTC15M-X,65000,${t0 + 60_000},0.55,10,0.57,4,0.43,4,0.45,10",
            RecordingFormat.bookRow(t0, "KXBTC15M-X", 65000.0, t0 + 60_000, top)
        )
        assertEquals(
            "$t0,KXBTC15M-X,,,0.55,,,,,,0.45,",
            RecordingFormat.bookRow(t0, "KXBTC15M-X", null, null, TopOfBook(yesBid = 0.55, noAsk = 0.45))
        )
        assertEquals("$t0,KXBTC15M-X,0.56,3,yes", RecordingFormat.tradeRow(t0, "KXBTC15M-X", 0.56, 3.0, "YES"))
        assertEquals("$t0,KXBTC15M-X,0.56,,", RecordingFormat.tradeRow(t0, "KXBTC15M-X", 0.56, null, null))
        assertEquals("KXBTC15M-X,$t0,65000,no", RecordingFormat.settleRow("KXBTC15M-X", t0, 65000.0, "NO"))
        assertEquals(12, RecordingFormat.BOOK_HEADER.split(',').size)
    }

    @Test
    fun localBookTopDerivesAsksFromTheOtherSide() {
        val book = LocalOrderBook()
        book.replaceSnapshot(yesLevels = listOf(0.50 to 5.0, 0.55 to 10.0), noLevels = listOf(0.40 to 7.0, 0.43 to 4.0))
        val top = book.top()
        assertEquals(0.55, top.yesBid!!, 1e-9)
        assertEquals(10.0, top.yesBidQty!!, 1e-9)
        assertEquals(0.57, top.yesAsk!!, 1e-9)
        assertEquals(4.0, top.yesAskQty!!, 1e-9)
        assertEquals(0.43, top.noBid!!, 1e-9)
        assertEquals(0.45, top.noAsk!!, 1e-9)
        assertEquals(10.0, top.noAskQty!!, 1e-9)
        assertEquals(book.bestYesAsk()!!, top.yesAsk!!, 1e-12)
        assertEquals(book.bestNoAsk()!!, top.noAsk!!, 1e-12)

        val oneSided = LocalOrderBook()
        oneSided.replaceSnapshot(yesLevels = listOf(0.30 to 2.0), noLevels = emptyList())
        val t = oneSided.top()
        assertNull(t.yesAsk)
        assertNull(t.noBid)
        assertEquals(0.70, t.noAsk!!, 1e-9)
    }

    @Test
    fun tickBookTopPrefersLocalBookAndIgnoresTradePrints() {
        val tb = TickBook()
        val ticker = "KXBTC15M-26SEP281215-15"
        assertNull(tb.topOfBook(ticker))
        tb.applySnapshot(ticker, listOf(0.60 to 3.0), listOf(0.38 to 2.0))
        val top = tb.topOfBook(ticker)!!
        assertEquals(0.60, top.yesBid!!, 1e-9)
        assertEquals(0.62, top.yesAsk!!, 1e-9)
        assertEquals(2.0, top.yesAskQty!!, 1e-9)
    }

    @Test
    fun spotThrottleKeepsLatestPerQuarterSecond() {
        val th = SpotThrottle(250L)
        assertNull(th.offer(t0 + 10, "BTC-USD", 1.0))
        assertNull(th.offer(t0 + 100, "BTC-USD", 2.0))
        assertNull(th.offer(t0 + 240, "BTC-USD", 3.0))
        val released = th.offer(t0 + 260, "BTC-USD", 4.0)
        assertEquals(SpotThrottle.Print(t0 + 240, "BTC-USD", 3.0), released)
        assertTrue(th.drainDue(t0 + 300).isEmpty())
        assertEquals(listOf(SpotThrottle.Print(t0 + 260, "BTC-USD", 4.0)), th.drainDue(t0 + 500))
        assertNull(th.offer(t0 + 600, "BTC-USD", Double.NaN))
    }

    @Test
    fun bookGateWritesOnChangeAtMostOncePerSecondPlusHeartbeat() {
        val g = BookRowGate(1_000L, 5_000L)
        val a = TopOfBook(yesBid = 0.5)
        val b = TopOfBook(yesBid = 0.51)
        assertTrue(g.shouldWrite("T", a, t0))
        g.markWritten("T", a, t0)
        assertFalse(g.shouldWrite("T", b, t0 + 500))
        assertTrue(g.shouldWrite("T", b, t0 + 1_000))
        assertFalse(g.shouldWrite("T", a, t0 + 4_999))
        assertTrue(g.shouldWrite("T", a, t0 + 5_000))
    }

    @Test
    fun filesAppendAcrossRestartsAsMultiMemberGzipWithOneHeader() {
        val files = RecordingFiles(dir)
        files.append("spot", "2026-09-28", listOf("1,BTC-USD,1"), t0)
        files.flush(t0)
        // Sync-flushed data is readable before the member is finished.
        val partial = File(dir, "spot_2026-09-28.csv.gz")
        assertTrue(partial.length() > 0)
        files.closeAll()
        val again = RecordingFiles(dir) // app restart
        again.append("spot", "2026-09-28", listOf("2,BTC-USD,2"), t0 + 1)
        again.closeAll()
        assertEquals(
            listOf(RecordingFormat.SPOT_HEADER, "1,BTC-USD,1", "2,BTC-USD,2"),
            gunzipLines(partial)
        )
        again.appendSettle("2026-09-28", listOf("A,1,2,yes"))
        again.appendSettle("2026-09-28", listOf("B,1,2,no"))
        assertEquals(
            listOf(RecordingFormat.SETTLE_HEADER, "A,1,2,yes", "B,1,2,no"),
            File(dir, "settle_2026-09-28.csv").readLines()
        )
    }

    @Test
    fun capDeletesOldestDaysButNeverToday() {
        val files = RecordingFiles(dir, maxTotalBytes = 1_500L)
        for (day in listOf("2026-09-25", "2026-09-26", "2026-09-27")) {
            File(dir, "book_$day.csv.gz").writeBytes(ByteArray(600))
        }
        File(dir, "settle_2026-09-25.csv").writeText("x\n")
        val deleted = files.enforceCap("2026-09-27")
        assertEquals(listOf("2026-09-25", "2026-09-26"), deleted)
        assertEquals(listOf("2026-09-27"), files.days().map { it.day })
        assertFalse(files.overCap)
        File(dir, "spot_2026-09-27.csv.gz").writeBytes(ByteArray(1_000))
        files.enforceCap("2026-09-27")
        assertTrue(files.overCap)
    }

    @Test
    fun recorderWritesAllFourFilesAndExportsAZip() {
        val ticker = "KXBTC15M-26SEP281215-15"
        var top = TopOfBook(0.55, 10.0, 0.57, 4.0, 0.43, 4.0, 0.45, 10.0)
        var now = t0
        val recorder = MarketDataRecorder(
            files = RecordingFiles(dir),
            topOf = { top },
            metaOf = { 65000.0 to t0 + 60_000L },
            watchedTickers = { setOf(ticker, "KXBTCD-26SEP2813-T65000") },
            // Cancelled scope: setActive flips the flag but runs no loop; the test drives step().
            scope = CoroutineScope(Job().apply { cancel() }),
            clock = { now }
        )
        recorder.setActive(true)
        recorder.onSpot("BTC-USD", 65000.0)
        now += 100; recorder.onSpot("BTC-USD", 65001.0)
        now += 100; recorder.onSpot("ETH-USD", 2500.0) // not recorded
        recorder.onTick(trade(ticker, 0.56, 3.0, "yes"))
        recorder.onTick(trade("KXBTCD-26SEP2813-T65000", 0.5, 1.0, "no")) // other series
        recorder.onTick(trade(ticker, 0.56, 1.0, null).copy(source = TickSource.WS_TICKER)) // not a trade
        recorder.step(now) // first book row
        now += 500; top = top.copy(yesBid = 0.56); recorder.step(now) // changed, < 1 s: skip
        now += 600; recorder.step(now) // changed, ≥ 1 s: row
        now += 3_000; recorder.step(now) // unchanged: no row, flush
        now += 2_100; recorder.step(now) // 5 s heartbeat
        recorder.finish(now)

        val spot = gunzipLines(File(dir, "spot_2026-09-28.csv.gz"))
        assertEquals(listOf(RecordingFormat.SPOT_HEADER, "${t0 + 100},BTC-USD,65001"), spot)
        val trades = gunzipLines(File(dir, "trades_2026-09-28.csv.gz"))
        assertEquals(listOf(RecordingFormat.TRADES_HEADER, "${t0 + 200},$ticker,0.56,3,yes"), trades)
        val book = gunzipLines(File(dir, "book_2026-09-28.csv.gz"))
        assertEquals(RecordingFormat.BOOK_HEADER, book[0])
        assertEquals(4, book.size)
        assertTrue(book[1].startsWith("${t0 + 200},$ticker,65000,${t0 + 60_000},0.55,10,"))
        assertTrue(book[2].startsWith("${t0 + 1_300},$ticker,65000,${t0 + 60_000},0.56,10,"))
        assertTrue(book[3].startsWith("${t0 + 6_400},"))

        // Settlement: closed + grace → asked for; result appended once.
        assertTrue(recorder.pendingSettlementTickers(t0 + 60_000L).isEmpty())
        assertEquals(setOf(ticker), recorder.pendingSettlementTickers(t0 + 120_000L))
        recorder.onSettled(ticker, "yes")
        recorder.onSettled(ticker, "yes")
        assertEquals(
            listOf(RecordingFormat.SETTLE_HEADER, "$ticker,${t0 + 60_000},65000,yes"),
            File(dir, "settle_2026-09-28.csv").readLines()
        )
        assertTrue(recorder.pendingSettlementTickers(t0 + 120_000L).isEmpty())

        val stats = recorder.stats(now)
        assertEquals(listOf("2026-09-28"), stats.days.map { it.day })
        assertTrue(stats.todayBytes > 0)

        val bytes = ByteArrayOutputStream()
        assertEquals(4, recorder.export(emptyList(), bytes))
        val names = ArrayList<String>()
        ZipInputStream(ByteArrayInputStream(bytes.toByteArray())).use { zip ->
            while (true) names += (zip.nextEntry ?: break).name
        }
        assertEquals(
            listOf(
                "recordings/book_2026-09-28.csv.gz",
                "recordings/settle_2026-09-28.csv",
                "recordings/spot_2026-09-28.csv.gz",
                "recordings/trades_2026-09-28.csv.gz"
            ),
            names
        )
        assertEquals(0, recorder.export(listOf("2026-01-01"), ByteArrayOutputStream()))
    }

    @Test
    fun pendingSettlementsSurviveARestart() {
        val ticker = "KXBTC15M-26SEP281215-15"
        val make = {
            MarketDataRecorder(
                files = RecordingFiles(dir),
                topOf = { TopOfBook(yesBid = 0.5) },
                metaOf = { null to t0 },
                watchedTickers = { setOf(ticker) },
                scope = CoroutineScope(Job().apply { cancel() }),
                clock = { t0 }
            )
        }
        val first = make()
        first.step(t0 + 5_000L)
        first.finish(t0 + 5_000L)
        val second = make()
        assertEquals(setOf(ticker), second.pendingSettlementTickers(t0 + 60_000L))
        second.onSettled(ticker, "void")
        assertFalse(File(dir, "settle_2026-09-28.csv").exists())
        assertTrue(make().pendingSettlementTickers(t0 + 60_000L).isEmpty())
    }

    @Test
    fun inactiveRecorderIgnoresTheTickPath() {
        val recorder = MarketDataRecorder(
            files = RecordingFiles(dir),
            topOf = { null },
            metaOf = { null to null },
            watchedTickers = { emptySet() },
            scope = CoroutineScope(Job().apply { cancel() }),
            clock = { t0 }
        )
        recorder.onSpot("BTC-USD", 1.0)
        recorder.onTick(trade("KXBTC15M-X", 0.5, 1.0, "yes"))
        recorder.finish(t0 + 1_000L)
        assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".gz") })
        assertTrue(MarketDataRecorder.isRecordedTicker("KXBTC15M-26SEP281215-15"))
        assertFalse(MarketDataRecorder.isRecordedTicker("KXBTCD-26SEP2813-T65000"))
    }

    private fun trade(ticker: String, price: Double, count: Double, side: String?) = MarketTick(
        ticker = ticker,
        series = "KXBTC15M",
        yesBid = price,
        yesAsk = price,
        lastPrice = price,
        volume = null,
        openInterest = null,
        closeTimeEpochMs = null,
        source = TickSource.WS_TRADE,
        receiveElapsedNanos = 0L,
        tradeSize = count,
        takerSide = side
    )
}
