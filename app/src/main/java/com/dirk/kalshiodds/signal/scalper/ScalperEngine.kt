package com.dirk.kalshiodds.signal.scalper

import com.dirk.kalshiodds.signal.trade.KalshiFee
import kotlin.math.abs

/** How many contracts were ahead of a paper bid when it was posted. */
enum class QueueBucket(val label: String) {
    FRONT("0–100 ahead"),
    NEAR("101–250 ahead"),
    MID("251–1,000 ahead"),
    DEEP("over 1,000 ahead"),
    UNKNOWN("queue not shown");

    companion object {
        fun of(contractsAhead: Double, known: Boolean): QueueBucket = when {
            !known -> UNKNOWN
            contractsAhead <= 100.0 -> FRONT
            contractsAhead <= 250.0 -> NEAR
            contractsAhead <= 1_000.0 -> MID
            else -> DEEP
        }
    }
}

enum class ExitKind { TARGET, STOP, TIMEOUT, SETTLED }

/**
 * The paper scalper's strategies. They all run at once, each with its own
 * positions and its own line on the scoreboard, so the scoreboard is a
 * tournament: same live market, same fills, same fees, one row each.
 *
 * Scalps exit the same way: a resting offer [targetDollars] above the entry
 * (no fee), or a sale at the bid with the taker fee when the bid falls
 * [stopDollars] below the entry or [timeoutMs] passes. [hold] strategies
 * have no exit: they ride to the settlement ($1 or $0).
 *
 * [taker] strategies buy at the ask the moment the signal fires and pay the
 * fee; the others rest a bid and wait up to 20 s for it to fill. Entries
 * are taken only between [entryMin] and [entryMax] (the side's price).
 *
 * [historyCents] is what one filled scalp made, in cents per contract, on
 * seven days of recorded order books (2026-10-03 → 10-09, 577 windows,
 * `tools/research/scalp/rec_g.py`): the real size displayed ahead of every
 * resting order had to trade or leave before it filled. Null for the eight
 * strategies added in 1.9.0, which have no history on this engine yet.
 * A fact about the past, shown on the card; it gates nothing.
 *
 * [origin] is where the idea came from (another branch's app or study).
 */
enum class ScalpStrategy(
    val label: String,
    val taker: Boolean,
    val targetDollars: Double,
    val stopDollars: Double,
    val timeoutMs: Long,
    /** Seconds between decisions per window. */
    val cadenceSeconds: Int,
    /** Positions this strategy may have working at once, per side, per window. */
    val maxPerSide: Int,
    val historyCents: Double?,
    val hold: Boolean = false,
    val entryMin: Double = PrintGrid.MIN_PRICE,
    val entryMax: Double = PrintGrid.MAX_PRICE,
    val origin: String = "Claude",
    /** Kashi's late-favourite rule; null for every other strategy. */
    val fav: FavSpec? = null,
    /** The stop follows the best bid since the entry up ([stopDollars] under it) instead of staying put. */
    val trail: Boolean = false,
    /** Trades only in the first minute of the window (the opening ride). */
    val opening: Boolean = false,
    /** First minute of the window it opens orders in (the "from minute" setting starts here). */
    val startMinute: Int = 0,
    /** Seconds it waits after opening an order before the next (the "wait after an order" setting starts here). */
    val cooldownSeconds: Int = 0
) {
    /** The trained model picks calm moments. Rest in, +1¢ out. */
    ML_REST("ML scalper · resting", false, 0.01, 0.04, 120_000L, 5, 12, -2.01),

    /** The side's price fell 2¢ or more in 10 s: buy the dip now. */
    DIP_HUNTER("Dip-hunter · buy now", true, 0.02, 0.02, 30_000L, 2, 6, -3.86),

    /** The side's price rose 2¢ or more in 10 s: buy the move now. */
    MOMENTUM_SNIPER("Momentum-sniper · buy now", true, 0.02, 0.02, 30_000L, 2, 6, -3.29),

    /** The side's price fell 6¢ or more in 30 s: buy the snap-back now. */
    EXTREME_REVERSION("Extreme reversion · buy now", true, 0.03, 0.03, 60_000L, 2, 6, -3.88),

    /** Same three signals with a resting bid instead of paying the ask. */
    DIP_HUNTER_REST("Dip-hunter · resting", false, 0.01, 0.04, 30_000L, 2, 6, -2.44),
    MOMENTUM_SNIPER_REST("Momentum-sniper · resting", false, 0.01, 0.04, 30_000L, 2, 6, -2.20),
    EXTREME_REVERSION_REST("Extreme reversion · resting", false, 0.01, 0.04, 30_000L, 2, 6, -2.44),

    /**
     * Bounce (codex/bitcoin-edge-limit-orders `ScalpEngine`): the side fell
     * [BOUNCE_DROP_DOLLARS] or more from its 60 s high and has come back
     * [BOUNCE_REBOUND_DOLLARS] off its 60 s low, spread at most 2¢. Buy now,
     * +8¢ / −6¢ like the original (no fair-value check here).
     */
    BOUNCE("Bounce · buy now", true, 0.08, 0.06, 120_000L, 2, 1, null, origin = "codex"),

    /** 50¢ breakout (grok-bitcoin `breakout_50`): the side crossed up through 50¢ in the last 10 s. */
    BREAKOUT_50("50¢ breakout · buy now", true, 0.05, 0.03, 90_000L, 2, 1, null, origin = "grok"),

    /**
     * One cent better (grok-bitcoin `CentBetter`): when the spread is 2¢ or
     * wider, rest a bid 1¢ above the best bid, minutes 3–13. Out at +2¢.
     */
    IMPROVE_1C("1¢ better · resting", false, 0.02, 0.04, 60_000L, 5, 2, null, origin = "grok"),

    /**
     * Flow fade (grok-bitcoin `FlowFade`): takers bought the other side 3:1
     * ([FLOW_RATIO]) with at least [FLOW_MIN_CONTRACTS] contracts in 30 s.
     * Rest a bid on this side, held to the settlement like the original.
     */
    FLOW_FADE("Flow fade · resting, hold", false, 0.0, 0.0, 0L, 5, 1, null, hold = true, origin = "grok"),

    /**
     * Late favorite (grok-bitcoin `LateFavoriteRule`, kashi D3): minutes
     * 10–13, the side costs 80–95¢. Buy now, hold to the settlement.
     */
    LATE_FAVORITE(
        "Late favorite · buy, hold", true, 0.0, 0.0, 0L, 10, 1, null,
        hold = true, entryMin = 0.80, entryMax = 0.95, origin = "grok / kashi"
    ),

    /**
     * Early favorite (the price form of the Clear lead rule, 1.8.2): minutes
     * 3–10, the side costs 65–85¢. Buy now, hold to the settlement.
     */
    EARLY_FAVORITE(
        "Early favorite · buy, hold", true, 0.0, 0.0, 0L, 10, 1, null,
        hold = true, entryMin = 0.65, entryMax = 0.85, origin = "Claude clear lead"
    ),

    /**
     * Momentum ride (kashi forecast ride, swarm): the side rose 4¢ or more in
     * 30 s, minutes 5–13, 50–85¢. Buy now, hold to the settlement.
     */
    MOMENTUM_HOLD(
        "Momentum ride · buy, hold", true, 0.0, 0.0, 0L, 5, 1, null,
        hold = true, entryMin = 0.50, entryMax = 0.85, origin = "kashi / swarm"
    ),

    /**
     * Long shot (the app's HUNTER_VALUE ticket, grok `penny_press`): minutes
     * 5–13, the side costs 10–20¢. Buy now, hold to the settlement.
     */
    LONG_SHOT(
        "Long shot · buy, hold", true, 0.0, 0.0, 0L, 10, 1, null,
        hold = true, entryMin = 0.10, entryMax = 0.20, origin = "app / grok"
    ),

    // ---- 1.9.4: fifteen more, each on its own signal. None has history on this engine yet. ----

    /** Stretch fade: the side's mid sits [STRETCH_DOLLARS] or more under its own 60 s average. Buy the snap back. */
    STRETCH_FADE("Stretch fade · buy now", true, 0.02, 0.03, 45_000L, 2, 2, null, origin = "Claude 1.9.4"),

    /**
     * Squeeze breakout: two quiet minutes (the mid stayed inside [SQUEEZE_RANGE_DOLLARS]), then the mid
     * breaks 1¢ above that range. Buy the break.
     */
    SQUEEZE_BREAKOUT("Squeeze breakout · buy now", true, 0.03, 0.02, 60_000L, 2, 1, null, origin = "Claude 1.9.4"),

    /** Range floor: a 3–8¢ two-minute range and the mid back on its low. Rest a bid at the floor. */
    RANGE_FLOOR("Range floor · resting", false, 0.02, 0.03, 60_000L, 5, 2, null, origin = "Claude 1.9.4"),

    /** Trend pullback: up [PULLBACK_TREND_DOLLARS] over two minutes, then down 1¢ in the last 10 s. Buy the dip in the trend. */
    TREND_PULLBACK("Trend pullback · buy now", true, 0.03, 0.03, 60_000L, 2, 2, null, origin = "Claude 1.9.4"),

    /** Flow follow: the mirror of flow fade. Takers bought this side 3:1, 500+ contracts in 30 s: go with them. */
    FLOW_FOLLOW("Flow follow · buy now", true, 0.02, 0.02, 30_000L, 2, 2, null, origin = "Claude 1.9.4"),

    /** Whale follow: one taker bought [WHALE_CONTRACTS]+ contracts of this side in a single print in the last 5 s. */
    WHALE_FOLLOW("Whale follow · buy now", true, 0.02, 0.02, 30_000L, 2, 1, null, origin = "Claude 1.9.4"),

    /** Quiet maker: the mid has not moved for 30 s. Rest a bid and take 1¢. */
    QUIET_MAKER("Quiet maker · resting", false, 0.01, 0.02, 60_000L, 5, 2, null, origin = "Claude 1.9.4"),

    /**
     * Opening drive: minutes 2–4, the side is up [DRIVE_DOLLARS] or more since minute 1 and costs 50–80¢.
     * Buy now, hold to the settlement.
     */
    OPENING_DRIVE(
        "Opening drive · buy, hold", true, 0.0, 0.0, 0L, 10, 1, null,
        hold = true, entryMin = 0.50, entryMax = 0.80, origin = "Claude 1.9.4"
    ),

    /** Late tiebreak: minutes 12–13, a near coin flip (45–60¢) that rose 2¢ in the last minute. Buy, hold. */
    LATE_TIEBREAK(
        "Late tiebreak · buy, hold", true, 0.0, 0.0, 0L, 10, 1, null,
        hold = true, entryMin = 0.45, entryMax = 0.60, origin = "Claude 1.9.4"
    ),

    /** Heavy favorite: minutes 12–13, the side costs 92–97¢. Buy now, hold for the last few cents. */
    HEAVY_FAVORITE(
        "Heavy favorite · buy, hold", true, 0.0, 0.0, 0L, 10, 1, null,
        hold = true, entryMin = 0.92, entryMax = 0.97, origin = "Claude 1.9.4"
    ),

    /**
     * Panic fade: the side fell [PANIC_DROP_DOLLARS] in 10 s on [PANIC_CONTRACTS]+ contracts sold into it.
     * Buy the overshoot now.
     */
    PANIC_FADE("Panic fade · buy now", true, 0.03, 0.03, 60_000L, 2, 1, null, origin = "Claude 1.9.4"),

    /** Steady climb: up 1¢ over 10 s, 3¢ over 30 s and 4¢ over 60 s — a move that keeps going. Buy now. */
    STEADY_CLIMB("Steady climb · buy now", true, 0.03, 0.02, 60_000L, 2, 1, null, origin = "Claude 1.9.4"),

    /**
     * Penny scalper: a 1¢ spread and at most [PENNY_MAX_QUEUE] contracts ahead on the bid (the book must
     * show the queue). Rest a bid, out at +1¢, quick time-out.
     */
    PENNY_SCALPER("Penny scalper · resting", false, 0.01, 0.02, 30_000L, 2, 2, null, origin = "Claude 1.9.4"),

    /** Book imbalance: the bid shows 3× the size of the ask and 300+ contracts (the book must be live). Buy now. */
    BOOK_IMBALANCE("Book imbalance · buy now", true, 0.02, 0.02, 30_000L, 2, 1, null, origin = "Claude 1.9.4"),

    /**
     * Late comeback: minutes 10–13, an underdog (20–40¢) up 4¢ in 30 s. Buy now, hold to the settlement.
     * Up to two a side, 60 s apart (sweep of 2026-10-20 on the 12 recorded days: 487 trades and +$214.93
     * against 381 and +$107.40 with one).
     */
    LATE_COMEBACK(
        "Late comeback · buy, hold", true, 0.0, 0.0, 0L, 5, 2, null,
        hold = true, entryMin = 0.20, entryMax = 0.40, origin = "Claude 1.9.4", startMinute = 10, cooldownSeconds = 60
    ),

    /**
     * Kashi's late favourites (`ShockFav.kt` sf16 / sf19 / sf21 / sf23 / sf31,
     * 0.3.70): in the last minute, the side the live Bitcoin price makes a
     * heavy favourite ([FavSpec.fairMin], from [ScalpFair]) while its ask is
     * still at least [FavSpec.gap] under that fair price. Buy now, hold to the
     * settlement; the only exit is Kashi's stop at the bid. On Kashi's
     * out-of-sample test (BTC, 2 s entry delay, 2026-10-05 → 10-10, before its
     * stops were added) sf19 / sf21 / sf31 made about +$1.40 a 10-contract
     * trade and sf23 +$1.79, intervals above zero but on 10–16 trades each;
     * sf16's interval crossed zero.
     *
     * Tweaked 2026-10-20 by the arena sweep on the 12 recorded days (ArenaReplayTest.sweepTweaks, rule fixed
     * before the run: 10%+ more trades, no less net, up in both parts of the days): base buys from 90 s left
     * (169 trades, +$47.05, was 93 and +$46.43), strict from a 93% fair price (74, +$34.33, was 67 and
     * +$26.92), 97% on a 2¢ edge (82, +$20.23, was 72 and +$19.84), the last-30 copy from 45 s left (30,
     * +$33.22, was 16 and +$17.18) and the off-US-hours copy two a side 15 s apart (50, +$39.87, was 45 and
     * +$35.22). Chosen on the same days they are scored on.
     */
    KASHI_FAV_BASE(
        "Kashi late fav · base", true, 0.0, 0.10, 0L, 1, 1, null,
        hold = true, entryMin = 0.50, entryMax = 0.97, origin = "kashi sf16", fav = FavSpec(20, 90, 0.90, 0.05)
    ),
    KASHI_FAV_STRICT(
        "Kashi late fav · strict", true, 0.0, 0.15, 0L, 1, 1, null,
        hold = true, entryMin = 0.50, entryMax = 0.95, origin = "kashi sf19", fav = FavSpec(20, 60, 0.93, 0.05)
    ),
    KASHI_FAV_97(
        "Kashi late fav · 97%", true, 0.0, 0.10, 0L, 1, 1, null,
        hold = true, entryMin = 0.50, entryMax = 0.97, origin = "kashi sf21", fav = FavSpec(20, 60, 0.97, 0.02)
    ),
    KASHI_FAV_LAST30(
        "Kashi late fav · last 45 s", true, 0.0, 0.10, 0L, 1, 1, null,
        hold = true, entryMin = 0.50, entryMax = 0.95, origin = "kashi sf23", fav = FavSpec(10, 45, 0.95, 0.05)
    ),
    KASHI_FAV_NIGHT(
        "Kashi late fav · off US hours", true, 0.0, 0.25, 0L, 1, 2, null,
        hold = true, entryMin = 0.50, entryMax = 0.95, origin = "kashi sf31",
        fav = FavSpec(20, 60, 0.95, 0.05, offUsHours = true), cooldownSeconds = 15
    ),

    /**
     * Opening ride (asked for 2026-10-16): in the window's first minute, buy
     * the side the live Bitcoin price leans to (its fair price from
     * [ScalpFair] above 50%; the market's own favourite while the index is
     * not in), at whatever it costs inside 40–65¢. Out at +10¢ on a resting
     * offer, or sold at the bid once it falls 3¢ under the best bid since the
     * entry (a trailing stop). Anything still open settles. Once a window.
     */
    OPENING_RIDE(
        "Opening ride · buy, +10¢ / trail 3¢", true, 0.10, 0.03, 900_000L, 1, 1, null,
        entryMin = 0.40, entryMax = 0.65, origin = "you", trail = true, opening = true
    ),

    /**
     * Late edge (report "Kalshi Bitcoin 15 minute edge", rank 1): the
     * settlement is the average of the last 60 index prints, so with
     * [FavSpec.tteMin]–[FavSpec.tteMax] seconds left most of it is already
     * fixed. [ScalpFair] prices the side from the prints fixed so far plus
     * the live index; buy any side whose fair price is at least
     * [FavSpec.fairMin] and beats its ask by the taker fee plus
     * [FavSpec.gap]. Hold to the settlement, no stop. Once a window. From 60 s left on a 1¢ edge since the
     * 2026-10-20 sweep (153 trades, +$67.14, was 5–45 s and 2¢: 63 and +$55.80; its interval now crosses zero).
     */
    LATE_EDGE(
        "Late edge · settle average, buy, hold", true, 0.0, 0.0, 0L, 1, 1, null,
        hold = true, entryMin = 0.50, entryMax = 0.98, origin = "edge report", fav = FavSpec(5, 60, 0.80, 0.01)
    );

    /**
     * Kashi's late-favourite entry: [tteMin]–[tteMax] seconds left, the side's
     * fair price at least [fairMin] and at least [gap] above its ask;
     * [offUsHours] skips 13:00–20:59 UTC.
     */
    data class FavSpec(
        val tteMin: Int,
        val tteMax: Int,
        val fairMin: Double,
        val gap: Double,
        val offUsHours: Boolean = false
    )

    companion object {
        /**
         * Strategies taken out of the arena on 2026-10-20: they lost money in a replay of
         * the 11 recorded days (2026-10-03 → 10-16, ArenaReplayTest / arena-replay.yml) through
         * this engine on their defaults. They no longer run or show; the enum keeps them so
         * older paper records and trade files still read.
         */
        val RETIRED: Set<ScalpStrategy> by lazy {
            setOf(
                LATE_TIEBREAK, OPENING_DRIVE, HEAVY_FAVORITE, QUIET_MAKER, MOMENTUM_HOLD, EARLY_FAVORITE,
                SQUEEZE_BREAKOUT, LATE_FAVORITE, LONG_SHOT, RANGE_FLOOR, IMPROVE_1C, OPENING_RIDE, BREAKOUT_50,
                PENNY_SCALPER, FLOW_FOLLOW, BOUNCE, TREND_PULLBACK, STEADY_CLIMB, PANIC_FADE, EXTREME_REVERSION_REST,
                BOOK_IMBALANCE, MOMENTUM_SNIPER_REST, ML_REST, DIP_HUNTER_REST, STRETCH_FADE, WHALE_FOLLOW,
                EXTREME_REVERSION, MOMENTUM_SNIPER, DIP_HUNTER,
                // 2026-10-20, with the phone's 2026-09-28 day: +$1.80 on 979 trades over all 12 days, and lost that day.
                FLOW_FADE
            )
        }

        /** The strategies the arena runs and shows: every one that made money in the replay. */
        val ACTIVE: List<ScalpStrategy> by lazy { values().filter { it !in RETIRED } }
        private val RETIRED_NAMES: Set<String> by lazy { RETIRED.mapTo(HashSet()) { it.name } }

        /** A trade-file or ledger name of a retired strategy. */
        fun isRetired(name: String): Boolean = name in RETIRED_NAMES

        /** Price move over 10 s that the dip-hunter and the momentum-sniper react to. */
        const val FAST_MOVE_DOLLARS = 0.02

        /** Price fall over 30 s that extreme reversion reacts to. */
        const val EXTREME_MOVE_DOLLARS = 0.06

        /** Bounce: fall from the 60 s high, and the climb back off the 60 s low. */
        const val BOUNCE_DROP_DOLLARS = 0.05
        const val BOUNCE_REBOUND_DOLLARS = 0.01

        /** Flow fade: taker contracts bought on the other side over 30 s, and their ratio to this side's. */
        const val FLOW_MIN_CONTRACTS = 500.0
        const val FLOW_RATIO = 3.0

        /** Momentum ride: rise over 30 s. */
        const val RIDE_MOVE_DOLLARS = 0.04

        /** Spread limit for the bounce and the breakout, and the minimum spread for 1¢ better. */
        const val TIGHT_SPREAD_DOLLARS = 0.02

        /** Stretch fade: distance under the 60 s average mid. */
        const val STRETCH_DOLLARS = 0.03

        /** Squeeze breakout: widest two-minute range that counts as quiet, and the break above it. */
        const val SQUEEZE_RANGE_DOLLARS = 0.04
        const val SQUEEZE_BREAK_DOLLARS = 0.01

        /** Range floor: the two-minute range it trades in. */
        const val FLOOR_MIN_RANGE_DOLLARS = 0.03
        const val FLOOR_MAX_RANGE_DOLLARS = 0.08

        /** Trend pullback: the two-minute rise, and the 10 s dip inside it. */
        const val PULLBACK_TREND_DOLLARS = 0.05
        const val PULLBACK_DIP_DOLLARS = 0.01

        /** Whale follow: one print at least this size. */
        const val WHALE_CONTRACTS = 1_000.0

        /** Opening drive: the rise since minute 1. */
        const val DRIVE_DOLLARS = 0.06

        /** Late tiebreak: the last minute's rise. */
        const val TIEBREAK_MOVE_DOLLARS = 0.02

        /** Panic fade: the 10 s fall and the contracts sold into it. */
        const val PANIC_DROP_DOLLARS = 0.04
        const val PANIC_CONTRACTS = 300.0

        /** Penny scalper: deepest queue on the bid it will join. */
        const val PENNY_MAX_QUEUE = 200.0

        /** Book imbalance: bid size against ask size, and the smallest bid that counts. */
        const val IMBALANCE_RATIO = 3.0
        const val IMBALANCE_MIN_CONTRACTS = 300.0

        /** Late comeback: the underdog's 30 s rise. */
        const val COMEBACK_MOVE_DOLLARS = 0.04
    }
}

/**
 * What the scalper saw when it sent a paper order. Saved with the order in
 * the order log ([ScalpTradeLog]); it decides nothing.
 */
class ScalpContext(
    /** Best bid / ask of the order's side and the sizes shown there (null without the book). */
    val bid: Double?,
    val ask: Double?,
    val bidQty: Double?,
    val askQty: Double?,
    /** The side's price move over the last 10 s / 30 s, dollars. */
    val move10: Double?,
    val move30: Double?,
    /** The model's inputs in [PrintGrid.FEATURES] order (ML scalper only). */
    val features: FloatArray? = null
)

/** One paper scalp order as posted. Never a real order. */
data class ScalpOrder(
    val id: Long,
    val strategy: ScalpStrategy,
    val ticker: String,
    /** "YES" (UP) or "NO" (DOWN). */
    val side: String,
    /** Entry price in that side's dollars: the resting bid, or the ask paid by a buy-now strategy. */
    val price: Double,
    val contracts: Int,
    /** Taker fee paid on a buy-now entry; 0 for a resting bid. */
    val entryFeeUsd: Double = 0.0,
    /** Contracts shown ahead of the bid when it was posted (the assumed size when the book does not show it). */
    val queueAhead: Double,
    val queueKnown: Boolean,
    val postedAtMs: Long,
    /** Model output: expected cents per contract from the front of the queue. */
    val predictionCents: Double,
    /** [predictionCents] minus the queue penalty: what this order is expected to make where it sits. */
    val expectedCents: Double,
    val context: ScalpContext? = null
) {
    /** Queue bucket of a resting bid; buy-now entries have no entry queue. */
    val bucket: QueueBucket? get() = if (strategy.taker) null else QueueBucket.of(queueAhead, queueKnown)

    /** ML scalper only: true when the order is expected to pay after its place in the queue is counted. */
    val queueAware: Boolean get() = strategy == ScalpStrategy.ML_REST && queueKnown && expectedCents >= 0.0
}

data class ClosedScalp(
    val order: ScalpOrder,
    val exitPrice: Double,
    val kind: ExitKind,
    /** Exit fee (a sale at the bid); the entry fee is on the order. */
    val feeUsd: Double,
    /** Dollars for the whole order: (exit − entry) × contracts − both fees. */
    val pnlUsd: Double,
    val filledAtMs: Long,
    val closedAtMs: Long
)

sealed class ScalpEvent {
    data class Posted(val order: ScalpOrder) : ScalpEvent()
    data class Unfilled(val order: ScalpOrder) : ScalpEvent()
    data class Filled(val order: ScalpOrder, val atMs: Long) : ScalpEvent()
    data class Closed(val scalp: ClosedScalp) : ScalpEvent()
}

/**
 * Paper scalps, both legs resting, filled only by real public trades.
 *
 * One scalp (docs/scalping-2026-10-09.md; the ML scalper's numbers are the
 * ones its model was trained and tested on):
 *
 *  1. Entry: rest a bid at the best bid for up to [Config.entryWaitMs], or,
 *     for a buy-now strategy, pay the ask and the taker fee at once.
 *  2. Rest an offer [ScalpStrategy.targetDollars] above the entry.
 *  3. If the side's bid falls [ScalpStrategy.stopDollars] below the entry,
 *     or [ScalpStrategy.timeoutMs] passes, sell at the bid and pay the fee.
 *  4. Anything still open when the window closes settles at $1 or $0.
 *
 * Fills are conservative: a resting order fills when a print trades
 * **through** its price, or when the contracts ahead of it **plus its own
 * size** have traded at its price since it was posted. Nobody ahead cancels.
 * Resting fills pay no fee (KXBTC15M maker fee is 0).
 *
 * Aggressive by design: every [ScalpStrategy] runs at once on both sides,
 * each holding up to [ScalpStrategy.maxPerSide] positions per side, with no
 * cap on scalps per window, no daily cap and no queue check before posting;
 * the ledger splits the results by strategy and by queue instead. Pure and deterministic; not thread-safe (the
 * [PaperScalper] holds the lock). There is no path from here to an order.
 */
class ScalperEngine(
    val config: Config = Config(),
    /** Your per-strategy settings (sizes, exits, bands); untouched strategies run on their defaults. */
    val tuning: ScalpTuning = ScalpTuning()
) {
    init {
        tuning.defaultContracts = config.contracts
    }

    /**
     * Fee rate on resting fills (entry bids and target offers). 0 while Kalshi
     * lists the series as `quadratic`; the contract check sets
     * [KalshiFee.MAKER_COEFFICIENT] × the multiplier if it ever lists maker fees.
     */
    @Volatile
    var makerFeeRate: Double = 0.0

    data class Config(
        val contracts: Int = 10,
        val entryWaitMs: Long = 20_000L,
        /** Queue assumed when the book shows no size (same figure the flow-fade tracker uses). */
        val unknownQueue: Double = 3_500.0,
        val feeRate: Double = KalshiFee.TAKER_COEFFICIENT,
        val minPrice: Double = PrintGrid.MIN_PRICE,
        val maxPrice: Double = PrintGrid.MAX_PRICE
    )

    private class Working(val order: ScalpOrder) {
        /** Best bid seen since the fill: where a trailing stop hangs from. */
        var peakBid = order.price
        /** Maker fee charged when a resting entry bid filled (0 while makers are free). */
        var makerEntryFee = 0.0
        var traded = 0.0
        var filledAtMs: Long? = null
        var exitPrice = 0.0
        var exitQueue = 0.0
        var exitTraded = 0.0
        val resting: Boolean get() = filledAtMs == null
    }

    private val byTicker = HashMap<String, MutableList<Working>>()
    private var nextId = 1L

    /** (resting bids, resting offers) on [ticker]. */
    fun working(ticker: String): Pair<Int, Int> {
        val list = byTicker[ticker] ?: return 0 to 0
        val bids = list.count { it.resting }
        return bids to (list.size - bids)
    }

    fun workingTotal(): Pair<Int, Int> {
        var bids = 0
        var offers = 0
        for (list in byTicker.values) for (w in list) if (w.resting) bids++ else offers++
        return bids to offers
    }

    /** Tickers that hold a filled scalp waiting to exit (they need a settlement if the window ends). */
    fun openTickers(): Set<String> =
        byTicker.filterValues { list -> list.any { !it.resting } }.keys.toSet()

    fun hasWork(ticker: String): Boolean = !byTicker[ticker].isNullOrEmpty()

    /** A filled paper position still waiting to exit (or for the settlement). */
    data class OpenPosition(val order: ScalpOrder, val filledAtMs: Long)

    /** Filled positions on [ticker], oldest first. Resting bids that have not filled are not positions. */
    fun openPositions(ticker: String): List<OpenPosition> =
        byTicker[ticker].orEmpty().mapNotNull { w -> w.filledAtMs?.let { OpenPosition(w.order, it) } }
            .sortedBy { it.filledAtMs }

    /** Positions [strategy] has working on one side of [ticker] (resting bids and filled scalps). */
    fun workingFor(strategy: ScalpStrategy, ticker: String, side: String): Int {
        val want = if (PrintGrid.isYes(side)) "YES" else "NO"
        return byTicker[ticker]?.count { it.order.strategy == strategy && it.order.side == want } ?: 0
    }

    /**
     * Rest a paper bid for a resting [strategy]. Null when the price is
     * outside 10–90¢ or the strategy already has its limit working on that
     * side. [queueAhead] null = the book did not show a size.
     */
    fun post(
        strategy: ScalpStrategy,
        ticker: String,
        side: String,
        price: Double,
        queueAhead: Double?,
        predictionCents: Double,
        expectedCents: Double,
        nowMs: Long,
        context: ScalpContext? = null
    ): ScalpEvent.Posted? {
        require(!strategy.taker) { "${strategy.name} buys at the ask: use buyNow" }
        val t = tuning.tuned(strategy)
        if (!inBand(t, price)) return null
        val want = if (PrintGrid.isYes(side)) "YES" else "NO"
        if (workingFor(strategy, ticker, want) >= t.maxPerSide) return null
        val known = queueAhead != null && queueAhead.isFinite() && queueAhead >= 0.0
        val order = ScalpOrder(
            id = nextId++,
            strategy = strategy,
            ticker = ticker,
            side = want,
            price = round4(price),
            contracts = t.contracts,
            queueAhead = if (known) queueAhead!! else config.unknownQueue,
            queueKnown = known,
            postedAtMs = nowMs,
            predictionCents = predictionCents,
            expectedCents = expectedCents,
            context = context
        )
        byTicker.getOrPut(ticker) { ArrayList() }.add(Working(order))
        return ScalpEvent.Posted(order)
    }

    /**
     * A buy-now [strategy] pays [askPrice] plus the taker fee and at once
     * rests its offer. [offerQueue] = contracts already offered at the
     * target price (null when the book does not show it). Empty when the
     * price is outside 10–90¢ or the strategy is at its limit on that side.
     */
    fun buyNow(
        strategy: ScalpStrategy,
        ticker: String,
        side: String,
        askPrice: Double,
        offerQueue: (side: String, price: Double) -> Double?,
        nowMs: Long,
        context: ScalpContext? = null
    ): List<ScalpEvent> {
        require(strategy.taker) { "${strategy.name} rests a bid: use post" }
        val t = tuning.tuned(strategy)
        if (!inBand(t, askPrice)) return emptyList()
        val want = if (PrintGrid.isYes(side)) "YES" else "NO"
        if (workingFor(strategy, ticker, want) >= t.maxPerSide) return emptyList()
        val price = round4(askPrice)
        val order = ScalpOrder(
            id = nextId++,
            strategy = strategy,
            ticker = ticker,
            side = want,
            price = price,
            contracts = t.contracts,
            entryFeeUsd = KalshiFee.total(t.contracts, price, config.feeRate),
            queueAhead = 0.0,
            queueKnown = true,
            postedAtMs = nowMs,
            predictionCents = 0.0,
            expectedCents = 0.0,
            context = context
        )
        val w = Working(order)
        w.filledAtMs = nowMs
        w.exitPrice = round4(price + t.targetDollars)
        w.exitQueue = offerQueue(want, w.exitPrice)?.takeIf { q -> q.isFinite() && q >= 0.0 } ?: config.unknownQueue
        byTicker.getOrPut(ticker) { ArrayList() }.add(w)
        return listOf(ScalpEvent.Posted(order), ScalpEvent.Filled(order, nowMs))
    }

    /**
     * A public trade on [ticker]: [takerYes] = the taker bought YES at
     * [yesPrice]. [exitQueue] returns the contracts already offered at a
     * side's price (null when the book does not show it); it is asked once,
     * when an entry fills.
     */
    fun onTrade(
        ticker: String,
        takerYes: Boolean,
        yesPrice: Double,
        contracts: Double,
        nowMs: Long,
        exitQueue: (side: String, price: Double) -> Double?
    ): List<ScalpEvent> {
        val list = byTicker[ticker] ?: return emptyList()
        if (list.isEmpty() || !yesPrice.isFinite() || !contracts.isFinite() || contracts <= 0.0) return emptyList()
        val events = ArrayList<ScalpEvent>(2)
        val it = list.iterator()
        while (it.hasNext()) {
            val w = it.next()
            val o = w.order
            val yes = o.side == "YES"
            // The print's price in this order's own side.
            val sidePrice = if (yes) yesPrice else 1.0 - yesPrice
            if (w.resting) {
                if (nowMs - o.postedAtMs > tuning.tuned(o.strategy).entryWaitMs(config.entryWaitMs)) {
                    it.remove()
                    events += ScalpEvent.Unfilled(o)
                    continue
                }
                // Our bid is hit by takers selling our side, i.e. buying the other one.
                if (takerYes == yes) continue
                val through = sidePrice < o.price - TICK_EPS
                if (abs(sidePrice - o.price) <= TICK_EPS) w.traded += contracts
                if (through || w.traded >= o.queueAhead + o.contracts) {
                    w.filledAtMs = nowMs
                    w.makerEntryFee = makerFee(o.contracts, o.price)
                    w.exitPrice = round4(o.price + tuning.tuned(o.strategy).targetDollars)
                    val shown = if (o.queueKnown) exitQueue(o.side, w.exitPrice) else null
                    w.exitQueue = shown?.takeIf { q -> q.isFinite() && q >= 0.0 } ?: config.unknownQueue
                    events += ScalpEvent.Filled(o, nowMs)
                }
            } else {
                // A hold has no offer: it waits for the settlement.
                if (o.strategy.hold) continue
                // Our offer is lifted by takers buying our side.
                if (takerYes != yes) continue
                val through = sidePrice > w.exitPrice + TICK_EPS
                if (abs(sidePrice - w.exitPrice) <= TICK_EPS) w.exitTraded += contracts
                if (through || w.exitTraded >= w.exitQueue + o.contracts) {
                    it.remove()
                    events += ScalpEvent.Closed(
                        close(w, w.exitPrice, ExitKind.TARGET, feeUsd = makerFee(o.contracts, w.exitPrice), nowMs = nowMs)
                    )
                }
            }
        }
        return events
    }

    /**
     * Time and price checks: expire unfilled bids, stop out or time out
     * filled scalps at the side's current bid ([bidYes] / [bidNo]; null =
     * no bid to sell into, so the scalp waits).
     */
    fun onClock(ticker: String, nowMs: Long, bidYes: Double?, bidNo: Double?): List<ScalpEvent> {
        val list = byTicker[ticker] ?: return emptyList()
        if (list.isEmpty()) return emptyList()
        val events = ArrayList<ScalpEvent>(2)
        val it = list.iterator()
        while (it.hasNext()) {
            val w = it.next()
            val o = w.order
            val filledAt = w.filledAtMs
            if (filledAt == null) {
                if (nowMs - o.postedAtMs > tuning.tuned(o.strategy).entryWaitMs(config.entryWaitMs)) {
                    it.remove()
                    events += ScalpEvent.Unfilled(o)
                }
                continue
            }
            // A hold has no time-out; it stops out only when it has a stop (Kashi's late favourites, or one you set).
            val t = tuning.tuned(o.strategy)
            if (o.strategy.hold && t.stopDollars <= 0.0) continue
            val bid = (if (o.side == "YES") bidYes else bidNo)?.takeIf { b -> b.isFinite() && b >= 0.0 && b < 1.0 }
                ?: continue
            if (t.trail && bid > w.peakBid) w.peakBid = bid
            val stopFrom = if (t.trail) w.peakBid else o.price
            val stopped = t.stopDollars > 0.0 && bid <= stopFrom - t.stopDollars + EPS
            val timedOut = !o.strategy.hold && nowMs - filledAt >= t.timeoutMs
            if (stopped || timedOut) {
                it.remove()
                val fee = if (bid > 0.0) KalshiFee.total(o.contracts, bid, config.feeRate) else 0.0
                events += ScalpEvent.Closed(
                    close(w, bid, if (stopped) ExitKind.STOP else ExitKind.TIMEOUT, feeUsd = fee, nowMs = nowMs)
                )
            }
        }
        return events
    }

    /** The window settled: filled scalps pay $1 or $0, unfilled bids are dropped. "void" returns the stake. */
    fun settle(ticker: String, result: String, nowMs: Long): List<ScalpEvent> {
        val outcome = result.lowercase().trim()
        if (outcome != "yes" && outcome != "no" && outcome != "void") return emptyList()
        val list = byTicker.remove(ticker) ?: return emptyList()
        val events = ArrayList<ScalpEvent>(list.size)
        for (w in list) {
            val o = w.order
            if (w.resting) {
                events += ScalpEvent.Unfilled(o)
                continue
            }
            if (outcome == "void") {
                // A void refunds the stake and the fee: nothing won, nothing lost.
                events += ScalpEvent.Closed(
                    ClosedScalp(o, o.price, ExitKind.SETTLED, 0.0, 0.0, w.filledAtMs ?: nowMs, nowMs)
                )
                continue
            }
            val value = if ((outcome == "yes") == (o.side == "YES")) 1.0 else 0.0
            events += ScalpEvent.Closed(close(w, value, ExitKind.SETTLED, feeUsd = 0.0, nowMs = nowMs))
        }
        return events
    }

    /** Drop everything on tickers that are no longer watched (nothing is booked for them). */
    fun retain(tickers: Set<String>) {
        byTicker.keys.retainAll(tickers)
    }

    fun clear() {
        byTicker.clear()
    }

    /**
     * The original 7 trade 10–90¢ ([Config.minPrice] / [Config.maxPrice]);
     * a strategy with its own band ([ScalpStrategy.entryMin] /
     * [ScalpStrategy.entryMax]) trades inside that band only.
     */
    private fun inBand(t: Tuned, price: Double): Boolean {
        if (!price.isFinite()) return false
        val lo = if (t.ownBand) t.entryMin else config.minPrice
        val hi = if (t.ownBand) t.entryMax else config.maxPrice
        return price >= lo - EPS && price <= hi + EPS
    }

    private fun makerFee(contracts: Int, price: Double): Double {
        val rate = makerFeeRate
        return if (rate > 0.0 && price > 0.0 && price < 1.0) KalshiFee.total(contracts, price, rate) else 0.0
    }

    /** [feeUsd] is the exit's fee; a resting entry's maker fee is added to it. */
    private fun close(w: Working, exitPrice: Double, kind: ExitKind, feeUsd: Double, nowMs: Long): ClosedScalp {
        val o = w.order
        val fees = feeUsd + w.makerEntryFee
        return ClosedScalp(
            order = o,
            exitPrice = exitPrice,
            kind = kind,
            feeUsd = fees,
            pnlUsd = (exitPrice - o.price) * o.contracts - o.entryFeeUsd - fees,
            filledAtMs = w.filledAtMs ?: nowMs,
            closedAtMs = nowMs
        )
    }

    companion object {
        /** Kalshi quotes some levels in tenths of a cent. */
        const val TICK_EPS = 5e-4
        private const val EPS = 1e-9

        fun round4(v: Double): Double = Math.round(v * 10_000.0) / 10_000.0
    }
}
