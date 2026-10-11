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
 * Strategies (15 total):
 *  1. MID_WINDOW_REVERSAL  — fade an early-window overreaction.
 *  2. T_MINUS_EXIT         — buy the dip, always exit by T−3 minutes.
 *  3. BOOK_IMBALANCE       — ask-side thinning with a static bid.
 *  4. ROLLOVER_GAP         — first 30s of a window reprices stale info.
 *  5. LEAD_LAG             — Kalshi lags Binance/Coinbase spot ticks.
 *  6. AI_EDGE              — the always-on edge-sized AI fill (baseline).
 *  7. SPREAD_SQUEEZE       — tight spread + last trade at the ask.
 *  8. VOLUME_SURGE         — 24h volume surge flags fast money entering.
 *  9. OI_FLIP              — open interest above liquidity = positioning.
 * 10. CONFIDENCE_PRESS     — the AI's confidence spikes with an edge.
 * 11. SEQUENCE_MOMENTUM    — repeated same-side AI prints early in the window.
 * 12. CROSS_WINDOW_DRIFT   — prior window's settle direction carries over.
 * 13. BID_WALL            — a heavy bid holds while the ask softens.
 * 14. CALM_BEFORE_CLOSE    — mid-window quiet, stable ask, model edge.
 * 15. SETTLE_LOTTO        — tiny ask late (outside the binary zone).
 object ScalpStrategies {

    enum class Kind {
        MID_WINDOW_REVERSAL,
        T_MINUS_EXIT,
        BOOK_IMBALANCE,
        ROLLOVER_GAP,
        LEAD_LAG,
        AI_EDGE,
        SPREAD_SQUEEZE,
        VOLUME_SURGE,
        OI_FLIP,
        CONFIDENCE_PRESS,
        SEQUENCE_MOMENTUM,
        CROSS_WINDOW_DRIFT,
        BID_WALL,
        CALM_BEFORE_CLOSE,
        SETTLE_LOTTO
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

        val yesBid = KalshiPrice.usable(market.yesBid)
        val last = KalshiPrice.usable(market.lastPrice)
        val aiConf = market.aiConfidence
        val spotNow = lastSpot[market.ticker]
        val strikeNow = market.floorStrike

        // 7. Spread squeeze: tight book with the last trade printing at the
        // ask side — buyers lifting offers in a compressed spread.
        if (spread != null && spread <= SPREAD_SQUEEZE_MAX && last != null) {
            if (last >= ask - 0.01) {
                out += Signal(
                    Kind.SPREAD_SQUEEZE, market.ticker, "YES", ask,
                    "exit when the spread widens past ${pct(SPREAD_SQUEEZE_MAX)}",
                    "last ${pct(last)} at the ask in a ${pct(spread)} spread"
                )
            } else if (noAsk != null && yesBid != null && last <= yesBid + 0.01) {
                out += Signal(
                    Kind.SPREAD_SQUEEZE, market.ticker, "NO", noAsk,
                    "exit when the spread widens past ${pct(SPREAD_SQUEEZE_MAX)}",
                    "last ${pct(last)} at the bid in a ${pct(spread)} spread"
                )
            }
        }

        // 8. Volume surge: 24h volume far above current-window volume flags
        // fast money positioned on this series this window.
        val vol = market.volume
        val vol24 = market.volume24h
        if (vol != null && vol24 != null && vol > 0.0 && ai != null) {
            if (vol24 >= vol * VOLUME_SURGE_RATIO && ai > ask + CONF_EDGE_MARGIN) {
                out += Signal(
                    Kind.VOLUME_SURGE, market.ticker, "YES", ask,
                    "exit on the first 2-4¢ move or T−3:00",
                    "24h volume ${pct(vol24 / (vol * VOLUME_SURGE_RATIO))}× window volume with the AI above"
                )
            }
        }

        // 9. OI flip: open interest above dollar liquidity — heavy
        // positioning — taken with the AI's direction.
        val oi = market.openInterest
        val liq = market.liquidityDollars
        if (oi != null && liq != null && liq > 0.0 && ai != null) {
            if (oi >= liq * OI_FLIP_RATIO) {
                if (ai > ask + CONF_EDGE_MARGIN) {
                    out += Signal(
                        Kind.OI_FLIP, market.ticker, "YES", ask,
                        "exit on the first 3-5¢ move or T−3:00",
                        "open interest ${pct(oi / (liq * OI_FLIP_RATIO))}× liquidity with the AI above"
                    )
                } else if (noAsk != null && (1.0 - ai) > noAsk + CONF_EDGE_MARGIN) {
                    out += Signal(
                        Kind.OI_FLIP, market.ticker, "NO", noAsk,
                        "exit on the first 3-5¢ move or T−3:00",
                        "open interest ${pct(oi / (liq * OI_FLIP_RATIO))}× liquidity with the AI below"
                    )
                }
            }
        }

        // 10. Confidence press: the AI's own confidence spikes while it also
        // holds a price edge — press the same direction it is pressing.
        if (aiConf != null && ai != null && aiConf >= CONFIDENCE_MIN) {
            if (ai > ask + CONF_EDGE_MARGIN) {
                out += Signal(
                    Kind.CONFIDENCE_PRESS, market.ticker, "YES", ask,
                    "exit when confidence fades or T−3:00",
                    "AI confidence ${pct(aiConf)} with a ${pct(ai - ask)} edge"
                )
            } else if (noAsk != null && (1.0 - ai) > noAsk + CONF_EDGE_MARGIN) {
                out += Signal(
                    Kind.CONFIDENCE_PRESS, market.ticker, "NO", noAsk,
                    "exit when confidence fades or T−3:00",
                    "AI confidence ${pct(aiConf)} against with a ${pct(1.0 - ai - noAsk)} edge"
                )
            }
        }

        // 11. Sequence momentum: repeated same-side AI prints early in the
        // window — the model keeps landing on the same side.
        if (ai != null && age in 60_000..360_000 && aiConf != null && aiConf >= SEQUENCE_MIN_CONF) {
            if (ai > ask + CONF_EDGE_MARGIN) {
                out += Signal(
                    Kind.SEQUENCE_MOMENTUM, market.ticker, "YES", ask,
                    "exit on the first opposite AI print or T−3:00",
                    "AI holding above the ask through the early window"
                )
            } else if (noAsk != null && (1.0 - ai) > noAsk + CONF_EDGE_MARGIN) {
                out += Signal(
                    Kind.SEQUENCE_MOMENTUM, market.ticker, "NO", noAsk,
                    "exit on the first opposite AI print or T−3:00",
                    "AI holding below through the early window"
                )
            }
        }

        // 12. Cross-window drift: the prior window settled the same direction
        // the spot is drifting now — carry trades into the fresh window.
        if (age <= CROSS_WINDOW_MS && spotNow != null && strikeNow != null && ask <= 0.70) {
            val driftPp = (spotNow - strikeNow) / strikeNow * 100.0
            if (driftPp >= CROSS_WINDOW_MIN_DRIFT_PP && lastSettleUp) {
                out += Signal(
                    Kind.CROSS_WINDOW_DRIFT, market.ticker, "YES", ask,
                    "exit when the drift stalls or T−3:00",
                    "prior window settled UP and spot is still ${String.format(java.util.Locale.US, "%.3f", driftPp)}% over"
                )
            } else if (driftPp <= -CROSS_WINDOW_MIN_DRIFT_PP && !lastSettleUp && noAsk != null && noAsk <= 0.70) {
                out += Signal(
                    Kind.CROSS_WINDOW_DRIFT, market.ticker, "NO", noAsk,
                    "exit when the drift stalls or T−3:00",
                    "prior window settled DOWN and spot is still ${String.format(java.util.Locale.US, "%.3f", driftPp)}% under"
                )
            }
        }

        // 13. Bid wall: a heavy bid holds the floor while the ask softens —
        // a buyer absorbing every dip.
        if (yesBid != null && askSize != null && yesBid >= BID_WALL_MIN_BID && askSize <= BID_WALL_MAX_ASK_SIZE) {
            out += Signal(
                Kind.BID_WALL, market.ticker, "YES", ask,
                "exit when the wall lifts or T−3:00",
                "bid holding ${pct(yesBid)} with the ask thinned to $askSize ct"
            )
        }

        // 14. Calm before close: mid-window quiet, tight book, a stable ask,
        // and the AI holding a clear edge — the classic settle-quality entry.
        if (age in CALM_MIN_AGE_MS..CALM_MAX_AGE_MS && spread != null && spread <= CALM_MAX_SPREAD && ai != null) {
            if (ai > ask + CALM_MIN_EDGE) {
                out += Signal(
                    Kind.CALM_BEFORE_CLOSE, market.ticker, "YES", ask,
                    "exit at T−3:00 — never into the binary zone",
                    "calm ${pct(spread)} spread with a ${pct(ai - ask)} edge mid-window"
                )
            } else if (noAsk != null && (1.0 - ai) > noAsk + CALM_MIN_EDGE) {
                out += Signal(
                    Kind.CALM_BEFORE_CLOSE, market.ticker, "NO", noAsk,
                    "exit at T−3:00 — never into the binary zone",
                    "calm ${pct(spread)} spread with a ${pct(1.0 - ai - noAsk)} edge mid-window"
                )
            }
        }

        // 15. Settle lotto: a tiny ask in the last safe stretch before the
        // binary zone — cheap, mostly dead, occasionally huge. Explicitly
        // capped to only fire OUTSIDE the no-auto-bet window (enforced by the
        // guard at the top of evaluate).
        if (left in NO_AUTO_BET_MS..LOTTO_MAX_LEFT_MS && ai != null) {
            if (ask <= LOTTO_MAX_ASK && ai >= ask + LOTTO_MIN_EDGE) {
                out += Signal(
                    Kind.SETTLE_LOTTO, market.ticker, "YES", ask,
                    "exit at the binary-zone boundary — hold to settle only if already winning",
                    "${pct(ask)} ask with a ${pct(ai)} model at ${left / 1000}s left"
                )
            } else if (noAsk != null && noAsk <= LOTTO_MAX_ASK && (1.0 - ai) >= noAsk + LOTTO_MIN_EDGE) {
                out += Signal(
                    Kind.SETTLE_LOTTO, market.ticker, "NO", noAsk,
                    "exit at the binary-zone boundary — hold to settle only if already winning",
                    "${pct(noAsk)} ask with a ${pct(1.0 - ai)} model at ${left / 1000}s left"
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
    const val SPREAD_SQUEEZE_MAX = 0.03
    const val VOLUME_SURGE_RATIO = 8.0
    const val OI_FLIP_RATIO = 1.0
    const val CONFIDENCE_MIN = 0.65
    const val CONF_EDGE_MARGIN = 0.03
    const val SEQUENCE_MIN_CONF = 0.55
    const val CROSS_WINDOW_MS = 120_000L
    const val CROSS_WINDOW_MIN_DRIFT_PP = 0.05
    const val BID_WALL_MIN_BID = 0.25
    const val BID_WALL_MAX_ASK_SIZE = 40.0
    const val CALM_MIN_AGE_MS = 240_000L
    const val CALM_MAX_AGE_MS = 600_000L
    const val CALM_MAX_SPREAD = 0.03
    const val CALM_MIN_EDGE = 0.08
    const val LOTTO_MAX_LEFT_MS = 420_000L
    const val LOTTO_MAX_ASK = 0.12
    const val LOTTO_MIN_EDGE = 0.10

    /** Direction of the prior settled window for the cross-window drift strategy. */
    @Volatile
    var lastSettleUp: Boolean = false

    /** Record a settlement so the drift strategy can carry it forward. */
    fun noteSettle(resultUp: Boolean) {
        lastSettleUp = resultUp
    }
}
