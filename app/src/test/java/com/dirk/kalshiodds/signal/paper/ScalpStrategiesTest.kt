package com.dirk.kalshiodds.signal.paper

import com.dirk.kalshiodds.domain.MarketUiModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScalpStrategiesTest {

    private val open = 1_700_000_000_000L
    private val close = open + 900_000L

    private fun market(
        yesAsk: Double = 0.50,
        aiYes: Double? = 0.50,
        askSize: Double? = null,
        spread: Double? = 0.02,
        strike: Double? = 67_000.0
    ) = MarketUiModel(
        ticker = "KXBTC15M-T",
        title = "BTC",
        subtitle = null,
        floorStrike = strike,
        yesBid = yesAsk - 0.01,
        yesAsk = yesAsk,
        noBid = 1.0 - yesAsk - 0.01,
        noAsk = 1.0 - yesAsk,
        lastPrice = yesAsk,
        yesProbabilityPercent = yesAsk * 100.0,
        noProbabilityPercent = (1.0 - yesAsk) * 100.0,
        aiYesPercent = aiYes?.times(100.0),
        aiNoPercent = aiYes?.let { (100.0 - it * 100.0) },
        yesAskSize = askSize,
        spreadDollars = spread,
        volume = 5_000.0,
        volume24h = 5_000.0,
        openInterest = 500.0,
        liquidityDollars = 5_000.0,
        closeTimeLocal = null,
        closeTimeEpochMs = close,
        openTimeEpochMs = open,
        status = "open",
        seriesLabel = "Bitcoin"
    )

    @Test
    fun midWindowReversalFadesEarlyOverreaction() {
        // 3 minutes into the window, ask stretched 20c above the AI.
        val now = open + 180_000L
        val signals = ScalpStrategies.evaluate(market(yesAsk = 0.60, aiYes = 0.40), now)
        val reversal = signals.firstOrNull { it.kind == ScalpStrategies.Kind.MID_WINDOW_REVERSAL }
        assertNotNull(reversal)
        assertEquals("NO", reversal!!.side)
        assertTrue(reversal.reason.contains("stretched above"))
    }

    @Test
    fun midWindowReversalIgnoresLateWindows() {
        // 8 minutes in: no reversal scalp.
        val now = open + 480_000L
        val signals = ScalpStrategies.evaluate(market(yesAsk = 0.60, aiYes = 0.40), now)
        assertNull(signals.firstOrNull { it.kind == ScalpStrategies.Kind.MID_WINDOW_REVERSAL })
    }

    @Test
    fun tMinusExitBuysDeepDipsWithTimeLeft() {
        // 4 minutes left, ask 15c under the AI.
        val now = close - 240_000L
        val signals = ScalpStrategies.evaluate(market(yesAsk = 0.25, aiYes = 0.40), now)
        val exit = signals.firstOrNull { it.kind == ScalpStrategies.Kind.T_MINUS_EXIT }
        assertNotNull(exit)
        assertEquals("YES", exit!!.side)
        assertTrue(exit.exitNote.contains("T\u22123:00"))
    }

    @Test
    fun tMinusExitSkipsDipsTooCloseToClose() {
        // 2 minutes left: inside the binary zone, no trade.
        val now = close - 120_000L
        val signals = ScalpStrategies.evaluate(market(yesAsk = 0.25, aiYes = 0.40), now)
        assertNull(signals.firstOrNull { it.kind == ScalpStrategies.Kind.T_MINUS_EXIT })
    }

    @Test
    fun bookImbalanceFiresOnThinAskWithStaticBid() {
        val now = open + 300_000L
        val signals = ScalpStrategies.evaluate(market(yesAsk = 0.50, askSize = 10.0, spread = 0.02), now)
        assertNotNull(signals.firstOrNull { it.kind == ScalpStrategies.Kind.BOOK_IMBALANCE })
    }

    @Test
    fun bookImbalanceSkipsDeepAskBooks() {
        val now = open + 300_000L
        val signals = ScalpStrategies.evaluate(market(yesAsk = 0.50, askSize = 500.0, spread = 0.02), now)
        assertNull(signals.firstOrNull { it.kind == ScalpStrategies.Kind.BOOK_IMBALANCE })
    }

    @Test
    fun rolloverGapFadesStaleSpotInFirstThirtySeconds() {
        ScalpStrategies.noteSpot("KXBTC15M-T", 67_080.0, open + 10_000L)
        val signals = ScalpStrategies.evaluate(market(yesAsk = 0.45, strike = 67_000.0), open + 20_000L)
        val gap = signals.firstOrNull { it.kind == ScalpStrategies.Kind.ROLLOVER_GAP }
        assertNotNull(gap)
        assertEquals("YES", gap!!.side)
    }

    @Test
    fun leadLagFiresOnFreshSpotTickOverStrike() {
        ScalpStrategies.noteSpot("KXBTC15M-T", 67_100.0, open + 400_000L)
        val now = open + 401_000L
        val signals = ScalpStrategies.evaluate(market(yesAsk = 0.55, strike = 67_000.0), now)
        val lag = signals.firstOrNull { it.kind == ScalpStrategies.Kind.LEAD_LAG }
        assertNotNull(lag)
        assertEquals("YES", lag!!.side)
    }

    @Test
    fun leaderboardRanksStrategiesBySettledPnl() {
        val fills = listOf(
            fill("scalp lead lag", pnl = 30.0),
            fill("scalp lead lag", pnl = 10.0),
            fill("scalp t minus exit", pnl = -5.0),
            fill("AI hunter", pnl = 50.0),
            fill("scalp book imbalance", pnl = null, settled = false)
        )
        val board = ScalpStrategies.leaderboard(fills)
        assertEquals(3, board.size)
        assertEquals(ScalpStrategies.Kind.LEAD_LAG, board[0].first)
        assertEquals(40.0, board[0].second, 1e-9)
        assertEquals(ScalpStrategies.Kind.T_MINUS_EXIT, board[2].first)
    }

    @Test
    fun noScalpsFireInsideTheBinaryZone() {
        // 2 minutes left: even a 1¢ collapsed ask with a model edge must not fire.
        val now = close - 120_000L
        val signals = ScalpStrategies.evaluate(market(yesAsk = 0.01, aiYes = 0.30), now)
        assertTrue(signals.isEmpty())
    }

    @Test
    fun noAutomaticTicketInsideTheBinaryZoneOnOneCentCollapse() {
        val market = market(yesAsk = 0.01, aiYes = 0.30)
        val ctx = com.dirk.kalshiodds.signal.trade.TicketBuilder.Context(
            settings = com.dirk.kalshiodds.signal.config.SignalSettings(),
            alertsPaused = false,
            nowMs = close - 120_000L
        )
        assertNull(com.dirk.kalshiodds.signal.trade.TicketBuilder.propose(market, ctx))
        assertNull(com.dirk.kalshiodds.signal.trade.TicketBuilder.proposeHunter(market, ctx))
        assertNull(com.dirk.kalshiodds.signal.trade.TicketBuilder.proposeHunterValue(market, ctx))
    }

    @Test
    fun considerScalpDedupesPerTickerAndStrategy() {
        val ids = java.util.concurrent.atomic.AtomicInteger()
        val book = PaperBook(idFactory = { "s${ids.incrementAndGet()}" }, nowMs = { 5L })
        val signal = ScalpStrategies.Signal(
            kind = ScalpStrategies.Kind.LEAD_LAG,
            ticker = "KXBTC15M-T",
            side = "YES",
            ask = 0.55,
            exitNote = "exit within 2s",
            reason = "spot tick over strike"
        )
        val first = book.considerScalp(signal, enabled = true)
        assertNotNull(first)
        // Same strategy + ticker: deduped.
        assertNull(book.considerScalp(signal, enabled = true))
        // Different strategy on the same ticker: allowed.
        val other = book.considerScalp(
            signal.copy(kind = ScalpStrategies.Kind.BOOK_IMBALANCE, reason = "thin ask"),
            enabled = true
        )
        assertNotNull(other)
        assertEquals(2, book.snapshot().fills.count { !it.settled })
        assertTrue(first!!.source == "scalp lead lag")
    }

    private fun fill(source: String, pnl: Double?, settled: Boolean = true) =
        PaperFill(
            id = "$source-$pnl",
            ticker = "KXBTC15M-T",
            side = "YES",
            stakeUsd = 2.0,
            contracts = 4,
            limitPrice = 0.50,
            source = source,
            createdAtMs = 1L,
            settled = settled,
            won = pnl != null && pnl > 0,
            pnlUsd = pnl,
            note = "test"
        )
}
