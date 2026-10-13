package com.dirk.kalshiodds.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.dirk.kalshiodds.signal.scalper.LiveWindow
import com.dirk.kalshiodds.signal.scalper.ScalpOrder
import com.dirk.kalshiodds.signal.scalper.ScalpStrategy
import com.dirk.kalshiodds.signal.scalper.ScalpTrade
import com.dirk.kalshiodds.signal.scalper.ScalperEngine
import com.dirk.kalshiodds.signal.scalper.ScalperLedger
import com.dirk.kalshiodds.signal.scalper.ScalperState
import com.dirk.kalshiodds.ui.theme.KalshiOddsTheme
import org.junit.Rule
import org.junit.Test
import java.util.Random

/** JVM screenshots of the scalper results screen (made-up paper scalps through the real engine and ledger). */
class ScalperResultsScreenshotTest {

    @get:Rule
    val paparazzi = Paparazzi(
        // One pixel per dp, so the report's 1,000-pixel images are full size and readable.
        deviceConfig = DeviceConfig.PIXEL_6.copy(
            softButtons = false, screenWidth = 411, screenHeight = 1000, xdpi = 160, ydpi = 160,
            density = com.android.resources.Density.MEDIUM
        ),
        theme = "android:Theme.Material3.DayNight.NoActionBar",
        maxPercentDifference = 1.0
    )

    private fun fixture(): Pair<ScalperState, List<ScalpTrade>> {
        val trades = ArrayList<ScalpTrade>()
        // 2026-10-09 13:00 UTC; the hour label uses UTC so the picture does not depend on the machine's zone.
        val t0 = 1_791_550_800_000L
        val ledger = ScalperLedger(
            hourOf = { ((it / 3_600_000L) % 24).toInt() },
            record = { a, _ -> trades += a },
            run = 1L
        )
        val e = ScalperEngine()
        val rnd = Random(11)
        val none: (String, Double) -> Double? = { _, _ -> null }
        val ticker = "KXBTC15M-26OCT091315-15"
        for (i in 0 until 160) {
            val at = t0 + i * 95_000L
            val st = ScalpStrategy.values()[rnd.nextInt(ScalpStrategy.values().size)]
            val side = if (rnd.nextBoolean()) "YES" else "NO"
            val yes = side == "YES"
            val p = (30 + rnd.nextInt(40)) / 100.0
            if (st.taker) {
                ledger.apply(e.buyNow(st, ticker, side, p, none, at))
            } else {
                ledger.apply(listOfNotNull(e.post(st, ticker, side, p, 80.0, 0.4, 0.2, at)))
                // A taker sells our side through our bid: filled.
                ledger.apply(e.onTrade(ticker, !yes, if (yes) p - 0.01 else 1.0 - (p - 0.01), 20.0, at + 2_000L, none))
            }
            if (rnd.nextDouble() < (if (st.taker) 0.42 else 0.74)) {
                val hit = p + st.targetDollars + 0.01
                ledger.apply(e.onTrade(ticker, yes, if (yes) hit else 1.0 - hit, 20.0, at + 9_000L, none))
            } else {
                val bid = p - st.stopDollars
                ledger.apply(e.onClock(ticker, at + 12_000L, if (yes) bid else null, if (yes) null else bid))
            }
        }
        // The window settles: the hold strategies (and any scalp still open) close at $1 / $0.
        ledger.apply(e.settle(ticker, "yes", t0 + 160 * 95_000L))
        return ledger.snapshot() to trades.asReversed()
    }

    @Test
    fun darkTop() = snap("scalper_results_dark_top", true, 0)

    @Test
    fun darkBreakdowns() = snap("scalper_results_dark_breakdowns", true, 3)

    @Test
    fun darkTrades() = snap("scalper_results_dark_trades", true, 6)

    @Test
    fun lightTop() = snap("scalper_results_light_top", false, 0)

    // Sections: items 0 headline, 1 section chips, 2 follow row, then the section's cards from item 3.
    @Test
    fun darkOverviewLive() = snap("arena_dark_overview_live", true, 3, ArenaSection.OVERVIEW)

    @Test
    fun darkOverviewRace() = snap("arena_dark_overview_race", true, 5, ArenaSection.OVERVIEW)

    @Test
    fun darkOverviewDrawdown() = snap("arena_dark_overview_drawdown", true, 8, ArenaSection.OVERVIEW)

    @Test
    fun darkStrategiesStatsAndPrice() = snap("arena_dark_strategies_stats_price", true, 3, ArenaSection.STRATEGIES)

    @Test
    fun darkStrategiesExitsWinsFees() = snap("arena_dark_strategies_exits_wins_fees", true, 5, ArenaSection.STRATEGIES)

    @Test
    fun darkTimingDaysHoursHold() = snap("arena_dark_timing", true, 3, ArenaSection.TIMING)

    @Test
    fun darkEdgeCalibrationAndFan() = snap("arena_dark_edge_calibration_fan", true, 3, ArenaSection.EDGE)

    @Test
    fun darkEdgeRollingAndCorrelation() = snap("arena_dark_edge_rolling_corr", true, 5, ArenaSection.EDGE)

    @Test
    fun lightEdgeCalibrationAndFan() = snap("arena_light_edge_calibration_fan", false, 3, ArenaSection.EDGE)

    @Test
    fun darkExecutionPayoffAndTwins() = snap("arena_dark_execution_payoff_twins", true, 3, ArenaSection.EXECUTION)

    @Test
    fun darkExecutionFillsAndWait() = snap("arena_dark_execution_fill_wait", true, 5, ArenaSection.EXECUTION)

    @Test
    fun darkExecutionQueueAndModel() = snap("arena_dark_execution_queue_model", true, 7, ArenaSection.EXECUTION)

    @Test
    fun lightExecutionPayoffAndTwins() = snap("arena_light_execution_payoff_twins", false, 3, ArenaSection.EXECUTION)

    // The Compare table, and one strategy's own page (items: 0 head, 1 rules, 2 numbers, 3 curve, 4 drawdown, …).
    @Test
    fun darkCompare() = snap("arena_dark_compare", true, 3, ArenaSection.COMPARE)

    @Test
    fun lightCompare() = snap("arena_light_compare", false, 3, ArenaSection.COMPARE)

    @Test
    fun darkStrategyPageTop() = snap("arena_dark_strategy_page_top", true, 0, open = ScalpStrategy.BOUNCE)

    @Test
    fun darkStrategyPageCharts() = snap("arena_dark_strategy_page_charts", true, 3, open = ScalpStrategy.BOUNCE)

    @Test
    fun lightStrategyPageHold() = snap("arena_light_strategy_page_hold", false, 0, open = ScalpStrategy.LATE_FAVORITE)

    @Test
    fun darkTradesHistogram() = snap("arena_dark_trades_histogram", true, 3, ArenaSection.TRADES)

    @Test
    fun lightOverviewLive() = snap("arena_light_overview_live", false, 3, ArenaSection.OVERVIEW)

    @Test
    fun lightStrategiesStatsAndPrice() = snap("arena_light_strategies_stats_price", false, 3, ArenaSection.STRATEGIES)

    @Test
    fun lightTimingDaysHoursHold() = snap("arena_light_timing", false, 3, ArenaSection.TIMING)

    @Test
    fun empty() {
        paparazzi.snapshot(name = "scalper_results_empty") {
            KalshiOddsTheme(darkTheme = true) {
                ScalperResultsScreen(state = ScalperState.fresh(), trades = emptyList(), onBack = {})
            }
        }
    }

    private val liveTicker = "KXBTC15M-26OCT091745-45"
    private val liveOpen = 1_791_567_000_000L

    /** A live window 9 minutes in: a drifting UP price, six closed paper trades on it and two still open. */
    private fun liveWindow(): LiveWindow {
        val mids = (0..540).map { s -> s to (0.52 + 0.08 * kotlin.math.sin(s / 70.0) + 0.02 * kotlin.math.sin(s / 9.0)) }
        fun order(st: ScalpStrategy, side: String, px: Double, at: Long) = ScalpOrder(
            id = at, strategy = st, ticker = liveTicker, side = side, price = px, contracts = 10,
            queueAhead = 0.0, queueKnown = true, postedAtMs = at, predictionCents = 0.0, expectedCents = 0.0
        )
        return LiveWindow(
            liveTicker, liveOpen, mids,
            listOf(
                ScalperEngine.OpenPosition(order(ScalpStrategy.EARLY_FAVORITE, "YES", 0.66, liveOpen + 430_000), liveOpen + 430_000),
                ScalperEngine.OpenPosition(order(ScalpStrategy.LONG_SHOT, "NO", 0.15, liveOpen + 500_000), liveOpen + 500_000)
            )
        )
    }

    private fun trade(
        st: ScalpStrategy, ticker: String, side: String, entry: Double, exit: Double, filled: Long, closed: Long, id: Long,
        kind: String = "TARGET", posted: Long = filled, queue: Double = 0.0, expected: Double = 0.0
    ) =
        ScalpTrade(
            closedAtMs = closed, run = 7L, id = id, strategy = st.name, ticker = ticker, side = side, contracts = 10,
            entry = entry, exit = exit, kind = kind, entryFeeUsd = 0.0, exitFeeUsd = 0.0,
            pnlUsd = (exit - entry) * 10, postedAtMs = posted, filledAtMs = filled, queueAhead = queue, queueKnown = true,
            predictionCents = expected, expectedCents = expected
        )

    /** Six trades on the live window plus ten days of history, so every grid has something in it. */
    private fun extraTrades(): List<ScalpTrade> {
        val out = ArrayList<ScalpTrade>()
        val w = listOf(
            Triple(ScalpStrategy.BOUNCE, "YES", 0.47 to 0.55), Triple(ScalpStrategy.DIP_HUNTER, "YES", 0.55 to 0.53),
            Triple(ScalpStrategy.MOMENTUM_SNIPER, "NO", 0.42 to 0.44), Triple(ScalpStrategy.IMPROVE_1C, "YES", 0.58 to 0.60),
            Triple(ScalpStrategy.BREAKOUT_50, "YES", 0.50 to 0.47), Triple(ScalpStrategy.EXTREME_REVERSION, "NO", 0.40 to 0.43)
        )
        w.forEachIndexed { i, (st, side, px) ->
            val filled = liveOpen + (40 + i * 70) * 1000L
            out += trade(st, liveTicker, side, px.first, px.second, filled, filled + 35_000L, 10_000L + i)
        }
        val rnd = java.util.Random(5)
        val day = 86_400_000L
        val strategies = ScalpStrategy.values()
        for (d in 1..10) for (k in 0 until 12) {
            val st = strategies[rnd.nextInt(strategies.size)]
            val entry = (12 + rnd.nextInt(80)) / 100.0
            val move = (rnd.nextInt(9) - 5) / 100.0
            val at = liveOpen - d * day + k * 3_600_000L
            out += trade(st, "KXBTC15M-D$d-$k", "YES", entry, (entry + move).coerceIn(0.0, 1.0), at - (5 + rnd.nextInt(300)) * 1000L, at, 20_000L + d * 100 + k)
        }
        return out
    }

    /**
     * 48 earlier windows with real tickers, every strategy trading each one:
     * the scalps share a window factor (so the correlation map has structure)
     * and the holds settle at $1 / $0 around their price (late favourites a
     * little better than the price, long shots worse) for the calibration.
     */
    private fun edgeTrades(): List<ScalpTrade> {
        val out = ArrayList<ScalpTrade>()
        val rnd = java.util.Random(23)
        val key = java.time.format.DateTimeFormatter.ofPattern("yyMMMddHHmm", java.util.Locale.US)
        val et = java.time.ZoneId.of("America/New_York")
        val strategies = ScalpStrategy.values()
        for (w in 0 until 48) {
            val close = liveOpen - 2 * 86_400_000L + w * 900_000L
            val ticker = "KXBTC15M-" + key.format(java.time.Instant.ofEpochMilli(close).atZone(et)).uppercase(java.util.Locale.US) + "-00"
            val open = close - 900_000L
            val factor = rnd.nextGaussian()
            strategies.forEachIndexed { i, st ->
                val filled = open + (30 + rnd.nextInt(800)) * 1000L
                if (st.hold) {
                    val entry = Math.round((st.entryMin + rnd.nextDouble() * (st.entryMax - st.entryMin)) * 100) / 100.0
                    val bias = when (st) {
                        ScalpStrategy.LATE_FAVORITE -> 0.07
                        ScalpStrategy.LONG_SHOT -> -0.08
                        else -> 0.0
                    }
                    val exit = if (rnd.nextDouble() < entry + bias) 1.0 else 0.0
                    out += trade(st, ticker, "YES", entry, exit, filled, close, 40_000L + w * 100 + i, kind = "SETTLED")
                } else {
                    val loading = if (i % 3 == 0) -1.0 else 1.0
                    // Resting bids wait and queue; the quick fills and the deep queues do worse (adverse selection).
                    val wait = if (st.taker) 0L else (rnd.nextDouble() * rnd.nextDouble() * 90_000).toLong()
                    val queue = if (st.taker) 0.0 else Math.round(rnd.nextDouble() * rnd.nextDouble() * 1_500.0).toDouble()
                    val expected = if (st == ScalpStrategy.ML_REST) rnd.nextDouble() * 3.0 - 0.3 else 0.0
                    val skew = if (st.taker) 0.0 else (if (wait < 5_000) -0.8 else 0.4) - queue / 1_000.0
                    val cents = (
                        loading * 2 * factor + rnd.nextGaussian() * 2 + (if (i % 4 == 0) 0.6 else -0.4) + skew + 0.5 * expected
                        ).toInt()
                    out += trade(
                        st, ticker, "YES", 0.5, 0.5 + cents / 100.0, filled, filled + 40_000L, 40_000L + w * 100 + i,
                        posted = filled - wait, queue = queue, expected = expected
                    )
                }
            }
        }
        return out
    }

    private fun snap(
        name: String,
        dark: Boolean,
        firstItem: Int,
        section: ArenaSection = ArenaSection.OVERVIEW,
        open: ScalpStrategy? = null
    ) {
        val (state, trades) = fixture()
        paparazzi.snapshot(name = name) {
            KalshiOddsTheme(darkTheme = dark) {
                ScalperResultsScreen(
                    state = state, trades = trades.take(40), onBack = {}, chartTrades = trades + extraTrades() + edgeTrades(),
                    listState = androidx.compose.foundation.lazy.LazyListState(firstVisibleItemIndex = firstItem),
                    live = liveWindow(),
                    initialSection = section,
                    today = java.time.LocalDate.of(2026, 10, 10),
                    initialOpen = open,
                    pageListState = androidx.compose.foundation.lazy.LazyListState(firstVisibleItemIndex = firstItem)
                )
            }
        }
    }
}
