package com.dirk.kalshiodds.signal.scalper

import com.dirk.kalshiodds.signal.trade.KalshiFee
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.ZoneId
import java.util.concurrent.Executor
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/** The results screen's numbers and the on-disk record of every paper order. */
class ScalperResultsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val t = "KXBTC15M-26OCT091315-15"
    private val noQueue: (String, Double) -> Double? = { _, _ -> null }
    private val direct = Executor { it.run() }

    /** Three closed scalps (a win, a stop, a time-out) and one bid that expired, through the real engine. */
    private fun play(ledger: ScalperLedger, clock: (Long) -> Unit = {}) {
        val e = ScalperEngine()
        // 1. ML scalp, +1¢ on 10 contracts, no fee: +$0.10 at 2 s.
        ledger.apply(listOf(e.post(ScalpStrategy.ML_REST, t, "YES", 0.50, 50.0, 0.40, 0.28, 0L,
            ScalpContext(0.50, 0.51, 50.0, 800.0, 0.01, -0.02, floatArrayOf(0.505f, 0.01f, Float.NaN)))!!))
        ledger.apply(e.onTrade(t, false, 0.49, 1.0, 1_000L, noQueue))
        ledger.apply(e.onTrade(t, true, 0.52, 1.0, 2_000L, noQueue))
        // 2. ML scalp stopped at 45¢: −$0.50 − fee.
        ledger.apply(listOf(e.post(ScalpStrategy.ML_REST, t, "YES", 0.50, 3_000.0, 0.40, -1.4, 3_000L)!!))
        ledger.apply(e.onTrade(t, false, 0.49, 1.0, 4_000L, noQueue))
        ledger.apply(e.onClock(t, 5_000L, 0.45, 0.54))
        // 3. Dip-hunter buys DOWN at 40¢ and times out at 40¢: two fees.
        ledger.apply(e.buyNow(ScalpStrategy.DIP_HUNTER, t, "NO", 0.40, noQueue, 6_000L))
        ledger.apply(e.onClock(t, 3_700_000L, 0.59, 0.40))
        // 4. A resting bid nobody hits: expires unfilled.
        ledger.apply(listOf(e.post(ScalpStrategy.DIP_HUNTER_REST, t, "YES", 0.55, 10.0, 0.0, 0.0, 3_700_000L)!!))
        clock(3_730_000L)
        ledger.apply(e.onClock(t, 3_730_000L, 0.55, 0.44))
    }

    @Test
    fun totalsCountWinsLossesFeesAndExtremes() {
        var now = 0L
        // Hour of the day = hours since the epoch, so the third scalp (at 61 min) lands in hour 1.
        val ledger = ScalperLedger(nowMs = { now }, hourOf = { (it / 3_600_000L).toInt() })
        play(ledger) { now = it }
        val s = ledger.snapshot()
        val h = ScalperResults.headline(s)
        val stopFee = KalshiFee.total(10, 0.45)
        val dipFee = KalshiFee.total(10, 0.40)
        val stopLoss = 0.50 + stopFee
        assertEquals(3, h.closed)
        assertEquals(1, h.wins)
        assertEquals(2, h.losses)
        assertEquals(0, h.flat)
        assertEquals(0.10, h.wonUsd, 1e-9)
        assertEquals(stopLoss + 2 * dipFee, h.lostUsd, 1e-9)
        assertEquals("net = won − lost", h.wonUsd - h.lostUsd, h.netUsd, 1e-9)
        assertEquals(stopFee + 2 * dipFee, h.feesUsd, 1e-9)
        assertEquals(0.10, h.avgWinUsd!!, 1e-9)
        assertEquals((stopLoss + 2 * dipFee) / 2, h.avgLossUsd!!, 1e-9)
        assertEquals(0.10, h.biggestWinUsd, 1e-9)
        assertEquals("the stop lost more than the time-out", stopLoss, h.biggestLossUsd, 1e-9)
        assertEquals(ScalperResults.START_BANKROLL_USD + h.netUsd, h.bankrollUsd, 1e-9)
        assertEquals(ScalperState.VERSION, s.version)

        val strategies = ScalperResults.byStrategy(s)
        assertEquals("only strategies with a closed scalp, best first", listOf("Dip-hunter · buy now", "ML scalper · resting"), strategies.map { it.label })
        assertEquals(2, strategies[1].stats.closed)
        val coins = ScalperResults.byCoin(s)
        assertEquals(listOf("Bitcoin"), coins.map { it.label })
        assertEquals(h.netUsd, coins[0].stats.pnlUsd, 1e-9)
        val hours = ScalperResults.byHour(s)
        assertEquals(listOf("12 AM", "1 AM"), hours.map { it.label })
        assertEquals(2, hours[0].stats.closed)
        assertEquals(-2 * dipFee, hours[1].stats.pnlUsd, 1e-9)
        assertEquals("2 scalps · 50% won · −$0.29 each", ScalperResults.rowDetail(strategies[1].stats))
    }

    @Test
    fun bankrollCurveKeepsOnePointPerBucketAndThinsItself() {
        val ledger = ScalperLedger(hourOf = { 0 })
        play(ledger)
        val s = ledger.snapshot()
        // Scalps 1 and 2 close in the first minute (one point: the total after both), scalp 3 an hour later.
        assertEquals(2, s.curve.size)
        assertEquals(0.10 - (0.50 + KalshiFee.total(10, 0.45)), s.curve[0].pnlUsd, 1e-9)
        assertEquals(s.stats(ScalperState.ALL).pnlUsd, s.curve.last().pnlUsd, 1e-9)
        val line = ScalperResults.bankroll(s)
        assertEquals("starts at the paper bankroll", ScalperResults.START_BANKROLL_USD, line.first().pnlUsd, 1e-9)
        assertEquals(0L, line.first().tMs)
        assertEquals(ScalperResults.START_BANKROLL_USD + s.stats(ScalperState.ALL).pnlUsd, line.last().pnlUsd, 1e-9)
        assertTrue(ScalperResults.bankroll(ScalperState.fresh()).isEmpty())

        // Far more buckets than the cap: the curve halves its detail, keeps its end and stays bounded.
        val big = ScalperLedger(hourOf = { 0 })
        val e = ScalperEngine()
        var total = 0.0
        for (i in 0 until 2_000) {
            val at = i * 60_000L
            big.apply(e.buyNow(ScalpStrategy.DIP_HUNTER, t, "YES", 0.50, noQueue, at))
            big.apply(e.onTrade(t, true, 0.53, 1.0, at + 1_000L, noQueue))
            total += 0.20 - KalshiFee.total(10, 0.50)
        }
        val c = big.snapshot()
        assertTrue("bounded: ${c.curve.size}", c.curve.size in 300..ScalperLedger.MAX_CURVE)
        assertTrue(c.curveBucketMs > ScalperLedger.CURVE_BUCKET_MS)
        assertEquals(total, c.curve.last().pnlUsd, 1e-6)
        assertTrue("time order kept", c.curve.zipWithNext().all { (a, b) -> a.tMs < b.tMs })
    }

    @Test
    fun everyOrderIsWrittenToDiskAndReadBackNewestFirst() {
        val dir = tmp.newFolder("scalper")
        val log = ScalpTradeLog(dir, run = 77L, io = direct)
        var now = 0L
        val ledger = ScalperLedger(nowMs = { now }, hourOf = { 0 }, run = log.run, record = { a, b -> log.add(a, b) })
        play(ledger) { now = it }
        assertEquals("queued rows are listed before they reach the disk", 3, log.recent(10).size)
        assertTrue(log.days().isEmpty())
        log.flush()
        assertEquals(listOf("1970-01-01"), log.days())

        val rows = log.recent(10)
        assertEquals(3, rows.size)
        assertEquals("newest first", listOf("DIP_HUNTER", "ML_REST", "ML_REST"), rows.map { it.strategy })
        val win = rows[2]
        assertEquals(77L, win.run)
        assertEquals(0.50, win.entry, 1e-9)
        assertEquals(0.51, win.exit, 1e-9)
        assertEquals("TARGET", win.kind)
        assertEquals(0.10, win.pnlUsd, 1e-9)
        assertEquals(1_000L, win.filledAtMs)
        assertEquals(2_000L, win.closedAtMs)
        assertTrue(win.queueKnown)
        assertEquals(50.0, win.queueAhead, 1e-9)
        assertEquals(0.40, win.predictionCents, 1e-9)
        val dip = rows[0]
        assertEquals("NO", dip.side)
        assertEquals(2 * KalshiFee.total(10, 0.40), dip.feesUsd, 1e-9)
        assertEquals(-dip.feesUsd, dip.pnlUsd, 1e-9)
        assertEquals("the page after the first row", listOf(rows[1], rows[2]), log.recent(5, offset = 1))
        assertEquals(1, log.recent(1).size)

        // Trade file: header + 3 rows. Order file: header + 4 orders, the last one unfilled.
        val tradeLines = File(dir, "scalps_1970-01-01.csv").readLines()
        assertEquals(ScalpTrade.HEADER, tradeLines[0])
        assertEquals(4, tradeLines.size)
        val orderLines = GZIPInputStream(File(dir, "scalp_orders_1970-01-01.csv.gz").inputStream()).bufferedReader().readLines()
        assertEquals(ScalpTradeLog.ORDER_HEADER, orderLines[0])
        assertEquals(5, orderLines.size)
        val cols = ScalpTradeLog.ORDER_HEADER.split(',')
        val first = orderLines[1].split(',')
        assertEquals(cols.size, first.size)
        assertEquals("what the model saw is saved with the order", "0.505;0.01;", first[cols.indexOf("features")])
        assertEquals("800", first[cols.indexOf("ask_qty")])
        assertEquals("-0.02", first[cols.indexOf("move30")])
        val unfilled = orderLines[4].split(',')
        assertEquals(ScalpTradeLog.UNFILLED, unfilled[cols.indexOf("kind")])
        assertEquals("DIP_HUNTER_REST", unfilled[cols.indexOf("strategy")])
        assertEquals("", unfilled[cols.indexOf("exit")])
        assertEquals("3730000", unfilled[cols.indexOf("resolved_ms")])

        // A second flush appends (a second gzip member), it does not rewrite.
        ledger.apply(ScalperEngine().buyNow(ScalpStrategy.MOMENTUM_SNIPER, t, "YES", 0.30, noQueue, 3_800_000L))
        log.flush()
        assertEquals(3, log.recent(10).size)

        // Export holds every file; reset archives instead of deleting.
        val zipped = ByteArrayOutputStream()
        assertEquals(2, log.zip(zipped))
        val names = ArrayList<String>()
        ZipInputStream(zipped.toByteArray().inputStream()).use { z -> while (true) names += (z.nextEntry ?: break).name }
        assertEquals(listOf("scalper/scalp_orders_1970-01-01.csv.gz", "scalper/scalps_1970-01-01.csv"), names)
        log.archive(nowMs = 123L)
        assertTrue("the list starts again", log.recent(10).isEmpty())
        assertTrue(File(dir, "archive/123/scalps_1970-01-01.csv").exists())
        assertEquals("archived files are still exported", 2, log.zip(ByteArrayOutputStream()))
        assertTrue(log.totalBytes() > 0)
    }

    @Test
    fun tradeRowsSurviveTheFileAndBadLinesAreSkipped() {
        val row = ScalpTrade(
            closedAtMs = 1_791_400_000_123L, run = 5L, id = 9L, strategy = "ML_REST", ticker = t, side = "NO",
            contracts = 10, entry = 0.905, exit = 0.0, kind = "SETTLED", entryFeeUsd = 0.0, exitFeeUsd = 0.0,
            pnlUsd = -9.05, postedAtMs = 1L, filledAtMs = 2L, queueAhead = 3_500.0, queueKnown = false,
            predictionCents = -0.0343, expectedCents = -1.9
        )
        assertEquals(row, ScalpTrade.parse(row.toCsv()))
        assertNull(ScalpTrade.parse(ScalpTrade.HEADER))
        assertNull(ScalpTrade.parse("1,2,3"))
        assertNull(ScalpTrade.parse(""))
        assertTrue("plain decimals, no exponent", row.toCsv().endsWith(",3500,0,-0.0343,-1.9"))
        assertFalse(ScalpTrade.num(1e-7).contains("E"))
    }

    @Test
    fun copyForTheScreen() {
        assertEquals("BTC", ScalperState.coinOf("KXBTC15M-26OCT091315-15"))
        assertEquals("ETH", ScalperState.coinOf("kxeth15m-26OCT091315-15"))
        assertEquals("Bitcoin", ScalperResults.coinName("BTC"))
        assertEquals("12 AM", ScalperResults.hourLabel(0))
        assertEquals("12 PM", ScalperResults.hourLabel(12))
        assertEquals("1 PM", ScalperResults.hourLabel(13))
        assertEquals("11 PM", ScalperResults.hourLabel(23))
        assertEquals("54¢", ScalperResults.cents(0.54))
        assertEquals("90.5¢", ScalperResults.cents(0.905))
        assertEquals("$1,012.34", ScalperResults.plainMoney(1012.34))
        assertEquals("+$0.10", ScalperResults.money(0.10))
        assertEquals("–", ScalperResults.pct(null))
        val utc = ZoneId.of("UTC")
        val win = ScalpTrade(2_000L, 1L, 1L, "ML_REST", t, "YES", 10, 0.50, 0.51, "TARGET", 0.0, 0.0, 0.10, 0L, 1_000L, 50.0, true, 0.4, 0.28)
        assertEquals("ML scalper · resting · UP", ScalperResults.tradeTitle(win))
        assertEquals("Jan 1 12:00:02 AM · 10 × 50¢ → 51¢", ScalperResults.tradePrices(win, utc))
        assertEquals("target hit · held 1 s · fees $0.00", ScalperResults.tradeDetail(win))
        val settled = win.copy(side = "NO", exit = 1.0, kind = "SETTLED", closedAtMs = 601_000L, entryFeeUsd = 0.18, strategy = "DIP_HUNTER")
        assertEquals("Dip-hunter · buy now · DOWN", ScalperResults.tradeTitle(settled))
        assertEquals("Jan 1 12:10:01 AM · 10 × 50¢ → $1.00", ScalperResults.tradePrices(settled, utc))
        assertEquals("held to the close · held 10 min · fees $0.18", ScalperResults.tradeDetail(settled))
        assertEquals("won $0.10 · lost $0.00", ScalperResults.wonLost(ScalpStats(wonUsd = 0.10)))
        assertNotNull(ScalperResults.headline(ScalperState.fresh()))
        assertEquals(0, ScalperResults.headline(ScalperState.fresh()).closed)
        assertNull(ScalperResults.headline(ScalperState.fresh()).avgWinUsd)
    }
}
