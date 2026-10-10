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
    const val TAU_MAX_S = 840.0
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
    const val ENTRY_HI = 0.90
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
        val bookGap: Boolean = false
    ) {
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
            sigmaPerSec = sigmaPerSec
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
            if (ask < ENTRY_LO - 1e-9 || ask > ENTRY_HI + 1e-9) return@mapNotNull null
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
                    if (ask < ENTRY_LO - 1e-9 || ask > ENTRY_HI + 1e-9) return@mapNotNull null
                    val ref = past.mapNotNull { it.ask(side) }.maxOrNull() ?: return@mapNotNull null
                    val drop = ref - ask
                    // Spot must not explain the drop: fair still at or above the ask.
                    if (drop + 1e-9 < p.minGap || fair + 1e-9 < ask) return@mapNotNull null
                    drop * 0.5 // half retrace
                }
                ScalpStrategy.MOMENTUM -> {
                    if (ask < ENTRY_LO - 1e-9 || ask > ENTRY_HI + 1e-9) return@mapNotNull null
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
            if (ask < ENTRY_LO - 1e-9 || ask > ENTRY_HI + 1e-9) return@mapNotNull null
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

    /** Exit decision for an open scalp at this quote, or null to keep holding. */
    fun exitSignal(side: String, entry: Double, q: Quote, p: ScalpParams = ScalpParams.CLASSIC): ExitReason? {
        if (q.tauS <= TIME_STOP_S) return ExitReason.TIME_STOP
        val bid = q.bid(side)
        val fair = q.fair(side)
        if (bid != null && bid > 0.0) {
            if (bid - entry >= p.target - 1e-12) return ExitReason.PROFIT_TARGET
            if (bid <= entry - p.stop + 1e-12) return ExitReason.STOP
            if (fair != null && bid - feePerContract(CONTRACTS, bid) >= fair) return ExitReason.GAP_CLOSED
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
            "10 contracts, displayed depth only, fill on the next fresh book (≥ 3 s). Several round trips per window: " +
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

    /** Mark-to-bid P&L after the exit fee for what is still held. */
    fun unrealizedUsd(bid: Double?): Double? {
        if (state != ScalpState.OPEN && state != ScalpState.PENDING_EXIT) return null
        val b = bid ?: return null
        val rem = remaining
        val exitValue = rem * b - ScalpRule.orderFee(rem, b)
        return proceedsUsd - exitFeeUsd + exitValue - entryCostUsd
    }
}

interface ScalpPersistence {
    fun loadScalps(): List<ScalpTrade>
    fun upsertScalp(trade: ScalpTrade)
}

class InMemoryScalpPersistence : ScalpPersistence {
    private val rows = LinkedHashMap<String, ScalpTrade>()
    override fun loadScalps(): List<ScalpTrade> = synchronized(rows) { rows.values.toList() }
    override fun upsertScalp(trade: ScalpTrade) { synchronized(rows) { rows[trade.id] = trade } }
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
    private val idFactory: () -> String = { UUID.randomUUID().toString() }
) {
    private val lock = Any()
    private val _all = MutableStateFlow(ScalpMemory.bound(store.loadScalps().sortedByDescending { it.signalAtMs }))
    private val _trades = MutableStateFlow(_all.value.filter { it.isPrimary })
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

    fun snapshot(): List<ScalpTrade> = _trades.value
    /** Primary + shadow variants (tuner input). */
    fun allTrades(): List<ScalpTrade> = _all.value

    fun paramsFor(coin: String): ScalpParams = _tune.value.paramsFor(coin)
    fun paramsFor(coin: String, strategy: ScalpStrategy): ScalpParams = _tune.value.paramsFor(coin, strategy)

    private val history = HashMap<String, ArrayDeque<ScalpRule.Snap>>()

    /** Cost tied up in open primary scalps (pending entries reserve ask × clip + entry fee). */
    fun openCostUsd(): Double = _trades.value.sumOf { t ->
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
        _marks.value = (_marks.value + (q.ticker.uppercase() to q)).let { m ->
            if (m.size <= ScalpMemory.MAX_TICKERS) m else m.filterValues { it.closeMs > q.nowMs - 60_000L || it.ticker.equals(q.ticker, true) }
        }
        val changed = ArrayList<ScalpTrade>()
        val coin = ScalpParams.coinOf(q.ticker)
        val primary = paramsFor(coin)
        // 0.3.50: six paper models = five strategy primaries + maker-first dip (buy the dip at the bid, sell the rebound at the ask).
        val primaries = ScalpStrategy.values().map { paramsFor(coin, it) } + ScalpModels.MAKER_DIP_PARAMS
        val primaryIds = primaries.map { it.id }.toSet()
        val key = q.ticker.uppercase()
        val hist = history.getOrPut(key) { ArrayDeque() }
        val recent = hist.toList()
        hist.addLast(ScalpRule.Snap(q.nowMs, q.yesBid, q.yesAsk, ScalpRule.fairYes(q.spot, q.strike, q.sigmaPerSec, q.tauS)))
        while (hist.isNotEmpty() && q.nowMs - hist.first().atMs > 2 * ScalpRule.LOOKBACK_MS) hist.removeFirst()
        if (q.nowMs >= q.closeMs) history.remove(key)
        // 0.3.50: bounded — forget closed windows' history/marks (they used to accumulate forever).
        if (history.size > ScalpMemory.MAX_TICKERS) history.keys.filter { it != key && (ScalpTicker.closeMs(it) ?: Long.MAX_VALUE) < q.nowMs }.forEach { history.remove(it) }
        val mineAll = _all.value.filter { it.ticker.equals(q.ticker, true) }
        // Step every open scalp first (one per market side per variant).
        mineAll.filter { isActive(it) }.forEach { t ->
            val p = ScalpParams.byId(t.variantId) ?: primary
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
                if (mine.count { it.state != ScalpState.NO_FILL } >= ScalpRule.MAX_ENTRIES_PER_MARKET) continue
                if (q.nowMs >= q.closeMs) continue
                val lastClose = mine.mapNotNull { it.closedAtMs }.maxOrNull()
                if (lastClose != null && q.nowMs - lastClose < ScalpRule.REENTRY_COOLDOWN_MS) continue
                val openSides = mine.filter { isActive(it) }.map { it.side }.toSet()
                // 0.3.43 CF-reprice: one direction per market (no YES and NO at once).
                val free = if (p.strategy == ScalpStrategy.CF_REPRICE && openSides.isNotEmpty()) emptyList()
                    else listOf("YES", "NO").filter { it !in openSides }
                if (free.isEmpty()) continue
                val (sig, _) = ScalpRule.strategySignal(q, p, free, recent)
                if (sig != null) {
                    val role = if (p.id in primaryIds) "P" else "S"
                    if (role == "P") {
                        val cost = sig.ask * ScalpRule.CONTRACTS + ScalpRule.orderFee(ScalpRule.CONTRACTS, sig.ask)
                        if (openCost + cost > bankrollUsd() + 1e-9) continue // paper bankroll fully committed
                        // 0.3.50: each of the six models has its own bankroll slice (independent ledger).
                        val model = ScalpModels.modelOf(p)
                        val used = sliceUsed[model] ?: 0.0
                        if (used + cost > ScalpModels.sliceUsd(bankrollUsd()) + 1e-9) continue
                        sliceUsed[model] = used + cost
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

    /** Re-tune when enough new closed round trips (all variants) have accumulated. Deterministic. */
    fun maybeTune(nowMs: Long, force: Boolean = false): ScalpTuneState = synchronized(lock) {
        val closed = _all.value.count { it.state == ScalpState.CLOSED && it.netUsd != null }
        val cur = _tune.value
        if (!force && closed - cur.closedAtLastRun < ScalpTuner.RETUNE_EVERY) return cur
        val next = ScalpTuner.tune(_all.value, cur, nowMs).copy(closedAtLastRun = closed)
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
        return close(next.copy(soldContracts = t.soldContracts + n, proceedsUsd = t.proceedsUsd + n * limit, exitFeeUsd = t.exitFeeUsd + fee, restingLimit = null),
            q.nowMs, "${t.exitReason} (maker exit)")
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
                val reason = (if (p.strategy == ScalpStrategy.CF_REPRICE)
                    ScalpRule.cfRepriceExit(t.side, t.entryPrice ?: return null, t.entryAtMs, q, p)
                else ScalpRule.exitSignal(t.side, t.entryPrice ?: return null, q, p)) ?: return null
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
        val next = t.copy(
            soldContracts = t.soldContracts + n,
            proceedsUsd = t.proceedsUsd + n * bid,
            exitFeeUsd = t.exitFeeUsd + ScalpRule.orderFee(n, bid)
        )
        return if (next.remaining <= 0) close(next, q.nowMs, next.exitReason) else next
    }

    private fun close(t: ScalpTrade, atMs: Long, reason: String?): ScalpTrade {
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
                    t.copy(soldContracts = t.contracts, proceedsUsd = t.proceedsUsd + t.remaining * pay),
                    atMs,
                    if (t.state == ScalpState.PENDING_EXIT) "${t.exitReason} → ${ScalpRule.ExitReason.SETTLED.label}" else ScalpRule.ExitReason.SETTLED.label
                )
            }
        }
        out.forEach { save(it) }
        out.filter { it.isPrimary }
    }

    private fun save(t: ScalpTrade) {
        runCatching { store.upsertScalp(t) }
        val cur = _all.value
        val idx = cur.indexOfFirst { it.id == t.id }
        _all.value = if (idx >= 0) cur.toMutableList().also { it[idx] = t } else ScalpMemory.boundIfNeeded(listOf(t) + cur)
        if (t.isPrimary) {
            val pc = _trades.value
            val pi = pc.indexOfFirst { it.id == t.id }
            _trades.value = if (pi >= 0) pc.toMutableList().also { it[pi] = t } else ScalpMemory.boundIfNeeded(listOf(t) + pc)
        }
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
