package com.dirk.kalshiodds.decision

import com.dirk.kalshiodds.signal.trade.KalshiFee
import java.util.Locale
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 0.3.38 intra-window SCALP rule (PAPER ONLY). Deterministic and versioned; no LLM, no
 * fitted weights at runtime.
 *
 * Derivation (scalp-research/REPORT.md, 2026-10-09):
 *  - Spot-implied fair value of YES: p = Φ(z), z = ln(S/K) / (σ·√τeff), with σ per √second and
 *    τeff = τ − 40 s, because settlement averages the final 60 s of the CF index (variance of a 60 s mean ≈ 20 s).
 *  - A round trip costs spread + fee(entry) + fee(exit), with fee = ceil(0.07·C·P·(1−P)) to the cent per order.
 *    At 50¢ and 10 contracts that is 1¢ + 1.8¢ + 1.8¢ = 4.6¢ per contract.
 *  - By optional stopping, take-profit, stop-loss and trailing exits on a martingale price add nothing.
 *    The only edge is the predictable part of the move, β·gap. Estimated β ≈ 0.03 at 30 s, 0.10 at 120 s,
 *    0.15 at settlement, so the breakeven gap is ≈ cost/β (well over 10¢).
 *  - Rule: enter only when the ask is ≥ 10¢ below fair after the entry fee. Exit when selling beats holding
 *    (bid − exit fee ≥ fair). Exit when fair turns down 10¢ below entry. Time stop at τ ≤ 20 s.
 *
 * 0.3.40 (v2 rule, multi round trip + target/stop + 60 s hard exit): every coin negative out of sample, see
 * BACKTEST_LABEL and scalp-research/REPORT.md addendum. Seed params kept.
 *
 * v1 backtest, walk-forward. Train Sep 25–Oct 1: −1.11¢/contract (n=850). Untouched test Oct 2–8: −0.40¢/contract,
 * 95% window-clustered CI [−2.04, +1.22], n=1,068, win 45%. NOT PROVEN. 5,004 parameter combos were tried in
 * total; none had a positive mean in-sample.
 */
object ScalpRule {
    const val ID = "scalp"
    const val VERSION = "scalp-fairgap-v2-20261009"
    const val LEGACY_VERSION = "scalp-fairgap-v1-20261009"
    const val BACKTEST_LABEL =
        "Backtest v2 (walk-forward, held-out Oct 2–8, net after both fees): BTC −2.93¢/contract 95% CI [−3.69, −2.16] n=1,177; " +
            "ETH −2.80¢ [−4.00, −1.64] n=458; SOL −3.16¢ [−4.49, −1.70] n=258. 24 grid trials; no coin or hour held up " +
            "out of sample. Long history (Jan–Sep 2026, rolling weekly walk-forward): fair-gap −3.23¢ [−3.74, −2.68] 1/38 weeks " +
            "positive; dip-hunter −4.44¢, momentum-sniper −4.77¢, extreme-reversion −2.53¢ (all CIs below 0). " +
            "Not proven — paper only."

    const val CONTRACTS = 10
    const val MIN_GAP = 0.10
    const val TURN_DOWN = 0.10
    const val MAX_SPREAD = 0.02
    const val TAU_MIN_S = 300.0
    // 0.3.53: no start-of-window wait — every scalper may enter from the moment a 15m market opens.
    const val TAU_MAX_S = 900.0
    /** 0.3.40: hard exit at ≥ 60 s left — never hold into the final (settlement-averaging) minute. */
    const val TIME_STOP_S = 60.0
    /** 0.3.40: re-entry cooldown after a scalp closes on the same market (per strategy variant). */
    const val REENTRY_COOLDOWN_MS = 15_000L
    /** 0.3.40 multi-strategy: look-back for dip / momentum triggers, and the minimum history needed. */
    const val LOOKBACK_MS = 60_000L
    const val MIN_LOOKBACK_MS = 20_000L
    /** Extreme-reversion buys only the very cheap side. */
    const val CHEAP_MIN = 0.02
    const val CHEAP_MAX = 0.15
    const val ENTRY_LO = 0.10
    const val ENTRY_HI = 0.55

    /** Hard ceiling for every scalping buy, independent of settings or saved strategy rules. */
    fun buyPriceOk(price: Double?): Boolean = price != null && price.isFinite() && price > 0.0 && price <= ENTRY_HI + 1e-9
    const val SLIPPAGE = 0.01
    const val LATENCY_MS = 3_000L
    const val FRESH_BOOK_MS = 10_000L
    const val MAX_ENTRIES_PER_MARKET = 12
    const val PROMOTION_ROUND_TRIPS = 300
    /** 0.3.43 CF-reprice. */
    const val CF_MAX_AGE_MS = 2_000L
    const val CF_MIN_NET_EXIT = 0.02
    const val CF_LIMIT_TTL_MS = 1_000L
    const val CF_MAX_HOLD_MS = 90_000L
    const val CF_EXIT_BY_S = 90.0
    /** 0.3.43 maker-first scalps. */
    const val MAKER_ENTRY_TTL_MS = 30_000L
    const val MAKER_EXIT_TTL_MS = 20_000L
    /** Only half of a level's displayed shrink counts as queue consumed (the rest may be cancels). Conservative. */
    const val QUEUE_CONSUME_SHARE = 0.5

    /** Fallback σ per √second (train medians, Binance 1 s) until the in-app estimator has 2 minutes of spot. */
    fun defaultSigmaPerSec(ticker: String): Double {
        val u = ticker.uppercase()
        return when {
            u.contains("SOL") -> 8.2e-5
            u.contains("ETH") -> 4.4e-5
            else -> 2.9e-5
        }
    }

    /** Taker fee for one order of [contracts] at [price], rounded up to the cent (dollars). */
    fun orderFee(contracts: Int, price: Double): Double {
        if (contracts <= 0 || !price.isFinite() || price <= 0.0 || price >= 1.0) return 0.0
        val cents = ceil(7.0 * contracts * price * (1.0 - price) - 1e-9)
        return cents / 100.0
    }

    fun feePerContract(contracts: Int, price: Double): Double =
        if (contracts <= 0) 0.0 else orderFee(contracts, price) / contracts

    /** Round-trip cost per contract (dollars): spread + fee in + fee out at the same mid. */
    fun roundTripCost(price: Double, spread: Double, contracts: Int = CONTRACTS): Double =
        spread + feePerContract(contracts, price) + feePerContract(contracts, price)

    fun effectiveTauSeconds(tauS: Double): Double = if (tauS >= 60.0) tauS - 40.0 else (tauS / 3.0).coerceAtLeast(1e-3)

    /** Spot-implied P(YES). Null when inputs are missing. */
    fun fairYes(spot: Double?, strike: Double?, sigmaPerSec: Double?, tauS: Double): Double? {
        if (spot == null || strike == null || sigmaPerSec == null) return null
        if (!spot.isFinite() || !strike.isFinite() || spot <= 0.0 || strike <= 0.0) return null
        if (!sigmaPerSec.isFinite() || sigmaPerSec <= 0.0) return null
        if (tauS <= 0.0) return if (spot >= strike) 1.0 else 0.0
        val z = ln(spot / strike) / (sigmaPerSec * sqrt(effectiveTauSeconds(tauS)))
        return normCdf(z)
    }

    fun normCdf(x: Double): Double {
        if (!x.isFinite()) return if (x > 0) 1.0 else 0.0
        val ax = kotlin.math.abs(x) / sqrt(2.0)
        val t = 1.0 / (1.0 + 0.3275911 * ax)
        val y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * exp(-ax * ax)
        val erf = if (x < 0) -y else y
        return 0.5 * (1.0 + erf)
    }

    /** One fresh book view for a market. Prices in dollars; sizes in contracts. */
    data class Quote(
        val ticker: String,
        val nowMs: Long,
        val closeMs: Long,
        val bookAtMs: Long,
        val yesBid: Double?,
        val yesBidSize: Double?,
        val yesAsk: Double?,
        val yesAskSize: Double?,
        val spot: Double?,
        val strike: Double?,
        val sigmaPerSec: Double?,
        /** 0.3.43 CF-reprice: CF Benchmarks settlement-aware price (running final-minute avg when inside it). */
        val cfSpot: Double? = null,
        /** Age of the latest CF tick (ms). Null = no CF feed → CF-reprice is NO TRADE. */
        val cfAgeMs: Long? = null,
        /** True when the orderbook for this ticker had a sequence gap and has not been re-snapshotted. */
        val bookGap: Boolean = false,
        /** 0.3.68: full ladders (best first) for depth-walking multi-contract paper fills. Empty = top of book only. */
        val yesBidsDesc: List<Pair<Double, Double>> = emptyList(),
        val noBidsDesc: List<Pair<Double, Double>> = emptyList()
    ) {
        /** Asks to lift for [side], best (lowest) first. YES asks come from NO bids, NO asks from YES bids. */
        fun askLadder(side: String): List<Pair<Double, Double>> {
            val src = if (side == "YES") noBidsDesc else yesBidsDesc
            return if (src.isEmpty()) listOfNotNull(ask(side)?.let { a -> askSize(side)?.let { a to it } }) else src.map { (1.0 - it.first) to it.second }
        }
        /** Bids to hit for [side], best (highest) first. */
        fun bidLadder(side: String): List<Pair<Double, Double>> {
            val src = if (side == "YES") yesBidsDesc else noBidsDesc
            return if (src.isEmpty()) listOfNotNull(bid(side)?.let { b -> bidSize(side)?.let { b to it } }) else src
        }
        val tauS: Double get() = (closeMs - nowMs) / 1000.0
        fun cfFresh(): Boolean = cfSpot != null && cfAgeMs != null && cfAgeMs in 0..CF_MAX_AGE_MS
        fun cfFair(side: String): Double? = if (!cfFresh()) null else
            fairYes(cfSpot, strike, sigmaPerSec, tauS)?.let { if (side == "YES") it else 1.0 - it }
        fun fresh(): Boolean = nowMs - bookAtMs in 0..FRESH_BOOK_MS
        fun ask(side: String): Double? = if (side == "YES") yesAsk else yesBid?.let { 1.0 - it }
        fun askSize(side: String): Double? = if (side == "YES") yesAskSize else yesBidSize
        fun bid(side: String): Double? = if (side == "YES") yesBid else yesAsk?.let { 1.0 - it }
        fun bidSize(side: String): Double? = if (side == "YES") yesBidSize else yesAskSize
        fun fair(side: String): Double? = fairYes(spot, strike, sigmaPerSec, tauS)?.let { if (side == "YES") it else 1.0 - it }
    }

    /**
     * Quote from the live order book (YES bids and NO bids, dollars). Best YES ask = 1 − best NO bid,
     * and its displayed size is the size of that NO bid. Null without a book: no book, no fill.
     */
    fun quoteFromBook(
        ticker: String,
        nowMs: Long,
        closeMs: Long?,
        bookAgeMs: Long?,
        yesBids: List<Pair<Double, Double>>,
        noBids: List<Pair<Double, Double>>,
        spot: Double?,
        strike: Double?,
        sigmaPerSec: Double?
    ): Quote? {
        val close = closeMs ?: return null
        val age = bookAgeMs ?: return null
        val yb = yesBids.filter { it.second > 0.0 }.maxByOrNull { it.first }
        val nb = noBids.filter { it.second > 0.0 }.maxByOrNull { it.first }
        if (yb == null && nb == null) return null
        return Quote(
            ticker = ticker,
            nowMs = nowMs,
            closeMs = close,
            bookAtMs = nowMs - age,
            yesBid = yb?.first,
            yesBidSize = yb?.second,
            yesAsk = nb?.let { 1.0 - it.first },
            yesAskSize = nb?.second,
            spot = spot,
            strike = strike,
            sigmaPerSec = sigmaPerSec,
            yesBidsDesc = yesBids.filter { it.second > 0.0 }.sortedByDescending { it.first }.take(20),
            noBidsDesc = noBids.filter { it.second > 0.0 }.sortedByDescending { it.first }.take(20)
        )
    }

    data class Entry(val side: String, val ask: Double, val fair: Double, val gapAfterFee: Double)

    /** Entry signal at this quote, or a skip reason. Uses only this quote (no look-ahead). */
    fun entrySignal(q: Quote, p: ScalpParams = ScalpParams.CLASSIC, sides: Collection<String> = listOf("YES", "NO")): Pair<Entry?, String> {
        if (!q.fresh()) return null to "stale book"
        val tau = q.tauS
        if (tau < p.tauMinS || tau > p.tauMaxS) return null to "outside entry window"
        val yb = q.yesBid ?: return null to "no bid"
        val ya = q.yesAsk ?: return null to "no ask"
        val spread = ya - yb
        if (spread > MAX_SPREAD + 1e-9) return null to "spread over 2¢"
        val best = sides.mapNotNull { side ->
            val ask = q.ask(side) ?: return@mapNotNull null
            val fair = q.fair(side) ?: return@mapNotNull null
            if (ask < ENTRY_LO - 1e-9 || !buyPriceOk(if (p.maker) q.bid(side) else ask)) return@mapNotNull null
            Entry(side, ask, fair, fair - ask - feePerContract(CONTRACTS, ask))
        }.maxByOrNull { it.gapAfterFee } ?: return null to "no spot fair value"
        if (best.gapAfterFee + 1e-9 < p.minGap) {
            return null to String.format(Locale.US, "gap %.1f¢ under %.0f¢", best.gapAfterFee * 100, p.minGap * 100)
        }
        // 0.3.40: expected move (fair − ask) must beat spread + entry fee + exit fee.
        val move = best.fair - best.ask
        val cost = spread + feePerContract(CONTRACTS, best.ask) + feePerContract(CONTRACTS, best.fair.coerceIn(0.01, 0.99))
        if (move <= cost + 1e-9) {
            return null to String.format(Locale.US, "expected move %.1f¢ ≤ spread + both fees %.1f¢", move * 100, cost * 100)
        }
        return best to "enter"
    }

    /** One past book view for a market (strategy look-back). Prices in dollars. */
    data class Snap(val atMs: Long, val yesBid: Double?, val yesAsk: Double?, val fairYes: Double?) {
        fun ask(side: String): Double? = if (side == "YES") yesAsk else yesBid?.let { 1.0 - it }
        fun bid(side: String): Double? = if (side == "YES") yesBid else yesAsk?.let { 1.0 - it }
        fun fair(side: String): Double? = fairYes?.let { if (side == "YES") it else 1.0 - it }
    }

    /**
     * 0.3.40 multi-strategy entry. Same guards for every strategy (fresh book, entry window, spread ≤ 2¢,
     * expected move > spread + entry fee + exit fee); the trigger differs. [recent] holds only books strictly
     * before this quote (no look-ahead).
     */
    fun strategySignal(
        q: Quote,
        p: ScalpParams,
        sides: Collection<String>,
        recent: List<Snap>
    ): Pair<Entry?, String> {
        if (p.strategy == ScalpStrategy.FAIR_GAP) return entrySignal(q, p, sides)
        if (p.strategy == ScalpStrategy.CF_REPRICE) return cfRepriceSignal(q, p, sides)
        if (!q.fresh()) return null to "stale book"
        val tau = q.tauS
        if (tau < p.tauMinS || tau > p.tauMaxS) return null to "outside entry window"
        val yb = q.yesBid ?: return null to "no bid"
        val ya = q.yesAsk ?: return null to "no ask"
        val spread = ya - yb
        if (spread > MAX_SPREAD + 1e-9) return null to "spread over 2¢"
        val past = recent.filter { it.atMs in (q.nowMs - LOOKBACK_MS)..(q.nowMs - LATENCY_MS) }
        val needHistory = p.strategy != ScalpStrategy.EXTREME_REVERSION
        if (needHistory && (past.isEmpty() || q.nowMs - past.minOf { it.atMs } < MIN_LOOKBACK_MS)) return null to "not enough history"
        data class Cand(val e: Entry, val move: Double, val cost: Double)
        val cands = sides.mapNotNull { side ->
            val ask = q.ask(side) ?: return@mapNotNull null
            val bid = q.bid(side) ?: return@mapNotNull null
            val fair = q.fair(side) ?: return@mapNotNull null
            val move: Double = when (p.strategy) {
                ScalpStrategy.DIP_HUNTER -> {
                    if (ask < ENTRY_LO - 1e-9 || !buyPriceOk(if (p.maker) q.bid(side) else ask)) return@mapNotNull null
                    val ref = past.mapNotNull { it.ask(side) }.maxOrNull() ?: return@mapNotNull null
                    val drop = ref - ask
                    // Spot must not explain the drop: fair still at or above the ask.
                    if (drop + 1e-9 < p.minGap || fair + 1e-9 < ask) return@mapNotNull null
                    drop * 0.5 // half retrace
                }
                ScalpStrategy.MOMENTUM -> {
                    if (ask < ENTRY_LO - 1e-9 || !buyPriceOk(if (p.maker) q.bid(side) else ask)) return@mapNotNull null
                    val refBid = past.mapNotNull { it.bid(side) }.minOrNull() ?: return@mapNotNull null
                    val rise = bid - refBid
                    val fairThen = past.minByOrNull { it.atMs }?.fair(side) ?: return@mapNotNull null
                    // Spot confirmation: fair moved the same way by at least half the trigger; not paying far above fair.
                    if (rise + 1e-9 < p.minGap || fair - fairThen + 1e-9 < p.minGap / 2 || ask > fair + 0.02 + 1e-9) return@mapNotNull null
                    rise * 0.5 // continuation
                }
                ScalpStrategy.EXTREME_REVERSION -> {
                    if (ask < CHEAP_MIN - 1e-9 || ask > CHEAP_MAX + 1e-9) return@mapNotNull null
                    if (fair - ask + 1e-9 < p.minGap) return@mapNotNull null
                    fair - ask
                }
                ScalpStrategy.FAIR_GAP, ScalpStrategy.CF_REPRICE -> return@mapNotNull null
            }
            val cost = spread + feePerContract(CONTRACTS, ask) + feePerContract(CONTRACTS, (ask + move).coerceIn(0.01, 0.99))
            Cand(Entry(side, ask, fair, move - cost), move, cost)
        }
        val best = cands.maxByOrNull { it.e.gapAfterFee } ?: return null to "no ${p.strategy.label} trigger"
        if (best.move <= best.cost + 1e-9) {
            return null to String.format(Locale.US, "expected move %.1f¢ ≤ spread + both fees %.1f¢", best.move * 100, best.cost * 100)
        }
        return best.e to "enter"
    }

    /**
     * 0.3.43 CF-reprice entry. Filters: CF tick ≤ 2 s old (else NO TRADE — never Coinbase), no orderbook seq gap,
     * fresh book, 3–13 min left, spread ≤ 2¢. Enter when CF fair − executable ask − entry fee ≥ minGap (6¢) AND
     * the predicted net exit (sell at CF fair after the exit fee) ≥ 2¢. Sizing: 10 contracts, displayed depth only.
     */
    fun cfRepriceSignal(q: Quote, p: ScalpParams, sides: Collection<String>): Pair<Entry?, String> {
        if (q.cfSpot == null || q.cfAgeMs == null) return null to "NO TRADE — no CF Benchmarks feed"
        if (!q.cfFresh()) return null to "NO TRADE — CF tick ${q.cfAgeMs} ms old (> 2 s)"
        if (q.bookGap) return null to "NO TRADE — orderbook seq gap"
        if (!q.fresh()) return null to "stale book"
        val tau = q.tauS
        if (tau < p.tauMinS || tau > p.tauMaxS) return null to "outside entry window"
        val yb = q.yesBid ?: return null to "no bid"
        val ya = q.yesAsk ?: return null to "no ask"
        val spread = ya - yb
        if (spread > MAX_SPREAD + 1e-9) return null to "spread over 2¢"
        val best = sides.mapNotNull { side ->
            val ask = q.ask(side) ?: return@mapNotNull null
            if (ask < ENTRY_LO - 1e-9 || !buyPriceOk(if (p.maker) q.bid(side) else ask)) return@mapNotNull null
            val fair = q.cfFair(side) ?: return@mapNotNull null
            val entryFee = feePerContract(CONTRACTS, ask)
            Entry(side, ask, fair, fair - ask - entryFee)
        }.maxByOrNull { it.gapAfterFee } ?: return null to "no CF fair value"
        if (best.gapAfterFee + 1e-9 < p.minGap) {
            return null to String.format(Locale.US, "CF gap %.1f¢ under %.0f¢", best.gapAfterFee * 100, p.minGap * 100)
        }
        val netExit = best.gapAfterFee - feePerContract(CONTRACTS, best.fair.coerceIn(0.01, 0.99))
        if (netExit + 1e-9 < CF_MIN_NET_EXIT) {
            return null to String.format(Locale.US, "predicted net exit %.1f¢ under 2¢", netExit * 100)
        }
        return best to "enter"
    }

    /** 0.3.43 CF-reprice exits: TP / stop / 90 s hold / mandatory exit by 90 s left / gap gone (Kalshi repriced). */
    fun cfRepriceExit(side: String, entry: Double, entryAtMs: Long?, q: Quote, p: ScalpParams): ExitReason? {
        if (q.tauS <= CF_EXIT_BY_S) return ExitReason.CF_EXIT_BY_CLOSE
        if (entryAtMs != null && q.nowMs - entryAtMs >= CF_MAX_HOLD_MS) return ExitReason.CF_TIME
        val bid = q.bid(side)
        if (bid != null && bid > 0.0) {
            if (bid - entry >= p.target - 1e-12) return ExitReason.PROFIT_TARGET
            if (bid <= entry - p.stop + 1e-12) return ExitReason.STOP
        }
        val fair = q.cfFair(side)
        val ask = q.ask(side)
        if (fair != null && ask != null && fair - ask <= p.turnDown + 1e-12) return ExitReason.CF_GAP_GONE
        return null
    }

    enum class ExitReason(val label: String) {
        CF_EXIT_BY_CLOSE("mandatory exit: 90 s left"),
        CF_TIME("time exit: 90 s hold"),
        CF_GAP_GONE("early exit: CF gap disappeared"),
        GAP_CLOSED("take-profit: bid beats fair after fee"),
        TURN_DOWN("turn-down: fair fell below entry"),
        PROFIT_TARGET("profit target hit"),
        STOP("stop hit"),
        TIME_STOP("hard exit: 60 s left"),
        SETTLED("held to settlement (no exit liquidity)")
    }

    /** Per-contract gain after entry + exit taker fees on a 10-contract clip. */
    fun netAfterFees(entry: Double, bid: Double, n: Int = CONTRACTS): Double = (bid - entry) - (orderFee(n, bid) + orderFee(n, entry)) / n

    /** Exit decision for an open scalp at this quote, or null to keep holding. */
    fun exitSignal(side: String, entry: Double, q: Quote, p: ScalpParams = ScalpParams.CLASSIC): ExitReason? {
        if (q.tauS <= TIME_STOP_S) return ExitReason.TIME_STOP
        val bid = q.bid(side)
        val fair = q.fair(side)
        if (bid != null && bid > 0.0) {
            if (bid - entry >= p.target - 1e-12) return ExitReason.PROFIT_TARGET
            if (bid <= entry - p.stop + 1e-12) return ExitReason.STOP
            // 0.3.65: fair-value take-profit only when the round trip nets > $0 after both taker fees.
            if (fair != null && bid - feePerContract(CONTRACTS, bid) >= fair && netAfterFees(entry, bid) > 1e-9) return ExitReason.GAP_CLOSED
        }
        if (fair != null && fair <= entry - p.turnDown + 1e-12) return ExitReason.TURN_DOWN
        return null
    }

    fun rulesText(): String =
        "Scalp $VERSION (PAPER). Five strategies run side by side — " +
            ScalpStrategy.values().joinToString("; ") { "${it.label}: ${it.blurb}" } +
            " — each with one open scalp per market side, its own per-coin params and walk-forward tuning. " +
            "Total open scalp cost is capped by the paper bankroll. Fair-gap: enter when the ask is below spot-implied fair by the coin's gap threshold after " +
            "the entry fee AND the expected move beats spread + both fees, inside the coin's time window, spread ≤ 2¢, " +
            "Buy price ≤ 55¢ for every scalper. 10 contracts, displayed depth only, fill on the next fresh book (≥ 3 s). Several round trips per window: " +
            "one open scalp per strategy per market side, 15 s cooldown after an exit. Exit at the bid on the profit target, the stop, " +
            "gap close or turn-down, and always by 60 s left. Per-coin params come from the walk-forward tuner. " +
            "Taker fee on both legs. $BACKTEST_LABEL"
}

enum class ScalpState { PENDING_ENTRY, OPEN, PENDING_EXIT, CLOSED, NO_FILL }

/** One paper scalp. Money in dollars; all fields persisted (scalp_trades table). */
data class ScalpTrade(
    val id: String,
    val ticker: String,
    val side: String,
    val state: ScalpState,
    val signalAtMs: Long,
    val signalAsk: Double,
    val fairAtSignal: Double,
    val contracts: Int = 0,
    val entryPrice: Double? = null,
    val entryFeeUsd: Double = 0.0,
    val entryAtMs: Long? = null,
    val exitDecidedAtMs: Long? = null,
    val exitReason: String? = null,
    val soldContracts: Int = 0,
    val proceedsUsd: Double = 0.0,
    val exitFeeUsd: Double = 0.0,
    val closedAtMs: Long? = null,
    val netUsd: Double? = null,
    val note: String = "",
    val ruleVersion: String = ScalpRule.VERSION,
    /** 0.3.43 maker queue model (in memory): displayed size ahead of us at post time, and estimated consumed since. */
    val queueAhead: Double? = null,
    val queueConsumed: Double = 0.0,
    val levelSize: Double? = null,
    val restingLimit: Double? = null,
    val restingSinceMs: Long? = null,
    /** Adverse selection: side mid − entry price, 30 s / 60 s after the entry fill (dollars; negative = adverse). */
    val adverse30: Double? = null,
    val adverse60: Double? = null
) {
    val isMaker: Boolean get() = ScalpParams.byId(variantId)?.maker == true
    val remaining: Int get() = contracts - soldContracts
    val entryCostUsd: Double get() = (entryPrice ?: 0.0) * contracts + entryFeeUsd
    val holdMs: Long? get() = entryAtMs?.let { e -> closedAtMs?.let { it - e } }
    /** Window cluster key shared by BTC/ETH/SOL markets that close together. */
    val windowKey: String get() = ticker.uppercase().substringAfter('-').substringBefore('-')
    /** 0.3.40: ruleVersion = "VERSION|variantId|P" (primary) or "…|S" (shadow tuning variant). Legacy rows are primary. */
    val variantId: String by lazy(LazyThreadSafetyMode.PUBLICATION) { ruleVersion.split('|').getOrNull(1) ?: ScalpParams.LEGACY_ID }
    val isPrimary: Boolean by lazy(LazyThreadSafetyMode.PUBLICATION) { ruleVersion.split('|').getOrNull(2) != "S" }
    val coin: String get() = ScalpParams.coinOf(ticker)
    val strategy: ScalpStrategy get() = ScalpParams.byId(variantId)?.strategy ?: ScalpStrategy.FAIR_GAP
    /** 0.3.54: display/breakdown label — forecast trades are their own algos, never "Fair-gap". */
    val algoLabel: String get() = when {
        ScalpV2.isV2(variantId) -> ScalpModels.modelOf(this)?.label ?: "v2"
        variantId.startsWith(ForecastScalp.RIDE_PREFIX) -> ScalpModels.Model.FORECAST_RIDE.label
        variantId.startsWith(ForecastScalp.FADE_PREFIX) -> ScalpModels.Model.FORECAST_FADE.label
        ShockFav.isShockFav(variantId) -> ShockFav.byVariantId(variantId)?.let { "${it.code} ${it.label}" } ?: variantId
        else -> strategy.label
    }
    /** Strategy for per-strategy grouping, null for forecast trades (grouped separately). */
    val strategyOrNull: ScalpStrategy? get() = if (ForecastScalp.isForecast(this) || ShockFav.isShockFav(variantId) || ScalpV2.isV2(variantId)) null else strategy

    /** Mark-to-bid P&L after the exit fee for what is still held. */
    fun unrealizedUsd(bid: Double?): Double? {
        if (state != ScalpState.OPEN && state != ScalpState.PENDING_EXIT) return null
        val b = bid ?: return null
        val rem = remaining
        val exitValue = rem * b - ScalpRule.orderFee(rem, b)
        return proceedsUsd - exitFeeUsd + exitValue - entryCostUsd
    }
}

/**
 * Paper scalp book. Feed it one [ScalpRule.Quote] per market per tick ([onQuote]) and settlement results
 * ([settle]). A signal at time t can only fill on a later, fresh book at least [ScalpRule.LATENCY_MS] after t,
 * at that book's price and displayed size. Never calls Kalshi and never places a live order.
 */
class ScalpBook(
    private val store: ScalpPersistence = InMemoryScalpPersistence(),
    /** 0.3.40: shadow tuning variants run beside the primary params (paper only). */
    private val variants: List<ScalpParams> = ScalpParams.ALL_VARIANTS,
    private val tuneStore: ScalpTuneStore = InMemoryScalpTuneStore(),
    /** Paper bankroll: total open primary scalp cost (entries + fees, pending included) never exceeds it. */
    private val bankrollUsd: () -> Double = { com.dirk.kalshiodds.signal.config.SignalConstants.PAPER_START_USD },
    /** 0.3.65 app-only extras: v2 copies + entry/exit detail notes (off in legacy unit-test books). */
    private val liveExtras: Boolean = false,
    private val controls: ScalpControlsRepository = MemoryScalpControls(),
    private val idFactory: () -> String = { UUID.randomUUID().toString() }
) {
    private val lock = Any()
    // 0.3.67: only open/pending rows live in RAM; closed history stays in SQLite + scalp_stats.
    private val _all = MutableStateFlow(store.loadActiveScalps().sortedByDescending { it.signalAtMs })
    private val _trades = MutableStateFlow(_all.value.filter { it.isPrimary })
    private val _stats = MutableStateFlow(store.loadStats())
    @Volatile private var v2Evidence = if (liveExtras) store.loadWindows(48) else emptyList()
    val stats: StateFlow<List<ScalpStat>> = _stats.asStateFlow()
    /** Coalesced by id — last write wins; drained by [flushWrites]. */
    @Volatile private var _lastPruneBucket = -1L
    private val flushLock = Any()
    private val writeQ = java.util.concurrent.ConcurrentHashMap<String, Pair<ScalpTrade, ScalpTrade?>>()
    /** Primary scalps only (what the Scalp screen, stats and ladder use). */
    val trades: StateFlow<List<ScalpTrade>> = _trades.asStateFlow()
    /** 0.3.43: primary + shadow (incl. maker-first variants), for the maker-vs-taker comparison. */
    val all: StateFlow<List<ScalpTrade>> = _all.asStateFlow()
    private val vol = HashMap<String, SpotVol>()
    private val _marks = MutableStateFlow<Map<String, ScalpRule.Quote>>(emptyMap())
    /** Latest quote per ticker, for mark-to-bid P&L on screen. */
    val marks: StateFlow<Map<String, ScalpRule.Quote>> = _marks.asStateFlow()
    private val _tune = MutableStateFlow(tuneStore.load())
    /** Current per-coin params version and its last out-of-sample result. */
    val tune: StateFlow<ScalpTuneState> = _tune.asStateFlow()

    fun snapshot(): List<ScalpTrade> = _trades.value // 0.3.67: open/pending primaries only
    /** Primary + shadow variants (tuner input). */
    fun allTrades(): List<ScalpTrade> = _all.value

    fun paramsFor(coin: String): ScalpParams = _tune.value.paramsFor(coin)
    fun paramsFor(coin: String, strategy: ScalpStrategy): ScalpParams = _tune.value.paramsFor(coin, strategy)

    private val history = HashMap<String, ArrayDeque<ScalpRule.Snap>>()

    /** Cost tied up in open primary scalps (pending entries reserve ask × clip + entry fee). */
    fun openCostUsd(): Double = _trades.value.sumOf { t ->
        if (ScalpV2.isV2(t.variantId)) return@sumOf 0.0 // 0.3.65: v2 copies never crowd out originals
        when (t.state) {
            ScalpState.PENDING_ENTRY -> t.signalAsk * ScalpRule.CONTRACTS + ScalpRule.orderFee(ScalpRule.CONTRACTS, t.signalAsk)
            ScalpState.OPEN, ScalpState.PENDING_EXIT -> (t.entryPrice ?: 0.0) * t.remaining + t.entryFeeUsd
            else -> 0.0
        }
    }

    /** Open cost of one model's primary scalps (its bankroll slice in use). */
    fun openCostUsd(model: ScalpModels.Model): Double = _trades.value.filter { isActive(it) && ScalpModels.modelOf(it) == model }.sumOf(::activeCost)

    private fun activeCost(t: ScalpTrade): Double = when (t.state) {
        ScalpState.PENDING_ENTRY -> t.signalAsk * ScalpRule.CONTRACTS + ScalpRule.orderFee(ScalpRule.CONTRACTS, t.signalAsk)
        ScalpState.OPEN, ScalpState.PENDING_EXIT -> (t.entryPrice ?: 0.0) * t.remaining + t.entryFeeUsd
        else -> 0.0
    }

    fun openTickers(): Set<String> = _trades.value.filter {
        it.state == ScalpState.OPEN || it.state == ScalpState.PENDING_EXIT || it.state == ScalpState.PENDING_ENTRY
    }.map { it.ticker.uppercase() }.toSet()

    /** Trailing 900 s realized σ per √second from spot observations, per coin. Null until 120 s of data. */
    fun observeSpot(coin: String, spot: Double?, atMs: Long): Double? = synchronized(lock) {
        val v = vol.getOrPut(coin) { SpotVol() }
        if (spot != null && spot.isFinite() && spot > 0.0) v.add(atMs, spot)
        v.sigmaPerSec()
    }

    private fun isActive(t: ScalpTrade) = t.state != ScalpState.CLOSED && t.state != ScalpState.NO_FILL

    fun onQuote(q: ScalpRule.Quote, enabled: Boolean): List<ScalpTrade> = synchronized(lock) {
        if (q.nowMs / 15_000L != (_lastPruneBucket)) { _lastPruneBucket = q.nowMs / 15_000L; pruneClosed(q.nowMs) }
        _marks.value = (_marks.value + (q.ticker.uppercase() to q)).let { m ->
            if (m.size <= ScalpMemory.MAX_TICKERS) m else m.filterValues { it.closeMs > q.nowMs - 60_000L || it.ticker.equals(q.ticker, true) }
        }
        val changed = ArrayList<ScalpTrade>()
        val coin = ScalpParams.coinOf(q.ticker)
        val primary = paramsFor(coin)
        // 0.3.50: six paper models = five strategy primaries + maker-first dip (buy the dip at the bid, sell the rebound at the ask).
        val primaries = ScalpStrategy.values().map { paramsFor(coin, it) } + ScalpModels.MAKER_DIP_PARAMS + ScalpParams.HOLD60_SIBLINGS + ScalpParams.QUEUED_COPIES
        val primaryIds = primaries.map { it.id }.toSet()
        val key = q.ticker.uppercase()
        val hist = history.getOrPut(key) { ArrayDeque() }
        val recent = hist.toList()
        hist.addLast(ScalpRule.Snap(q.nowMs, q.yesBid, q.yesAsk, ScalpRule.fairYes(q.spot, q.strike, q.sigmaPerSec, q.tauS)))
        while (hist.isNotEmpty() && q.nowMs - hist.first().atMs > maxOf(2 * ScalpRule.LOOKBACK_MS, controls.state.value.windowSeconds * 1000L)) hist.removeFirst()
        if (q.nowMs >= q.closeMs) history.remove(key)
        // 0.3.50: bounded — forget closed windows' history/marks (they used to accumulate forever).
        if (history.size > ScalpMemory.MAX_TICKERS) history.keys.filter { it != key && (ScalpTicker.closeMs(it) ?: Long.MAX_VALUE) < q.nowMs }.forEach { history.remove(it) }
        stepShadows(q, null)
        val mineAll = _all.value.filter { it.ticker.equals(q.ticker, true) }
        // Step every open scalp first (one per market side per variant).
        // 0.3.65: peak bid for every open trade (stored on close) + v2 lane.
        mineAll.forEach { t -> if ((!ScalpV2.isV2(t.variantId) || ScalpV2.fresh(q)) && (t.state == ScalpState.OPEN || t.state == ScalpState.PENDING_EXIT)) q.bid(t.side)?.takeIf { it > 0.0 }?.let { b -> peakBid[t.id] = maxOf(peakBid[t.id] ?: b, b) } }
        changed += stepV2(q)
        mineAll.filter { isActive(it) && !ForecastScalp.isForecast(it) && !ScalpV2.isV2(it.variantId) }.forEach { t ->
            val p = ScalpParams.byId(t.variantId) ?: primary
            // Opening originals also pass through this generic execution lane. Never fill an obsolete side here.
            if (t.state == ScalpState.PENDING_ENTRY && ShockFav.byVariantId(t.variantId)?.o50 == ShockFav.O50.UPTICK) {
                if (!ScalpV2.fresh(q)) return@forEach
                if (openingDirection(q) != t.side) {
                    changed += t.copy(state = ScalpState.NO_FILL, closedAtMs = q.nowMs,
                        note = "${t.note}; opening direction changed before fill; wait for a new live signal")
                    return@forEach
                }
            }
            step(t, q, p)?.let { changed += it }
        }
        if (enabled) {
            val run = (primaries + variants).distinctBy { it.id }
            // 0.3.50: per-model slice usage computed once per quote from active rows (was a full-ledger scan per candidate).
            val sliceUsed = HashMap<ScalpModels.Model, Double>()
            _trades.value.forEach { t -> if (isActive(t)) ScalpModels.modelOf(t)?.let { m -> sliceUsed[m] = (sliceUsed[m] ?: 0.0) + activeCost(t) } }
            changed.forEach { t -> if (t.isPrimary && t.state == ScalpState.PENDING_ENTRY && t.signalAtMs == q.nowMs) ScalpModels.modelOf(t)?.let { m -> sliceUsed[m] = (sliceUsed[m] ?: 0.0) + activeCost(t) } }
            var openCost = openCostUsd() + changed.filter { it.isPrimary && it.state == ScalpState.PENDING_ENTRY && it.signalAtMs == q.nowMs }
                .sumOf { it.signalAsk * ScalpRule.CONTRACTS + ScalpRule.orderFee(ScalpRule.CONTRACTS, it.signalAsk) }
            for (p in run) {
                val mine = mineAll.filter { it.variantId == p.id }.map { old -> changed.firstOrNull { it.id == old.id } ?: old }
                if (!liveExtras && mine.count { it.state != ScalpState.NO_FILL } >= ScalpRule.MAX_ENTRIES_PER_MARKET) continue
                if (q.nowMs >= q.closeMs) continue
                val lastClose = mine.mapNotNull { it.closedAtMs }.maxOrNull()
                if (lastClose != null && q.nowMs - lastClose < ScalpRule.REENTRY_COOLDOWN_MS) continue
                val openSides = mine.filter { isActive(it) }.map { it.side }.toSet()
                // 0.3.43 CF-reprice: one direction per market (no YES and NO at once).
                val free = if (p.strategy == ScalpStrategy.CF_REPRICE && openSides.isNotEmpty()) emptyList()
                    else listOf("YES", "NO").filter { it !in openSides }
                if (free.isEmpty()) continue
                val (sig, _) = ScalpRule.strategySignal(q, p, free, recent)
                if (sig != null && ScalpRule.buyPriceOk(if (p.maker) q.bid(sig.side) else sig.ask)) {
                    val role = if (p.id in primaryIds) "P" else "S"
                    if (role == "P") {
                        val cost = sig.ask * ScalpRule.CONTRACTS + ScalpRule.orderFee(ScalpRule.CONTRACTS, sig.ask)
                        if (openCost + cost > bankrollUsd() + 1e-9) continue // paper bankroll fully committed
                        // 0.3.50: each of the six models has its own bankroll slice (independent ledger).
                        val model = ScalpModels.modelOf(p)
                        // 0.3.54: no per-model cap — all scalpers share the one free paper bankroll.
                        sliceUsed[model] = (sliceUsed[model] ?: 0.0) + cost
                        openCost += cost
                    }
                    val makerBid = if (p.maker) q.bid(sig.side)?.takeIf { it > 0.0 } else null
                    if (p.maker && makerBid == null) continue
                    changed += ScalpTrade(
                        id = idFactory(),
                        ticker = q.ticker.uppercase(),
                        side = sig.side,
                        state = ScalpState.PENDING_ENTRY,
                        signalAtMs = q.nowMs,
                        signalAsk = makerBid ?: sig.ask,
                        queueAhead = if (p.maker) q.bidSize(sig.side) ?: 0.0 else null,
                        levelSize = if (p.maker) q.bidSize(sig.side) else null,
                        restingLimit = makerBid,
                        restingSinceMs = if (p.maker) q.nowMs else null,
                        fairAtSignal = sig.fair,
                        note = String.format(Locale.US, "%s · edge %.1f¢ after fees · %s", p.strategy.label, sig.gapAfterFee * 100, p.id) +
                            (if (p.maker) String.format(Locale.US, " · maker post @ %s, queue ahead %.0f", com.dirk.kalshiodds.domain.KalshiQuoteDisplay.formatPriceCents(makerBid!!), q.bidSize(sig.side) ?: 0.0) else ""),
                        ruleVersion = "${ScalpRule.VERSION}|${p.id}|$role"
                    )
                }
            }
        }
        changed.forEach { save(it) }
        if (changed.any { it.state == ScalpState.CLOSED }) maybeTune(q.nowMs)
        changed.filter { it.isPrimary }
    }

    // ---- 0.3.53 Forecast ride / fade (paper only) ----
    private val fcHist = HashMap<String, ArrayDeque<ShortHorizonForecaster.Sample>>()
    private val leadHist = HashMap<String, ArrayDeque<Pair<Long, Double>>>()

    /**
     * Feed one fresh book per market tick (same quote as [onQuote]) plus top-3 level sizes. Ride buys the side the
     * forecast says rises; fade (exact mirror) buys the other side. Entry = resting limit at the ask, touch fill capped
     * by displayed depth, cancelled after 2 s; exits sell at the bid capped by depth; taker fee on every fill.
     */
    fun onForecastQuote(q: ScalpRule.Quote, top3Yes: Double, top3No: Double, enabled: Boolean, nowHorizonS: Int? = null): List<ScalpTrade> = synchronized(lock) {
        val yb = q.yesBid ?: return@synchronized emptyList()
        val ya = q.yesAsk ?: return@synchronized emptyList()
        if (ya <= yb) return@synchronized emptyList()
        val key = q.ticker.uppercase()
        val coin = ScalpParams.coinOf(q.ticker)
        val horizon = nowHorizonS ?: ShortHorizonForecaster.horizonFor(coin)
        val mid = (yb + ya) / 2
        val sample = ShortHorizonForecaster.Sample(q.nowMs, mid, top3Yes, top3No)
        val hist = fcHist.getOrPut(key) { ArrayDeque() }
        hist.addLast(sample)
        while (hist.isNotEmpty() && q.nowMs - hist.first().atMs > 200_000L) hist.removeFirst()
        if (q.nowMs >= q.closeMs) fcHist.remove(key)
        if (fcHist.size > ScalpMemory.MAX_TICKERS) fcHist.keys.filter { it != key && (ScalpTicker.closeMs(it) ?: Long.MAX_VALUE) < q.nowMs }.forEach { fcHist.remove(it) }
        // 0.3.54 fc54: lead (CF index, Coinbase spot fallback) history per coin — spans windows, ready at the open.
        val lead = (if (q.cfFresh()) q.cfSpot else q.spot)?.takeIf { it > 0 && it.isFinite() }
        val lh = leadHist.getOrPut(coin) { ArrayDeque() }
        if (lead != null) {
            lh.addLast(q.nowMs to lead)
            while (lh.isNotEmpty() && q.nowMs - lh.first().first > 75_000L) lh.removeFirst()
        }
        val cfg = LeadForecaster.configFor(coin)
        val h54 = nowHorizonS ?: cfg.horizonS
        val mid60 = hist.firstOrNull { it.atMs >= q.nowMs - 60_000L }?.takeIf { it.atMs < q.nowMs }?.mid
        val imb = (top3Yes - top3No) / maxOf(top3Yes + top3No, 1e-9)
        val x54 = if (lead == null || q.strike == null || q.sigmaPerSec == null) null else
            LeadForecaster.features(mid, mid60, imb, lead, q.strike, q.sigmaPerSec, q.tauS) { k ->
                lh.lastOrNull { it.first <= q.nowMs - k * 1000L }?.second
            }
        val pred54 = x54?.let { LeadForecaster.predict(coin, h54, it) }
        // 0.3.56: stale/missing lead → no forecast. No entries, and open trades HOLD (only TP/horizon/close exits)
        // instead of reading a fake 0 forecast as "forecast turned".
        val pred = pred54 ?: Double.NaN
        val enabledNow = enabled && pred54 != null
        val changed = ArrayList<ScalpTrade>()
        for (ride in listOf(true, false)) {
            val f = if (ride) pred else -pred
            val vid = ForecastScalp.variantId(ride, h54)
            val prefix = if (ride) ForecastScalp.RIDE_PREFIX else ForecastScalp.FADE_PREFIX
            val active = _all.value.firstOrNull { it.ticker == key && it.variantId.startsWith(prefix) && isActive(it) }
            if (active != null) {
                fcStep(active, q, f, h54)?.let { changed += it }
                continue
            }
            if (!enabledNow || q.nowMs >= q.closeMs || !q.fresh()) continue
            val side = if (f > 0) "YES" else "NO"
            val ask = q.ask(side) ?: continue
            if (!ForecastScalp.wantsEntry(mid, ya - yb, ask, kotlin.math.abs(f), q.tauS, cfg.marginUsd)) continue
            val model = if (ride) ScalpModels.Model.FORECAST_RIDE else ScalpModels.Model.FORECAST_FADE
            val cost = ask * ScalpRule.CONTRACTS + ScalpRule.orderFee(ScalpRule.CONTRACTS, ask)
            if (openCostUsd() + cost > bankrollUsd() + 1e-9) continue
            changed += ScalpTrade(
                id = idFactory(), ticker = key, side = side, state = ScalpState.PENDING_ENTRY, signalAtMs = q.nowMs,
                signalAsk = ask, fairAtSignal = mid + f, restingLimit = ask, restingSinceMs = q.nowMs,
                note = String.format(Locale.US, "%s · forecast %+.1f¢ over %s · %s", if (ride) "Forecast ride" else "Forecast fade",
                    f * 100, ShortHorizonForecaster.horizonLabel(h54), LeadForecaster.VERSION),
                ruleVersion = "${ScalpRule.VERSION}|$vid|P"
            )
        }
        changed.forEach { save(it) }
        changed
    }

    private fun fcStep(t: ScalpTrade, q: ScalpRule.Quote, f: Double, horizon: Int): ScalpTrade? {
        val sideF = if (t.side == "YES") f else -f
        return when (t.state) {
            ScalpState.PENDING_ENTRY -> {
                val limit = t.restingLimit ?: t.signalAsk
                if (!ScalpRule.buyPriceOk(limit)) return cappedEntry(t, q.nowMs)
                if (q.nowMs - t.signalAtMs > ForecastScalp.ENTRY_LIMIT_TTL_MS || q.nowMs >= q.closeMs)
                    return t.copy(state = ScalpState.NO_FILL, closedAtMs = q.nowMs, note = "${t.note}; limit cancelled unfilled")
                val n = com.dirk.kalshiodds.signal.paper.PaperLimitFill.touchFillQty(limit, q.ask(t.side), q.askSize(t.side), ScalpRule.CONTRACTS, buy = true)
                if (n <= 0) null else t.copy(state = ScalpState.OPEN, contracts = n, entryPrice = limit, entryFeeUsd = ScalpRule.orderFee(n, limit),
                    entryAtMs = q.nowMs, restingLimit = null, note = "${t.note}; filled $n @ ${com.dirk.kalshiodds.domain.KalshiQuoteDisplay.formatPriceCents(limit)}")
            }
            ScalpState.OPEN, ScalpState.PENDING_EXIT -> {
                if (q.nowMs >= q.closeMs || !q.fresh()) return null
                val bid = q.bid(t.side)?.takeIf { it > 0.0 } ?: return null
                val fcEntry = t.entryPrice ?: 0.0
                val stopHit = t.state == ScalpState.OPEN && bid <= fcEntry - ForecastScalp.STOP_C / 100.0 + 1e-9
                if (stopHit && t.isPrimary) addShadow(t, sf = false) { qq, _, b, _ -> b >= ForecastScalp.TAKE_PROFIT - 1e-9 || qq.tauS < ForecastScalp.EXIT_BEFORE_CLOSE_S }
                val reason = t.exitReason.takeIf { t.state == ScalpState.PENDING_EXIT }
                    ?: (if (stopHit) "stop −${ForecastScalp.STOP_C}¢" else null)
                    ?: ForecastScalp.wantsExit(sideF, bid, (q.nowMs - (t.entryAtMs ?: q.nowMs)) / 1000.0, horizon, q.tauS) ?: return null
                val n = minOf(t.remaining, kotlin.math.floor((q.bidSize(t.side) ?: 0.0) + 1e-9).toInt())
                val base = if (t.state == ScalpState.OPEN) t.copy(state = ScalpState.PENDING_EXIT, exitDecidedAtMs = q.nowMs, exitReason = reason) else t
                if (n <= 0) return base
                val next = ScalpFills.sell(base, q.nowMs, n, n * bid, ScalpRule.orderFee(n, bid))
                if (next.remaining <= 0) close(next, q.nowMs, reason) else next
            }
            else -> null
        }
    }

    // ---------------- 0.3.57 sf57: twenty shock / late-favourite paper scalpers ----------------
    private val sfSpot = HashMap<String, ShockFav.SpotRing>()
    private val sfBasis = HashMap<String, Double>()

    /** 0.3.58: feed the sf spot ring from the spot stream every second (not only on book ticks), like the backtest. */
    fun tickSpot(coin: String, spot: Double?, nowMs: Long) = synchronized(lock) {
        if (spot != null && spot.isFinite() && spot > 0.0) sfSpot.getOrPut(coin) { ShockFav.SpotRing() }.put(nowMs / 1000L, spot)
    }

    /**
     * One conflated quote → all twenty sf57 variants (paper). Called from the paper lane (never Main). Cheap: one scan
     * of this ticker's active sf trades, O(1) spot-ring lookups per variant (multi-shock: ≤ 30).
     */
    /** sf exit rule without the stop (shared by live exits and the no-stop shadow). Maker target handled by the caller. */
    private fun sfNoStopExit(p: ShockFav.Params, entry: Double, heldS: Double, tauS: Double, bid: Double, sideFair: Double): String? = when (p.kind) {
        ShockFav.Kind.SHOCK_FAV -> ShockFav.shockExit(p, heldS, tauS, bid, sideFair, entry)
        ShockFav.Kind.LAGGARD -> when {
            bid >= entry + p.tp - 1e-9 -> "take profit +${Math.round(p.tp * 100)}¢"
            tauS < 30.0 -> "τ < 30 s"; heldS >= p.maxHoldS -> "max hold ${p.maxHoldS}s"; else -> null }
        ShockFav.Kind.IMBALANCE -> when {
            p.tpNet && (bid - entry) - (ScalpRule.orderFee(ShockFav.CLIP, bid) + ScalpRule.orderFee(ShockFav.CLIP, entry)) / ShockFav.CLIP > p.tp + 1e-9 -> "net take profit > ${Math.round(p.tp * 100)}¢"
            tauS < 30.0 -> "τ < 30 s"; heldS >= p.maxHoldS -> "max hold ${p.maxHoldS}s"; else -> null }
        ShockFav.Kind.SIMPLE_DIP -> when {
            bid >= entry + p.tp - 1e-9 -> "take profit +${Math.round(p.tp * 100)}¢"
            tauS < 60.0 -> "60 s before close"; else -> null }
        ShockFav.Kind.OPEN50 -> ShockFav.o50Exit(p, entry, bid, bid, heldS, tauS)
        ShockFav.Kind.FADE_MAKER -> when { bid >= entry + p.tp + 0.01 - 1e-9 -> "maker target"; tauS < 45.0 -> "τ < 45 s"; heldS >= p.maxHoldS -> "max hold ${p.maxHoldS}s"; else -> null }
    }

    // 0.3.64 Open 50 state: YES mid at first quote after open, peak side bid per trade.
    private val o50OpenMid = HashMap<String, Double>()
    private val o50Peak = HashMap<String, Double>()

    /** Current window's move, checked again before buying a delayed uptick signal. */
    private fun openingDirection(q: ScalpRule.Quote): String? {
        val open = o50OpenMid[q.ticker.uppercase()] ?: return null
        val bid = q.yesBid ?: return null
        val ask = q.yesAsk ?: return null
        return ShockFav.o50UptickSide(open, (bid + ask) / 2)
    }

    /** Open 50 AI features (see Open50Model), side-signed. Null without spot data. */
    private fun o50Features(side: String, q: ScalpRule.Quote, sec: Long, sNow: Double, strike: Double, basis: Double, sig: Double, openMid: Double): DoubleArray? {
        val g = if (side == "YES") 1.0 else -1.0
        val b = sfSpot["BTC"] ?: return null
        val ya = q.yesAsk ?: return null; val yb = q.yesBid ?: return null
        val ask = q.ask(side) ?: return null; val bid = q.bid(side) ?: return null
        val r = { ring: ShockFav.SpotRing?, y: Int -> ring?.let { ShockFav.moveBp(it, sec, y) } ?: 0.0 }
        val bq = q.yesBidSize ?: 0.0; val aq = q.yesAskSize ?: 0.0
        val im = if (bq + aq > 0) (bq - aq) / (bq + aq) else 0.0
        return doubleArrayOf(g * r(b, 1), g * r(b, 5), g * r(b, 15), g * r(b, 60), sig / (ShockFav.MEDIAN_SIGMA["BTC"] ?: 2.654e-5),
            g * kotlin.math.ln(sNow * kotlin.math.exp(-basis) / strike) * 1e4, g * r(sfSpot["ETH"], 15), g * r(sfSpot["SOL"], 15), g * im,
            ask, ask - bid, g * ((ya + yb) / 2 - openMid), 900.0 - q.tauS)
    }

    /** 0.3.65: Open 50 price-only entries (uptick / both / maker), one per variant per side per window ticker. */
    private fun o50PriceEntries(q: ScalpRule.Quote, key: String, ya: Double, yb: Double): List<ScalpTrade> {
        if (!ShockFav.o50InEntryWindow(q.tauS)) return emptyList()
        val openMid = o50OpenMid[key] ?: return emptyList()
        val mine = _trades.value.filter { it.ticker == key && ShockFav.isO50(it.variantId) }
        val out = ArrayList<ScalpTrade>()
        for (p in ShockFav.VARIANTS) {
            if (p.kind != ShockFav.Kind.OPEN50 || p.o50 == ShockFav.O50.AI) continue
            val sides = ShockFav.o50Sides(p, openMid, (ya + yb) / 2)
            if (p.o50 == ShockFav.O50.UPTICK && mine.any { it.variantId.take(4) == p.code && it.state != ScalpState.NO_FILL }) continue
            for (sd in sides) {
                val vid = ShockFav.o50VariantId(p, sd)
                if (mine.any { it.variantId == vid && it.state != ScalpState.NO_FILL } || out.any { it.variantId == vid }) continue
                val ask = q.ask(sd)?.takeIf { it in 0.05..0.95 } ?: continue
                sfEnter(q, p, vid, sd, ask, 0.5, 0.0, Double.MAX_VALUE, "open 50")?.let {
                    out += if (p.o50 == ShockFav.O50.MAKER) it.copy(restingLimit = ShockFav.O50_MAKER_PX, restingSinceMs = q.nowMs) else it
                }
            }
        }
        return out
    }

    private val simpleHist = HashMap<String, ArrayDeque<Triple<Long, Double, Double>>>()

    /** Side whose ask sits ≥ p.dip below its own 5-min average ask (needs ≥ 4 min of history, τ ≥ 120 s, ask 5–55¢). */
    private fun simpleDipSide(key: String, p: ShockFav.Params, q: ScalpRule.Quote): String? {
        val h = simpleHist[key] ?: return null
        if (h.isEmpty() || q.nowMs - h.first().first < ShockFav.SIMPLE_MIN_HISTORY_MS || q.tauS < 120.0) return null
        val avgY = h.sumOf { it.second } / h.size
        val avgN = h.sumOf { it.third } / h.size
        return ShockFav.simpleDipSide(p, q.ask("YES"), avgY, q.ask("NO"), avgN)
    }

    fun onShockFavQuote(q: ScalpRule.Quote, enabled: Boolean): List<ScalpTrade> = synchronized(lock) {
        val yb = q.yesBid ?: return@synchronized emptyList()
        val ya = q.yesAsk ?: return@synchronized emptyList()
        if (ya <= yb) return@synchronized emptyList()
        val key = q.ticker.uppercase()
        val coin = ScalpParams.coinOf(q.ticker)
        val sec = q.nowMs / 1000L
        if (q.tauS in 0.0..900.0 && ScalpV2.fresh(q) && !o50OpenMid.containsKey(key)) o50OpenMid[key] = (ya + yb) / 2
        if (o50OpenMid.size > 200) o50OpenMid.keys.filter { (ScalpTicker.closeMs(it) ?: Long.MAX_VALUE) < q.nowMs }.forEach { o50OpenMid.remove(it) }
        // Cancel obsolete opening orders before any spot/σ early return. A new live side can signal immediately.
        if (ScalpV2.fresh(q)) {
            val direction = openingDirection(q)
            _trades.value.filter { it.ticker == key && it.state == ScalpState.PENDING_ENTRY &&
                ShockFav.byVariantId(it.variantId)?.o50 == ShockFav.O50.UPTICK && it.side != direction
            }.forEach { save(it.copy(state = ScalpState.NO_FILL, closedAtMs = q.nowMs,
                note = "${it.note}; opening direction changed before fill; wait for a new live signal")) }
        }
        // Open 50 (non-AI): fresh chance at EVERY 15-min open — needs only the book, not spot/σ/strike.
        val o50Early = if (enabled && q.nowMs < q.closeMs && ScalpV2.fresh(q) && coin == ShockFav.TRADE_COIN) o50PriceEntries(q, key, ya, yb) else emptyList()
        o50Early.forEach { save(it) }
        // 0.3.63 Simple dip: rolling 5-min side asks (price only), before any spot/fair early return.
        simpleHist.getOrPut(key) { ArrayDeque() }.let { h ->
            h.addLast(Triple(q.nowMs, ya, 1.0 - yb))
            while (h.isNotEmpty() && q.nowMs - h.first().first > ShockFav.SIMPLE_WINDOW_MS) h.removeFirst()
            while (h.size > 2_000) h.removeFirst()
        }
        if (simpleHist.size > ScalpMemory.MAX_TICKERS) simpleHist.keys.filter { it != key && (ScalpTicker.closeMs(it) ?: Long.MAX_VALUE) < q.nowMs }.forEach { simpleHist.remove(it) }
        val ring = sfSpot.getOrPut(coin) { ShockFav.SpotRing() }
        q.spot?.takeIf { it > 0.0 && it.isFinite() }?.let { ring.put(sec, it) }
        // 0.3.58: same fair as bt58 — 900 s 1 s-return σ from the ring, open basis to strike, raw τ.
        val strike = q.strike?.takeIf { it > 0.0 && it.isFinite() } ?: return@synchronized emptyList()
        val sNow = ring.at(sec) ?: return@synchronized emptyList()
        val sig = ring.sigma900(sec) ?: return@synchronized emptyList()
        val basis = sfBasis.getOrPut(key) {
            val osec = (q.closeMs - 900_000L) / 1000L
            ring.at(osec)?.let { kotlin.math.ln(it / strike) } ?: 0.0
        }
        if (sfBasis.size > 200) sfBasis.keys.filter { (ScalpTicker.closeMs(it) ?: Long.MAX_VALUE) < q.nowMs }.forEach { sfBasis.remove(it) }
        val fairYes = ShockFav.fairYes(sNow, strike, sig, q.tauS, basis)
        stepShadows(q, fairYes)
        val mine = _trades.value.filter { it.ticker == key && ShockFav.isShockFav(it.variantId) }
        val giveUpMs = { t: ScalpTrade -> if (ShockFav.byVariantId(t.variantId)?.let { it.kind == ShockFav.Kind.FADE_MAKER || it.o50 == ShockFav.O50.MAKER } == true) Long.MAX_VALUE else ShockFav.ENTRY_GIVE_UP_MS }
        val changed = ArrayList<ScalpTrade>()
        val live = q.nowMs < q.closeMs && q.fresh()
        // 0.3.58 delayed entry: fill at the first quote ≥ 2 s after the signal, at that ask, depth-capped.
        for (t in mine) {
            if (t.state != ScalpState.PENDING_ENTRY) continue
            val age = q.nowMs - t.signalAtMs
            if (q.nowMs >= q.closeMs || age > giveUpMs(t)) {
                changed += t.copy(state = ScalpState.NO_FILL, closedAtMs = q.nowMs, note = "${t.note}; no fill within ${ShockFav.ENTRY_GIVE_UP_MS / 1000}s"); continue
            }
            if (age < ShockFav.ENTRY_DELAY_MS || !q.fresh()) continue
            val pk = ShockFav.byVariantId(t.variantId)
            if (pk?.o50 == ShockFav.O50.UPTICK && openingDirection(q) != t.side) {
                changed += t.copy(state = ScalpState.NO_FILL, closedAtMs = q.nowMs,
                    note = "${t.note}; opening direction changed before fill; wait for a new live signal")
                continue
            }
            if (pk?.o50 == ShockFav.O50.MAKER) {
                // 0.3.64 Open 50 maker: resting 49¢ bid; filled only when the side ask trades THROUGH (≤ 48¢) — adverse-selection aware, fee 0.
                if (q.nowMs - t.signalAtMs > ShockFav.O50_MAKER_TTL_S * 1000L || q.tauS <= 60.0) {
                    changed += t.copy(state = ScalpState.NO_FILL, closedAtMs = q.nowMs, restingLimit = null, note = "${t.note}; 49¢ bid unfilled")
                } else {
                    val a = q.ask(t.side)
                    if (a != null && a <= ShockFav.O50_MAKER_PX - 0.01 + 1e-9)
                        changed += t.copy(state = ScalpState.OPEN, contracts = ShockFav.CLIP, entryPrice = ShockFav.O50_MAKER_PX, entryFeeUsd = 0.0, entryAtMs = q.nowMs,
                            note = String.format(Locale.US, "%s; maker filled %d @ 49¢ (ask traded through)", t.note, ShockFav.CLIP))
                }
                continue
            }
            if (pk?.kind == ShockFav.Kind.FADE_MAKER) {
                // 0.3.59 maker: post at the side's bid 2 s after the signal; filled only when the ask trades THROUGH (≤ px − 1¢).
                val px = t.restingLimit
                if (px != null && !ScalpRule.buyPriceOk(px)) { changed += cappedEntry(t, q.nowMs); continue }
                if (px == null) {
                    val b = q.bid(t.side)?.takeIf { it in 0.05..0.95 && ScalpRule.buyPriceOk(Math.round(it * 100) / 100.0) }
                    changed += if (b == null) t.copy(state = ScalpState.NO_FILL, closedAtMs = q.nowMs, note = "${t.note}; no bid to join")
                        else t.copy(restingLimit = Math.round(b * 100) / 100.0, restingSinceMs = q.nowMs, note = String.format(Locale.US, "%s; resting bid %.0f¢", t.note, b * 100))
                } else if (q.nowMs - (t.restingSinceMs ?: q.nowMs) > pk.ttlS * 1000L || q.tauS <= 60.0) {
                    changed += t.copy(state = ScalpState.NO_FILL, closedAtMs = q.nowMs, restingLimit = null, note = "${t.note}; maker bid unfilled")
                } else {
                    val a = q.ask(t.side)
                    if (a != null && a <= px - 0.01 + 1e-9)
                        changed += t.copy(state = ScalpState.OPEN, contracts = ShockFav.CLIP, entryPrice = px, entryFeeUsd = 0.0, entryAtMs = q.nowMs,
                            note = String.format(Locale.US, "%s; maker filled %d @ %.0f¢ (ask traded through)", t.note, ShockFav.CLIP, px * 100))
                }
                continue
            }
            val ask = q.ask(t.side) ?: continue
            val n = minOf(ShockFav.CLIP, kotlin.math.floor((q.askSize(t.side) ?: 0.0) + 1e-9).toInt())
            if (n <= 0) continue
            if (!ScalpRule.buyPriceOk(ask)) { changed += cappedEntry(t, q.nowMs); continue }
            changed += t.copy(state = ScalpState.OPEN, contracts = n, entryPrice = ask, entryFeeUsd = ScalpRule.orderFee(n, ask), entryAtMs = q.nowMs,
                note = String.format(Locale.US, "%s; filled %d @ %.0f¢ after %.1fs", t.note, n, ask * 100, age / 1000.0))
        }
        // exits first (shock legs; fav legs hold to settlement)
        for (t in mine) {
            if (t.state != ScalpState.OPEN && t.state != ScalpState.PENDING_EXIT) continue
            if (!live) continue
            val p = ShockFav.byVariantId(t.variantId) ?: continue
            val bid = q.bid(t.side)?.takeIf { it > 0.0 } ?: continue
            val sideFair = if (t.side == "YES") fairYes else 1.0 - fairYes
            val heldS = (q.nowMs - (t.entryAtMs ?: q.nowMs)) / 1000.0
            val favLeg = ShockFav.isFavLeg(t.variantId)
            // 0.3.61 per-bet stop on every sf leg (fav legs included): bid ≤ entry − stop.
            val stopC = ShockFav.stopC(p)
            val stopHit = t.state == ScalpState.OPEN && bid <= (t.entryPrice ?: 0.0) - stopC / 100.0 + 1e-9
            if (stopHit && t.isPrimary) {
                val ep = t.entryPrice ?: 0.0
                if (p.kind == ShockFav.Kind.OPEN50) {
                    var pk = o50Peak[t.id] ?: bid
                    addShadow(t, sf = true) { qq, h, b, _ -> pk = maxOf(pk, b); ShockFav.o50Exit(p, ep, pk, b, h, qq.tauS) != null }
                } else
                addShadow(t, sf = true) { qq, h, b, f -> if (favLeg) false else sfNoStopExit(p, ep, h, qq.tauS, b, f ?: b) != null }
            }
            if (favLeg && !stopHit && t.state == ScalpState.OPEN) continue
            if (p.kind == ShockFav.Kind.FADE_MAKER && t.state == ScalpState.OPEN) {
                val tgt = (t.entryPrice ?: 0.0) + p.tp
                if (bid >= tgt + 0.01 - 1e-9) { // resting ask at target traded through: maker exit, fee 0
                    changed += close(ScalpFills.sell(t, q.nowMs, t.remaining, t.remaining * tgt, 0.0), q.nowMs, "maker target +${Math.round(p.tp * 100)}¢")
                    continue
                }
            }
            val reason = t.exitReason.takeIf { t.state == ScalpState.PENDING_EXIT }
                ?: (if (stopHit) "stop −${stopC}¢" else null)
                ?: (if (p.kind == ShockFav.Kind.OPEN50) {
                    val pk = maxOf(o50Peak[t.id] ?: bid, bid).also { o50Peak[t.id] = it }
                    val ai = if (p.o50 == ShockFav.O50.AI) o50Features(t.side, q, sec, sNow, strike, basis, sig, o50OpenMid[key] ?: ((ya + yb) / 2))?.let { Open50Model.predict(it) } else null
                    ShockFav.o50Exit(p, t.entryPrice ?: 1.0, pk, bid, heldS, q.tauS, ai, t.contracts.coerceAtLeast(1))
                } else null)
                ?: (if (favLeg || p.kind == ShockFav.Kind.OPEN50) null else sfNoStopExit(p, t.entryPrice ?: 1.0, heldS, q.tauS, bid, sideFair)) ?: continue
            val n = minOf(t.remaining, kotlin.math.floor((q.bidSize(t.side) ?: 0.0) + 1e-9).toInt())
            val base = if (t.state == ScalpState.OPEN) t.copy(state = ScalpState.PENDING_EXIT, exitDecidedAtMs = q.nowMs, exitReason = reason) else t
            if (n <= 0) { if (base !== t) changed += base; continue }
            val next = ScalpFills.sell(base, q.nowMs, n, n * bid, ScalpRule.orderFee(n, bid))
            changed += if (next.remaining <= 0) close(next, q.nowMs, reason) else next
        }
        // 0.3.59: Scalp tab is Bitcoin only — sf entries on BTC markets only (other coins' spot is signal input).
        if (enabled && live && coin == ShockFav.TRADE_COIN) {
            val sigma: Double? = sig
            var openCost = openCostUsd()
            val bankroll = bankrollUsd()
            var brtiFair: Double? = null
            for (p in ShockFav.VARIANTS) {
                if (p.kind == ShockFav.Kind.OPEN50) {
                    if (p.o50 != ShockFav.O50.AI) continue // 0.3.65: handled in o50PriceEntries
                    // Open 50: one filled trade per market per side; observe from the first fresh opening quote.
                    if (!ShockFav.o50InEntryWindow(q.tauS)) continue
                    val openMid = o50OpenMid[key] ?: continue
                    val sides: List<String> = when (p.o50) {
                        ShockFav.O50.BOTH, ShockFav.O50.MAKER -> listOf("YES", "NO")
                        ShockFav.O50.UPTICK -> listOfNotNull(ShockFav.o50UptickSide(openMid, (ya + yb) / 2))
                        ShockFav.O50.AI -> listOf("YES", "NO").mapNotNull { sd ->
                            val a = q.ask(sd) ?: return@mapNotNull null; val b = q.bid(sd) ?: return@mapNotNull null
                            val x = o50Features(sd, q, sec, sNow, strike, basis, sig, openMid) ?: return@mapNotNull null
                            val net = Open50Model.predict(x) - (a - b) - (ScalpRule.orderFee(ShockFav.CLIP, a) + ScalpRule.orderFee(ShockFav.CLIP, b)) / ShockFav.CLIP
                            if (net > Open50Model.THRESHOLD) sd to net else null
                        }.maxByOrNull { it.second }?.let { listOf(it.first) } ?: emptyList()
                        null -> emptyList()
                    }
                    if (p.o50 != ShockFav.O50.BOTH && p.o50 != ShockFav.O50.MAKER && mine.any { it.variantId.take(4) == p.code }) continue
                    for (sd in sides) {
                        val vid = if (sd == "NO" && (p.o50 == ShockFav.O50.BOTH || p.o50 == ShockFav.O50.MAKER)) p.code + "n" else p.shockId
                        if (mine.any { it.variantId == vid }) continue
                        val ask = q.ask(sd)?.takeIf { it in 0.05..0.95 } ?: continue
                        val f = if (sd == "YES") fairYes else 1.0 - fairYes
                        val what = if (p.o50 == ShockFav.O50.AI) "open 50 · ${Open50Model.VERSION}" else "open 50"
                        sfEnter(q, p, vid, sd, ask, f, openCost, bankroll, what)?.let {
                            val tr = if (p.o50 == ShockFav.O50.MAKER) it.copy(restingLimit = ShockFav.O50_MAKER_PX, restingSinceMs = q.nowMs) else it
                            changed += tr; openCost += tr.entryCostUsd
                        }
                    }
                    continue
                }
                if (p.kind != ShockFav.Kind.SHOCK_FAV) {
                    if (mine.any { it.variantId == p.shockId && isActive(it) }) continue
                    val side = when (p.kind) {
                        ShockFav.Kind.LAGGARD -> ShockFav.laggardSide(p, coin, sfSpot, sec, q.tauS)?.takeIf { sd -> q.ask(sd)?.let { it in 0.10..0.90 } == true }
                        ShockFav.Kind.IMBALANCE -> ShockFav.imbalanceSide(p, yb, ya, q.yesBidSize ?: 0.0, q.yesAskSize ?: 0.0, q.tauS)?.takeIf { sd -> q.ask(sd)?.let { it in 0.10..0.90 } == true }
                        ShockFav.Kind.SIMPLE_DIP -> simpleDipSide(key, p, q)
                        ShockFav.Kind.FADE_MAKER -> if (q.tauS in 120.0..870.0) ShockFav.moveBp(ring, sec, 5)?.takeIf { kotlin.math.abs(it) >= p.xBp }?.let { if (it > 0) "NO" else "YES" } else null
                        else -> null
                    } ?: continue
                    val ask = q.ask(side) ?: continue
                    val f = if (side == "YES") fairYes else 1.0 - fairYes
                    sfEnter(q, p, p.shockId, side, ask, f, openCost, bankroll, p.kind.name.lowercase().replace('_', ' '))?.let { changed += it; openCost += it.entryCostUsd }
                    continue
                }
                if (p.shock && mine.none { it.variantId == p.shockId && isActive(it) }) {
                    val side = ShockFav.shockSide(p, coin, ring, sec, q.tauS, sigma)
                    if (side != null) {
                        val sideFair = if (side == "YES") fairYes else 1.0 - fairYes
                        val ask = q.ask(side)
                        if (ask != null && ShockFav.shockEntryOk(p, sideFair, ask))
                            sfEnter(q, p, p.shockId, side, ask, sideFair, openCost, bankroll, "shock")?.let { changed += it; openCost += it.entryCostUsd }
                    }
                }
                if (p.fav && mine.none { it.variantId == p.favId } && q.tauS <= p.favTmax && q.tauS >= p.favTmin) {
                    val fy = if (p.brti && sigma != null) {
                        brtiFair ?: run {
                            val m = (60.0 - q.tauS).toInt().coerceIn(0, 60)
                            val obs = (sec - m + 1..sec).mapNotNull { ring.at(it) }
                            ShockFav.brtiFairYes(sNow * kotlin.math.exp(-basis), strike, sigma, q.tauS, obs.map { it * kotlin.math.exp(-basis) }).also { brtiFair = it }
                        }
                    } else fairYes
                    for (side in listOf("YES", "NO")) {
                        val f = if (side == "YES") fy else 1.0 - fy
                        val ask = q.ask(side) ?: continue
                        if (!ShockFav.favEntryOk(p, f, ask, q.tauS, coin, q.closeMs)) continue
                        sfEnter(q, p, p.favId, side, ask, f, openCost, bankroll, "late fav")?.let { changed += it; openCost += it.entryCostUsd }
                        break
                    }
                }
            }
        }
        changed.forEach { save(it) }
        changed
    }

    /** Taker paper entry at the displayed ask, capped at displayed size; skipped when shared free cash can't cover it. */
    private fun sfEnter(q: ScalpRule.Quote, p: ShockFav.Params, vid: String, side: String, ask: Double, fair: Double,
                        openCost: Double, bankroll: Double, what: String): ScalpTrade? {
        // Fade makers choose their purchase bid after the delay; the post/fill paths enforce the ceiling.
        if (p.kind != ShockFav.Kind.FADE_MAKER && !ScalpRule.buyPriceOk(if (p.o50 == ShockFav.O50.MAKER) ShockFav.O50_MAKER_PX else ask)) return null
        val n = if (p.kind == ShockFav.Kind.FADE_MAKER) ShockFav.CLIP else minOf(ShockFav.CLIP, kotlin.math.floor((q.askSize(side) ?: 0.0) + 1e-9).toInt())
        if (n <= 0) return null
        val cost = ask * n + ScalpRule.orderFee(n, ask)
        if (p.kind != ShockFav.Kind.OPEN50 && openCost + cost > bankroll + 1e-9) return null // 0.3.65: Open 50 unlimited paper
        return ScalpTrade(
            id = idFactory(), ticker = q.ticker.uppercase(), side = side, state = ScalpState.PENDING_ENTRY, signalAtMs = q.nowMs,
            signalAsk = ask, fairAtSignal = fair,
            note = String.format(Locale.US, "%s %s · %s · fair %.0f¢ vs ask %.0f¢ · %s", p.code, p.label, what, fair * 100, ask * 100, ShockFav.VERSION),
            ruleVersion = "${ScalpRule.VERSION}|$vid|P"
        )
    }

    /** Re-tune when enough new closed round trips (all variants) have accumulated. Deterministic. */
    fun maybeTune(nowMs: Long, force: Boolean = false): ScalpTuneState = synchronized(lock) {
        val closed = _stats.value.sumOf { it.n }
        val cur = _tune.value
        if (!force && closed - cur.closedAtLastRun < ScalpTuner.RETUNE_EVERY) return cur
        // Tuner needs closed history; pull a bounded page per primary from SQLite when retuning.
        val hist = store.loadScalps().take(ScalpMemory.MAX_CLOSED)
        val next = ScalpTuner.tune(hist, cur, nowMs).copy(closedAtLastRun = closed)
        _tune.value = next
        runCatching { tuneStore.save(next) }
        next
    }

    private fun step(t0: ScalpTrade, q: ScalpRule.Quote, p: ScalpParams): ScalpTrade? {
        val marked = markAdverse(t0, q)
        val t = marked ?: t0
        val out = stepInner(t, q, p)
        return out ?: marked
    }

    /** 0.3.43: record side mid − entry 30 s and 60 s after the entry fill (adverse selection), once each. */
    private fun markAdverse(t: ScalpTrade, q: ScalpRule.Quote): ScalpTrade? {
        if (t.state != ScalpState.OPEN && t.state != ScalpState.PENDING_EXIT) return null
        val e = t.entryAtMs ?: return null
        val px = t.entryPrice ?: return null
        val bid = q.bid(t.side) ?: return null
        val ask = q.ask(t.side) ?: return null
        val mid = (bid + ask) / 2.0
        val age = q.nowMs - e
        return when {
            t.adverse30 == null && age >= 30_000L -> (mid - px).let { t.copy(adverse30 = it, note = t.note + String.format(Locale.US, " · AS30 %+.1f¢", it * 100)) }
            t.adverse60 == null && t.adverse30 != null && age >= 60_000L -> (mid - px).let { t.copy(adverse60 = it, note = t.note + String.format(Locale.US, " · AS60 %+.1f¢", it * 100)) }
            else -> null
        }
    }

    /**
     * 0.3.43 conservative maker queue model for STRATEGY resting orders (manual paper limits keep the exact-touch rule):
     * fills only on a trade-through (the opposite side crosses our price, or our whole level is wiped) or once the
     * estimated queue ahead is consumed (only [ScalpRule.QUEUE_CONSUME_SHARE] of each level shrink counts).
     * [buy]: resting bid at [limit] on [side]. Returns (filled, updated trade).
     */
    private fun makerQueueStep(t: ScalpTrade, q: ScalpRule.Quote, limit: Double, buy: Boolean): Pair<Boolean, ScalpTrade> {
        val ourBest = if (buy) q.bid(t.side) else q.ask(t.side)
        val opp = if (buy) q.ask(t.side) else q.bid(t.side)
        val oppCrossed = opp != null && (if (buy) opp < limit - 1e-9 else opp > limit + 1e-9)
        val levelWiped = ourBest != null && (if (buy) ourBest < limit - 1e-9 else ourBest > limit + 1e-9)
        if (oppCrossed || levelWiped) return true to t
        if (ourBest == null || kotlin.math.abs(ourBest - limit) > 1e-9) return false to t
        val size = (if (buy) q.bidSize(t.side) else q.askSize(t.side)) ?: 0.0
        val prev = t.levelSize ?: size
        val consumed = t.queueConsumed + (prev - size).coerceAtLeast(0.0) * ScalpRule.QUEUE_CONSUME_SHARE
        val next = t.copy(queueConsumed = consumed, levelSize = size)
        return (consumed + 1e-9 >= (t.queueAhead ?: 0.0) && (t.queueAhead ?: 0.0) > 0.0) to next
    }

    private fun fillMakerEntry(t: ScalpTrade, q: ScalpRule.Quote): ScalpTrade? {
        if (!ScalpRule.buyPriceOk(t.restingLimit ?: t.signalAsk)) return cappedEntry(t, q.nowMs)
        if (q.bookAtMs <= t.signalAtMs) return null
        val limit = t.restingLimit ?: t.signalAsk
        if (q.nowMs - t.signalAtMs > ScalpRule.MAKER_ENTRY_TTL_MS || q.nowMs >= q.closeMs || !q.fresh()) {
            return t.copy(state = ScalpState.NO_FILL, closedAtMs = q.nowMs, note = "${t.note}; maker entry cancelled: unfilled / stale")
        }
        val (filled, next) = makerQueueStep(t, q, limit, buy = true)
        if (!filled) return next.takeIf { it != t }
        val fee = com.dirk.kalshiodds.signal.trade.KalshiFee.makerFee(ScalpRule.CONTRACTS, limit, com.dirk.kalshiodds.domain.CryptoMarkets.inferSeries(t.ticker))
        return next.copy(
            state = ScalpState.OPEN, contracts = ScalpRule.CONTRACTS, entryPrice = limit, entryFeeUsd = fee, entryAtMs = q.nowMs,
            restingLimit = null, note = "${t.note}; maker fill @ ${com.dirk.kalshiodds.domain.KalshiQuoteDisplay.formatPriceCents(limit)} (queue model)"
        )
    }

    private fun fillMakerExit(t: ScalpTrade, q: ScalpRule.Quote): ScalpTrade? {
        val limit = t.restingLimit ?: return fillExit(t, q)
        val since = t.restingSinceMs ?: t.exitDecidedAtMs ?: q.nowMs
        // Fall back to a taker exit at the bid when the maker exit has not filled in time or the window is ending.
        if (q.nowMs - since > ScalpRule.MAKER_EXIT_TTL_MS || q.tauS <= ScalpRule.TIME_STOP_S) {
            return t.copy(restingLimit = null, exitReason = "${t.exitReason} (maker exit unfilled → taker)")
        }
        if (!q.fresh() || q.nowMs >= q.closeMs) return null
        val (filled, next) = makerQueueStep(t, q, limit, buy = false)
        if (!filled) return next.takeIf { it != t }
        val n = t.remaining
        val fee = com.dirk.kalshiodds.signal.trade.KalshiFee.makerFee(n, limit, com.dirk.kalshiodds.domain.CryptoMarkets.inferSeries(t.ticker))
        return close(ScalpFills.sell(next, q.nowMs, n, n * limit, fee).copy(restingLimit = null),
            q.nowMs, "${t.exitReason} (maker exit)")
    }

    /** 0.3.61 hold60 sibling: only the stop (armed after 60 s held); otherwise hold to settlement. */
    private fun hold60Exit(t: ScalpTrade, q: ScalpRule.Quote, p: ScalpParams, entry: Double): ScalpRule.ExitReason? {
        val held = (q.nowMs - (t.entryAtMs ?: q.nowMs)) / 1000.0
        if (held < 60.0 || !q.fresh()) return null
        val bid = q.bid(t.side) ?: return null
        return if (bid <= entry - p.stop + 1e-9) ScalpRule.ExitReason.STOP else null
    }

    /**
     * 0.3.61 no-stop shadow: when a stop fires, keep evaluating that trade's own non-stop exit rule on later quotes
     * (hold-to-settle legs → settlement) for the full clip at the bid with taker fee. Result written into the closed
     * trade's note as "nostop=±x.xx" (no schema change). In memory only; an app restart drops unresolved shadows.
     */
    companion object ShadowLimits { const val MAX_SHADOWS = 500 }
    private class Shadow(val tradeId: String, val ticker: String, val side: String, val n: Int, val cost: Double, val entryAtMs: Long,
                         val sf: Boolean, val exit: (ScalpRule.Quote, Double, Double, Double?) -> Boolean) { var value: Double? = null }
    private val shadows = LinkedHashMap<String, Shadow>()
    internal fun shadowCount(): Int = synchronized(lock) { shadows.size }

    private fun addShadow(t: ScalpTrade, sf: Boolean, exit: (ScalpRule.Quote, Double, Double, Double?) -> Boolean) {
        if (t.contracts <= 0 || shadows.containsKey(t.id)) return
        shadows[t.id] = Shadow(t.id, t.ticker.uppercase(), t.side, t.contracts, t.entryCostUsd, t.entryAtMs ?: 0L, sf, exit)
        // 0.3.62: hard bound + drop shadows of windows that closed > 1 h ago without a settlement callback.
        val cutoff = System.currentTimeMillis() - 3_600_000L
        shadows.values.removeAll { (ScalpTicker.closeMs(it.ticker) ?: Long.MAX_VALUE) < cutoff }
        while (shadows.size > MAX_SHADOWS) shadows.remove(shadows.keys.first())
    }

    private fun stepShadows(q: ScalpRule.Quote, fairYes: Double?) {
        if (shadows.isEmpty()) return
        val key = q.ticker.uppercase()
        for (sh in shadows.values) {
            if (sh.value != null || sh.ticker != key || sh.sf != (fairYes != null) || !q.fresh() || q.nowMs >= q.closeMs) continue
            val bid = q.bid(sh.side)?.takeIf { it > 0.0 } ?: continue
            val heldS = (q.nowMs - sh.entryAtMs) / 1000.0
            val sideFair = fairYes?.let { if (sh.side == "YES") it else 1.0 - it }
            if (runCatching { sh.exit(q, heldS, bid, sideFair) }.getOrDefault(false)) sh.value = sh.n * bid - ScalpRule.orderFee(sh.n, bid) - sh.cost
        }
        applyShadows()
    }

    private fun applyShadows() {
        // 0.3.62: only index the ledger when something resolved (was a linear ledger scan per shadow per quote).
        if (shadows.values.none { it.value != null }) return
        val byId = _all.value.associateBy { x -> x.id }
        val it = shadows.values.iterator()
        while (it.hasNext()) {
            val sh = it.next(); val v = sh.value ?: continue
            val t = byId[sh.tradeId] ?: run { it.remove(); null } ?: continue
            if (t.state != ScalpState.CLOSED) continue
            if (!t.note.contains("nostop=")) save(t.copy(note = String.format(Locale.US, "%s · nostop=%+.2f", t.note, v)))
            it.remove()
        }
    }

    private fun cappedEntry(t: ScalpTrade, atMs: Long) = t.copy(
        state = ScalpState.NO_FILL, closedAtMs = atMs, restingLimit = null,
        note = "${t.note}; no fill: hard 55¢ buy-price ceiling")

    private fun stepInner(t: ScalpTrade, q: ScalpRule.Quote, p: ScalpParams): ScalpTrade? {
        return when (t.state) {
            ScalpState.PENDING_ENTRY -> when {
                p.maker -> fillMakerEntry(t, q)
                p.strategy == ScalpStrategy.CF_REPRICE -> fillRestingLimitEntry(t, q)
                else -> fillEntry(t, q)
            }
            ScalpState.OPEN -> {
                if (q.nowMs >= q.closeMs) return null
                val entry = t.entryPrice ?: return null
                val reason = (if (p.hold60) hold60Exit(t, q, p, entry)
                else if (p.strategy == ScalpStrategy.CF_REPRICE)
                    ScalpRule.cfRepriceExit(t.side, entry, t.entryAtMs, q, p)
                else ScalpRule.exitSignal(t.side, entry, q, p)) ?: return null
                if (reason == ScalpRule.ExitReason.STOP && t.isPrimary) addShadow(t, sf = false) { qq, _, _, _ ->
                    if (p.hold60) false
                    else if (p.strategy == ScalpStrategy.CF_REPRICE) ScalpRule.cfRepriceExit(t.side, entry, t.entryAtMs, qq, p.copy(stop = 9.0)) != null
                    else ScalpRule.exitSignal(t.side, entry, qq, p.copy(stop = 9.0)) != null }
                val urgent = reason == ScalpRule.ExitReason.STOP || reason == ScalpRule.ExitReason.TIME_STOP || reason == ScalpRule.ExitReason.CF_EXIT_BY_CLOSE
                val makerAsk = if (p.maker && !urgent) q.ask(t.side) else null
                t.copy(
                    state = ScalpState.PENDING_EXIT, exitDecidedAtMs = q.nowMs, exitReason = reason.label,
                    restingLimit = makerAsk, restingSinceMs = makerAsk?.let { q.nowMs },
                    queueAhead = makerAsk?.let { q.askSize(t.side) ?: 0.0 }, queueConsumed = 0.0, levelSize = makerAsk?.let { q.askSize(t.side) }
                )
            }
            ScalpState.PENDING_EXIT -> if (t.restingLimit != null) fillMakerExit(t, q) else fillExit(t, q)
            else -> null
        }
    }

    /** Next fresh book ≥ latency after the signal: fill at that ask if ≤ limit and size covers the clip. One try. */
    private fun fillEntry(t: ScalpTrade, q: ScalpRule.Quote): ScalpTrade? {
        if (q.nowMs - t.signalAtMs < ScalpRule.LATENCY_MS || q.bookAtMs <= t.signalAtMs) return null
        val miss = { why: String -> t.copy(state = ScalpState.NO_FILL, closedAtMs = q.nowMs, note = "${t.note}; no fill: $why") }
        if (!q.fresh() || q.nowMs >= q.closeMs) return miss("stale book")
        val ask = q.ask(t.side) ?: return miss("no ask")
        if (!ScalpRule.buyPriceOk(ask)) return cappedEntry(t, q.nowMs)
        if (ask > t.signalAsk + ScalpRule.SLIPPAGE + 1e-9) return miss(String.format(Locale.US, "ask moved to %s", com.dirk.kalshiodds.domain.KalshiQuoteDisplay.formatPriceCents(ask)))
        val size = q.askSize(t.side) ?: 0.0
        if (size + 1e-9 < ScalpRule.CONTRACTS) return miss("displayed size ${size.toInt()} < ${ScalpRule.CONTRACTS}")
        return t.copy(
            state = ScalpState.OPEN,
            contracts = ScalpRule.CONTRACTS,
            entryPrice = ask,
            entryFeeUsd = ScalpRule.orderFee(ScalpRule.CONTRACTS, ask),
            entryAtMs = q.nowMs
        )
    }

    /**
     * 0.3.43 CF-reprice resting limit entry at the signal ask (touch-fill paper rule, PaperLimitFill): fills at exactly
     * the limit as soon as a later book shows ask ≤ limit, up to the displayed depth (partial allowed); cancelled
     * unfilled after [ScalpRule.CF_LIMIT_TTL_MS]. Touch fills are slightly optimistic vs real queue priority.
     */
    private fun fillRestingLimitEntry(t: ScalpTrade, q: ScalpRule.Quote): ScalpTrade? {
        if (!ScalpRule.buyPriceOk(t.signalAsk)) return cappedEntry(t, q.nowMs)
        if (q.bookAtMs <= t.signalAtMs) return null
        val miss = { why: String -> t.copy(state = ScalpState.NO_FILL, closedAtMs = q.nowMs, note = "${t.note}; limit cancelled: $why") }
        if (q.nowMs - t.signalAtMs > ScalpRule.CF_LIMIT_TTL_MS) return miss("unfilled after 1 s")
        if (!q.fresh() || q.nowMs >= q.closeMs) return miss("stale book")
        val ask = q.ask(t.side) ?: return null
        val n = com.dirk.kalshiodds.signal.paper.PaperLimitFill.touchFillQty(
            limit = t.signalAsk, best = ask, depth = q.askSize(t.side), remaining = ScalpRule.CONTRACTS, buy = true
        )
        if (n <= 0) return null
        return t.copy(
            state = ScalpState.OPEN,
            contracts = n,
            entryPrice = t.signalAsk,
            entryFeeUsd = ScalpRule.orderFee(n, t.signalAsk),
            entryAtMs = q.nowMs,
            note = "${t.note}; resting limit filled $n ct @ ${com.dirk.kalshiodds.domain.KalshiQuoteDisplay.formatPriceCents(t.signalAsk)} (touch fill, optimistic vs queue)"
        )
    }

    /** Sell what the displayed bid size allows on a fresh book ≥ latency after the decision; rest waits. */
    private fun fillExit(t: ScalpTrade, q: ScalpRule.Quote): ScalpTrade? {
        val decided = t.exitDecidedAtMs ?: return null
        if (q.nowMs - decided < ScalpRule.LATENCY_MS || q.bookAtMs <= decided) return null
        if (!q.fresh() || q.nowMs >= q.closeMs) return null
        val bid = q.bid(t.side)?.takeIf { it > 0.0 } ?: return null
        val size = kotlin.math.floor((q.bidSize(t.side) ?: 0.0) + 1e-9).toInt()
        val n = minOf(size, t.remaining)
        if (n <= 0) return null
        val next = ScalpFills.sell(t, q.nowMs, n, n * bid, ScalpRule.orderFee(n, bid))
        return if (next.remaining <= 0) close(next, q.nowMs, next.exitReason) else next
    }

    private fun close(t: ScalpTrade, atMs: Long, reason: String?): ScalpTrade {
        o50Peak.remove(t.id)
        while (o50Peak.size > 500) o50Peak.remove(o50Peak.keys.first())
        val net = t.proceedsUsd - t.exitFeeUsd - t.entryCostUsd
        return t.copy(state = ScalpState.CLOSED, closedAtMs = atMs, netUsd = net, exitReason = reason)
    }

    /** Settlement: anything still held pays $1 / $0, no fee; pending entries become no-fills. */
    fun settle(ticker: String, result: String, atMs: Long = System.currentTimeMillis()): List<ScalpTrade> = synchronized(lock) {
        val r = result.lowercase()
        val out = _all.value.filter {
            it.ticker.equals(ticker, true) && it.state != ScalpState.CLOSED && it.state != ScalpState.NO_FILL
        }.map { t ->
            if (t.state == ScalpState.PENDING_ENTRY) {
                t.copy(state = ScalpState.NO_FILL, closedAtMs = atMs, note = "${t.note}; no fill: window closed")
            } else {
                val voided = r != "yes" && r != "no"
                val pay = when {
                    voided -> t.entryPrice ?: 0.0
                    t.side.equals(r, true) -> 1.0
                    else -> 0.0
                }
                close(
                    ScalpFills.sell(t, atMs, t.remaining, t.remaining * pay, 0.0, settlement = true),
                    atMs,
                    if (t.state == ScalpState.PENDING_EXIT) "${t.exitReason} → ${ScalpRule.ExitReason.SETTLED.label}" else ScalpRule.ExitReason.SETTLED.label
                )
            }
        }
        out.forEach { save(it) }
        val voided = r != "yes" && r != "no"
        for (sh in shadows.values) if (sh.value == null && sh.ticker.equals(ticker, true)) {
            val t = _all.value.firstOrNull { it.id == sh.tradeId }
            val pay = when { voided -> t?.entryPrice ?: 0.0; sh.side.equals(r, true) -> 1.0; else -> 0.0 }
            sh.value = sh.n * pay - sh.cost
        }
        applyShadows()
        out.filter { it.isPrimary }
    }

    /** 0.3.56 reinstall restore: add closed round trips whose id is unknown. */
    fun importRestored(rows: List<ScalpTrade>): Int = synchronized(lock) {
        val have = _all.value.map { it.id }.toHashSet()
        val add = rows.filter { it.id !in have }.distinctBy { it.id }
        add.forEach { save(it) }
        add.size
    }

    private fun save(t0: ScalpTrade) {
        val prev = _all.value.firstOrNull { it.id == t0.id }
        val t = if (liveExtras) withDetail(t0, prev) else t0
        // 0.3.67: enqueue DB+stats off the quote lock; flushBatches drains on a worker.
        writeQ.merge(t.id, t to prev) { old, neu -> neu.first to (old.second ?: neu.second) }
        if (writeQ.size >= 80) flushWrites() // 0.3.67: never let the queue grow unboundedly (soak / burst quotes)
        if (prev == null && liveExtras) spawnV2(t)
        val cur = _all.value
        val idx = cur.indexOfFirst { it.id == t.id }
        _all.value = when {
            idx >= 0 -> cur.toMutableList().also { it[idx] = t }
            else -> listOf(t) + cur // closed rows stay only until their window ends (pruneClosed)
        }
        if (t.isPrimary) {
            val pc = _trades.value
            val pi = pc.indexOfFirst { it.id == t.id }
            _trades.value = when {
                pi >= 0 -> pc.toMutableList().also { it[pi] = t }
                else -> listOf(t) + pc
            }
        }
    }

    /** 0.3.67: forget closed / no-fill rows once their 15-min window is over (+60 s). History is in SQLite. */
    private fun pruneClosed(nowMs: Long) {
        fun keep(t: ScalpTrade) = isActive(t) || (ScalpTicker.closeMs(t.ticker) ?: ((t.closedAtMs ?: t.signalAtMs) + 900_000L)) > nowMs - 60_000L
        val a = _all.value
        if (a.any { !keep(it) }) { _all.value = a.filter { keep(it) }; _trades.value = _trades.value.filter { keep(it) } }
    }

    /** Drain pending upserts (call off Main / off the quote lock). Returns rows written. */
    fun flushWrites(): Int = synchronized(flushLock) {
        if (writeQ.isEmpty()) return@synchronized 0
        val batch = ArrayList<Pair<ScalpTrade, ScalpTrade?>>(writeQ.size)
        // Conditional removal never discards a newer update queued while we drain.
        for ((id, pair) in writeQ.entries) if (writeQ.remove(id, pair)) batch += pair
        var written = 0
        for (pair in batch) {
            try {
                store.upsertScalpAndStats(pair.first, pair.second)
                val (t, prev) = pair
                if (ScalpV2.isV2(t.variantId) && t.state == ScalpState.CLOSED && prev != null && prev.state != ScalpState.CLOSED)
                    controls.recordOutcome(ScalpModels.modelOf(t)?.code ?: t.variantId, t)
                written++
            } catch (e: Exception) {
                // Preserve the newest row but retain the first transition for retry/learning.
                writeQ.merge(pair.first.id, pair) { newer, failed -> newer.first to (failed.second ?: newer.second) }
            }
        }
        if (written > 0) {
            _stats.value = store.loadStats()
            if (liveExtras) {
                v2Evidence = store.loadWindows(48)
                // Never take the quote lock here: save() may flush while holding it.
                val before = controlDaily.get()
                if (before.first.isNotEmpty()) controlDaily.compareAndSet(before,
                    before.first to store.loadDaily(before.first).filter { it.scalperId.startsWith("v2-") }.sumOf { it.netUsd })
            }
        }
        written
    }

    fun reloadStats() { _stats.value = store.loadStats() }
    /** 0.3.68 aggregate reads (≈ hundreds of rows, indexed). Call off Main. */
    fun loadExits() = store.loadExits()
    fun loadWindows(limitWindows: Int = 48) = store.loadWindows(limitWindows)
    fun loadDaily(dayEt: String) = store.loadDaily(dayEt)
    fun pageTrades(scalperId: String, afterClosedAtMs: Long? = null, afterId: String? = null, limit: Int = 50) =
        store.pageTrades(scalperId, afterClosedAtMs, afterId, limit)
    fun windowTrades(scalperId: String, ticker: String) = store.windowTrades(scalperId, ticker)
    fun windowPrimaryTrades(ticker: String) = store.windowPrimaryTrades(ticker)
    fun unsyncedClosed(limit: Int) = store.unsyncedClosed(limit)
    fun markSynced(ids: List<String>) = store.markSynced(ids)
    fun migrateStatsChunk(limit: Int = 500): Int = store.migrateStatsChunk(limit).also { if (it > 0) _stats.value = store.loadStats() }
    fun tradeCount(): Int = store.tradeCount()
    fun statsMigrated(): Boolean = store.statsMigrated()

    // ---------------- 0.3.65 trade detail + v2 copies ----------------
    /** Peak side bid since entry, per open trade (all scalpers; v2 exits use it). */
    private val controlDaily = java.util.concurrent.atomic.AtomicReference("" to 0.0)
    private val peakBid = HashMap<String, Double>()
    private val v2Armed = HashSet<String>()

    private fun bookStr(q: ScalpRule.Quote?, side: String): String {
        if (q == null) return "n/a"
        return String.format(Locale.US, "yb=%.2f×%.0f ya=%.2f×%.0f bid=%.2f ask=%.2f spot=%s strike=%s fair=%s tau=%.0fs",
            q.yesBid ?: Double.NaN, q.yesBidSize ?: 0.0, q.yesAsk ?: Double.NaN, q.yesAskSize ?: 0.0,
            q.bid(side) ?: Double.NaN, q.ask(side) ?: Double.NaN, q.spot?.let { "%.2f".format(Locale.US, it) } ?: "n/a",
            q.strike?.let { "%.2f".format(Locale.US, it) } ?: "n/a", q.fair(side)?.let { "%.3f".format(Locale.US, it) } ?: "n/a", q.tauS)
    }

    /** Appends entry / exit book snapshots, peak bid and stop level to the persisted note (once each). */
    private fun withDetail(t: ScalpTrade, prev: ScalpTrade?): ScalpTrade {
        val q = _marks.value[t.ticker.uppercase()]
        var note = ScalpFills.orderNote(t, prev)
        if (t.state == ScalpState.OPEN && prev?.state != ScalpState.OPEN && !note.contains("entryBook[")) {
            note += " · entryBook[" + bookStr(q, t.side) + "]"
            t.entryPrice?.let { peakBid[t.id] = if (ScalpV2.peakRule(t)) q?.bid(t.side)?.takeIf { b -> b.isFinite() && b > 0 } ?: 0.0 else q?.bid(t.side) ?: it }
        }
        if (t.state == ScalpState.CLOSED && prev?.state != ScalpState.CLOSED && !note.contains("exitBook[")) {
            val stop = if (ScalpV2.peakRule(t)) ScalpV2.riskLabel(t) else ScalpModels.modelOf(t)?.let { ScalpModels.stopLabel(it) } ?: "n/a"
            note += " · exitBook[" + bookStr(q, t.side) + "]" + String.format(Locale.US, " · peakBid=%s · stopLevel=%s · fees=%.2f",
                peakBid[t.id]?.let { "%.2f".format(Locale.US, it) } ?: "n/a", stop, t.entryFeeUsd + t.exitFeeUsd)
            peakBid.remove(t.id); v2Armed.remove(t.id)
        }
        while (peakBid.size > 2_000) peakBid.remove(peakBid.keys.first())
        return if (note === t.note) t else t.copy(note = note)
    }

    /** New primary signal of an original (BTC, not v2, model has a v2 copy) → a v2 pending entry with the same side. */
    private val strategyEntryChecks = HashMap<String, Long>()
    private val pendingEntryChecks = HashMap<String, Long>()

    private fun spawnV2(t: ScalpTrade) {
        if (!t.isPrimary || ScalpV2.isV2(t.variantId) || t.coin != ShockFav.TRADE_COIN) return
        if (t.state != ScalpState.PENDING_ENTRY && t.state != ScalpState.OPEN) return
        val m = ScalpModels.modelOf(t) ?: return
        ScalpModels.v2Of(m) ?: return
        // 0.3.68: plain v2 copy + (for sf41–44) tweak and size copies; each carries its own params in the note.
        for (seed in listOf(ScalpV2.spec(ScalpV2.v2Id(t.variantId))) + ScalpV2.extrasFor(t.variantId)) {
            val model = ScalpModels.modelOf(t.copy(ruleVersion = "${ScalpRule.VERSION}|${seed.id}|P"))
            // A closed contract is not complete evidence while this model still awaits an exit/settlement.
            val unfinished = _all.value.asSequence().filter { isActive(it) && ScalpModels.modelOf(it) == model }
                .map { it.windowKey }.toSet()
            val cfg = controls.state.value
            if (model == null || !cfg.permits(model.code)) continue
            val learned = ScalpV2.plan(seed.id, if (cfg.adaptive && !cfg.manual) v2Evidence else emptyList(), t.signalAtMs, model.code, unfinished)
            val feedback = controls.feedback(model.code)
            // Every completed trade updates this EWMA; completed-window evidence still controls promotion.
            val informed = if (learned.adjusted && feedback.trades > 0 && feedback.recentNetPerContract < 0)
                learned.copy(arm = (learned.arm + feedback.recentNetPerContract * .1).coerceIn(.01, learned.arm)) else learned
            val knobs = cfg.settings(model.code)
            val close = ScalpTicker.closeMs(t.ticker)
            if (close != null && !knobs.timeOk(t.signalAtMs, close)) continue
            val checkKey = "${t.ticker}|${model.code}|${t.side}"
            val lastCheck = strategyEntryChecks[checkKey]
            if (lastCheck != null && t.signalAtMs - lastCheck < knobs.cadenceSeconds * 1000L) continue
            strategyEntryChecks[checkKey] = t.signalAtMs
            if (strategyEntryChecks.size > 2000) strategyEntryChecks.keys.filter { (ScalpTicker.closeMs(it.substringBefore('|')) ?: 0) < t.signalAtMs }.forEach { strategyEntryChecks.remove(it) }
            val plan = cfg.plan(informed, model.code)
            val sp = if (cfg.peakTrailEnabled) plan.spec.copy(trail = (knobs.trailCents ?: cfg.peakTrailCents) / 100.0)
                else plan.spec.copy(trail = knobs.trailCents?.div(100.0) ?: plan.spec.trail)
            val stopPct = knobs.stopLossPercent ?: cfg.stopLossPercent
            val stop = plan.stop
            save(ScalpTrade(id = idFactory(), ticker = t.ticker.uppercase(), side = t.side, state = ScalpState.PENDING_ENTRY,
                signalAtMs = t.signalAtMs, signalAsk = t.signalAsk, fairAtSignal = t.fairAtSignal,
                note = String.format(Locale.US, "v2 copy of %s (%s)%s · ask ≤ %.0f¢ · trail %.0f¢ · clip %d%s · v2stop=%.4f",
                    m.label, t.variantId, if (sp.tag.isNotEmpty()) " " + sp.tag else "", sp.maxAsk * 100, sp.trail * 100, sp.clip,
                    if (sp.walk) " depth-walk" else "", stop) +
                    String.format(Locale.US, " · v2ask=%.4f · v2trail=%.4f · v2arm=%.4f · %s", sp.maxAsk, sp.trail, plan.arm, plan.note + "; learned ${feedback.trades} closed; recent net/ct ${feedback.recentNetPerContract}") +
                    String.format(Locale.US, " · v2tp=%.4f · v2hold=%d · v2clip=%d", (knobs.takeProfitCents ?: if (cfg.manual) cfg.takeProfitCents else 0) / 100.0, knobs.maxHoldSeconds ?: cfg.maxHoldSeconds, sp.clip) +
                    (if (cfg.peakTrailEnabled) " · v2peakrule=1 · v2stopPct=$stopPct · exit policy: $stopPct% below fill / ${Math.round(sp.trail * 100)}¢ below bid peak, active from entry" else "") +
                    " · v2min=${knobs.minEntryCents / 100.0} · v2until=${knobs.untilMinute} · v2sidecap=${knobs.maxOpenPerSide} · v2cadence=${knobs.cadenceSeconds}",
                ruleVersion = "${ScalpRule.VERSION}|${sp.id}|P"))
        }
    }

    /** V2 paper lane: frozen per-entry adaptive params, protective stops, delayed exits and near-close re-evaluation. */
    private fun stepV2(q: ScalpRule.Quote): List<ScalpTrade> {
        val key = q.ticker.uppercase()
        val out = ArrayList<ScalpTrade>()
        val cfg = controls.state.value
        val day = ScalpModels.dayEt(q.nowMs)
        if (controlDaily.get().first != day) controlDaily.set(day to
            if (liveExtras) store.loadDaily(day).filter { it.scalperId.startsWith("v2-") }.sumOf { it.netUsd } else 0.0)
        val controlDailyNet = controlDaily.get().second
        val unflushedNet = writeQ.values.map { it.first to it.second }.filter { (t, prev) ->
            ScalpV2.isV2(t.variantId) && t.state == ScalpState.CLOSED && prev?.state != ScalpState.CLOSED &&
                ScalpModels.dayEt(t.closedAtMs ?: 0) == day }.sumOf { it.first.netUsd ?: 0.0 }
        var openCount = _all.value.count { ScalpV2.isV2(it.variantId) && it.state in listOf(ScalpState.OPEN, ScalpState.PENDING_EXIT) }
        val openByModelSide = _all.value.asSequence().filter {
            it.ticker == key && ScalpV2.isV2(it.variantId) && it.state in listOf(ScalpState.OPEN, ScalpState.PENDING_EXIT)
        }.groupingBy { "${ScalpModels.modelOf(it)?.code ?: it.variantId}|${it.side}" }.eachCount().toMutableMap()
        for (t in _all.value) {
            if (t.ticker != key || !ScalpV2.isV2(t.variantId) || !isActive(t)) continue
            when (t.state) {
                ScalpState.PENDING_ENTRY -> {
                    val age = q.nowMs - t.signalAtMs
                    val code = ScalpModels.modelOf(t)?.code ?: t.variantId
                    val blocked = cfg.entryBlock(code, openCount, controls.entries(q.nowMs), controlDailyNet + unflushedNet)
                    if (blocked != null) { out += t.copy(state = ScalpState.NO_FILL, closedAtMs = q.nowMs, note = "${t.note}; $blocked"); continue }
                    if (q.tauS <= ScalpV2.EXIT_BY_S || age > ScalpV2.GIVE_UP_MS) { out += t.copy(state = ScalpState.NO_FILL, closedAtMs = q.nowMs, note = "${t.note}; no fill: final minute, expired signal, or no executable entry"); continue }
                    val until = ScalpV2.savedNumber(t.note, "v2until", 14.0).toInt().coerceIn(1, 14)
                    if (q.nowMs >= q.closeMs - 900_000 + until * 60_000L) {
                        out += t.copy(state = ScalpState.NO_FILL, closedAtMs = q.nowMs, note = "${t.note}; entry minute limit reached"); continue
                    }
                    val sideCap = ScalpV2.savedNumber(t.note, "v2sidecap", 1.0).toInt().coerceIn(1, 20)
                    val sideKey = "$code|${t.side}"
                    if ((openByModelSide[sideKey] ?: 0) >= sideCap) continue
                    if (age < ScalpV2.ENTRY_DELAY_MS || !ScalpV2.fresh(q) || q.bookAtMs <= t.signalAtMs) continue
                    val cadenceMs = ScalpV2.savedNumber(t.note, "v2cadence", 0.0).coerceIn(0.0, 30.0).toLong() * 1000
                    val lastCheck = pendingEntryChecks[t.id]
                    if (lastCheck != null && q.nowMs - lastCheck < cadenceMs) continue
                    pendingEntryChecks[t.id] = q.nowMs
                    if (pendingEntryChecks.size > 2000) {
                        val activeIds = _all.value.filter { it.state == ScalpState.PENDING_ENTRY }.map { it.id }.toSet()
                        pendingEntryChecks.keys.retainAll(activeIds)
                    }
                    val seed = ScalpV2.spec(t.variantId)
                    if (ShockFav.byVariantId(seed.base)?.o50 == ShockFav.O50.UPTICK && openingDirection(q) != t.side) {
                        out += t.copy(state = ScalpState.NO_FILL, closedAtMs = q.nowMs,
                            note = "${t.note}; opening direction changed before fill; wait for a new live signal")
                        continue
                    }
                    val sp = seed.copy(maxAsk = minOf(ScalpRule.ENTRY_HI, ScalpV2.savedNumber(t.note, "v2ask", seed.maxAsk)),
                        clip = ScalpV2.savedNumber(t.note, "v2clip", seed.clip.toDouble()).toInt().coerceIn(1, 1000))
                    val ask = q.ask(t.side) ?: continue
                    val minAsk = ScalpV2.savedNumber(t.note, "v2min", .01).coerceIn(.01, ScalpRule.ENTRY_HI)
                    if (ask < minAsk - 1e-9 || !ScalpV2.entryOk(ask, sp.maxAsk)) continue
                    if (cfg.dipCents > 0) {
                        val historyPrices = history[key].orEmpty().filter { it.atMs in (q.nowMs - cfg.windowSeconds * 1000L)..q.nowMs }
                            .groupBy { it.atMs / 1000L }.values.map { it.maxBy { p -> p.atMs } }.sortedBy { it.atMs }
                            .mapNotNull { if (t.side == "YES") it.yesBid else it.yesAsk?.let { a -> 1 - a } }
                        if (!cfg.dipOk(historyPrices, ask)) continue
                    }
                    if (sp.walk) {
                        // 0.3.68: lift real ask levels up to max ask; partial fill allowed, avg price gets worse with depth.
                        val (n, usd, fee) = cfg.walkBuy(q.askLadder(t.side).filter { it.first >= minAsk - 1e-9 }, sp.clip, sp.maxAsk)
                        if (n <= 0) continue
                        val avg = usd / n
                        controls.recordEntry(q.nowMs); openCount++; openByModelSide[sideKey] = (openByModelSide[sideKey] ?: 0) + 1
                        out += t.copy(state = ScalpState.OPEN, contracts = n, entryPrice = avg, entryFeeUsd = fee, entryAtMs = q.nowMs,
                            note = ScalpV2.entryRiskNote(String.format(Locale.US, "%s; walked book: filled %d/%d avg %.2f¢ (top %.0f¢) after %.1fs", t.note, n, sp.clip, avg * 100, ask * 100, age / 1000.0), q.bid(t.side)))
                        continue
                    }
                    val n = minOf(cfg.affordableContracts(ask, sp.clip), kotlin.math.floor((q.askSize(t.side) ?: 0.0) + 1e-9).toInt())
                    if (n <= 0) continue
                    controls.recordEntry(q.nowMs); openCount++; openByModelSide[sideKey] = (openByModelSide[sideKey] ?: 0) + 1
                    out += t.copy(state = ScalpState.OPEN, contracts = n, entryPrice = ask, entryFeeUsd = ScalpRule.orderFee(n, ask), entryAtMs = q.nowMs,
                        note = ScalpV2.entryRiskNote(String.format(Locale.US, "%s; filled %d @ %.0f¢ after %.1fs", t.note, n, ask * 100, age / 1000.0), q.bid(t.side)))
                }
                ScalpState.OPEN, ScalpState.PENDING_EXIT -> {
                    if (q.nowMs >= q.closeMs) continue
                    val maxHold = ScalpV2.savedNumber(t.note, "v2hold", 0.0)
                    val holdExpired = maxHold > 0 && q.nowMs - (t.entryAtMs ?: t.signalAtMs) >= maxHold * 1000
                    if (!ScalpV2.fresh(q)) {
                        // Time-based risk decisions do not require liquidity; execution always does.
                        if (t.state == ScalpState.OPEN && (q.tauS <= ScalpV2.EXIT_BY_S || holdExpired))
                            out += t.copy(state = ScalpState.PENDING_EXIT, exitDecidedAtMs = q.nowMs,
                                exitReason = if (holdExpired) "V2 user max hold reached" else "v2 exit before close: stale or gapped book")
                        continue
                    }
                    val bid = q.bid(t.side)?.takeIf { it.isFinite() && it > 0.0 }
                    val entry = t.entryPrice ?: continue
                    val stop = ScalpV2.savedStop(t)
                    val pk = maxOf(peakBid[t.id] ?: 0.0, ScalpV2.savedNumber(t.note, "v2peak", 0.0), bid ?: 0.0)
                        .also { peakBid[t.id] = it }
                    val sp = ScalpV2.spec(t.variantId)
                    val trail = ScalpV2.savedNumber(t.note, "v2trail", sp.trail).coerceIn(0.01, 0.20)
                    val arm = ScalpV2.savedNumber(t.note, "v2arm", stop).coerceIn(0.01, 0.99)
                    val wasArmed = t.id in v2Armed || ScalpV2.savedNumber(t.note, "v2armed", 0.0) == 1.0
                    // Reconstruct arming from the persisted peak, including after process death.
                    val armed = ScalpV2.peakRule(t) || wasArmed || ScalpV2.netPerContract(t, pk) >= arm - 1e-9
                    if (armed) v2Armed += t.id
                    val note = ScalpV2.tag(ScalpV2.tag(t.note, "v2peak", String.format(Locale.US, "%.6f", pk)), "v2armed", if (armed) "1" else "0")
                    val marked = t.copy(note = note)
                    val target = ScalpV2.savedNumber(t.note, "v2tp", 0.0)
                    val timeExit = if (holdExpired) "V2 user max hold reached" else null
                    val priceExit = bid?.let {
                        (if (ScalpV2.peakRule(t)) ScalpV2.peakExit(entry, ScalpV2.stopPercent(t), pk, it, trail)
                        else ScalpV2.exit(entry, stop, pk, it, armed, t.contracts, trail, armThreshold = arm, netGain = ScalpV2.netPerContract(t, it)))
                            ?: if (target > 0 && ScalpV2.netPerContract(t, it) >= target - 1e-9) "V2 user net take-profit" else null
                    }
                    val reason = t.exitReason.takeIf { t.state == ScalpState.PENDING_EXIT } ?: priceExit ?: timeExit ?: ScalpV2.closeExit(q, t.side, entry, t.contracts)
                    if (reason == null) { if (marked != t) out += marked; continue }
                    val base = if (t.state == ScalpState.OPEN) marked.copy(state = ScalpState.PENDING_EXIT, exitDecidedAtMs = q.nowMs, exitReason = reason) else marked
                    val decided = base.exitDecidedAtMs ?: q.nowMs
                    val lastFill = ScalpV2.savedNumber(t.note, "v2fillbook", 0.0).toLong()
                    // Trigger first; sell only on a later executable book. Repeated callbacks cannot refill depth.
                    if (bid == null || q.nowMs - decided < ScalpV2.EXIT_DELAY_MS || q.bookAtMs <= maxOf(decided, lastFill)) {
                        if (base != t) out += base
                        continue
                    }
                    val next = if (sp.walk) {
                        // 0.3.68: hit real bid levels down the book (any price — it is an exit); partial = rest next quote.
                        var left = t.remaining; var usd = 0.0; var fee = 0.0; var n = 0
                        for ((px, sz) in q.bidLadder(t.side)) { if (left <= 0 || px <= 0.0) break; val k = minOf(left, kotlin.math.floor(sz + 1e-9).toInt()); if (k > 0) { usd += k * px; fee += ScalpRule.orderFee(k, px); n += k; left -= k } }
                        if (n <= 0) { if (base !== t) out += base; continue }
                        ScalpFills.sell(base, q.nowMs, n, usd, fee)
                    } else {
                        val n = minOf(t.remaining, kotlin.math.floor((q.bidSize(t.side) ?: 0.0) + 1e-9).toInt())
                        if (n <= 0) { if (base !== t) out += base; continue }
                        ScalpFills.sell(base, q.nowMs, n, n * bid, ScalpRule.orderFee(n, bid))
                    }
                    val filled = next.copy(note = ScalpV2.tag(next.note, "v2fillbook", q.bookAtMs.toString()))
                    out += if (filled.remaining <= 0) close(filled, q.nowMs, reason) else filled
                }
                else -> {}
            }
        }
        return out
    }

    private class SpotVol {
        private val pts = ArrayDeque<Pair<Long, Double>>()
        fun add(atMs: Long, px: Double) {
            val last = pts.lastOrNull()
            if (last != null && atMs <= last.first) return
            pts.addLast(atMs to px)
            while (pts.isNotEmpty() && atMs - pts.first().first > 900_000L) pts.removeFirst()
        }
        fun sigmaPerSec(): Double? {
            if (pts.size < 10) return null
            val span = (pts.last().first - pts.first().first) / 1000.0
            if (span < 120.0) return null
            var ss = 0.0
            var prev = pts.first()
            for (i in 1 until pts.size) {
                val cur = pts.elementAt(i)
                val r = ln(cur.second / prev.second)
                ss += r * r
                prev = cur
            }
            val s = sqrt(ss / span)
            return s.takeIf { it.isFinite() && it > 0.0 }
        }
    }
}

/** Closed-scalp stats and the ladder bar: 300 round trips with a window-clustered 95% CI lower bound > 0. */
object ScalpStats {
    data class Summary(
        val roundTrips: Int,
        val noFills: Int,
        val open: Int,
        val netUsd: Double,
        val winRate: Double?,
        val avgNetPerContractCents: Double?,
        val avgHoldS: Double?,
        val ci: ClusteredBootstrap.Ci?
    )

    fun summary(trades: List<ScalpTrade>): Summary {
        val closed = trades.filter { it.state == ScalpState.CLOSED && it.netUsd != null }
        val ci = ClusteredBootstrap.meanCi(closed.map { it.windowKey to (it.netUsd!! / it.contracts.coerceAtLeast(1)) })
        return Summary(
            roundTrips = closed.size,
            noFills = trades.count { it.state == ScalpState.NO_FILL },
            open = trades.count { it.state == ScalpState.OPEN || it.state == ScalpState.PENDING_EXIT || it.state == ScalpState.PENDING_ENTRY },
            netUsd = closed.sumOf { it.netUsd!! },
            winRate = if (closed.isEmpty()) null else closed.count { it.netUsd!! > 0.0 }.toDouble() / closed.size,
            avgNetPerContractCents = if (closed.isEmpty()) null else closed.sumOf { it.netUsd!! / it.contracts.coerceAtLeast(1) } / closed.size * 100.0,
            avgHoldS = closed.mapNotNull { it.holdMs }.takeIf { it.isNotEmpty() }?.average()?.div(1000.0),
            ci = ci
        )
    }

    fun ladderItems(trades: List<ScalpTrade>): List<StrategyLadder.Item> =
        trades.filter { it.state == ScalpState.CLOSED && it.netUsd != null }.map {
            StrategyLadder.Item(cluster = it.windowKey, filled = true, settled = true, pnlUsd = it.netUsd!! / it.contracts.coerceAtLeast(1))
        }
}


/**
 * 0.3.50: in-memory cap for the scalp ledger (the DB keeps every row). 0.3.49 kept every trade ever in RAM and
 * re-copied the whole list on each save — with event-driven evals that grew without bound.
 */
object ScalpMemory {
    const val MAX_CLOSED = 6_000
    const val MAX_NO_FILL = 1_000
    const val MAX_TICKERS = 48
    /** Trim only when over by 5 % so a save is O(n) copy, not a trim every time. */
    fun boundIfNeeded(list: List<ScalpTrade>): List<ScalpTrade> =
        if (list.size > (MAX_CLOSED + MAX_NO_FILL) * 105 / 100) bound(list) else list

    /** Keeps every active row, the newest [MAX_CLOSED] closed and newest [MAX_NO_FILL] no-fills; order preserved. */
    fun bound(newestFirst: List<ScalpTrade>): List<ScalpTrade> {
        var closed = 0
        var nofill = 0
        return newestFirst.filter {
            when (it.state) {
                ScalpState.CLOSED -> ++closed <= MAX_CLOSED
                ScalpState.NO_FILL -> ++nofill <= MAX_NO_FILL
                else -> true
            }
        }
    }
}
