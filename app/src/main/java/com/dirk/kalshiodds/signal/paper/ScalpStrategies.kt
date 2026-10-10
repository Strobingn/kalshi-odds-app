package com.dirk.kalshiodds.signal.paper

import com.dirk.kalshiodds.domain.KalshiPrice
import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.domain.MarketLifecycle
import com.dirk.kalshiodds.signal.config.SignalConstants

/**
 * Scalping strategies for the 15-minute Bitcoin windows. Each strategy is
 * paper-only, tagged on every fill, and scored on its own ledger so the
 * home scorecard can rank strategies by realized paper P&L.
 *
 * Strategies:
 * 1. [Kind.MID_WINDOW_REVERSAL]   — fade an early-window overreaction.
 * 2. [Kind.T_MINUS_EXIT]           — buy the dip, always exit by T\u22123 minutes.
 * 3. [Kind.BOOK_IMBALANCE]         — ask-side thinning with a static bid.
 * 4. [Kind.ROLLOVER_GAP]           — first 30s of a window reprices stale info.
 * 5. [Kind.LEAD_LAG]               — Kalshi lags Binance/Coinbase spot ticks.
 * 6. [Kind.AI_EDGE]                — the always-on edge-sized AI fill (baseline).
 */
object ScalpStrategies {

    enum class Kind {
        MID_WINDOW_REVERSAL,
        T_MINUS_EXIT,
        BOOK_IMBALANCE,
        ROLLOVER_GAP,
        LEAD_LAG,
        AI_EDGE
    }

    data class Signal(
        val kind: Kind,
        val ticker: String,
        val side: String,
        val ask: Double,
        /** Strategy-owned exit rule, rendered on the fill note. */
        val exitNote: String,
        val reason: String
    ) {
        val source: String get() = "scalp ${kind.name.lowercase()}"
    }

    /** Latest spot tick per ticker (Binance/Coinbase print). */
    private val lastSpot = HashMap<String, Double>()
    private val lastSpotMs = HashMap<String, Long>()

    fun noteSpot(ticker: String, spotUsd: Double?, nowMs: Long = System.currentTimeMillis()) {
        if (spotUsd != null && spotUsd > 0.0) {
            lastSpot[ticker] = spotUsd
            lastSpotMs[ticker] = nowMs
        }
    }

    fun windowAgeMs(market: MarketUiModel, nowMs: Long): Long? {
        val open = market.openTimeEpochMs ?: return null
        return (nowMs - open).coerceAtLeast(0L)
    }

    fun msToClose(market: MarketUiModel, nowMs: Long): Long? {
        val close = market.closeTimeEpochMs ?: return null
        return (close - nowMs).coerceAtLeast(0L)
    }

    /**
     * Evaluate every strategy against one market snapshot. Returns at most one
     * signal per strategy; the caller decides dedupe (one open fill per
     * ticker per strategy).
     */
    fun evaluate(market: MarketUiModel, nowMs: Long): List<Signal> {
        val out = ArrayList<Signal>()
        val ask = KalshiPrice.usable(market.yesAsk ?: market.lastPrice) ?: return out
        val noAsk = KalshiPrice.usable(market.noAsk)
        val age = windowAgeMs(market, nowMs) ?: return out
        val left = msToClose(market, nowMs) ?: return out
        // Binary zone: under MarketLifecycle.NO_AUTO_BET_MS a dip has no time
        // to swing back and the losing side collapses to the 1¢ tick —
        // no scalp fires.
        if (left < com.dirk.kalshiodds.domain.MarketLifecycle.NO_AUTO_BET_MS) return out

        // 1. Mid-window reversal: early-window overreaction on the YES leg.
        // A big early move in the first 3-5 minutes that stretched the ask far
        // from the AI's fair value is faded back the other way.
        val ai = market.aiYesPercent?.div(100.0)
        if (age in 60_000..300_000 && ai != null) {
            if (ask > ai + REVERSAL_STRETCH) {
                out += Signal(
                    Kind.MID_WINDOW_REVERSAL, market.ticker, "NO",
                    noAsk ?: (1.0 - ask),
                    "exit on first 10-15\u00a2 recovery",
                    "ask ${pct(ask)} stretched above AI ${pct(ai)} early in window"
                )
            } else if (ask < ai - REVERSAL_STRETCH) {
                out += Signal(
                    Kind.MID_WINDOW_REVERSAL, market.ticker, "YES", ask,
                    "exit on first 10-15\u00a2 recovery",
                    "ask ${pct(ask)} stretched below AI ${pct(ai)} early in window"
                )
            }
        }

        // 2. T-minus exit: same dip-buy, but the exit is the clock. Only fires
        // with enough time left to scalp it back before the binary zone.
        if (left in T_MINUS_EXIT_MIN_LEFT_MS..WINDOW_MS) {
            val dip = ai != null && ask < ai - DIP_MARGIN && ask <= DIP_MAX_ASK
            if (dip) {
                out += Signal(
                    Kind.T_MINUS_EXIT, market.ticker, "YES", ask,
                    "hard exit at T\u22123:00 (time stop, never hold into the binary zone)",
                    "dip ${pct(ask)} vs AI ${pct(ai!!)} with ${left / 1000}s left"
                )
            }
        }

        // 3. Book imbalance: visible ask depth thinned while the bid held.
        val askSize = market.yesAskSize
        val spread = market.spreadDollars
        if (askSize != null && spread != null && askSize in 1.0..IMBALANCE_MAX_ASK_SIZE && spread <= IMBALANCE_MAX_SPREAD) {
            out += Signal(
                Kind.BOOK_IMBALANCE, market.ticker, "YES", ask,
                "exit on the first 2-4\u00a2 move or T\u22123:00",
                "ask side thinned to $askSize ct with a ${pct(spread)} spread"
            )
        }

        // 4. Rollover gap: the first 30s of a fresh window prices off stale
        // spot. If the last spot print is materially above the strike, the UP
        // contract is briefly cheap (and vice versa).
        if (age <= ROLLOVER_GAP_MS) {
            val spot = lastSpot[market.ticker]
            val strike = market.floorStrike
            if (spot != null && strike != null) {
                if (spot > strike + rolloverGapUsd(strike) && ask <= 0.60) {
                    out += Signal(
                        Kind.ROLLOVER_GAP, market.ticker, "YES", ask,
                        "exit as soon as the book reprices (seconds)",
                        "spot $spot above strike $strike in the first 30s"
                    )
                } else if (spot < strike - rolloverGapUsd(strike) && noAsk != null && noAsk <= 0.60) {
                    out += Signal(
                        Kind.ROLLOVER_GAP, market.ticker, "NO", noAsk,
                        "exit as soon as the book reprices (seconds)",
                        "spot $spot below strike $strike in the first 30s"
                    )
                }
            }
        }

        // 5. Lead-lag: a large fresh Binance/Coinbase tick that Kalshi's book
        // has not yet reflected in the ask.
        val spot = lastSpot[market.ticker]
        val spotMs = lastSpotMs[market.ticker] ?: 0L
        val strike = market.floorStrike
        if (spot != null && strike != null && nowMs - spotMs <= SignalConstants.LEAD_WINDOW_MS * 2) {
            val gapPp = (spot - strike) / strike * 100.0
            if (gapPp >= LEAD_LAG_MIN_GAP_PP && ask <= 0.70) {
                out += Signal(
                    Kind.LEAD_LAG, market.ticker, "YES", ask,
                    "exit within 2s of the book catching up",
                    "fresh spot tick $spot is ${String.format(java.util.Locale.US, "%.3f", gapPp)}% over strike"
                )
            } else if (gapPp <= -LEAD_LAG_MIN_GAP_PP && noAsk != null && noAsk <= 0.70) {
                out += Signal(
                    Kind.LEAD_LAG, market.ticker, "NO", noAsk,
                    "exit within 2s of the book catching up",
                    "fresh spot tick $spot is ${String.format(java.util.Locale.US, "%.3f", gapPp)}% under strike"
                )
            }
        }

        return out
    }

    /** Per-strategy realized P&L, best first. */
    fun leaderboard(fills: List<PaperFill>): List<Pair<Kind, Double>> {
        return fills.filter { it.settled }
            .groupBy { strategyOf(it.source) }
            .mapNotNull { (kind, rows) ->
                kind ?: return@mapNotNull null
                kind to rows.mapNotNull { it.pnlUsd }.sum()
            }
            .sortedByDescending { it.second }
    }

    fun strategyOf(source: String): Kind? =
        Kind.entries.firstOrNull { it.name.lowercase() == source.removePrefix("scalp ").trim() }

    private fun pct(d: Double): String =
        String.format(java.util.Locale.US, "%.0f\u00a2", d * 100.0)

    private fun rolloverGapUsd(strike: Double): Double = strike * ROLLOVER_GAP_PCT / 100.0

    const val WINDOW_MS = MarketLifecycle.WINDOW_MS
    const val REVERSAL_STRETCH = 0.12
    const val DIP_MARGIN = 0.05
    const val DIP_MAX_ASK = 0.40
    const val T_MINUS_EXIT_MIN_LEFT_MS = 240_000L
    const val IMBALANCE_MAX_ASK_SIZE = 25.0
    const val IMBALANCE_MAX_SPREAD = 0.04
    const val ROLLOVER_GAP_MS = 30_000L
    const val ROLLOVER_GAP_PCT = 0.05
    const val LEAD_LAG_MIN_GAP_PP = 0.10
}
