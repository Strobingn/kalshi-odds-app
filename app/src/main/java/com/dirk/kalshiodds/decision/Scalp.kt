package com.dirk.kalshiodds.decision

import com.dirk.kalshiodds.signal.trade.KalshiFee
import java.util.Locale
import java.util.UUID
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
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
    /** Kept for older notes. Paper entries do not wait on this. */
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
    /** Kept for older notes. Paper entries are not capped per window. */
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

    /** Taker fee for one order of [contracts] at [price], rounded up to the cent once (dollars). */
    fun orderFee(contracts: Int, price: Double): Double {
        if (contracts <= 0 || !price.isFinite() || price <= 0.0 || price >= 1.0) return 0.0
        return KalshiFee.total(contracts, price)
    }

    /** One order swept across prices. Ceil once, not once per level. */
    fun sweepFee(lots: List<Pair<Double, Int>>): Double =
        KalshiFee.sweepFee(lots.map { (px, n) -> n to px })

    fun feePerContract(contracts: Int, price: Double): Double =
        if (contracts <= 0) 0.0 else orderFee(contracts, price) / contracts

    /** Round-trip cost per contract (dollars): spread + fee in + fee out at the same mid. */
    fun roundTripCost(price: Double, spread: Double, contracts: Int = CONTRACTS): Double =
        spread + feePerContract(contracts, price) + feePerContract(contracts, price)

    /** Shrink a clip by 20%, rounded to the nearest contract. Never integer-truncates `contracts * 4 / 5`. */
    fun downsizeContracts(contracts: Int, min: Int = 1): Int {
        val floor = min.coerceAtLeast(1)
        if (contracts <= floor) return floor
        return (contracts * 0.8).roundToInt().coerceAtLeast(floor)
    }

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
        if (x == 0.0) return 0.5
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
        val noBidsDesc: List<Pair<Double, Double>> = emptyList(),
        /** Running settlement average (final-minute window when Kalshi publishes it). */
        val settleAvg: Double? = null
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
        val cands = sides.mapNotNull { side ->
            val ask = q.ask(side) ?: return@mapNotNull null
            val fair = q.fair(side) ?: return@mapNotNull null
            val px = if (p.maker) q.bid(side) else ask
            val lesson = !p.maker && FairGapTune.lessonAllowed(q, side, ask, fair, CONTRACTS)
            if (ask < ENTRY_LO - 1e-9 || px == null || (!buyPriceOk(px) && !lesson)) return@mapNotNull null
            Entry(side, ask, fair, fair - ask - feePerContract(CONTRACTS, ask))
        }
        val best = SidePick.onlyMaxBy(cands) { it.gapAfterFee }
            ?: return null to if (cands.isEmpty()) "no spot fair value" else "exact tie"
        val chop = p.strategy == ScalpStrategy.FAIR_GAP && FairGapTune.chopDiscount(p.id, q, best.ask, best.fair, CONTRACTS)
        if (!chop && best.gapAfterFee + 1e-9 < p.minGap) {
            return null to String.format(Locale.US, "gap %.1f¢ under %.0f¢", best.gapAfterFee * 100, p.minGap * 100)
        }
        // 0.3.40: expected move (fair − ask) must beat spread + entry fee + exit fee.
        val move = best.fair - best.ask
        val cost = spread + feePerContract(CONTRACTS, best.ask) + feePerContract(CONTRACTS, best.fair.coerceIn(0.01, 0.99))
        if (!chop && move <= cost + 1e-9) {
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
            val px = if (p.maker) q.bid(side) else ask
            val lesson = !p.maker && FairGapTune.lessonAllowed(q, side, ask, fair, CONTRACTS)
            val move: Double = when (p.strategy) {
                ScalpStrategy.DIP_HUNTER -> {
                    if (ask < ENTRY_LO - 1e-9 || px == null || (!buyPriceOk(px) && !lesson)) return@mapNotNull null
                    val ref = past.mapNotNull { it.ask(side) }.maxOrNull() ?: return@mapNotNull null
                    val drop = ref - ask
                    // Spot must not explain the drop: fair still at or above the ask.
                    if (drop + 1e-9 < p.minGap || fair + 1e-9 < ask) return@mapNotNull null
                    drop * 0.5 // half retrace
                }
                ScalpStrategy.MOMENTUM -> {
                    if (ask < ENTRY_LO - 1e-9 || px == null || (!buyPriceOk(px) && !lesson)) return@mapNotNull null
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
        val best = SidePick.onlyMaxBy(cands) { it.e.gapAfterFee }
            ?: return null to if (cands.isEmpty()) "no ${p.strategy.label} trigger" else "exact tie"
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
        val cands = sides.mapNotNull { side ->
            val ask = q.ask(side) ?: return@mapNotNull null
            if (ask < ENTRY_LO - 1e-9 || !buyPriceOk(if (p.maker) q.bid(side) else ask)) return@mapNotNull null
            val fair = q.cfFair(side) ?: return@mapNotNull null
            val entryFee = feePerContract(CONTRACTS, ask)
            Entry(side, ask, fair, fair - ask - entryFee)
        }
        val best = SidePick.onlyMaxBy(cands) { it.gapAfterFee }
            ?: return null to if (cands.isEmpty()) "no CF fair value" else "exact tie"
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
        GAP_CLOSED("fair-value exit: bid crossed fair after the exit fee"),
        TURN_DOWN("turn-down: fair fell below entry"),
        PROFIT_TARGET("profit target hit"),
        STOP("stop hit"),
        TIME_STOP("hard exit: 60 s left"),
        SETTLED("held to settlement (no exit liquidity)"),
        FAIR_GAP_HOLD("fair-gap rule: hold time done (1.0.3)")
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
            // Fair-value exit, not a take-profit: the bid has crossed fair after the exit fee.
            // That can be a loss when fair fell. The profit target is [ExitReason.PROFIT_TARGET].
            if (fair != null && bid - feePerContract(CONTRACTS, bid) >= fair - 1e-12) return ExitReason.GAP_CLOSED
        }
        if (fair != null && fair <= entry - p.turnDown + 1e-12) return ExitReason.TURN_DOWN
        return null
    }

    fun rulesText(): String =
        "Scalp $VERSION (PAPER). Live board: ${KeptScalpers.ALL.joinToString(", ") { it.id }}. " +
            "Removed scalpers do not run and are not listed on the Scalp tab, in settings, or on the arena. " +
            "Past trades of a removed scalper stay in history as \"${KeptScalpers.REMOVED_LABEL}\". " +
            "Nothing in this rule places a live order. $BACKTEST_LABEL"
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
    val algoLabel: String get() = ScalpModels.labelFor(variantId)
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
    /** Default book runs only the kept fair-gap param. Tests may pass an explicit list. */
    private val variants: List<ScalpParams> = emptyList(),
    private val tuneStore: ScalpTuneStore = InMemoryScalpTuneStore(),
    /** Paper bankroll: total open primary scalp cost (entries + fees, pending included) never exceeds it. */
    private val bankrollUsd: () -> Double = { com.dirk.kalshiodds.signal.config.SignalConstants.PAPER_START_USD },
    /** 0.3.65 app-only extras: v2 copies + entry/exit detail notes (off in legacy unit-test books). */
    private val liveExtras: Boolean = false,
    private val controls: ScalpControlsRepository = MemoryScalpControls(),
    private val idFactory: () -> String = { UUID.randomUUID().toString() }
) {
    init {
        runCatching { ModelFilter.retrainFrom(store.loadScalps(), System.currentTimeMillis()) }
    }
    private val lock = Any()
    private val hft = HftBook()
    private val fleet = TwentyScalperFleet.FleetBook()
    private val gates = ScalpGateLog()
    /** Close time last seen on a quote. Wins over the ticker stamp when a test pins closeMs. */
    private val closeSeen = HashMap<String, Long>()

    /** Last blocking gate for one scalper, for the Scalp tab. Null when the latest tick entered. */
    fun gateLine(code: String, windowKey: String? = null): String? = gates.line(code, windowKey)

    fun gateSnapshot(): List<ScalpGateLog.SyncRow> = gates.snapshot(KeptScalpers.IDS + ScalpVariants.IDS)

    /** Book or spot is missing. Every live scalper shows the same outage reason until a fresh tick. */
    fun noteOutage(tickerOrWindow: String) {
        val key = if (tickerOrWindow.contains('-')) ScalpModels.windowKeyOf(tickerOrWindow) else tickerOrWindow
        gates.noteOutage(key.ifBlank { "outage" }, KeptScalpers.IDS + ScalpVariants.IDS)
    }
    // 0.3.67: only open/pending rows live in RAM; closed history stays in SQLite + scalp_stats.
    private val _all = MutableStateFlow(store.loadActiveScalps().sortedByDescending { it.signalAtMs })
    private val _trades = MutableStateFlow(_all.value.filter { it.isPrimary })
    private val _stats = MutableStateFlow(store.loadStats())
    @Volatile private var v2Evidence = if (liveExtras) store.loadWindows(48) else emptyList()
    val stats: StateFlow<List<ScalpStat>> = _stats.asStateFlow()

    // 0.3.69 "green size": a scalper that is net positive after fees (≥ GREEN_MIN_TRADES closes) may walk the
    // book, but never above its own clip. greenContracts is not a second clip.
    private var greenSrc: List<ScalpStat>? = null
    private var greenIds: Set<String> = emptySet()
    fun isGreen(scalperId: String?): Boolean {
        val st = _stats.value
        if (st !== greenSrc) { greenSrc = st; greenIds = st.filter { it.n >= GREEN_MIN_TRADES && it.netUsd > 0.0 }.map { it.scalperId }.toSet() }
        return scalperId != null && scalperId in greenIds
    }
    /** One in-app setting: contracts a net-positive scalper buys (book-depth fills). */
    private val greenClip: Int get() = controls.state.value.greenContracts.coerceIn(1, 500)
    private fun greenOf(t: ScalpTrade): Boolean = isGreen(ScalpModels.scalperIdOf(t))
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
            ScalpState.PENDING_ENTRY -> {
                val n = if (t.contracts > 0) t.contracts else ScalpRule.CONTRACTS
                t.signalAsk * n + ScalpRule.orderFee(n, t.signalAsk)
            }
            ScalpState.OPEN, ScalpState.PENDING_EXIT -> (t.entryPrice ?: 0.0) * t.remaining + t.entryFeeUsd
            else -> 0.0
        }
    }

    /** Open cost of one model's primary scalps (its bankroll slice in use). */
    fun openCostUsd(model: ScalpModels.Model): Double = _trades.value.filter { isActive(it) && ScalpModels.modelOf(it) == model }.sumOf(::activeCost)

    private fun activeCost(t: ScalpTrade): Double = when (t.state) {
        ScalpState.PENDING_ENTRY -> {
            val n = if (t.contracts > 0) t.contracts else ScalpRule.CONTRACTS
            t.signalAsk * n + ScalpRule.orderFee(n, t.signalAsk)
        }
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
        closeSeen[q.ticker.uppercase()] = q.closeMs
        expireOrphansLocked(q.nowMs)
        if (q.nowMs / 15_000L != (_lastPruneBucket)) { _lastPruneBucket = q.nowMs / 15_000L; pruneClosed(q.nowMs) }
        _marks.value = (_marks.value + (q.ticker.uppercase() to q)).let { m ->
            if (m.size <= ScalpMemory.MAX_TICKERS) m else m.filterValues { it.closeMs > q.nowMs - 60_000L || it.ticker.equals(q.ticker, true) }
        }
        val changed = ArrayList<ScalpTrade>()
        val coin = ScalpParams.coinOf(q.ticker)
        val primary = paramsFor(coin)
        // Kept roster: BTC fair-gap g14-t12-s08-d15-w300 only. Other strategy primaries no longer open.
        val fairKept = if (coin == ShockFav.TRADE_COIN) ScalpParams.byId(KeptScalpers.FAIR_ID) else null
        // A variant list that includes the tuned primary (fill tests) keeps that primary. The app passes none.
        val pinned = paramsFor(coin).takeIf { p -> variants.any { it.id == p.id } }
        val primaries = listOfNotNull(pinned ?: fairKept)
        val primaryIds = primaries.map { it.id }.toSet()
        val key = q.ticker.uppercase()
        val recent = recordHistory(q)
        stepShadows(q, null)
        val mineAll = _all.value.filter { it.ticker.equals(q.ticker, true) }
        // Step every open scalp first (one per market side per variant).
        // 0.3.65: peak bid for every open trade (stored on close) + v2 lane.
        mineAll.forEach { t -> if ((!ScalpV2.isV2(t.variantId) || ScalpV2.fresh(q)) && (t.state == ScalpState.OPEN || t.state == ScalpState.PENDING_EXIT)) q.bid(t.side)?.takeIf { it > 0.0 }?.let { b -> peakBid[t.id] = maxOf(peakBid[t.id] ?: b, b) } }
        changed += stepV2(q)
        if (liveExtras) changed += stepModelTwins(q)
        changed += stepResearch(q, enabled)
        // 1.0.2: Dirk's manual sliders on original strategies (stop % below fill, take profit after fees, max hold).
        val overlay = settingsExits(mineAll, q); changed += overlay.values
        val mineCur = if (overlay.isEmpty()) mineAll else mineAll.map { overlay[it.id] ?: it }
        mineCur.filter { isActive(it) && !ForecastScalp.isForecast(it) && !ScalpV2.isV2(it.variantId) && !Research.isResearch(it.variantId) && !ModelFilter.isTwin(it.variantId) && !HftMathEngines.isHft(it.variantId) && !TwentyScalperFleet.isFleet(it.variantId) }.forEach { t ->
            // 0.3.69 fix: sf rows have their own lane (entries + TP/stop/60 s exits). They used to also fall through here with
            // the generic primary params, whose profit-target / fair take-profit / turn-down exits fired first, so the sf
            // take-profit never decided (sf41≡sf42, sf43≡sf44). Here they now only get the adverse-selection marks.
            if (ShockFav.isShockFav(t.variantId)) { markAdverse(t, q)?.let { changed += it }; return@forEach }
            val baseP = ScalpParams.byId(t.variantId) ?: primary
            val p = tuned(baseP, ScalpModels.modelOf(t)?.code)
            step(t, q, p)?.let { changed += it }
        }
        if (!q.fresh() || q.bookGap || q.yesBid == null || q.yesAsk == null) {
            gates.noteOutage(ScalpModels.windowKeyOf(q.ticker).ifBlank { "outage" }, KeptScalpers.IDS + ScalpVariants.IDS)
        } else if (enabled) {
            val run = (primaries + variants).distinctBy { it.id }
            // 0.3.50: per-model slice usage computed once per quote from active rows (was a full-ledger scan per candidate).
            val sliceUsed = HashMap<ScalpModels.Model, Double>()
            _trades.value.forEach { t -> if (isActive(t)) ScalpModels.modelOf(t)?.let { m -> sliceUsed[m] = (sliceUsed[m] ?: 0.0) + activeCost(t) } }
            changed.forEach { t -> if (t.isPrimary && t.state == ScalpState.PENDING_ENTRY && t.signalAtMs == q.nowMs) ScalpModels.modelOf(t)?.let { m -> sliceUsed[m] = (sliceUsed[m] ?: 0.0) + activeCost(t) } }
            var openCost = openCostUsd() + changed.filter { it.isPrimary && it.state == ScalpState.PENDING_ENTRY && it.signalAtMs == q.nowMs && !shadowEntry(it) }
                .sumOf { it.signalAsk * ScalpRule.CONTRACTS + ScalpRule.orderFee(ScalpRule.CONTRACTS, it.signalAsk) }
            for (p in run) {
                val modelCode = ScalpModels.modelOf(p).code
                if (com.dirk.kalshiodds.decision.insights.ScalpFreeze.blocksRestored(modelCode, controls.state.value.benchOverrides[modelCode])) continue
                val mine = mineAll.filter { it.variantId == p.id }.map { old -> changed.firstOrNull { it.id == old.id } ?: old }
                if (q.nowMs >= q.closeMs) continue
                val window = ScalpModels.windowKeyOf(q.ticker)
                // A new clip on every trigger. An open or in-flight clip does not block the next one.
                val free = listOf("YES", "NO")
                val (sig, skip) = ScalpRule.strategySignal(q, tuned(p, ScalpModels.modelOf(p).code), free, recent)
                if (sig == null) { clearTrigger(p.id, q.ticker); gates.block(modelCode, window, skip) }
                if (sig != null && (ScalpRule.buyPriceOk(if (p.maker) q.bid(sig.side) else sig.ask) || FairGapTune.lessonAllowed(q, sig.side, sig.ask, sig.fair, ScalpRule.CONTRACTS, variantId = p.id, open = _all.value))) {
                    val role = if (p.id in primaryIds) "P" else "S"
                    if (!triggerIsNew(p.id, q.ticker, sig.side)) {
                        gates.block(modelCode, window, "same trigger still on")
                        continue
                    }
                    if (role == "P" && !shadowCode(ScalpModels.modelOf(p).code)) { // 1.0.14: frozen/benched entries reserve nothing
                        val cost = sig.ask * ScalpRule.CONTRACTS + ScalpRule.orderFee(ScalpRule.CONTRACTS, sig.ask)
                        if (openCost + cost > bankrollUsd() + 1e-9) {
                            gates.block(modelCode, window, "paper cash spent")
                            continue
                        }
                        // 0.3.50: each of the six models has its own bankroll slice (independent ledger).
                        val model = ScalpModels.modelOf(p)
                        // 0.3.54: no per-model cap — all scalpers share the one free paper bankroll.
                        sliceUsed[model] = (sliceUsed[model] ?: 0.0) + cost
                        openCost += cost
                    }
                    val makerBid = if (p.maker) q.bid(sig.side)?.takeIf { it > 0.0 }?.let { touch ->
                        HftSignals.restingBid(touch, q.ask(sig.side), q.tauS, 0.0)
                    } else null
                    if (p.maker && makerBid == null) continue
                    armTrigger(p.id, q.ticker, sig.side)
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
                        contracts = ScalpRule.CONTRACTS,
                        note = String.format(Locale.US, "%s · edge %.1f¢ after fees · %s", p.strategy.label, sig.gapAfterFee * 100, p.id) +
                            (if (p.maker) String.format(Locale.US, " · maker post @ %s, queue ahead %.0f", com.dirk.kalshiodds.domain.KalshiQuoteDisplay.formatPriceCents(makerBid!!), q.bidSize(sig.side) ?: 0.0) else ""),
                        ruleVersion = "${ScalpRule.VERSION}|${p.id}|$role"
                    )
                    gates.entered(modelCode, window)
                } else if (sig != null) {
                    clearTrigger(p.id, q.ticker)
                    gates.block(modelCode, window, "ask above 55¢ ceiling")
                }
            }
            if (liveExtras && coin == ShockFav.TRADE_COIN) {
                val variantFair = enterFairVariants(q, recent, openCost)
                changed += variantFair.first
                openCost = variantFair.second
            }
            if (liveExtras && coin == ShockFav.TRADE_COIN) enterKeptV2(q, recent)
            if (liveExtras && coin == ShockFav.TRADE_COIN) changed += enterModelFair(q, recent)
        }
        if (liveExtras) {
            val hftCoin = ScalpParams.coinOf(q.ticker)
            if (hftCoin == "BTC" || hftCoin == "ETH" || hftCoin == "SOL") {
                changed += hft.onQuote(q, bankrollUsd(), idFactory)
                DosMmEngine.publish(hft.telemetry())
            }
            changed += fleet.onQuote(q, bankrollUsd(), idFactory)
        }
        changed.forEach { save(it) }
        unlatchClosed(changed)
        if (changed.any { it.state == ScalpState.CLOSED }) maybeTune(q.nowMs)
        changed.filter { it.isPrimary }
    }

    /** Fair-gap, dip, and v2 nudges. Same fee-rounded fills as the parent. Open clips do not block the next signal. */
    private fun enterFairVariants(q: ScalpRule.Quote, recent: List<ScalpRule.Snap>, openCostIn: Double): Pair<List<ScalpTrade>, Double> {
        var openCost = openCostIn
        val out = ArrayList<ScalpTrade>()
        val window = ScalpModels.windowKeyOf(q.ticker)
        val bankroll = bankrollUsd()
        for (spec in ScalpVariants.ALL) {
            val p0 = spec.fair ?: continue
            if (spec.kind == ScalpVariants.Kind.FADE || spec.kind == ScalpVariants.Kind.SIMPLE || spec.kind == ScalpVariants.Kind.SHOCK || spec.kind == ScalpVariants.Kind.FAV) continue
            val p = if (spec.kind == ScalpVariants.Kind.V2_DIP) {
                p0.copy(minGap = maxOf(p0.minGap, ScalpV2.feeClearingDipGap(ScalpV2.DIP_EDGE_ASK, spec.clip)))
            } else p0
            val (sig, skip) = if (p.strategy == ScalpStrategy.FAIR_GAP) ScalpRule.entrySignal(q, p) else ScalpRule.strategySignal(q, p, listOf("YES", "NO"), recent)
            if (sig == null) { clearTrigger(spec.id, q.ticker); gates.block(spec.id, window, skip); continue }
            if (spec.kind == ScalpVariants.Kind.V2_DIP && FairGapTune.fallingKnife(sig.side, q.spot, q.strike, q.fair(sig.side), sig.ask)) {
                gates.block(spec.id, window, "lesson guard: falling knife")
                continue
            }
            val px = if (p.maker) q.bid(sig.side) else sig.ask
            if (px == null) { gates.block(spec.id, window, "no bid"); continue }
            if (!ScalpRule.buyPriceOk(px) && !FairGapTune.lessonAllowed(q, sig.side, sig.ask, sig.fair, spec.clip, variantId = spec.id, open = _all.value)) {
                gates.block(spec.id, window, "ask above 55¢ ceiling")
                continue
            }
            val n = spec.clip
            val cost = px * n + ScalpRule.orderFee(n, px)
            if (openCost + cost > bankroll + 1e-9) { gates.block(spec.id, window, "paper cash spent"); continue }
            if (!triggerIsNew(spec.id, q.ticker, sig.side)) { gates.block(spec.id, window, "same trigger still on"); continue }
            armTrigger(spec.id, q.ticker, sig.side)
            val v2 = spec.kind == ScalpVariants.Kind.V2_FAIR || spec.kind == ScalpVariants.Kind.V2_DIP
            val note = if (v2) {
                val saved = controls.state.value.settings(spec.id)
                val (minPx, maxPx) = ScalpParamSanity.v2Band(spec.id, saved.minEntryCents / 100.0, (saved.maxEntryCents ?: ScalpFloors.ceilingCents(spec.id)) / 100.0)
                val stopC = if (spec.kind == ScalpVariants.Kind.V2_DIP) ScalpV2.dipStopCents(spec.id) else 0
                String.format(Locale.US, "v2 variant of %s · v2ask=%.4f · v2trail=%.4f · v2clip=%d · v2peakrule=1 · v2stopC=%d · v2stopPct=20 · v2min=%.4f · v2until=14 · v2sidecap=20 · v2cadence=0", spec.parentId, maxPx, ScalpV2.TRAIL, n, stopC, minPx)
            } else String.format(Locale.US, "%s · variant %s", p.strategy.label, spec.id)
            out += ScalpTrade(
                id = idFactory(), ticker = q.ticker.uppercase(), side = sig.side, state = ScalpState.PENDING_ENTRY,
                signalAtMs = q.nowMs, signalAsk = px, fairAtSignal = sig.fair, contracts = n,
                restingLimit = if (p.maker) px else null, restingSinceMs = if (p.maker) q.nowMs else null,
                note = note, ruleVersion = "${ScalpRule.VERSION}|${spec.id}|P"
            )
            openCost += cost
            gates.entered(spec.id, window)
        }
        return out to openCost
    }

    /** Open the two kept v2 configs from their base signal. The losing original is not saved. */
    private fun enterKeptV2(q: ScalpRule.Quote, recent: List<ScalpRule.Snap>) {
        val bases = listOf(
            ScalpParams.byId(KeptScalpers.V2_DIP_BASE) to KeptScalpers.V2_DIP_ID,
            ScalpParams.byId(KeptScalpers.V2_FAIR_BASE) to KeptScalpers.V2_FAIR_ID
        )
        for ((raw, v2Id) in bases) {
            if (raw == null) continue
            val signalParams = if (v2Id == KeptScalpers.V2_DIP_ID) {
                raw.copy(minGap = maxOf(raw.minGap, ScalpV2.feeClearingDipGap(ScalpV2.DIP_EDGE_ASK, ScalpV2.CLIP)))
            } else raw
            val sig = if (signalParams.strategy == ScalpStrategy.FAIR_GAP) ScalpRule.entrySignal(q, signalParams).first
                else ScalpRule.strategySignal(q, signalParams, listOf("YES", "NO"), recent).first
            if (sig == null) continue
            if (v2Id == KeptScalpers.V2_DIP_ID && FairGapTune.fallingKnife(sig.side, q.spot, q.strike, q.fair(sig.side), sig.ask)) continue
            val px = if (raw.maker) q.bid(sig.side) ?: continue else sig.ask
            if (!ScalpRule.buyPriceOk(px) && !FairGapTune.lessonAllowed(q, sig.side, sig.ask, sig.fair, ScalpV2.CLIP, variantId = v2Id, open = _all.value)) continue
            spawnV2(ScalpTrade(
                id = "v2src-$v2Id", ticker = q.ticker.uppercase(), side = sig.side, state = ScalpState.PENDING_ENTRY,
                signalAtMs = q.nowMs, signalAsk = px, fairAtSignal = sig.fair, note = "kept v2 source ${raw.id}",
                ruleVersion = "${ScalpRule.VERSION}|${raw.id}|P"
            ))
        }
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
        val changed = ArrayList<ScalpTrade>()
        val openFc = _all.value.filter { it.ticker == key && ForecastScalp.isForecast(it) && isActive(it) }
        for (active in openFc) {
            val ride = active.variantId.startsWith(ForecastScalp.RIDE_PREFIX)
            val f = if (ride) pred else -pred
            fcStep(active, q, f, h54)?.let { changed += it }
        }
        // Kept fade is horizon 600 only. Forecast ride does not open.
        val predFade = x54?.let { LeadForecaster.predict(coin, KeptScalpers.FADE_HORIZON_S, it) }
        val fadeWindow = ScalpModels.windowKeyOf(q.ticker)
        if (!q.fresh()) gates.noteOutage(fadeWindow, listOf(KeptScalpers.FADE_ID) + ScalpVariants.ALL.filter { it.kind == ScalpVariants.Kind.FADE }.map { it.id })
        if (predFade != null && enabled && q.nowMs < q.closeMs && q.fresh()) {
            val f = -predFade
            val side = ForecastScalp.sideOf(f)
            val ask = side?.let { q.ask(it) }
            val band = ForecastScalp.entryBand(KeptScalpers.FADE_ID, entryView(ForecastScalp.FADE_PREFIX) ?: explicit(ForecastScalp.FADE_PREFIX))
            val fadeKnife = side != null && ask != null && FairGapTune.fallingKnife(side, q.spot, q.strike, q.fair(side), ask)
            if (fadeKnife) {
                clearForecastEdge(ForecastScalp.variantId(false, KeptScalpers.FADE_HORIZON_S), key)
                gates.block(KeptScalpers.FADE_ID, fadeWindow, "lesson guard: falling knife")
            }
            else if (side != null && ask != null && fadeWantsEntry(mid, ya - yb, ask, kotlin.math.abs(f), q, cfg.marginUsd, band.first, band.second, side, ScalpRule.CONTRACTS, KeptScalpers.FADE_ID)) {
                val vid = ForecastScalp.variantId(false, KeptScalpers.FADE_HORIZON_S)
                val cost = ask * ScalpRule.CONTRACTS + ScalpRule.orderFee(ScalpRule.CONTRACTS, ask)
                val active = openFc.any { it.variantId == vid } || changed.any { it.variantId == vid && isActive(it) }
                if (active) armForecastEdge(vid, key, side)
                else if (!forecastEdgeArmed(vid, key) && vid == KeptScalpers.FADE_ID && openCostUsd() + cost <= bankrollUsd() + 1e-9) {
                    changed += ScalpTrade(
                        id = idFactory(), ticker = key, side = side, state = ScalpState.PENDING_ENTRY, signalAtMs = q.nowMs,
                        signalAsk = ask, fairAtSignal = mid + f, restingLimit = ask, restingSinceMs = q.nowMs,
                        note = String.format(Locale.US, "Forecast fade · forecast %+.1f¢ over %s · %s",
                            f * 100, ShortHorizonForecaster.horizonLabel(KeptScalpers.FADE_HORIZON_S), LeadForecaster.VERSION),
                        ruleVersion = "${ScalpRule.VERSION}|$vid|P"
                    )
                    armForecastEdge(vid, key, side)
                }
            } else {
                clearForecastEdge(ForecastScalp.variantId(false, KeptScalpers.FADE_HORIZON_S), key)
                gates.block(KeptScalpers.FADE_ID, fadeWindow, "fade trigger not met")
            }
        } else if (predFade == null && q.fresh() && enabled) {
            gates.block(KeptScalpers.FADE_ID, fadeWindow, "no forecast lead")
        }
        if (liveExtras && enabled && q.nowMs < q.closeMs && q.fresh()) for (spec in ScalpVariants.ALL) {
            if (spec.kind != ScalpVariants.Kind.FADE) continue
            val predV = x54?.let { LeadForecaster.predict(coin, spec.fadeHorizonS, it) }
            if (predV == null) { gates.block(spec.id, fadeWindow, "no forecast lead"); continue }
            val fv = -predV
            val sideV = ForecastScalp.sideOf(fv)
            val askV = sideV?.let { q.ask(it) }
            val fadeKnifeV = sideV != null && askV != null && FairGapTune.fallingKnife(sideV, q.spot, q.strike, q.fair(sideV), askV)
            if (fadeKnifeV) {
                clearForecastEdge(spec.id, key)
                gates.block(spec.id, fadeWindow, "lesson guard: falling knife")
            }
            else if (sideV != null && askV != null && fadeWantsEntry(mid, ya - yb, askV, kotlin.math.abs(fv), q, cfg.marginUsd, spec.fadeLo, spec.fadeHi, sideV, spec.clip, spec.id)) {
                val costV = askV * spec.clip + ScalpRule.orderFee(spec.clip, askV)
                val activeV = openFc.any { it.variantId == spec.id } || changed.any { it.variantId == spec.id && isActive(it) }
                if (activeV) armForecastEdge(spec.id, key, sideV)
                else if (forecastEdgeArmed(spec.id, key)) Unit
                else if (openCostUsd() + costV <= bankrollUsd() + 1e-9) {
                    changed += ScalpTrade(
                        id = idFactory(), ticker = key, side = sideV, state = ScalpState.PENDING_ENTRY, signalAtMs = q.nowMs,
                        signalAsk = askV, fairAtSignal = mid + fv, restingLimit = askV, restingSinceMs = q.nowMs,
                        note = String.format(Locale.US, "Forecast fade variant %s · clip %d", spec.id, spec.clip),
                        ruleVersion = "${ScalpRule.VERSION}|${spec.id}|P"
                    )
                    armForecastEdge(spec.id, key, sideV)
                    gates.entered(spec.id, fadeWindow)
                } else gates.block(spec.id, fadeWindow, "paper cash spent")
            } else {
                clearForecastEdge(spec.id, key)
                gates.block(spec.id, fadeWindow, "fade trigger not met")
            }
        }
        changed.forEach { save(it) }
        unlatchClosed(changed)
        changed
    }

    private fun fcStep(t: ScalpTrade, q: ScalpRule.Quote, f: Double, horizon: Int): ScalpTrade? {
        val sideF = if (t.side == "YES") f else -f
        return when (t.state) {
            ScalpState.PENDING_ENTRY -> {
                val limit = t.restingLimit ?: t.signalAsk
                val ok = ScalpRule.buyPriceOk(limit) || ScalpFloors.isFavorite(t.variantId) ||
                    FairGapTune.lessonAllowed(q, t.side, limit, q.fair(t.side) ?: t.fairAtSignal, t.contracts.coerceAtLeast(1), variantId = t.variantId)
                if (!ok) return cappedEntry(t, q.nowMs)
                if (q.nowMs - t.signalAtMs > ForecastScalp.ENTRY_LIMIT_TTL_MS || q.nowMs >= q.closeMs)
                    return t.copy(state = ScalpState.NO_FILL, closedAtMs = q.nowMs, note = "${t.note}; limit cancelled unfilled")
                val n = com.dirk.kalshiodds.signal.paper.PaperLimitFill.touchFillQty(limit, q.ask(t.side), q.askSize(t.side),
                    StrategyAlgo.clip(controls.state.value, codeOf(t), ScalpVariants.clipOf(codeOf(t)) ?: ScalpRule.CONTRACTS), buy = true)
                if (n <= 0) null else t.copy(state = ScalpState.OPEN, contracts = n, entryPrice = limit, entryFeeUsd = ScalpRule.orderFee(n, limit),
                    entryAtMs = q.nowMs, restingLimit = null, note = "${t.note}; filled $n @ ${com.dirk.kalshiodds.domain.KalshiQuoteDisplay.formatPriceCents(limit)}")
            }
            ScalpState.OPEN, ScalpState.PENDING_EXIT -> {
                if (q.nowMs >= q.closeMs || !q.fresh()) return null
                val bid = q.bid(t.side)?.takeIf { it > 0.0 } ?: return null
                val fcEntry = t.entryPrice ?: 0.0
                val fcCode = codeOf(t)
                if (t.isPrimary && FairGapTune.isTweaked(fcCode)) {
                    // 1.0.3: fair-gap hold (no stop, no forecast-turn exit).
                    val r = t.exitReason.takeIf { t.state == ScalpState.PENDING_EXIT } ?: FairGapTune.exit((q.nowMs - (t.entryAtMs ?: q.nowMs)) / 1000.0, entryView(fcCode) ?: explicit(fcCode)) ?: return null
                    val n = minOf(t.remaining, kotlin.math.floor((q.bidSize(t.side) ?: 0.0) + 1e-9).toInt())
                    val base = if (t.state == ScalpState.OPEN) t.copy(state = ScalpState.PENDING_EXIT, exitDecidedAtMs = q.nowMs, exitReason = r) else t
                    if (n <= 0) return base
                    val next = ScalpFills.sell(base, q.nowMs, n, n * bid, ScalpRule.orderFee(n, bid))
                    return if (next.remaining <= 0) close(next, q.nowMs, r) else next
                }
                val stopC = StrategyAlgo.storedInt(controls.state.value, fcCode, "stopCents", ForecastScalp.STOP_C)
                val heldS = (q.nowMs - (t.entryAtMs ?: q.nowMs)) / 1000.0
                val maxHold = StrategyAlgo.storedInt(controls.state.value, fcCode, "maxHoldSeconds", 0)
                val fcSpread = q.ask(t.side)?.let { a -> if (a >= bid) a - bid else 0.01 } ?: 0.01
                val fcFee = ScalpRule.feePerContract(t.contracts.coerceAtLeast(1), fcEntry.coerceIn(0.01, 0.99))
                val fcDist = maxOf(stopC / 100.0, ScalpV2.minStopDistanceUsd(fcSpread, fcFee))
                val stopHit = t.state == ScalpState.OPEN && bid <= fcEntry - fcDist + 1e-9
                if (stopHit && t.isPrimary) addShadow(t, sf = false) { qq, _, b, _ -> b >= ForecastScalp.TAKE_PROFIT - 1e-9 || qq.tauS < ForecastScalp.EXIT_BEFORE_CLOSE_S }
                val reason = t.exitReason.takeIf { t.state == ScalpState.PENDING_EXIT }
                    ?: (if (stopHit) "stop −${stopC}¢" else null)
                    ?: (if (maxHold > 0 && heldS >= maxHold) "max hold ${maxHold}s" else null)
                    ?: ForecastScalp.wantsExit(sideF, bid, heldS, horizon, q.tauS) ?: return null
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

    /** 1.0.11: spot at [atMs] from the live 1 s ring (within 3 s). Null if that moment was not observed. */
    fun spotAt(coin: String, atMs: Long): Double? = synchronized(lock) { sfSpot[coin]?.at(atMs / 1000L) }

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

    /** Open 50 price entries. Removed cards do not open. A live card may open again on the next trigger. */
    private fun o50PriceEntries(q: ScalpRule.Quote, key: String, ya: Double, yb: Double): List<ScalpTrade> {
        if (!ShockFav.o50InEntryWindow(q.tauS)) return emptyList()
        val openMid = o50OpenMid[key] ?: return emptyList()
        val out = ArrayList<ScalpTrade>()
        for (raw in ShockFav.VARIANTS) {
            val p = StrategyAlgo.overlayShock(raw, algoOf(raw.code))
            if (p.kind != ShockFav.Kind.OPEN50 || p.o50 == ShockFav.O50.AI) continue
            if (ScalpModels.isRemoved(p.code)) continue
            val sides = ShockFav.o50Sides(p, openMid, (ya + yb) / 2)
            for (sd in sides) {
                val vid = ShockFav.o50VariantId(p, sd)
                if (out.any { it.variantId == vid }) continue
                val ask = q.ask(sd)?.takeIf { it in 0.05..0.95 } ?: continue
                sfEnter(q, p, vid, sd, ask, 0.5, 0.0, Double.MAX_VALUE, "open 50")?.let {
                    out += if (p.o50 == ShockFav.O50.MAKER) it.copy(restingLimit = ShockFav.O50_MAKER_PX, restingSinceMs = q.nowMs) else it
                }
            }
        }
        return out
    }

    private val simpleHist = HashMap<String, ArrayDeque<Triple<Long, Double, Double>>>()

    /** Side whose ask sits ≥ p.dip below its own 5-min average ask (needs ≥ 4 min of history, τ ≥ 120 s, ask 5–95¢). */
    private fun simpleDipSide(key: String, p: ShockFav.Params, q: ScalpRule.Quote): String? {
        val h = simpleHist[key] ?: return null
        val fastVariant = p.code.endsWith("-v1") || p.code.endsWith("-v2")
        val minHist = if (fastVariant) 30_000L else ShockFav.SIMPLE_MIN_HISTORY_MS
        val minTau = if (fastVariant) 45.0 else 120.0
        if (h.isEmpty() || q.nowMs - h.first().first < minHist || q.tauS < minTau) return null
        val avgY = h.sumOf { it.second } / h.size
        val avgN = h.sumOf { it.third } / h.size
        val side = ShockFav.simpleDipSide(p, q.ask("YES"), avgY, q.ask("NO"), avgN) ?: return null
        val ask = q.ask(side) ?: return null
        if (FairGapTune.fallingKnife(side, q.spot, q.strike, q.fair(side), ask)) return null
        return side
    }

    fun onShockFavQuote(q: ScalpRule.Quote, enabled: Boolean): List<ScalpTrade> = synchronized(lock) {
        closeSeen[q.ticker.uppercase()] = q.closeMs
        expireOrphansLocked(q.nowMs)
        val yb = q.yesBid ?: return@synchronized staleReturn(q, emptyList())
        val ya = q.yesAsk ?: return@synchronized emptyList()
        // A locked book (ask == bid) is the print that fills a maker at that price.
        // Only a crossed book (ask below bid) is unusable.
        if (ya < yb - 1e-9) return@synchronized emptyList()
        val key = q.ticker.uppercase()
        val coin = ScalpParams.coinOf(q.ticker)
        val sec = q.nowMs / 1000L
        if (q.tauS <= 900.0 && !o50OpenMid.containsKey(key)) o50OpenMid[key] = (ya + yb) / 2
        if (o50OpenMid.size > 200) o50OpenMid.keys.filter { (ScalpTicker.closeMs(it) ?: Long.MAX_VALUE) < q.nowMs }.forEach { o50OpenMid.remove(it) }
        // 0.3.65 Open 50 (non-AI): fresh chance at EVERY 15-min open — needs only the book, not spot/σ/strike.
        val o50Early = if (enabled && q.nowMs < q.closeMs && q.fresh() && coin == ShockFav.TRADE_COIN) o50PriceEntries(q, key, ya, yb) else emptyList()
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
        // 0.3.58 fair, 1.0.16 basis: index gap applied to spot only. This contract's strike stays in fairYes.
        val strike = q.strike?.takeIf { it > 0.0 && it.isFinite() } ?: return@synchronized staleReturn(q, o50Early)
        val sNow = ring.at(sec) ?: return@synchronized staleReturn(q, o50Early)
        // A cold ring after an outage used to return here for the whole window. Median σ lets the
        // strategy price fair on the first fresh tick; the live σ replaces it once 120 seconds exist.
        val sig = ring.sigma900(sec) ?: ShockFav.MEDIAN_SIGMA[coin] ?: ScalpRule.defaultSigmaPerSec(q.ticker)
        val basisKey = "$coin|${q.closeMs}"
        val basis = sfBasis[basisKey] ?: run {
            val osec = (q.closeMs - 900_000L) / 1000L
            val openSpot = ring.at(osec)
            val index = q.cfSpot?.takeIf { q.cfFresh() && it > 0.0 && it.isFinite() }
            val atOpen = sec >= osec && sec - osec <= 3L
            when {
                atOpen && openSpot != null && index != null ->
                    ShockFav.indexBasis(openSpot, index).also { sfBasis[basisKey] = it }
                sec > osec + 3L -> 0.0.also { sfBasis[basisKey] = it }
                else -> 0.0
            }
        }
        if (sfBasis.size > 200) sfBasis.keys.filter { k ->
            (k.substringAfter('|', "").toLongOrNull() ?: Long.MAX_VALUE) < q.nowMs
        }.forEach { sfBasis.remove(it) }
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
            if (pk?.o50 == ShockFav.O50.MAKER) {
                // 0.3.64 Open 50 maker: resting 49¢ bid; filled only when the side ask trades THROUGH (≤ 48¢) — adverse-selection aware, fee 0.
                if (q.nowMs - t.signalAtMs > ShockFav.O50_MAKER_TTL_S * 1000L || q.tauS <= 60.0) {
                    changed += t.copy(state = ScalpState.NO_FILL, closedAtMs = q.nowMs, restingLimit = null, note = "${t.note}; 49¢ bid unfilled")
                } else {
                    val a = q.ask(t.side)
                    if (a != null && a <= ShockFav.O50_MAKER_PX + 1e-9) {
                        val clipN = StrategyAlgo.clip(controls.state.value, pk?.code, ShockFav.CLIP)
                        changed += t.copy(state = ScalpState.OPEN, contracts = clipN, entryPrice = ShockFav.O50_MAKER_PX, entryFeeUsd = 0.0, entryAtMs = q.nowMs,
                            note = String.format(Locale.US, "%s; maker filled %d @ 49¢ (ask traded through)", t.note, clipN))
                    }
                }
                continue
            }
            if (pk?.kind == ShockFav.Kind.FADE_MAKER) {
                // 0.3.59 maker: post at the side's bid 2 s after the signal; filled only when the ask trades THROUGH (≤ px − 1¢).
                val px = t.restingLimit
                if (px != null && !ScalpRule.buyPriceOk(px)) { changed += cappedEntry(t, q.nowMs); continue }
                if (px == null) {
                    // Keep the tenth-cent touch. Rounding to a whole cent (2.9¢ → 3¢, 96.8¢ → 97¢)
                    // posts off the level, and the old 5–95¢ band rejected both sides of a 97/3 book.
                    val b = q.bid(t.side)?.takeIf { it in 0.001..0.999 && ScalpRule.buyPriceOk(it) }
                    val posted = b?.let { kotlin.math.round(it * 1000.0) / 1000.0 }
                    changed += if (posted == null) t.copy(state = ScalpState.NO_FILL, closedAtMs = q.nowMs, note = "${t.note}; no bid to join")
                        else t.copy(restingLimit = posted, restingSinceMs = q.nowMs, note = String.format(Locale.US, "%s; resting bid %.1f¢", t.note, posted * 100))
                } else if (q.nowMs - (t.restingSinceMs ?: q.nowMs) > pk.ttlS * 1000L || q.tauS <= 60.0) {
                    changed += t.copy(state = ScalpState.NO_FILL, closedAtMs = q.nowMs, restingLimit = null, note = "${t.note}; maker bid unfilled")
                } else {
                    val a = q.ask(t.side)
                    if (a != null && a <= px + 1e-9) {
                        val clipN = StrategyAlgo.clip(controls.state.value, pk.code, ShockFav.CLIP)
                        changed += t.copy(state = ScalpState.OPEN, contracts = clipN, entryPrice = px, entryFeeUsd = 0.0, entryAtMs = q.nowMs,
                            note = String.format(Locale.US, "%s; maker filled %d @ %.0f¢ (ask traded through)", t.note, clipN, px * 100))
                    }
                }
                continue
            }
            val ask = q.ask(t.side) ?: continue
            val code = ScalpModels.modelOf(t)?.code ?: t.variantId
            val fillFair = q.fair(t.side) ?: t.fairAtSignal
            val moveBp = ShockFav.moveBp(ring, sec, 5)
            if (!FairGapTune.askAllowed(pk?.code, ask, ShockFav.isFavLeg(t.variantId), q, t.side, fillFair, t.contracts.coerceAtLeast(1), moveBp, null, t.variantId, emptyList())) { changed += cappedEntry(t, q.nowMs); continue }
            if (greenOf(t)) {
                val admitted = admitSize(t, ask, greenClip) ?: continue
                val maxPx = if (ScalpFloors.isFavorite(code)) 0.99 else maxOf(ScalpRule.ENTRY_HI, t.signalAsk + 0.04, ask + 0.02).coerceAtMost(0.97)
                val (gn, usd, fee) = walkBuy(q, t.side, admitted.n, maxPx)
                if (gn <= 0 || gn > admitted.clip) {
                    if (gn > admitted.clip) gates.block(code, ScalpModels.windowKeyOf(q.ticker),
                        String.format(Locale.US, "sanity: size %d is above the clip %d", gn, admitted.clip))
                    continue
                }
                gates.entered(code, ScalpModels.windowKeyOf(q.ticker))
                val cap = if (admitted.capped) String.format(Locale.US, " (green %d capped at clip %d)", greenClip, admitted.clip) else ""
                changed += t.copy(state = ScalpState.OPEN, contracts = gn, entryPrice = usd / gn, entryFeeUsd = fee, entryAtMs = q.nowMs,
                    note = String.format(Locale.US, "%s; green size: walked book %d/%d avg %.2f¢%s", t.note, gn, admitted.clip, usd / gn * 100, cap))
                continue
            }
            val want = if (t.contracts > 0) t.contracts else StrategyAlgo.clip(controls.state.value, t.variantId, ScalpVariants.clipOf(t.variantId) ?: ShockFav.CLIP)
            val admitted = admitSize(t, ask, want) ?: continue
            val n = minOf(admitted.n, kotlin.math.floor((q.askSize(t.side) ?: 0.0) + 1e-9).toInt())
            if (n <= 0) continue
            if (admitted.capped) gates.entered(code, ScalpModels.windowKeyOf(q.ticker))
            changed += t.copy(state = ScalpState.OPEN, contracts = n, entryPrice = ask, entryFeeUsd = ScalpRule.orderFee(n, ask), entryAtMs = q.nowMs,
                note = String.format(Locale.US, "%s; filled %d @ %.0f¢ after %.1fs", t.note, n, ask * 100, age / 1000.0))
        }
        // exits first (shock legs; fav legs hold to settlement)
        for (t in mine) {
            if (ModelFilter.isTwin(t.variantId)) continue
            if (t.state != ScalpState.OPEN && t.state != ScalpState.PENDING_EXIT) continue
            if (!live) continue
            val rawP = shockParamsOf(t.variantId) ?: continue
            val p = StrategyAlgo.overlayShock(rawP, algoOf(t.variantId))
            val bid = q.bid(t.side)?.takeIf { it > 0.0 } ?: continue
            val sideFair = if (t.side == "YES") fairYes else 1.0 - fairYes
            val heldS = (q.nowMs - (t.entryAtMs ?: q.nowMs)) / 1000.0
            val favLeg = ShockFav.isFavLeg(t.variantId)
            // Stop is this variant's own cents, then a saved slider, then the parent table.
            val stopC = ScalpVariants.stopCents(t.variantId)
                ?: StrategyAlgo.storedInt(controls.state.value, t.variantId, "stopCents", ShockFav.stopC(p))
            val sfCode = ScalpModels.modelOf(t)?.code ?: t.variantId
            val fgT = t.isPrimary && FairGapTune.fairGapHoldExit(sfCode)
            val sfEntry = t.entryPrice ?: 0.0
            val sfSpread = q.ask(t.side)?.let { a -> if (a >= bid) a - bid else 0.01 } ?: 0.01
            val sfFee = ScalpRule.feePerContract(t.contracts.coerceAtLeast(1), sfEntry.coerceIn(0.01, 0.99))
            val sfDist = maxOf(stopC / 100.0, ScalpV2.minStopDistanceUsd(sfSpread, sfFee))
            val stopHit = !fgT && t.state == ScalpState.OPEN && bid <= sfEntry - sfDist + 1e-9
            if (stopHit && t.isPrimary) {
                val ep = t.entryPrice ?: 0.0
                if (p.kind == ShockFav.Kind.OPEN50) {
                    var pk = o50Peak[t.id] ?: bid
                    addShadow(t, sf = true) { qq, h, b, _ -> pk = maxOf(pk, b); ShockFav.o50Exit(p, ep, pk, b, h, qq.tauS) != null }
                } else
                addShadow(t, sf = true) { qq, h, b, f -> if (favLeg) false else sfNoStopExit(p, ep, h, qq.tauS, b, f ?: b) != null }
            }
            // Kalshi Scalper hard rules for original sf strategies too: 3¢ trail below peak bid, and exit in the final
            // minute unless confidence the side settles in the money is over 95% (GBM → fair-value fallback).
            val ownVariant = ScalpVariants.isLive(t.variantId)
            val ruleExit = if (t.state != ScalpState.OPEN) null
                else if (fgT) FairGapTune.exit(heldS, entryView(sfCode) ?: explicit(sfCode))
                else if (!ownVariant && bid <= (peakBid[t.id] ?: bid) - ScalpV2.TRAIL + 1e-9) "3¢ trail below peak bid"
                else if (!ownVariant && q.tauS <= ScalpV2.EXIT_BY_S && ScalperBrain.holdConfidence(sfCode, bid, sideFair, GbmModel.pSide(q, t.side, dm60(key, q.nowMs))) <= ScalperBrain.HOLD_CONF) "exit before close: confidence ≤ 95%"
                else null
            if (favLeg && !stopHit && ruleExit == null && t.state == ScalpState.OPEN) continue
            if (fgT && ruleExit == null && t.state == ScalpState.OPEN) continue
            if (p.kind == ShockFav.Kind.FADE_MAKER && t.state == ScalpState.OPEN) {
                val tgt = (t.entryPrice ?: 0.0) + p.tp
                if (bid >= tgt + 0.01 - 1e-9) { // resting ask at target traded through: maker exit, fee 0
                    changed += close(ScalpFills.sell(t, q.nowMs, t.remaining, t.remaining * tgt, 0.0), q.nowMs, "maker target +${Math.round(p.tp * 100)}¢")
                    continue
                }
            }
            val reason = t.exitReason.takeIf { t.state == ScalpState.PENDING_EXIT }
                ?: (if (stopHit) "stop −${stopC}¢" else null)
                ?: ruleExit
                ?: (if (p.kind == ShockFav.Kind.OPEN50) {
                    val pk = maxOf(o50Peak[t.id] ?: bid, bid).also { o50Peak[t.id] = it }
                    val ai = if (p.o50 == ShockFav.O50.AI) o50Features(t.side, q, sec, sNow, strike, basis, sig, o50OpenMid[key] ?: ((ya + yb) / 2))?.let { Open50Model.predict(it) } else null
                    ShockFav.o50Exit(p, t.entryPrice ?: 1.0, pk, bid, heldS, q.tauS, ai, t.contracts.coerceAtLeast(1),
                        StrategyAlgo.storedInt(controls.state.value, p.code, "maxHoldSeconds", ShockFav.O50_MAX_HOLD_S.toInt()).toDouble())
                } else null)
                ?: (if (favLeg || p.kind == ShockFav.Kind.OPEN50) null else sfNoStopExit(p, t.entryPrice ?: 1.0, heldS, q.tauS, bid, sideFair)) ?: continue
            val base = if (t.state == ScalpState.OPEN) t.copy(state = ScalpState.PENDING_EXIT, exitDecidedAtMs = q.nowMs, exitReason = reason) else t
            if (t.contracts > ShockFav.CLIP) { // 0.3.69 green-size position: sell down the real bid levels
                val (sn, usd, fee) = walkSell(q, t.side, t.remaining)
                if (sn <= 0) { if (base !== t) changed += base; continue }
                val nx = base.copy(soldContracts = base.soldContracts + sn, proceedsUsd = base.proceedsUsd + usd, exitFeeUsd = base.exitFeeUsd + fee)
                changed += if (nx.remaining <= 0) close(nx, q.nowMs, reason) else nx
                continue
            }
            val n = minOf(t.remaining, kotlin.math.floor((q.bidSize(t.side) ?: 0.0) + 1e-9).toInt())
            if (n <= 0) { if (base !== t) changed += base; continue }
            val next = ScalpFills.sell(base, q.nowMs, n, n * bid, ScalpRule.orderFee(n, bid))
            changed += if (next.remaining <= 0) close(next, q.nowMs, reason) else next
        }
        if (!q.fresh() || q.bookGap) gates.noteOutage(ScalpModels.windowKeyOf(q.ticker).ifBlank { "outage" }, KeptScalpers.IDS + ScalpVariants.IDS)
        // 0.3.59: Scalp tab is Bitcoin only — sf entries on BTC markets only (other coins' spot is signal input).
        // A seq gap is the same outage as a stale book: no new clip until the next intact tick.
        if (enabled && live && !q.bookGap && coin == ShockFav.TRADE_COIN) {
            val sigma: Double? = sig
            var openCost = openCostUsd()
            val bankroll = bankrollUsd()
            var brtiFair: Double? = null
            for (raw in ShockFav.VARIANTS) {
                if (raw.code !in KeptScalpers.SHOCK_FAMILIES) continue
                val p = StrategyAlgo.overlayShock(raw, algoOf(raw.code))
                if (ScalpModels.isRemoved(p.code) || com.dirk.kalshiodds.decision.insights.ScalpFreeze.blocksRestored(p.code, controls.state.value.benchOverrides[p.code])) continue
                if (p.kind == ShockFav.Kind.OPEN50) {
                    if (p.o50 != ShockFav.O50.AI) continue // 0.3.65: handled in o50PriceEntries
                    // 0.3.64 Open 50: one shot per market per side, only 5–30 s after the open.
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
                        }.let { nets -> SidePick.onlyMaxBy(nets) { it.second }?.let { listOf(it.first) } ?: emptyList() }
                        null -> emptyList()
                    }
                    for (sd in sides) {
                        val vid = if (sd == "NO" && (p.o50 == ShockFav.O50.BOTH || p.o50 == ShockFav.O50.MAKER)) p.code + "n" else p.shockId
                        val ask = q.ask(sd)?.takeIf { it in 0.05..0.95 } ?: continue
                        val f = if (sd == "YES") fairYes else 1.0 - fairYes
                        val what = if (p.o50 == ShockFav.O50.AI) "open 50 · ${Open50Model.VERSION}" else "open 50"
                        sfEnter(q, p, vid, sd, ask, f, openCost, bankroll, what)?.let {
                            val tr = if (p.o50 == ShockFav.O50.MAKER) it.copy(restingLimit = ShockFav.O50_MAKER_PX, restingSinceMs = q.nowMs) else it
                            changed += tr; if (!shadowEntry(tr)) openCost += tr.entryCostUsd
                        }
                    }
                    continue
                }
                if (p.kind != ShockFav.Kind.SHOCK_FAV) {
                    val side = when (p.kind) {
                        ShockFav.Kind.LAGGARD -> ShockFav.laggardSide(p, coin, sfSpot, sec, q.tauS)?.takeIf { sd -> q.ask(sd)?.let { it in 0.10..0.90 } == true }
                        ShockFav.Kind.IMBALANCE -> ShockFav.imbalanceSide(p, yb, ya, q.yesBidSize ?: 0.0, q.yesAskSize ?: 0.0, q.tauS)?.takeIf { sd -> q.ask(sd)?.let { it in 0.10..0.90 } == true }
                        ShockFav.Kind.SIMPLE_DIP -> simpleDipSide(key, p, q)
                        ShockFav.Kind.FADE_MAKER -> if (q.tauS in 120.0..870.0) ShockFav.moveBp(ring, sec, 5)?.takeIf { kotlin.math.abs(it) >= p.xBp }?.let { if (it > 0) "NO" else "YES" } else null
                        else -> null
                    }
                    if (side == null) {
                        clearTrigger(p.code, q.ticker)
                        gates.block(p.code, ScalpModels.windowKeyOf(q.ticker), if (p.kind == ShockFav.Kind.SIMPLE_DIP) "dip trigger not met" else "trigger not met")
                        continue
                    }
                    val ask = q.ask(side) ?: continue
                    val f = if (side == "YES") fairYes else 1.0 - fairYes
                    sfEnter(q, p, p.shockId, side, ask, f, openCost, bankroll, p.kind.name.lowercase().replace('_', ' '), ShockFav.moveBp(ring, sec, 5))?.let { changed += it; if (!shadowEntry(it)) openCost += it.entryCostUsd }
                    continue
                }
                if (p.shock) {
                    val side = ShockFav.shockSide(p, coin, ring, sec, q.tauS, sigma)
                    if (side != null) {
                        val sideFair = if (side == "YES") fairYes else 1.0 - fairYes
                        val ask = q.ask(side)
                        if (ask != null && ShockFav.shockEntryOk(p, sideFair, ask))
                            sfEnter(q, p, p.shockId, side, ask, sideFair, openCost, bankroll, "shock", ShockFav.moveBp(ring, sec, p.yS))?.let { changed += it; if (!shadowEntry(it)) openCost += it.entryCostUsd }
                        else {
                            clearTrigger(p.shockId, q.ticker)
                            gates.block(p.code, ScalpModels.windowKeyOf(q.ticker), "shock lag or ask band not met")
                        }
                    } else {
                        clearTrigger(p.shockId, q.ticker)
                        gates.block(p.code, ScalpModels.windowKeyOf(q.ticker), "shock trigger not met")
                    }
                }
                val favCode = p.favId.take(4)
                val favFg = FairGapTune.favUsesFairGapGate(favCode) // own-rule late favourites use favEntryOk, including sf31 off-US hours
                val favTauMin = if (favFg) maxOf(p.favTmin, FairGapTune.SETTLE_MIN_S) else p.favTmin
                if (p.fav && q.tauS <= (if (favFg) maxOf(p.favTmax, 300.0) else p.favTmax) && q.tauS >= favTauMin) {
                    val fy = if (p.brti && sigma != null) {
                        brtiFair ?: run {
                            val m = (60.0 - q.tauS).toInt().coerceIn(0, 60)
                            val obs = (sec - m + 1..sec).mapNotNull { ring.at(it) }
                            ShockFav.brtiFairYes(sNow * kotlin.math.exp(-basis), strike, sigma, q.tauS, obs.map { it * kotlin.math.exp(-basis) }).also { brtiFair = it }
                        }
                    } else fairYes
                    val passing = ArrayList<Triple<String, Double, Double>>()
                    for (side in listOf("YES", "NO")) {
                        val f = if (side == "YES") fy else 1.0 - fy
                        val ask = q.ask(side) ?: continue
                        val edge = if (favFg) {
                            if (FairGapTune.entryBlock(q, side, ask, ShockFav.CLIP, entryView(p.favId.take(4)) ?: explicit(p.favId.take(4)), false, q.nowMs) != null) continue
                            val rf = Research.fair(q) ?: continue
                            val pf = if (side == "YES") rf.pYes else 1.0 - rf.pYes
                            FairGapTune.gapAfterFee(pf, ask, ShockFav.CLIP)
                        } else {
                            if (!ShockFav.favEntryOk(p, f, ask, q.tauS, coin, q.closeMs)) continue
                            f - ask
                        }
                        passing += Triple(side, edge, f)
                    }
                    val won = SidePick.onlyMaxBy(passing) { it.second }
                    if (won != null) {
                        val ask = q.ask(won.first)
                        if (ask != null) sfEnter(q, p, p.favId, won.first, ask, won.third, openCost, bankroll, "late fav")?.let {
                            changed += it; if (!shadowEntry(it)) openCost += it.entryCostUsd
                        }
                    } else {
                        clearTrigger(p.favId, q.ticker)
                        gates.block(p.favId, ScalpModels.windowKeyOf(q.ticker), "late-fav trigger not met")
                    }
                } else if (p.fav) {
                    clearTrigger(p.favId, q.ticker)
                    val why = when {
                        q.tauS > p.favTmax -> "outside late-fav window"
                        q.tauS < favTauMin -> "outside late-fav window"
                        else -> "late-fav trigger not met"
                    }
                    gates.block(p.favId, ScalpModels.windowKeyOf(q.ticker), why)
                }
            }
            if (liveExtras) {
                val variantPass = enterShockVariants(q, key, fairYes, ring, sec, sigma, openCost, bankroll, mine)
                changed += variantPass.first
                openCost = variantPass.second
                changed += enterModelDip(q, key, fairYes)
            }
        }
        changed.forEach { save(it) }
        changed
    }

    /** Missing spot or strike. Median σ is used instead of bailing when only the vol ring is cold. */
    private fun staleReturn(q: ScalpRule.Quote, already: List<ScalpTrade>): List<ScalpTrade> {
        gates.noteOutage(ScalpModels.windowKeyOf(q.ticker), KeptScalpers.IDS + ScalpVariants.IDS)
        return already
    }

    /** Paper nudges of the shock, simple-dip, and late-fav winners. One in-flight clip at a time; open clips do not block the next. */
    private fun enterShockVariants(
        q: ScalpRule.Quote, key: String, fairYes: Double, ring: ShockFav.SpotRing, sec: Long, sigma: Double?,
        openCostIn: Double, bankroll: Double, mine: List<ScalpTrade>
    ): Pair<List<ScalpTrade>, Double> {
        var openCost = openCostIn
        val out = ArrayList<ScalpTrade>()
        val window = ScalpModels.windowKeyOf(q.ticker)
        for (spec in ScalpVariants.ALL) {
            val p = spec.shock ?: continue
            if (spec.kind != ScalpVariants.Kind.SIMPLE && spec.kind != ScalpVariants.Kind.SHOCK && spec.kind != ScalpVariants.Kind.FAV) continue
            when (spec.kind) {
                ScalpVariants.Kind.SIMPLE -> {
                    val side = simpleDipSide(key, p, q)
                    if (side == null) { clearTrigger(spec.id, q.ticker); gates.block(spec.id, window, "dip trigger not met"); continue }
                    val ask = q.ask(side)
                    if (ask == null) { gates.block(spec.id, window, "no ask"); continue }
                    val f = if (side == "YES") fairYes else 1.0 - fairYes
                    sfEnter(q, p, spec.id, side, ask, f, openCost, bankroll, "simple dip")?.let {
                        out += it; if (!shadowEntry(it)) openCost += it.entryCostUsd
                    }
                }
                ScalpVariants.Kind.SHOCK -> {
                    val side = ShockFav.shockSide(p, ShockFav.TRADE_COIN, ring, sec, q.tauS, sigma)
                    if (side == null) { clearTrigger(spec.id, q.ticker); gates.block(spec.id, window, "shock trigger not met"); continue }
                    val sideFair = if (side == "YES") fairYes else 1.0 - fairYes
                    val ask = q.ask(side)
                    if (ask == null || !ShockFav.shockEntryOk(p, sideFair, ask)) {
                        clearTrigger(spec.id, q.ticker)
                        gates.block(spec.id, window, "shock lag or ask band not met"); continue
                    }
                    sfEnter(q, p, spec.id, side, ask, sideFair, openCost, bankroll, "shock")?.let {
                        out += it; if (!shadowEntry(it)) openCost += it.entryCostUsd
                    }
                }
                ScalpVariants.Kind.FAV -> {
                    if (q.tauS < p.favTmin || q.tauS > p.favTmax) {
                        clearTrigger(spec.id, q.ticker)
                        gates.block(spec.id, window, "outside late-fav window"); continue
                    }
                    var placed = false
                    for (side in listOf("YES", "NO")) {
                        val f = if (side == "YES") fairYes else 1.0 - fairYes
                        val ask = q.ask(side) ?: continue
                        if (!ShockFav.favEntryOk(p, f, ask, q.tauS, ShockFav.TRADE_COIN, q.closeMs)) continue
                        sfEnter(q, p, spec.id, side, ask, f, openCost, bankroll, "late fav")?.let {
                            out += it; if (!shadowEntry(it)) openCost += it.entryCostUsd
                            placed = true
                        }
                        break
                    }
                    if (!placed) { clearTrigger(spec.id, q.ticker); gates.block(spec.id, window, "late-fav trigger not met") }
                }
                else -> Unit
            }
        }
        return out to openCost
    }

    /** Per-strategy window-start entry limit for original sf strategies: 2–5 minutes after the open (distinct by code). */
    fun sfOpenMin(code: String): Double = ScalpV2.sfOpenMin(code)

    /**
     * YES mid history for [dm60], with no entry, exit, or size.
     * ETH and SOL forecasts need this. They must not go through [onQuote].
     */
    fun noteMidHistory(q: ScalpRule.Quote) {
        synchronized(lock) { recordHistory(q) }
    }

    /** Caller holds [lock]. Same book memory [onQuote] keeps for the traded coin. */
    private fun recordHistory(q: ScalpRule.Quote): List<ScalpRule.Snap> {
        val key = q.ticker.uppercase()
        val hist = history.getOrPut(key) { ArrayDeque() }
        val recent = hist.toList()
        hist.addLast(ScalpRule.Snap(q.nowMs, q.yesBid, q.yesAsk, ScalpRule.fairYes(q.spot, q.strike, q.sigmaPerSec, q.tauS)))
        while (hist.isNotEmpty() && q.nowMs - hist.first().atMs > maxOf(2 * ScalpRule.LOOKBACK_MS, controls.state.value.windowSeconds * 1000L)) hist.removeFirst()
        if (q.nowMs >= q.closeMs) history.remove(key)
        // 0.3.50: bounded — forget closed windows' history/marks (they used to accumulate forever).
        if (history.size > ScalpMemory.MAX_TICKERS) {
            history.keys.filter { it != key && (ScalpTicker.closeMs(it) ?: Long.MAX_VALUE) < q.nowMs }.forEach { history.remove(it) }
        }
        return recent
    }

    /** 1.0.4: [dm60] under the book lock, for readers outside the quote path (home AI banner). */
    fun dm60Snapshot(key: String, nowMs: Long): Double = synchronized(lock) { dm60(key, nowMs) }

    /** Change in YES mid over the last ~60 s from this market's recorded book history (0 when unknown). */
    fun dm60(key: String, nowMs: Long): Double {
        val h = history[key] ?: return 0.0
        val now = h.lastOrNull() ?: return 0.0
        val then = h.lastOrNull { it.atMs <= nowMs - 60_000L } ?: h.firstOrNull() ?: return 0.0
        val a = now.yesBid?.let { b -> now.yesAsk?.let { (b + it) / 2 } } ?: return 0.0
        val b = then.yesBid?.let { x -> then.yesAsk?.let { (x + it) / 2 } } ?: return 0.0
        return a - b
    }

    /** Armed when a scalper's trigger is currently true. A new clip needs a rising edge. */
    private val triggerOn = LinkedHashSet<String>()
    /**
     * Research and forecast edges stay armed after a close. They clear only when that
     * strategy's entry gate is false, so a still-true signal does not re-enter.
     */
    private val researchEdge = LinkedHashSet<String>()
    private val forecastEdge = LinkedHashSet<String>()

    private fun triggerKey(id: String, ticker: String, side: String) = "$id|${ticker.uppercase()}|$side"

    private fun clearTrigger(id: String, ticker: String) {
        val prefix = "$id|${ticker.uppercase()}|"
        triggerOn.removeAll { it.startsWith(prefix) }
    }

    private fun unlatchTrigger(t: ScalpTrade) {
        clearTrigger(t.variantId, t.ticker)
        ScalpModels.modelOf(t)?.code?.let { clearTrigger(it, t.ticker) }
    }

    private fun triggerIsNew(id: String, ticker: String, side: String): Boolean {
        val key = triggerKey(id, ticker, side)
        if (key !in triggerOn) return true
        val tk = ticker.uppercase()
        val matching = _all.value.filter {
            it.ticker.equals(tk, true) && (it.variantId == id || ScalpModels.modelOf(it)?.code == id)
        }
        if (matching.none { isActive(it) }) {
            triggerOn.remove(key)
            return true
        }
        return false
    }

    private fun armTrigger(id: String, ticker: String, side: String) {
        val prefix = "$id|${ticker.uppercase()}|"
        val key = prefix + side
        triggerOn.removeAll { it.startsWith(prefix) && it != key }
        triggerOn.add(key)
        while (triggerOn.size > 4_000) triggerOn.remove(triggerOn.first())
    }

    private fun edgePrefix(id: String, ticker: String) = "$id|${ticker.uppercase()}|"

    private fun clearEdge(set: LinkedHashSet<String>, id: String, ticker: String) {
        val prefix = edgePrefix(id, ticker)
        set.removeAll { it.startsWith(prefix) }
    }

    private fun armEdge(set: LinkedHashSet<String>, id: String, ticker: String, side: String) {
        val prefix = edgePrefix(id, ticker)
        set.removeAll { it.startsWith(prefix) }
        set.add(prefix + side)
        while (set.size > 4_000) set.remove(set.first())
    }

    private fun edgeArmed(set: LinkedHashSet<String>, id: String, ticker: String) =
        set.any { it.startsWith(edgePrefix(id, ticker)) }

    private fun clearForecastEdge(id: String, ticker: String) = clearEdge(forecastEdge, id, ticker)
    private fun armForecastEdge(id: String, ticker: String, side: String) = armEdge(forecastEdge, id, ticker, side)
    private fun forecastEdgeArmed(id: String, ticker: String) = edgeArmed(forecastEdge, id, ticker)

    /** Band entry, or the lesson path, both over the named round-trip hurdle. */
    private fun fadeWantsEntry(
        mid: Double, spread: Double, ask: Double, move: Double, q: ScalpRule.Quote, marginUsd: Double,
        bandLo: Double, bandHi: Double, side: String, clip: Int, variantId: String
    ): Boolean {
        if (ForecastScalp.wantsEntry(mid, spread, ask, move, q.tauS, marginUsd, bandLo, bandHi)) return true
        val fair = (if (side == "YES") mid else 1.0 - mid) + move
        return spread <= ForecastScalp.MAX_SPREAD + 1e-9 && q.tauS >= ForecastScalp.MIN_TAU_ENTRY_S &&
            move > ForecastScalp.minEntryEdgeUsd(spread, ask) + marginUsd.coerceAtLeast(0.0) &&
            FairGapTune.lessonAllowed(q, side, ask, fair, clip, variantId = variantId, open = _all.value)
    }

    private fun shockParamsOf(variantId: String): ShockFav.Params? =
        ScalpVariants.shockParams(variantId) ?: ShockFav.byVariantId(variantId)

    /** Sell up to the displayed bid now. A stop does not wait out the entry latency. */
    private fun sellAtDisplayedBid(t: ScalpTrade, q: ScalpRule.Quote): ScalpTrade? {
        val bid = q.bid(t.side)?.takeIf { it > 0.0 } ?: return null
        val n = minOf(t.remaining, kotlin.math.floor((q.bidSize(t.side) ?: 0.0) + 1e-9).toInt())
        if (n <= 0) return null
        val next = ScalpFills.sell(t, q.nowMs, n, n * bid, ScalpRule.orderFee(n, bid))
        return if (next.remaining <= 0) close(next, q.nowMs, next.exitReason) else next
    }

    /** Paper model-filter twins. Existing scalpers are not modified. */
    private fun stepModelTwins(q: ScalpRule.Quote): List<ScalpTrade> {
        val out = ArrayList<ScalpTrade>()
        for (t in _all.value) {
            if (!ModelFilter.isTwin(t.variantId) || !t.ticker.equals(q.ticker, true) || !isActive(t)) continue
            val twin = ModelFilter.twin(t.variantId) ?: continue
            when (t.state) {
                ScalpState.PENDING_ENTRY -> {
                    val age = q.nowMs - t.signalAtMs
                    if (q.tauS <= ModelFilter.exitBeforeSeconds() || age > 20_000L) {
                        val nf = t.copy(state = ScalpState.NO_FILL, closedAtMs = q.nowMs, note = "${t.note}; model filter: no fill")
                        unlatchTrigger(nf)
                        out += nf
                        ModelFilter.noteUnfilled(twin.id)
                        continue
                    }
                    if (age < ScalpRule.LATENCY_MS || !q.fresh()) continue
                    val ask = q.ask(t.side) ?: continue
                    val floor = ScalpFloors.cents(twin.parentId) / 100.0
                    val lesson = FairGapTune.lessonAllowed(q, t.side, ask, q.fair(t.side) ?: t.fairAtSignal, twin.clip, variantId = twin.id)
                    if ((!ScalpRule.buyPriceOk(ask) && !lesson) || ask + 1e-9 < floor) continue
                    val n = minOf(t.contracts.coerceAtLeast(1), kotlin.math.floor((q.askSize(t.side) ?: 0.0) + 1e-9).toInt(), twin.clip)
                    if (n <= 0) continue
                    out += t.copy(state = ScalpState.OPEN, contracts = n, entryPrice = ask, entryFeeUsd = ScalpRule.orderFee(n, ask), entryAtMs = q.nowMs)
                }
                ScalpState.OPEN, ScalpState.PENDING_EXIT -> {
                    val bid = q.bid(t.side)?.takeIf { it > 0.0 } ?: continue
                    val entry = t.entryPrice ?: continue
                    val reason = modelExitReason(twin, entry, bid, q.tauS) ?: continue
                    val sold = ScalpFills.sell(t, q.nowMs, t.remaining, t.remaining * bid, ScalpRule.orderFee(t.remaining, bid))
                    val closed = close(sold, q.nowMs, reason)
                    out += closed
                    ModelFilter.onClosed(twin.id, closed.netUsd ?: 0.0, q.tauS, q.nowMs, null, closed.contracts, closed.soldContracts)
                }
                else -> Unit
            }
        }
        return out
    }

    private fun modelExitReason(twin: ModelFilter.Twin, entry: Double, bid: Double, tauS: Double): String? {
        if (ModelFilter.mustExit(tauS)) return "model filter: exit ${ModelFilter.exitBeforeSeconds().toInt()}s before close"
        return when (twin.kind) {
            ModelFilter.Kind.SIMPLE_DIP -> {
                val p = ShockFav.v(twin.parentId)
                val stop = ShockFav.stopC(p) / 100.0
                when {
                    bid >= entry + p.tp - 1e-9 -> "take profit +${kotlin.math.round(p.tp * 100)}¢"
                    bid <= entry - stop + 1e-9 -> "stop −${ShockFav.stopC(p)}¢"
                    else -> null
                }
            }
            ModelFilter.Kind.FAIR, ModelFilter.Kind.V2_FAIR, ModelFilter.Kind.V2_DIP -> {
                val p = ScalpParams.byId(if (twin.kind == ModelFilter.Kind.FAIR) KeptScalpers.FAIR_ID else if (twin.kind == ModelFilter.Kind.V2_FAIR) KeptScalpers.V2_FAIR_BASE else KeptScalpers.V2_DIP_BASE) ?: return null
                when {
                    bid >= entry + p.target - 1e-9 -> "take profit +${kotlin.math.round(p.target * 100)}¢"
                    bid <= entry - p.stop + 1e-9 -> "stop −${kotlin.math.round(p.stop * 100)}¢"
                    else -> null
                }
            }
        }
    }

    private fun enterModelFair(q: ScalpRule.Quote, recent: List<ScalpRule.Snap>): List<ScalpTrade> {
        ModelFilter.observe(q)
        if (!ModelFilter.dueForEntry(q.ticker, q.nowMs, "fair")) return emptyList()
        val out = ArrayList<ScalpTrade>()
        for (twin in ModelFilter.TWINS) {
            if (twin.kind == ModelFilter.Kind.SIMPLE_DIP) continue
            val params = when (twin.kind) {
                ModelFilter.Kind.FAIR -> ScalpParams.byId(KeptScalpers.FAIR_ID)
                ModelFilter.Kind.V2_FAIR -> ScalpParams.byId(KeptScalpers.V2_FAIR_BASE)
                ModelFilter.Kind.V2_DIP -> ScalpParams.byId(KeptScalpers.V2_DIP_BASE)?.let {
                    it.copy(minGap = maxOf(it.minGap, ScalpV2.feeClearingDipGap(ScalpV2.DIP_EDGE_ASK, twin.clip)))
                }
                else -> null
            } ?: continue
            val (sig, skip) = if (params.strategy == ScalpStrategy.FAIR_GAP) ScalpRule.entrySignal(q, params)
            else ScalpRule.strategySignal(q, params, listOf("YES", "NO"), recent)
            val row = modelEntry(q, twin, sig, skip) ?: continue
            out += row
        }
        return out
    }

    private fun enterModelDip(q: ScalpRule.Quote, key: String, fairYes: Double): List<ScalpTrade> {
        ModelFilter.observe(q)
        if (!ModelFilter.dueForEntry(q.ticker, q.nowMs, "dip")) return emptyList()
        val out = ArrayList<ScalpTrade>()
        for (twin in ModelFilter.TWINS) {
            if (twin.kind != ModelFilter.Kind.SIMPLE_DIP) continue
            val p = ShockFav.v(twin.parentId)
            val side = simpleDipSide(key, p, q)
            val sig = side?.let { sd ->
                val ask = q.ask(sd) ?: return@let null
                val sideFair = if (sd == "YES") fairYes else 1.0 - fairYes
                val reboundFair = maxOf(
                    sideFair,
                    (ask + p.dipTarget + ScalpV2.feeClearingDipGap(ask, twin.clip)).coerceAtMost(0.95)
                )
                ScalpRule.Entry(sd, ask, reboundFair, reboundFair - ask)
            }
            val row = modelEntry(q, twin, sig, if (side == null) "dip trigger not met" else "enter") ?: continue
            out += row
        }
        return out
    }

    private fun modelEntry(q: ScalpRule.Quote, twin: ModelFilter.Twin, sig: ScalpRule.Entry?, skip: String): ScalpTrade? {
        val window = ScalpModels.windowKeyOf(q.ticker)
        if (sig == null) {
            clearTrigger(twin.id, q.ticker)
            gates.block(twin.id, window, skip)
            return null
        }
        if (q.tauS <= ModelFilter.exitBeforeSeconds()) {
            gates.block(twin.id, window, "model filter: inside the final ${ModelFilter.exitBeforeSeconds().toInt()}s")
            return null
        }
        val d = ModelFilter.decide(twin, q, sig.side, sig.ask, sig.fair)
        if (!d.enter) {
            clearTrigger(twin.id, q.ticker)
            gates.block(twin.id, window, d.reason)
            return null
        }
        val n = ModelFilter.sized(twin.clip, d.scalar, twin.clip)
        val floor = ScalpFloors.cents(twin.parentId) / 100.0
        val blocked = ScalpParamSanity.entryBlock(sig.side, sig.ask, n, floor, bankrollUsd(), twin.clip)
        val lesson = FairGapTune.lessonAllowed(q, sig.side, sig.ask, sig.fair, n, variantId = twin.id, open = _all.value)
        if (FairGapTune.stackingBlocked(twin.id, q, sig.side, sig.ask, _all.value)) {
            gates.block(twin.id, window, FairGapTune.STACK_GUARD)
            return null
        }
        if (blocked != null || (!ScalpRule.buyPriceOk(sig.ask) && !lesson)) {
            gates.block(twin.id, window, blocked ?: "ask above 55¢ ceiling")
            return null
        }
        if (!triggerIsNew(twin.id, q.ticker, sig.side)) {
            gates.block(twin.id, window, "same trigger still on")
            return null
        }
        armTrigger(twin.id, q.ticker, sig.side)
        gates.entered(twin.id, window)
        return ScalpTrade(
            id = idFactory(), ticker = q.ticker.uppercase(), side = sig.side, state = ScalpState.PENDING_ENTRY,
            signalAtMs = q.nowMs, signalAsk = sig.ask, fairAtSignal = sig.fair, contracts = n,
            note = "model filter ${twin.id} · ${d.reason} · ${d.policyVersion} · ${d.modelVersion}",
            ruleVersion = "${ScalpRule.VERSION}|${twin.id}|P"
        )
    }

    /** Taker paper entry at the displayed ask, capped at displayed size; skipped when shared free cash can't cover it. */
    private fun sfEnter(q: ScalpRule.Quote, p: ShockFav.Params, vid: String, side: String, ask: Double, fair: Double,
                        openCost: Double, bankroll: Double, what: String, moveBp: Double? = null): ScalpTrade? {
        val window = ScalpModels.windowKeyOf(q.ticker)
        if (p.kind == ShockFav.Kind.SIMPLE_DIP && FairGapTune.fallingKnife(side, q.spot, q.strike, fair, ask)) {
            gates.block(vid, window, "lesson guard: falling knife")
            return null
        }
        if (FairGapTune.stackingBlocked(vid, q, side, ask, _all.value)) {
            gates.block(vid, window, FairGapTune.STACK_GUARD)
            return null
        }
        // Fade makers choose their purchase bid after the delay; the post/fill paths enforce the ceiling.
        val clip = ScalpVariants.clipOf(vid) ?: ShockFav.CLIP
        if (p.kind != ShockFav.Kind.FADE_MAKER && !FairGapTune.askAllowed(p.code, if (p.o50 == ShockFav.O50.MAKER) ShockFav.O50_MAKER_PX else ask, ShockFav.isFavLeg(vid), q, side, fair, clip, moveBp, null, vid, _all.value)) {
            gates.block(vid, window, "ask above this scalper's price band")
            return null
        }
        // Simple-dip still waits for its own 4-minute average. There is no extra 2–5 minute cutoff.
        val want = StrategyAlgo.clip(controls.state.value, vid, ScalpVariants.clipOf(vid) ?: ShockFav.CLIP)
        val n = if (p.kind == ShockFav.Kind.FADE_MAKER) want else minOf(want, kotlin.math.floor((q.askSize(side) ?: 0.0) + 1e-9).toInt())
        if (n <= 0) {
            gates.block(vid, window, "no displayed depth")
            return null
        }
        val cost = ask * n + ScalpRule.orderFee(n, ask)
        if (p.kind != ShockFav.Kind.OPEN50 && !shadowCode(p.code) && openCost + cost > bankroll + 1e-9) {
            gates.block(vid, window, "paper cash spent")
            return null
        }
        if (!triggerIsNew(vid, q.ticker, side)) {
            gates.block(vid, window, "same trigger still on")
            return null
        }
        armTrigger(vid, q.ticker, side)
        gates.entered(vid, window)
        return ScalpTrade(
            id = idFactory(), ticker = q.ticker.uppercase(), side = side, state = ScalpState.PENDING_ENTRY, signalAtMs = q.nowMs,
            signalAsk = ask, fairAtSignal = fair, contracts = n,
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
        // At the price or through it. A tenth-cent book never trades a full cent through a 0.3¢ spread.
        val oppCrossed = opp != null && (if (buy) opp <= limit + 1e-9 else opp >= limit - 1e-9)
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
        val lim = t.restingLimit ?: t.signalAsk
        val ok = ScalpRule.buyPriceOk(lim) || ScalpFloors.isFavorite(t.variantId) ||
            FairGapTune.lessonAllowed(q, t.side, lim, q.fair(t.side) ?: t.fairAtSignal, t.contracts.coerceAtLeast(1), variantId = t.variantId)
        if (!ok) return cappedEntry(t, q.nowMs)
        if (q.bookAtMs <= t.signalAtMs) return null
        val limit = t.restingLimit ?: t.signalAsk
        if (q.nowMs - t.signalAtMs > ScalpRule.MAKER_ENTRY_TTL_MS || q.nowMs >= q.closeMs || !q.fresh()) {
            return t.copy(state = ScalpState.NO_FILL, closedAtMs = q.nowMs, note = "${t.note}; maker entry cancelled: unfilled / stale")
        }
        val (filled, next) = makerQueueStep(t, q, limit, buy = true)
        if (!filled) return next.takeIf { it != t }
        val want = if (t.contracts > 0) t.contracts else ScalpVariants.clipOf(t.variantId) ?: ScalpRule.CONTRACTS
        val admitted = admitSize(t, limit, want) ?: return next.takeIf { it != t }
        val n = minOf(admitted.n, kotlin.math.floor((q.askSize(t.side) ?: 0.0) + 1e-9).toInt())
        if (n <= 0) return next.takeIf { it != t }
        val fee = com.dirk.kalshiodds.signal.trade.KalshiFee.makerFee(n, limit, com.dirk.kalshiodds.domain.CryptoMarkets.inferSeries(t.ticker))
        return next.copy(
            state = ScalpState.OPEN, contracts = n, entryPrice = limit, entryFeeUsd = fee, entryAtMs = q.nowMs,
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
    companion object ShadowLimits {
        const val MAX_SHADOWS = 500
        /** 0.3.69 green size: contracts per trade for scalpers net positive after fees. */
        const val GREEN_CLIP_DEFAULT = 25
        const val GREEN_MIN_TRADES = 20
        /** 1.0.9 note marker: entry taken while its scalper was benched (shadow, not counted in paper P&L). */
        const val BENCH_SHADOW = "bench=shadow"
    }
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

    private fun cappedEntry(t: ScalpTrade, atMs: Long): ScalpTrade {
        unlatchTrigger(t)
        return t.copy(
            state = ScalpState.NO_FILL, closedAtMs = atMs, restingLimit = null,
            note = "${t.note}; no fill: hard 50¢ buy-price ceiling")
    }

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
                val fgCode = codeOf(t)
                val reason = (if (t.isPrimary && FairGapTune.fairGapHoldExit(fgCode)) {
                    // 1.0.3: no stop / trail / fair TP for tweaked strategies — hold ≈4.5 min, else to settlement.
                    if (q.fresh() && FairGapTune.exit((q.nowMs - (t.entryAtMs ?: q.nowMs)) / 1000.0, entryView(fgCode) ?: explicit(fgCode)) != null) ScalpRule.ExitReason.FAIR_GAP_HOLD else null
                } else if (p.hold60) hold60Exit(t, q, p, entry)
                else if (p.strategy == ScalpStrategy.CF_REPRICE)
                    ScalpRule.cfRepriceExit(t.side, entry, t.entryAtMs, q, p)
                else ScalpRule.exitSignal(t.side, entry, q, p)) ?: return null
                if (reason == ScalpRule.ExitReason.STOP && t.isPrimary) addShadow(t, sf = false) { qq, _, _, _ ->
                    if (p.hold60) false
                    else if (p.strategy == ScalpStrategy.CF_REPRICE) ScalpRule.cfRepriceExit(t.side, entry, t.entryAtMs, qq, p.copy(stop = 9.0)) != null
                    else ScalpRule.exitSignal(t.side, entry, qq, p.copy(stop = 9.0)) != null }
                val urgent = reason == ScalpRule.ExitReason.STOP || reason == ScalpRule.ExitReason.TIME_STOP || reason == ScalpRule.ExitReason.CF_EXIT_BY_CLOSE
                if (urgent) {
                    val marked = t.copy(state = ScalpState.PENDING_EXIT, exitDecidedAtMs = q.nowMs, exitReason = reason.label, restingLimit = null)
                    return sellAtDisplayedBid(marked, q) ?: marked
                }
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
        val miss = { why: String -> unlatchTrigger(t); t.copy(state = ScalpState.NO_FILL, closedAtMs = q.nowMs, note = "${t.note}; no fill: $why") }
        if (!q.fresh() || q.nowMs >= q.closeMs) return miss("stale book")
        val ask = q.ask(t.side) ?: return miss("no ask")
        val ok = ScalpRule.buyPriceOk(ask) || ScalpFloors.isFavorite(t.variantId) ||
            FairGapTune.lessonAllowed(q, t.side, ask, q.fair(t.side) ?: t.fairAtSignal, t.contracts.coerceAtLeast(1), variantId = t.variantId)
        if (!ok) return cappedEntry(t, q.nowMs)
        if (ask > t.signalAsk + ScalpRule.SLIPPAGE + 1e-9) return miss(String.format(Locale.US, "ask moved to %s", com.dirk.kalshiodds.domain.KalshiQuoteDisplay.formatPriceCents(ask)))
        val size = q.askSize(t.side) ?: 0.0
        val clipCode = ScalpModels.modelOf(t)?.code ?: t.variantId
        val want = if (t.contracts > 0) t.contracts else StrategyAlgo.clip(controls.state.value, clipCode, ScalpVariants.clipOf(clipCode) ?: ScalpRule.CONTRACTS)
        val admitted = admitSize(t, ask, want) ?: return miss("sanity")
        val n = minOf(admitted.n, kotlin.math.floor(size + 1e-9).toInt())
        if (n <= 0) return miss("no displayed depth")
        return t.copy(
            state = ScalpState.OPEN,
            contracts = n,
            entryPrice = ask,
            entryFeeUsd = ScalpRule.orderFee(n, ask),
            entryAtMs = q.nowMs,
            note = String.format(Locale.US, "%s; filled %d @ %.1f¢", t.note, n, ask * 100)
        )
    }

    /**
     * 0.3.43 CF-reprice resting limit entry at the signal ask (touch-fill paper rule, PaperLimitFill): fills at exactly
     * the limit as soon as a later book shows ask ≤ limit, up to the displayed depth (partial allowed); cancelled
     * unfilled after [ScalpRule.CF_LIMIT_TTL_MS]. Touch fills are slightly optimistic vs real queue priority.
     */
    private fun fillRestingLimitEntry(t: ScalpTrade, q: ScalpRule.Quote): ScalpTrade? {
        val ok = ScalpRule.buyPriceOk(t.signalAsk) || ScalpFloors.isFavorite(t.variantId) ||
            FairGapTune.lessonAllowed(q, t.side, t.signalAsk, q.fair(t.side) ?: t.fairAtSignal, t.contracts.coerceAtLeast(1), variantId = t.variantId)
        if (!ok) return cappedEntry(t, q.nowMs)
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
        val closed = t.copy(state = ScalpState.CLOSED, closedAtMs = atMs, netUsd = net, exitReason = reason)
        ModelFilter.noteControl(closed.variantId, net, closed.side)
        if (liveExtras && !ModelFilter.isTwin(closed.variantId)) {
            ModelFilter.recordClosedScalp(closed)
        }
        return closed
    }

    /** Settlement: anything still held pays $1 / $0, no fee; pending entries become no-fills. */
    /**
     * Paper positions whose 15-minute window has already closed. Pending clips become no-fills.
     * Open clips are voided at the entry price (no invented settlement) so they cannot pin the book
     * after a Kalshi outage. Safe to call every tick.
     */
    fun expireOrphans(nowMs: Long): List<ScalpTrade> = synchronized(lock) { expireOrphansLocked(nowMs) }

    private fun expireOrphansLocked(nowMs: Long): List<ScalpTrade> {
        val out = ArrayList<ScalpTrade>()
        for (t in _all.value) {
            if (!isActive(t)) continue
            val closeAt = closeSeen[t.ticker.uppercase()] ?: ScalpTicker.closeMs(t.ticker) ?: continue
            if (nowMs < closeAt) continue
            val next = if (t.state == ScalpState.PENDING_ENTRY || t.remaining <= 0) {
                t.copy(state = ScalpState.NO_FILL, closedAtMs = nowMs, restingLimit = null, note = "${t.note}; no fill: window closed")
            } else {
                val px = t.entryPrice ?: 0.0
                val n = t.remaining
                close(
                    ScalpFills.sell(t, nowMs, n, n * px, 0.0, settlement = true),
                    nowMs,
                    "orphaned paper position: window closed"
                )
            }
            out += next
        }
        out.forEach { save(it) }
        return out
    }

    fun settle(ticker: String, result: String, atMs: Long = System.currentTimeMillis()): List<ScalpTrade> = synchronized(lock) {
        fleet.onSettled(ticker)
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

    /** 1.0.9 auto-bench: model codes whose new entries become shadow-only (paper P&L frozen, hypothetical still tracked). */
    @Volatile var benched: Set<String> = emptySet()
    /** 1.0.14: benched / frozen entries become shadows, so they must not reserve paper bankroll or window cap. */
    private fun shadowCode(code: String?): Boolean = code != null && benched.isNotEmpty() && code in benched
    private fun shadowEntry(t: ScalpTrade): Boolean = benched.isNotEmpty() && shadowCode(ScalpModels.modelOf(t)?.code)
    /** 1.0.9: called (cheaply, under the book lock) when a primary or bench-shadow trade settles. Arg = scalper code. */
    @Volatile var onSettled: ((String, ScalpTrade) -> Unit)? = null

    /** Force trade wins. Force bench is the only automatic-looking stop, and it is manual. */
    private fun shouldBench(code: String): Boolean {
        when (controls.state.value.benchOverrides[code]) {
            "ACTIVE" -> return false
            "BENCHED" -> return true
        }
        return code in benched
    }

    private fun algoOf(code: String?): Map<String, Double> =
        if (code.isNullOrBlank()) emptyMap() else controls.state.value.algo[code].orEmpty()

    private fun tuned(p: ScalpParams, code: String?): ScalpParams =
        if (code.isNullOrBlank()) p else StrategyAlgo.overlay(p, algoOf(code))

    private fun entryView(code: String): ScalpStrategySettings? {
        val raw = controls.state.value
        if (raw.strategySettings[code] == null && raw.algo[code].isNullOrEmpty()) return null
        return StrategyAlgo.view(raw, code)
    }

    private class Admitted(val n: Int, val clip: Int, val capped: Boolean)

    /**
     * Size that may open. Caps [want] at the scalper's own clip and logs when the
     * request was larger. The strategy floor stays on the entry gate; this is the
     * size and cash check, so a tenth-cent book can still lift a displayed ask.
     */
    private fun admitSize(t: ScalpTrade, price: Double, want: Int): Admitted? {
        val code = ScalpModels.modelOf(t)?.code ?: t.variantId
        val clip = ScalpParamSanity.ownClip(code, controls.state.value.settings(code).contracts)
        val window = ScalpModels.windowKeyOf(t.ticker)
        val n = minOf(want.coerceAtLeast(0), clip)
        if (want > clip) {
            gates.block(code, window, String.format(Locale.US, "sanity: size %d is above the clip %d", want, clip))
        }
        if (n <= 0) return null
        if (price.isFinite() && price > 0.0 && n * price > bankrollUsd() + 1.0) {
            gates.block(code, window, "sanity: clip costs more than free cash")
            return null
        }
        return Admitted(n, clip, want > clip)
    }

    /** Skip a new paper clip at a nonsense price or size. Logs the reason and does not throw. */
    private fun guardNewEntry(t: ScalpTrade): ScalpTrade {
        if (t.state != ScalpState.PENDING_ENTRY) return t
        val code = ScalpModels.modelOf(t)?.code ?: t.variantId
        val floor = ScalpFloors.cents(code) / 100.0
        val px = t.restingLimit ?: t.signalAsk
        val clip = ScalpParamSanity.ownClip(code, controls.state.value.settings(code).contracts)
        val window = ScalpModels.windowKeyOf(t.ticker)
        val sized = if (t.contracts > clip) {
            ScalpParamSanity.entryBlock(t.side, px, t.contracts, floor, bankrollUsd(), clip)?.let { gates.block(code, window, it) }
            t.copy(contracts = clip, note = "${t.note}; sanity: size ${t.contracts} capped at clip $clip")
        } else t
        val why = ScalpParamSanity.entryBlock(sized.side, px, sized.contracts, floor, bankrollUsd(), clip) ?: return sized
        gates.block(code, window, why)
        return sized.copy(state = ScalpState.NO_FILL, closedAtMs = sized.signalAtMs, note = "${sized.note}; $why")
    }

    /** Log a closed trade whose exit or P&L does not reconcile. The row is kept. */
    private fun noteSanity(t: ScalpTrade) {
        val code = ScalpModels.modelOf(t)?.code ?: t.variantId
        val window = ScalpModels.windowKeyOf(t.ticker)
        val q = _marks.value[t.ticker.uppercase()]
        val exitPx = if (t.soldContracts > 0) t.proceedsUsd / t.soldContracts else null
        val bid = q?.bid(t.side)
        val depth = q?.bidSize(t.side)
        ScalpParamSanity.exitBlock(exitPx, bid, t.soldContracts, depth)?.let { gates.block(code, window, it) }
        val entry = t.entryPrice
        val net = t.netUsd
        if (entry != null && exitPx != null && net != null) {
            val gap = ScalpParamSanity.pnlGap(entry, exitPx, t.soldContracts.coerceAtLeast(t.contracts), t.entryFeeUsd, t.exitFeeUsd, net)
            if (gap != null && kotlin.math.abs(gap) > 0.05) gates.block(code, window, "sanity: P&L does not reconcile")
        }
    }

    private fun benchShadow(t: ScalpTrade): ScalpTrade {
        if (!t.isPrimary) return t
        val code = ScalpModels.modelOf(t)?.code ?: return t
        if (!shouldBench(code)) return t
        return t.copy(ruleVersion = t.ruleVersion.removeSuffix("|P") + "|S", note = (t.note + "; " + BENCH_SHADOW).trimStart(';', ' '))
    }

    private fun save(tIn: ScalpTrade) {
        val prev = _all.value.firstOrNull { it.id == tIn.id }
        val incoming = if (prev == null) guardNewEntry(tIn) else tIn
        val tf = if (prev == null && incoming.state == ScalpState.PENDING_ENTRY) fairGapEntryFilter(settingsEntryFilter(incoming)) else incoming
        // Bench after the live entry gates, so the shadow book only holds entries the scalper would really have taken.
        val t0 = if (prev == null && tf.ruleVersion.endsWith("|P")) benchShadow(tf) else tf
        val t = if (liveExtras) withDetail(t0, prev) else t0
        if (t.state == ScalpState.CLOSED && prev != null && prev.state != ScalpState.CLOSED && (t.isPrimary || BENCH_SHADOW in t.note)) {
            noteSanity(t)
            onSettled?.let { cb -> ScalpModels.modelOf(if (t.isPrimary) t else t.copy(ruleVersion = t.ruleVersion.removeSuffix("|S") + "|P"))?.code?.let { runCatching { cb(it, t) } } }
        }
        // 0.3.67: enqueue DB+stats off the quote lock; flushBatches drains on a worker.
        writeQ.merge(t.id, t to prev) { old, neu -> neu.first to (old.second ?: neu.second) }
        if (writeQ.size >= 80) flushWrites() // 0.3.67: never let the queue grow unboundedly (soak / burst quotes)
        if (prev == null && liveExtras) spawnV2(if (t0 !== tIn) tIn else t) // benched originals still seed their v2 copies
        if (t.state == ScalpState.CLOSED && prev?.state != ScalpState.CLOSED && ScalpV2.isV2(t.variantId))
            ScalperBrain.parse(t.note)?.let { x -> ScalperBrain.learn(ScalpModels.modelOf(t)?.code ?: t.variantId, x, (t.netUsd ?: 0.0) > 0.0) }
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

    private fun unlatchClosed(batch: List<ScalpTrade>) {
        for (t in batch) {
            if (Research.isResearch(t.variantId) && t.state == ScalpState.CLOSED) continue
            if (t.state == ScalpState.CLOSED || t.state == ScalpState.NO_FILL) {
                if (_all.value.none { isActive(it) && it.ticker.equals(t.ticker, true) && it.variantId == t.variantId }) {
                    unlatchTrigger(t)
                }
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
                if (t.isPrimary && t.state == ScalpState.CLOSED && prev != null && prev.state != ScalpState.CLOSED) aiLearn(t)
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

    /**
     * Archive the displayed scalping run and drop open clips from RAM. No Kalshi order.
     * Holds the quote lock and the write lock so a flush cannot put a pre-reset row back.
     */
    fun resetDisplayedRun(atMs: Long, note: String, cashUsd: Double): Int = synchronized(lock) {
        synchronized(flushLock) {
            writeQ.clear()
            val flattened = _all.value.filter { isActive(it) }.map { ScalpBankrollReset.flatten(it, atMs) }
            val n = store.archiveDisplayedRun(flattened, atMs, note, cashUsd)
            _all.value = emptyList()
            _trades.value = emptyList()
            _stats.value = store.loadStats()
            v2Evidence = emptyList()
            peakBid.clear()
            v2Armed.clear()
            controlDaily.set(ScalpModels.dayEt(atMs) to 0.0)
            n
        }
    }
    /** 0.3.68 aggregate reads (≈ hundreds of rows, indexed). Call off Main. */
    fun loadScalps(): List<ScalpTrade> = store.loadScalps()
    fun loadExits() = store.loadExits()
    fun loadWindows(limitWindows: Int = 48) = store.loadWindows(limitWindows)
    fun loadDaily(dayEt: String) = store.loadDaily(dayEt)
    fun loadDailySince(dayEtInclusive: String) = store.loadDailySince(dayEtInclusive)
    fun pageTrades(scalperId: String, afterClosedAtMs: Long? = null, afterId: String? = null, limit: Int = 50) =
        store.pageTrades(scalperId, afterClosedAtMs, afterId, limit)
    fun windowTrades(scalperId: String, ticker: String) = store.windowTrades(scalperId, ticker)
    fun windowPrimaryTrades(ticker: String) = store.windowPrimaryTrades(ticker)
    fun unsyncedClosed(limit: Int) = store.unsyncedClosed(limit)
    fun markSynced(ids: List<String>) = store.markSynced(ids)
    fun unsyncedScalpResets() = store.unsyncedScalpResets()
    fun markScalpResetsSynced(atMs: List<Long>) = store.markScalpResetsSynced(atMs)
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
        val seeds = (listOf(ScalpV2.spec(ScalpV2.v2Id(t.variantId))) + ScalpV2.extrasFor(t.variantId)).filter { it.id in KeptScalpers.IDS }
        if (seeds.isEmpty()) return
        val origin = ScalpModels.modelOf(t)?.label ?: t.variantId
        for (seed in seeds) {
            val model = ScalpModels.modelByCode(seed.id) ?: continue
            // A closed contract is not complete evidence while this model still awaits an exit/settlement.
            val unfinished = _all.value.asSequence().filter { isActive(it) && ScalpModels.modelOf(it) == model }
                .map { it.windowKey }.toSet()
            val cfg = controls.state.value
            if (!cfg.permits(model.code)) continue
            val learned = ScalpV2.plan(seed.id, if (cfg.adaptive && !cfg.manual) v2Evidence else emptyList(), t.signalAtMs, model.code, unfinished)
            val feedback = controls.feedback(model.code)
            // Every completed trade updates this EWMA; completed-window evidence still controls promotion.
            val informed = if (learned.adjusted && feedback.trades > 0 && feedback.recentNetPerContract < 0)
                learned.copy(arm = (learned.arm + feedback.recentNetPerContract * .1).coerceIn(.01, learned.arm)) else learned
            val knobs = StrategyAlgo.view(cfg, model.code)
            val close = ScalpTicker.closeMs(t.ticker)
            // Missing or untrained AI does not block a paper entry. The base signal is the strategy.
            val minuteIn = close?.let { 15.0 - (it - t.signalAtMs) / 60_000.0 } ?: 0.0
            val gq = _marks.value[t.ticker.uppercase()]
            val gp = gq?.let { GbmModel.pSide(it, t.side, dm60(t.ticker.uppercase(), it.nowMs)) }
            val bx = ScalperBrain.features(t.signalAsk, gp ?: t.fairAtSignal.takeIf { it.isFinite() }, minuteIn.coerceIn(0.0, 15.0))
            val conf = if (ScalperBrain.trained(model.code)) ScalperBrain.p(model.code, bx) else Double.NaN
            val checkKey = "${t.ticker}|${model.code}|${t.side}"
            strategyEntryChecks[checkKey] = t.signalAtMs
            if (strategyEntryChecks.size > 2000) strategyEntryChecks.keys.filter { (ScalpTicker.closeMs(it.substringBefore('|')) ?: 0) < t.signalAtMs }.forEach { strategyEntryChecks.remove(it) }
            val plan = cfg.plan(informed, model.code)
            // Hard rule: every Kalshi Scalper strategy trails 3¢ below the peak bid, active from entry.
            val seeded = plan.spec.copy(trail = ScalpV2.TRAIL)
            val (minPx, maxPx) = ScalpParamSanity.v2Band(model.code, knobs.minEntryCents / 100.0, seeded.maxAsk)
            val sp = seeded.copy(maxAsk = maxPx, clip = minOf(seeded.clip, ScalpParamSanity.ownClip(model.code, knobs.contracts)))
            if (_all.value.any { isActive(it) && it.variantId == sp.id && it.ticker.equals(t.ticker, true) }) continue
            val stopPct = knobs.stopLossPercent ?: cfg.stopLossPercent
            val stop = plan.stop
            save(ScalpTrade(id = idFactory(), ticker = t.ticker.uppercase(), side = t.side, state = ScalpState.PENDING_ENTRY,
                signalAtMs = t.signalAtMs, signalAsk = t.signalAsk, fairAtSignal = t.fairAtSignal,
                note = String.format(Locale.US, "v2 copy of %s (%s)%s · ask ≤ %.0f¢ · trail %.0f¢ · clip %d%s · v2stop=%.4f",
                    origin, t.variantId, if (sp.tag.isNotEmpty()) " " + sp.tag else "", sp.maxAsk * 100, sp.trail * 100, sp.clip,
                    if (sp.walk) " depth-walk" else "", stop) +
                    String.format(Locale.US, " · v2ask=%.4f · v2trail=%.4f · v2arm=%.4f · %s", sp.maxAsk, sp.trail, plan.arm, plan.note + "; learned ${feedback.trades} closed; recent net/ct ${feedback.recentNetPerContract}") +
                    String.format(Locale.US, " · v2tp=%.4f · v2hold=%d · v2clip=%d", (knobs.takeProfitCents ?: if (cfg.manual) cfg.takeProfitCents else 0) / 100.0, knobs.maxHoldSeconds ?: cfg.maxHoldSeconds, sp.clip) +
                    (if (true) " · v2peakrule=1 · v2stopPct=$stopPct · exit policy: $stopPct% below fill / ${Math.round(sp.trail * 100)}¢ below bid peak, active from entry" else "") +
                    String.format(Locale.US, " · %s · conf=%.3f · v2min=%.4f · v2until=%d · v2sidecap=%d · v2cadence=%d", ScalperBrain.tag(bx), conf, minPx, knobs.untilMinute, knobs.maxOpenPerSide, knobs.cadenceSeconds),
                ruleVersion = "${ScalpRule.VERSION}|${sp.id}|P"))
        }
    }

    /**
     * Kalshi Scalper LLM paper strategy entry. The loop may propose again through the window
     * (not the last 60 seconds). This card is removed, so the call does not queue a trade. PAPER ONLY.
     */
    /**
     * Queue one paper clip for the built-in / cloud LLM strategy. No Kalshi order.
     * A second clip on the same ticker and side waits until the first is closed.
     */
    fun llmEntry(q: ScalpRule.Quote, side: String, conf: Double, enabled: Boolean = true): String? = synchronized(lock) {
        if (!enabled) return "paper disabled"
        if (!q.fresh()) return "quote is stale"
        if (q.tauS <= 60.0) return "last 60 seconds"
        val want = side.trim().uppercase()
        if (want != "YES" && want != "NO") return "side must be YES or NO"
        val ask = q.ask(want) ?: return "no ask"
        if (!ScalpRule.buyPriceOk(ask)) return "ask above 55¢"
        val ticker = q.ticker.uppercase()
        val open = _all.value.any { it.variantId == "v2-llm" && it.ticker.equals(ticker, true) && it.side == want && isActive(it) }
        if (open) return "already open on this ticker"
        save(ScalpTrade(
            id = idFactory(),
            ticker = ticker,
            side = want,
            state = ScalpState.PENDING_ENTRY,
            signalAtMs = q.nowMs,
            signalAsk = ask,
            fairAtSignal = q.fair(want) ?: conf.coerceIn(0.0, 1.0),
            contracts = ScalpRule.CONTRACTS,
            note = "v2-llm paper",
            ruleVersion = "${ScalpRule.VERSION}|v2-llm|P"
        ))
        return "paper entry queued"
    }

    // ---------------- Kalshi Scalper 1.0.2: Research lane + per-strategy sliders for every strategy ----------------
    private val _researchStatus = MutableStateFlow("Research: waiting for a BTC quote")
    /** Fair value source, BRTI-vs-Binance gap and current settlement-aware p(YES), for the Research cards. */
    val researchStatus: StateFlow<String> = _researchStatus.asStateFlow()

    private fun codeOf(t: ScalpTrade): String {
        ScalpModels.modelOf(t)?.code?.let { return it }
        // Fair-gap params that are not a live card still use the fair family's own entry and exit, not the loser gate.
        if (ScalpParams.byId(t.variantId)?.strategy == ScalpStrategy.FAIR_GAP) return ScalpModels.Model.FAIR_GAP.code
        return t.variantId
    }
    private fun explicit(code: String): ScalpStrategySettings? = controls.state.value.strategySettings[code]?.normalized()

    /** "AI learns": nudge one slider of this strategy from its own closed paper trades (bounded; see [Research.learn]). */
    private fun aiLearn(t: ScalpTrade) {
        val code = codeOf(t)
        val cur = controls.state.value
        val s = cur.settings(code)
        if (!s.aiLearns) return
        val defMax = when {
            Research.isResearch(code) -> 99
            ScalpV2.isV2(code) -> Math.round(ScalpV2.spec(code).maxAsk * 100).toInt()
            else -> FairGapTune.defaultMaxCents(code) ?: Math.round(ScalpRule.ENTRY_HI * 100).toInt()
        }
        val nx = Research.learn(code, s, t.netUsd ?: 0.0, defMax)
        // 1.0.4: atomic with the AI tab's settings cards (same learning step, re-read under the repository lock).
        if (nx != s) controls.update { c2 -> val s2 = c2.settings(code); val n2 = if (s2 == s) nx else Research.learn(code, s2, t.netUsd ?: 0.0, defMax)
            if (n2 == s2) c2 else c2.copy(strategySettings = c2.strategySettings + (code to n2)) }
    }

    /** Original strategies: Dirk's price range / until-minute sliders gate new entries (v2, research and LLM have their own). */
    private fun settingsEntryFilter(t: ScalpTrade): ScalpTrade {
        if (t.state != ScalpState.PENDING_ENTRY || !t.isPrimary || ScalpV2.isV2(t.variantId) || Research.isResearch(t.variantId)) return t
        val code = codeOf(t)
        val s = entryView(code) ?: explicit(code)
        val hasDefault = FairGapTune.defaultMaxCents(code) != null || code == "fairh60"
        if (s == null && !hasDefault) return t
        val px = t.signalAsk
        val bad = FairGapTune.priceBlocked(code, px, s)
        return if (!bad) t else t.copy(state = ScalpState.NO_FILL, closedAtMs = t.signalAtMs, note = "${t.note}; skipped by your strategy sliders (price range / until minute)")
    }

    /** 1.0.3: losing strategies must also pass the winning fair-gap rule (see [FairGapTune]); winners are untouched. */
    private fun fairGapEntryFilter(t: ScalpTrade): ScalpTrade {
        if (t.state != ScalpState.PENDING_ENTRY || !t.isPrimary) return t
        if (FairGapTune.exempt(t.variantId)) return t
        val code = codeOf(t)
        if (FairGapTune.exempt(code) || !FairGapTune.entryGated(code)) return t
        val other = if (t.side == "YES") "NO" else "YES"
        val taken = _all.value.any { it.isPrimary && it.ticker.equals(t.ticker, true) && it.side == other && it.state != ScalpState.NO_FILL && codeOf(it) == code }
        val n = if (t.contracts > 0) t.contracts else ScalpRule.CONTRACTS
        val why = FairGapTune.entryBlock(_marks.value[t.ticker.uppercase()], t.side, t.signalAsk, n, entryView(code) ?: explicit(code), taken, t.signalAtMs) ?: return t
        return t.copy(state = ScalpState.NO_FILL, closedAtMs = t.signalAtMs, note = "${t.note}; $why")
    }

    /** Original strategies: optional stop % below fill, take profit after fees and max hold from Dirk's sliders. */
    private fun settingsExits(mine: List<ScalpTrade>, q: ScalpRule.Quote): Map<String, ScalpTrade> {
        val out = HashMap<String, ScalpTrade>()
        for (t in mine) {
            if (t.state != ScalpState.OPEN || ScalpV2.isV2(t.variantId) || Research.isResearch(t.variantId) || ForecastScalp.isForecast(t) || HftMathEngines.isHft(t.variantId) || TwentyScalperFleet.isFleet(t.variantId)) continue
            val s = entryView(codeOf(t)) ?: continue
            val bid = q.bid(t.side)?.takeIf { it > 0 } ?: continue
            val entry = t.entryPrice ?: continue
            val held = (q.nowMs - (t.entryAtMs ?: q.nowMs)) / 1000
            val reason = when {
                s.stopLossPercent != null && bid <= entry * (1 - s.stopLossPercent / 100.0) + 1e-9 -> "your slider: stop ${s.stopLossPercent}% below fill"
                (s.takeProfitCents ?: 0) > 0 && ScalpV2.netPerContract(t, bid) >= s.takeProfitCents!! / 100.0 - 1e-9 -> "your slider: take profit ${s.takeProfitCents}¢ after fees"
                (s.maxHoldSeconds ?: 0) > 0 && held >= s.maxHoldSeconds!! -> "your slider: max hold ${s.maxHoldSeconds}s"
                else -> null
            } ?: continue
            out[t.id] = t.copy(state = ScalpState.PENDING_EXIT, exitDecidedAtMs = q.nowMs, exitReason = reason)
        }
        return out
    }

    private fun researchNote(note: String, key: String, v: Double) = "$note · $key=" + String.format(Locale.US, "%.4f", v)

    /** Research paper strategies (BTC only). Exempt from the 3¢ trail / window-start / >95% rules. */
    private fun stepResearch(q: ScalpRule.Quote, enabled: Boolean): List<ScalpTrade> {
        if (!liveExtras || ScalpParams.coinOf(q.ticker) != ShockFav.TRADE_COIN) return emptyList()
        val f = Research.fair(q) ?: return emptyList()
        _researchStatus.value = String.format(Locale.US, "Fair value: %s · p(YES) %.0f%% · BRTI vs Binance gap %s",
            if (f.brti) "BRTI live (settlement average)" else "FALLBACK exchange spot (no fresh BRTI)", f.pYes * 100,
            f.gapUsd?.let { String.format(Locale.US, "%+.2f", it) } ?: "n/a")
        val cfg = controls.state.value
        val rp = cfg.research.normalized()
        val out = ArrayList<ScalpTrade>()
        val mine = _all.value.filter { it.ticker.equals(q.ticker, true) && Research.isResearch(it.variantId) }
        fun pOf(side: String) = if (side == "YES") f.pYes else 1.0 - f.pYes
        val midOf = { side: String -> q.bid(side)?.let { b -> q.ask(side)?.let { (b + it) / 2 } } }
        for (t in mine) {
            if (!isActive(t)) continue
            val ps = pOf(t.side)
            when (t.state) {
                ScalpState.PENDING_ENTRY -> if (t.restingLimit != null) {
                    val lim = t.restingLimit
                    if (q.tauS <= 60 || ps - lim < rp.makerEdgeCents / 200.0) { out += t.copy(state = ScalpState.NO_FILL, closedAtMs = q.nowMs, note = "${t.note}; maker bid cancelled (fair moved or final minute)"); continue }
                    val ask = q.ask(t.side) ?: continue
                    if (ask <= lim + 1e-9) { // at the bid or through it
                        val n = ScalpV2.savedNumber(t.note, "rn", rp.clip.toDouble()).toInt().coerceAtLeast(1)
                        out += t.copy(state = ScalpState.OPEN, contracts = n, entryPrice = lim, entryFeeUsd = 0.0, entryAtMs = q.nowMs,
                            note = researchNote("${t.note}; maker fill @ ${Math.round(lim * 100)}¢ (fee 0)", "fillmid", midOf(t.side) ?: lim))
                    }
                } else {
                    val age = q.nowMs - t.signalAtMs
                    if (age > 6_000 || q.tauS <= 0) { out += t.copy(state = ScalpState.NO_FILL, closedAtMs = q.nowMs, note = "${t.note}; no fill within 6 s"); continue }
                    if (age < 1_000 || !q.fresh()) continue
                    val ask = q.ask(t.side) ?: continue
                    if (ask > t.signalAsk + 0.01 + 1e-9) continue
                    val want = ScalpV2.savedNumber(t.note, "rn", rp.clip.toDouble()).toInt().coerceAtLeast(1)
                    val n = minOf(want, kotlin.math.floor((q.askSize(t.side) ?: 0.0) + 1e-9).toInt())
                    if (n <= 0) continue
                    out += t.copy(state = ScalpState.OPEN, contracts = n, entryPrice = ask, entryFeeUsd = ScalpRule.orderFee(n, ask), entryAtMs = q.nowMs,
                        note = String.format(Locale.US, "%s; filled %d @ %.0f¢ (fee $%.2f)", t.note, n, ask * 100, ScalpRule.orderFee(n, ask)))
                }
                ScalpState.OPEN, ScalpState.PENDING_EXIT -> {
                    var cur = t
                    if (t.variantId == Research.MAKER) {
                        val fm = ScalpV2.savedNumber(t.note, "fillmid", Double.NaN); val m = midOf(t.side); val held = q.nowMs - (t.entryAtMs ?: q.nowMs)
                        if (fm.isFinite() && m != null) {
                            if (held >= 5_000 && !t.note.contains("drift5=")) cur = cur.copy(note = researchNote(cur.note, "drift5", m - fm))
                            if (held >= 30_000 && !cur.note.contains("drift30=")) cur = cur.copy(note = researchNote(cur.note, "drift30", m - fm))
                        }
                    }
                    if (t.variantId == Research.HOLD || t.variantId == Research.KELLY) {
                        val bid = q.bid(t.side)?.takeIf { it > 0 }
                        val reason = cur.exitReason.takeIf { cur.state == ScalpState.PENDING_EXIT } ?: bid?.let { Research.fairExit(ps, it, cur.entryPrice ?: 1.0, cur.remaining.coerceAtLeast(1)) }
                        if (reason != null && bid != null && q.fresh()) {
                            val base = if (cur.state == ScalpState.OPEN) cur.copy(state = ScalpState.PENDING_EXIT, exitDecidedAtMs = q.nowMs, exitReason = reason) else cur
                            val n = minOf(base.remaining, kotlin.math.floor((q.bidSize(t.side) ?: 0.0) + 1e-9).toInt())
                            cur = if (n <= 0) base else ScalpFills.sell(base, q.nowMs, n, n * bid, ScalpRule.orderFee(n, bid)).let { if (it.remaining <= 0) close(it, q.nowMs, reason) else it }
                        }
                    }
                    if (cur != t) out += cur
                }
                else -> {}
            }
        }
        if (!enabled || q.tauS <= 0 || !q.fresh()) {
            if (q.tauS <= 0) {
                val mark = "|${q.ticker.uppercase()}|"
                researchEdge.removeAll { it.contains(mark) }
            }
            return out
        }
        var exposure = (mine.filter { isActive(it) } .map { o -> out.firstOrNull { it.id == o.id } ?: o }).filter { isActive(it) }.sumOf { activeCost(it) }
        for (id in Research.IDS) {
            if (id !in KeptScalpers.IDS) continue
            if (com.dirk.kalshiodds.decision.insights.ScalpFreeze.blocksRestored(id, cfg.benchOverrides[id])) continue
            if (!cfg.permits(id)) continue
            val activeClip = mine.any { it.variantId == id && isActive(it) }
            val s = StrategyAlgo.view(cfg, id)
            val edge = (s.edgeCents ?: Research.defaultEdge(id)).toDouble()
            // r-late and r-late-t1/t2 use their own window / band / max gap. Sliders and AI algo overrides still win the band.
            val late = Research.isLate(id)
            val fgBand = Research.lateBand(id, entryView(id) ?: explicit(id))
            val lo = if (late) Math.round(fgBand.lo * 100).toInt() else maxOf(s.minEntryCents, 1)
            val hi = if (late) Math.round(fgBand.hi * 100).toInt() else (s.maxEntryCents ?: 99)
            if (late && !Research.lateWindowOk(q.tauS, id, rp.lateMinS, rp.lateMaxS)) {
                clearEdge(researchEdge, id, q.ticker)
                continue
            }
            val clip = s.contracts ?: rp.clip
            val kellyCap = if (rp.windowCapUsd <= 0.0) rp.kellyBankrollUsd else (rp.windowCapUsd - exposure).coerceAtLeast(0.0)
            fun underCap(cost: Double) = shadowCode(id) || rp.windowCapUsd <= 0.0 || exposure + cost <= rp.windowCapUsd + 1e-9
            val options = ArrayList<Triple<Double, Double, ScalpTrade>>()
            for (side in listOf("YES", "NO")) {
                val ps = pOf(side); val ask = q.ask(side) ?: continue
                if (id == Research.MAKER) {
                    val lim = kotlin.math.floor((ps - rp.makerEdgeCents / 100.0) * 100 + 1e-9) / 100.0
                    if (lim !in maxOf(0.05, lo / 100.0)..minOf(0.95, hi / 100.0) || lim >= ask - 0.005) continue
                    val reserve = clip * lim
                    if (!underCap(reserve)) continue
                    options += Triple(ps - lim, if (shadowCode(id)) 0.0 else reserve, ScalpTrade(id = idFactory(), ticker = q.ticker.uppercase(), side = side, state = ScalpState.PENDING_ENTRY, signalAtMs = q.nowMs,
                        signalAsk = lim, fairAtSignal = ps, restingLimit = lim, restingSinceMs = q.nowMs,
                        note = String.format(Locale.US, "Research maker: resting bid %.0f¢ = fair %.1f¢ − %.1f¢ · rn=%d", lim * 100, ps * 100, rp.makerEdgeCents, clip),
                        ruleVersion = "${ScalpRule.VERSION}|$id|P"))
                    continue
                }
                if (ask < lo / 100.0 - 1e-9 || ask > hi / 100.0 + 1e-9) continue
                val hold = late
                val n = if (id == Research.KELLY) minOf(Research.kellyContracts(ps, ask, rp.kellyFraction, rp.kellyBankrollUsd, kellyCap), 1000) else clip
                if (n <= 0 || !Research.gateOk(ps, ask, n, hold, edge)) continue
                val scored = Research.edgeAfterCosts(ps, ask, n, hold)
                if (late && (!f.brti || scored > Research.lateMaxGapDollars(id) + 1e-9)) continue
                val cost = n * ask + ScalpRule.orderFee(n, ask)
                if (!underCap(cost)) continue
                options += Triple(scored, if (shadowCode(id)) 0.0 else cost, ScalpTrade(id = idFactory(), ticker = q.ticker.uppercase(), side = side, state = ScalpState.PENDING_ENTRY, signalAtMs = q.nowMs,
                    signalAsk = ask, fairAtSignal = ps,
                    note = String.format(Locale.US, "Research %s: fair %.1f¢ vs ask %.0f¢ · edge after fees %.1f¢ (gate %.0f¢) · %s · rn=%d", id, ps * 100, ask * 100,
                        scored * 100, edge, if (f.brti) "BRTI" else "spot fallback", n),
                    ruleVersion = "${ScalpRule.VERSION}|$id|P"))
            }
            val best = SidePick.onlyMaxBy(options) { it.first }
            if (best == null) {
                clearEdge(researchEdge, id, q.ticker)
                continue
            }
            val sideKey = edgePrefix(id, q.ticker) + best.third.side
            if (activeClip || sideKey in researchEdge) {
                armEdge(researchEdge, id, q.ticker, best.third.side)
                continue
            }
            armEdge(researchEdge, id, q.ticker, best.third.side)
            exposure += best.second
            out += best.third
        }
        return out
    }

    /** Lift ask levels (best first) up to [maxPx] for [want] contracts → (filled, $ cost, one order fee). */
    private fun walkBuy(q: ScalpRule.Quote, side: String, want: Int, maxPx: Double): Triple<Int, Double, Double> {
        var n = 0; var usd = 0.0
        val lots = ArrayList<Pair<Double, Int>>()
        for ((px, sz) in q.askLadder(side)) { if (n >= want || px > maxPx + 1e-9) break; val k = minOf(want - n, kotlin.math.floor(sz + 1e-9).toInt()); if (k > 0) { n += k; usd += k * px; lots += px to k } }
        return Triple(n, usd, ScalpRule.sweepFee(lots))
    }

    /** Hit bid levels (best first) for up to [want] contracts → (sold, $ proceeds, one order fee). */
    private fun walkSell(q: ScalpRule.Quote, side: String, want: Int): Triple<Int, Double, Double> {
        var n = 0; var usd = 0.0
        val lots = ArrayList<Pair<Double, Int>>()
        for ((px, sz) in q.bidLadder(side)) { if (n >= want || px <= 0.0) break; val k = minOf(want - n, kotlin.math.floor(sz + 1e-9).toInt()); if (k > 0) { n += k; usd += k * px; lots += px to k } }
        return Triple(n, usd, ScalpRule.sweepFee(lots))
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
            if (t.ticker != key || !ScalpV2.isV2(t.variantId) || ModelFilter.isTwin(t.variantId) || !isActive(t)) continue
            when (t.state) {
                ScalpState.PENDING_ENTRY -> {
                    val age = q.nowMs - t.signalAtMs
                    val code = ScalpModels.modelOf(t)?.code ?: t.variantId
                    val blocked = cfg.entryBlock(code, openCount, controls.entries(q.nowMs), controlDailyNet + unflushedNet)
                    if (blocked != null) { out += t.copy(state = ScalpState.NO_FILL, closedAtMs = q.nowMs, note = "${t.note}; $blocked"); continue }
                    if (q.tauS <= ScalpV2.EXIT_BY_S || age > ScalpV2.GIVE_UP_MS) { out += t.copy(state = ScalpState.NO_FILL, closedAtMs = q.nowMs, note = "${t.note}; no fill: final minute, expired signal, or no executable entry"); continue }
                    val sideKey = "$code|${t.side}"
                    if (age < ScalpV2.ENTRY_DELAY_MS || !ScalpV2.fresh(q) || q.bookAtMs <= t.signalAtMs) continue
                    pendingEntryChecks[t.id] = q.nowMs
                    if (pendingEntryChecks.size > 2000) {
                        val activeIds = _all.value.filter { it.state == ScalpState.PENDING_ENTRY }.map { it.id }.toSet()
                        pendingEntryChecks.keys.retainAll(activeIds)
                    }
                    val seed = ScalpV2.spec(t.variantId)
                    val (minAsk, maxAsk) = ScalpParamSanity.v2Band(
                        code,
                        ScalpV2.savedNumber(t.note, "v2min", ScalpFloors.cents(code) / 100.0),
                        ScalpV2.savedNumber(t.note, "v2ask", seed.maxAsk)
                    )
                    val noteClip = ScalpV2.savedNumber(t.note, "v2clip", seed.clip.toDouble()).toInt().coerceAtLeast(1)
                    val own = ScalpParamSanity.ownClip(code, cfg.settings(code).contracts)
                    val ask = q.ask(t.side) ?: continue
                    val v2Clip = minOf(noteClip, own)
                    val v2Lesson = FairGapTune.lessonAllowed(q, t.side, ask, q.fair(t.side) ?: t.fairAtSignal, v2Clip, variantId = code)
                    val effMaxAsk = if (v2Lesson || ScalpFloors.isFavorite(code)) {
                        maxOf(maxAsk, t.signalAsk + 0.04, ask + 0.01).coerceAtMost(FairGapTune.HARD_CAP)
                    } else {
                        minOf(ScalpRule.ENTRY_HI, maxAsk)
                    }
                    val sp = seed.copy(maxAsk = effMaxAsk, clip = v2Clip)
                    val askOk = if (v2Lesson || ScalpFloors.isFavorite(code)) ask <= sp.maxAsk + 1e-9 else ScalpV2.entryOk(ask, sp.maxAsk)
                    if (ask < minAsk - 1e-9 || !askOk) {
                        if (ask < minAsk - 1e-9) {
                            ScalpParamSanity.entryBlock(t.side, ask, sp.clip, minAsk, bankrollUsd(), sp.clip)?.let {
                                gates.block(code, ScalpModels.windowKeyOf(key), it)
                            }
                        }
                        continue
                    }
                    if (FairGapTune.entryGated(code) && FairGapTune.entryBlock(q, t.side, ask, sp.clip, entryView(code) ?: explicit(code), false, q.nowMs) != null) continue
                    val sideCap = cfg.settings(code).maxOpenPerSide
                    if (sideCap > 0 && (openByModelSide[sideKey] ?: 0) >= sideCap) {
                        gates.block(code, ScalpModels.windowKeyOf(key), "open at once per side is full")
                        continue
                    }
                    if (cfg.dipCents > 0) {
                        val historyPrices = history[key].orEmpty().filter { it.atMs in (q.nowMs - cfg.windowSeconds * 1000L)..q.nowMs }
                            .groupBy { it.atMs / 1000L }.values.map { it.maxBy { p -> p.atMs } }.sortedBy { it.atMs }
                            .mapNotNull { if (t.side == "YES") it.yesBid else it.yesAsk?.let { a -> 1 - a } }
                        if (!cfg.dipOk(historyPrices, ask)) continue
                    }
                    if (sp.walk) {
                        // 0.3.68: lift real ask levels up to max ask; partial fill allowed, avg price gets worse with depth.
                        val (n, usd, fee) = cfg.walkBuy(q.askLadder(t.side).filter { it.first >= minAsk - 1e-9 }, sp.clip, sp.maxAsk)
                        if (n <= 0 || n > sp.clip) {
                            if (n > sp.clip) gates.block(code, ScalpModels.windowKeyOf(key),
                                String.format(Locale.US, "sanity: size %d is above the clip %d", n, sp.clip))
                            continue
                        }
                        val avg = usd / n
                        controls.recordEntry(q.nowMs); openCount++; openByModelSide[sideKey] = (openByModelSide[sideKey] ?: 0) + 1
                        gates.entered(code, ScalpModels.windowKeyOf(key))
                        out += t.copy(state = ScalpState.OPEN, contracts = n, entryPrice = avg, entryFeeUsd = fee, entryAtMs = q.nowMs,
                            note = ScalpV2.entryRiskNote(String.format(Locale.US, "%s; walked book: filled %d/%d avg %.2f¢ (top %.0f¢) after %.1fs", t.note, n, sp.clip, avg * 100, ask * 100, age / 1000.0), q.bid(t.side)))
                        continue
                    }
                    val n = minOf(cfg.affordableContracts(ask, sp.clip), kotlin.math.floor((q.askSize(t.side) ?: 0.0) + 1e-9).toInt())
                    if (n <= 0 || n > sp.clip) continue
                    controls.recordEntry(q.nowMs); openCount++; openByModelSide[sideKey] = (openByModelSide[sideKey] ?: 0) + 1
                    gates.entered(code, ScalpModels.windowKeyOf(key))
                    out += t.copy(state = ScalpState.OPEN, contracts = n, entryPrice = ask, entryFeeUsd = ScalpRule.orderFee(n, ask), entryAtMs = q.nowMs,
                        note = ScalpV2.entryRiskNote(String.format(Locale.US, "%s; filled %d @ %.0f¢ after %.1fs", t.note, n, ask * 100, age / 1000.0), q.bid(t.side)))
                }
                ScalpState.OPEN, ScalpState.PENDING_EXIT -> {
                    if (q.nowMs >= q.closeMs) continue
                    val fgCode = ScalpModels.modelOf(t)?.code ?: t.variantId
                    val fgT = FairGapTune.fairGapHoldExit(fgCode)
                    val maxHold = if (fgT) FairGapTune.holdS(entryView(fgCode) ?: explicit(fgCode)).toDouble() else ScalpV2.savedNumber(t.note, "v2hold", 0.0)
                    val holdExpired = maxHold > 0 && q.nowMs - (t.entryAtMs ?: t.signalAtMs) >= maxHold * 1000
                    if (!ScalpV2.fresh(q)) {
                        // Time-based risk decisions do not require liquidity; execution always does.
                        if (t.state == ScalpState.OPEN && ((!fgT && q.tauS <= ScalpV2.EXIT_BY_S) || holdExpired))
                            out += t.copy(state = ScalpState.PENDING_EXIT, exitDecidedAtMs = q.nowMs,
                                exitReason = if (holdExpired) "V2 user max hold reached" else "v2 exit before close: stale or gapped book")
                        continue
                    }
                    val bid = q.bid(t.side)?.takeIf { it.isFinite() && it > 0.0 }
                    val entry = t.entryPrice ?: continue
                    val stop = ScalpV2.savedStop(t)
                    val pk = maxOf(peakBid[t.id] ?: 0.0, ScalpV2.savedNumber(t.note, "v2peak", 0.0), bid ?: 0.0)
                        .also { peakBid[t.id] = it }
                    val sp = ScalpV2.spec(t.variantId).let { if (t.contracts > ScalpV2.CLIP) it.copy(walk = true) else it }
                    val trail = ScalpV2.savedNumber(t.note, "v2trail", sp.trail).coerceIn(0.01, 0.20)
                    val arm = ScalpV2.savedNumber(t.note, "v2arm", stop).coerceIn(0.01, 0.99)
                    val wasArmed = t.id in v2Armed || ScalpV2.savedNumber(t.note, "v2armed", 0.0) == 1.0
                    // Reconstruct arming from the persisted peak, including after process death.
                    val armed = ScalpV2.peakRule(t) || wasArmed || ScalpV2.netPerContract(t, pk) >= arm - 1e-9
                    if (armed) v2Armed += t.id
                    val note = ScalpV2.tag(ScalpV2.tag(t.note, "v2peak", String.format(Locale.US, "%.6f", pk)), "v2armed", if (armed) "1" else "0")
                    val marked = t.copy(note = note)
                    val target = ScalpV2.savedNumber(t.note, "v2tp", 0.0)
                    val timeExit = if (holdExpired) (if (fgT) "fair-gap rule: ${maxHold.toInt()}s hold done" else "V2 user max hold reached") else null
                    val priceExit = if (fgT) null else bid?.let {
                        if (ScalpV2.dipFamily(fgCode)) {
                            val cents = ScalpV2.dipStopCents(fgCode, entryView(fgCode)?.stopCents)
                            val dist = ScalpV2.dipStopDistance(entry, t.contracts, cents)
                            val strategyTarget = ScalpVariants.fairParams(fgCode)?.target
                                ?: ScalpParams.byId(KeptScalpers.V2_DIP_BASE)?.target ?: 0.0
                            val n = t.contracts.coerceAtLeast(1)
                            ScalpV2.dipExit(entry, it, pk, trail, dist, n)
                                ?: if (strategyTarget > 0.0 && it + 1e-9 >= entry + strategyTarget &&
                                    ScalpRule.netAfterFees(entry, it, n) > 1e-9
                                ) "V2 target +${kotlin.math.round(strategyTarget * 100.0).toInt()}¢ after fees" else null
                        } else (if (ScalpV2.peakRule(t)) ScalpV2.peakExit(entry, ScalpV2.stopPercent(t), pk, it, trail, t.contracts)
                        else ScalpV2.exit(entry, stop, pk, it, armed, t.contracts, trail, armThreshold = arm, netGain = ScalpV2.netPerContract(t, it)))
                            ?: if (target > 0 && ScalpV2.netPerContract(t, it) >= target - 1e-9) "V2 user net take-profit" else null
                    }
                    val reason = t.exitReason.takeIf { t.state == ScalpState.PENDING_EXIT } ?: priceExit ?: timeExit ?: if (fgT) null else ScalpV2.closeExit(q, t.side, entry, t.contracts, ScalpModels.modelOf(t)?.code ?: t.variantId, GbmModel.pSide(q, t.side, dm60(key, q.nowMs)))
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
                        var left = t.remaining; var usd = 0.0; var n = 0
                        val lots = ArrayList<Pair<Double, Int>>()
                        for ((px, sz) in q.bidLadder(t.side)) { if (left <= 0 || px <= 0.0) break; val k = minOf(left, kotlin.math.floor(sz + 1e-9).toInt()); if (k > 0) { usd += k * px; lots += px to k; n += k; left -= k } }
                        val fee = ScalpRule.sweepFee(lots)
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
