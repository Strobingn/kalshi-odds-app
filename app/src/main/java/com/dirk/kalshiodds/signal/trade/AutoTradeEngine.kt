package com.dirk.kalshiodds.signal.trade

import android.content.Context
import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.config.SignalSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The strategy scouts that compete when auto-trade is armed. Each watches
 * for its own style of ticket and holds at most one open position; all of
 * them race for [SignalConstants.AUTO_MAX_OPEN_POSITIONS] open slots, so the
 * Settings leaderboard answers the only question that matters: which model
 * wins. [blurb] is shown in Settings so every model explains itself.
 *
 * Every matcher is a plain predicate over fields the ticket already carries
 * (kind, side, price, net edge, net EV, model vs implied, visible depth) —
 * no new engines, no new math. Proposed buy tickets always price at 20¢ or
 * cheaper and always carry 3+ points of net edge, so every band below can
 * actually fire.
 */
enum class AutoStrategy(
    val label: String,
    val blurb: String,
    private val matcher: (TradeTicket) -> Boolean
) {
    MOMENTUM(
        "Momentum",
        "The baseline model. Buys standard configured-edge tickets — sub-5¢ prints that clear the 20x payout gate and your edge threshold. Every other scalper is judged against this one.",
        { it.kind == TicketKind.CONFIGURED }
    ),
    HUNTER(
        "Hunter",
        "Buys ultra-cheap prints where a $1 stake can settle at $25 or more. Loses most clips by design; one win pays for a pile of losses.",
        { it.kind == TicketKind.HUNTER }
    ),
    VALUE(
        "Value",
        "Buys long-shots at 20¢ or less when the AI beats the implied chance after fees and margin. The slow grinder of the cheap-book families.",
        { it.kind == TicketKind.HUNTER_VALUE }
    ),
    BEST_EV(
        "Best-EV",
        "Takes whichever live ticket has the highest net expected value right now, any family. The pure math pick — if the model is calibrated, this should top the board.",
        { true }
    ),
    DEEP_VALUE(
        "Deep Value",
        "Only long-shots at 10¢ or cheaper — 9-to-1 or better on a win. Can be wrong nine times out of ten and still break even. Pure lottery discipline.",
        { it.kind == TicketKind.HUNTER_VALUE && it.limitPrice <= 0.10 + 1e-9 }
    ),
    VALUE_UPPER(
        "Value Upper",
        "The expensive half of the value family: long-shots priced 10–20¢. Pays less per win than Deep Value but should land more often.",
        {
        it.kind == TicketKind.HUNTER_VALUE &&
            it.limitPrice > 0.10 + 1e-9 && it.limitPrice <= 0.20 + 1e-9
        }
    ),
    DIRT_CHEAP(
        "Dirt Cheap",
        "Anything priced 3¢ or under, any family. Maximum-multiple lottery tickets — the cheapest prints the book ever shows.",
        { it.limitPrice <= 0.03 + 1e-9 }
    ),
    UP_ONLY(
        "Up Only",
        "Buys YES at any price, nothing else. A one-direction book that isolates how good the model's upside calls are.",
        { it.side == "YES" }
    ),
    DOWN_ONLY(
        "Down Only",
        "Buys NO at any price, nothing else. The mirror of Up Only — isolates the model at calling failed moves.",
        { it.side == "NO" }
    ),
    CHEAP_UP(
        "Cheap Up",
        "YES tickets at 5¢ or less — the deepest underdog side. Wins pay 20x or better; tests whether the model spots cheap upside the crowd ignores.",
        { it.side == "YES" && it.limitPrice <= 0.05 + 1e-9 }
    ),
    CHEAP_DOWN(
        "Cheap Down",
        "NO tickets at 5¢ or less — betting against near-certain-looking YES prices. Profits when an overhyped favorite face-plants.",
        { it.side == "NO" && it.limitPrice <= 0.05 + 1e-9 }
    ),
    BIG_EDGE(
        "Big Edge",
        "Only fires when the model beats the market by 8+ net points after fees. Rare and aggressive — the 'the market is just wrong' bet.",
        { (it.netEdgePp ?: 0.0) >= 8.0 }
    ),
    MID_EDGE(
        "Mid Edge",
        "Takes 5–8 point net edges. The middle ground between trade frequency and selectivity.",
        { (it.netEdgePp ?: 0.0) >= 5.0 && (it.netEdgePp ?: 0.0) < 8.0 }
    ),
    SMALL_EDGE(
        "Small Edge",
        "Grinds tiny 3–5 point net edges. Trades often, wins a little at a time. Very fee-sensitive — tests whether small signals survive costs.",
        { (it.netEdgePp ?: 0.0) >= 3.0 && (it.netEdgePp ?: 0.0) < 5.0 }
    ),
    FEE_AWARE(
        "Fee Aware",
        "Only takes tickets whose base $5 clip already shows at least $1 of net EV after estimated fees. Filters out trades where costs eat the whole edge.",
        { (it.netEvUsd ?: 0.0) >= 1.0 }
    ),
    EV_PER_CONTRACT(
        "EV / Contract",
        "Only tickets worth 5¢ or more of net EV per contract. Dollar-agnostic — measures the quality of each contract, not the clip size.",
        { (it.netEvPerContract ?: 0.0) >= 0.05 }
    ),
    MOONSHOT(
        "Moonshot",
        "Only tickets paying 25x the stake or better on a win, from any family. Hunter-class multiples only.",
        { it.stakeUsd > 1e-9 && it.maxPayoutUsd >= 25.0 * it.stakeUsd }
    ),
    PREMIUM_PAYOUT(
        "Premium Payout",
        "Only tickets paying 10x or better on a win. The multiples-only book — no grinders allowed.",
        { it.stakeUsd > 1e-9 && it.maxPayoutUsd >= 10.0 * it.stakeUsd }
    ),
    CONTRARIAN(
        "Contrarian",
        "Only fires when the model's probability sits 10+ points from the market price, betting the disagreement. The maximum-contrarian signal.",
        {
        val model = it.modelChance
        val implied = it.impliedChance
        model != null && implied != null && kotlin.math.abs(model - implied) >= 0.10
        }
    ),
    LONGSHOT_MODEL(
        "Model Long-shot",
        "Only trades where the model itself prices the chosen side at 15% or less. Picks the tickets the AI thinks are true long-shots.",
        { (it.modelChance ?: 1.0) <= 0.15 + 1e-9 }
    ),
    HUNTER_PLUS(
        "Hunter Plus",
        "Hunter tickets with a 5+ point net edge. Cheap-print hunting with a quality filter — fewer shots, better shots.",
        { it.kind == TicketKind.HUNTER && (it.netEdgePp ?: 0.0) >= 5.0 }
    ),
    VALUE_PLUS(
        "Value Plus",
        "Value long-shots with a 5+ point net edge. Hunter Plus's filter applied to the 20¢-or-less family.",
        { it.kind == TicketKind.HUNTER_VALUE && (it.netEdgePp ?: 0.0) >= 5.0 }
    ),
    CONFIGURED_CHEAP(
        "Configured Cheap",
        "Standard edge tickets at 2¢ or less — the cheaper half of Momentum's universe, where payouts reach 50x.",
        { it.kind == TicketKind.CONFIGURED && it.limitPrice <= 0.02 + 1e-9 }
    ),
    DEEP_BOOK(
        "Deep Book",
        "Only trades where 50+ contracts sit visible at the ask. No thin-book traps — size and fills come first.",
        { (it.visibleContracts ?: 0) >= 50 }
    );

    /** True when [ticket] is a live buy in this scout's lane. */
    fun matches(ticket: TradeTicket): Boolean =
        !ticket.isSell && !ticket.paperOnly &&
            ticket.kind != TicketKind.MANUAL && matcher(ticket)
}

/**
 * Auto-trade risk cage.
 *
 * The AI may place real Kalshi orders only while armed in Settings (typed
 * [SignalConstants.AUTO_TRADE_CONFIRM_PHRASE]) AND every gate in
 * [AutoTradeEngine] passes. Hard limits: per-order stake, open positions,
 * daily order count, per-market cooldown, daily-loss kill switch, and a
 * consecutive-error pause. State persists across process death so a restart
 * never resets the daily budget.
 */
class AutoTradeStore(context: Context) {

    /** One auto-opened live position awaiting an exit, tagged by strategy. */
    data class OpenPosition(
        val strategy: String,
        val ticker: String,
        val side: String,
        val contracts: Int,
        val entryPrice: Double,
        val costUsd: Double
    )

    /** One row of the Settings leaderboard. */
    data class StrategyStatus(
        val strategy: String,
        val label: String,
        val blurb: String,
        val pnlUsd: Double,
        /** Realized P&L from actual sells only — the honest scalping number. */
        val scalpPnlUsd: Double,
        /** Realized P&L from settlement outcomes — luck, not scalping skill. */
        val settlementPnlUsd: Double,
        val closedTrades: Int,
        val position: OpenPosition?
    ) {
        /**
         * True once the model has closed enough trades for its rank to mean
         * anything. Below the floor a couple of lucky prints can crown a
         * loser, so unranked rows sit under the ranked board.
         */
        val ranked: Boolean get() = closedTrades >= SignalConstants.AUTO_BOARD_MIN_TRADES
    }

    private val prefs = context.applicationContext
        .getSharedPreferences("autotrade_guard", Context.MODE_PRIVATE)
    private val lock = Any()

    var pausedReason: String?
        get() = synchronized(lock) { prefs.getString(KEY_PAUSED, null) }
        set(value) = synchronized(lock) {
            prefs.edit().putString(KEY_PAUSED, value).apply()
        }

    fun clearPause() {
        pausedReason = null
    }

    fun noteError(): Int = synchronized(lock) {
        val n = prefs.getInt(KEY_ERRORS, 0) + 1
        prefs.edit().putInt(KEY_ERRORS, n).apply()
        n
    }

    fun noteSuccess() = synchronized(lock) { prefs.edit().putInt(KEY_ERRORS, 0).apply() }

    fun ordersToday(nowMs: Long): Int = synchronized(lock) {
        rollDayLocked(nowMs)
        prefs.getInt(KEY_ORDERS_TODAY, 0)
    }

    fun realizedToday(nowMs: Long): Double = synchronized(lock) {
        rollDayLocked(nowMs)
        java.lang.Double.longBitsToDouble(prefs.getLong(KEY_REALIZED_TODAY, 0L))
    }

    /** Records an order attempt: daily count + per-market cooldown start. */
    fun noteOrder(ticker: String, nowMs: Long) = synchronized(lock) {
        rollDayLocked(nowMs)
        prefs.edit()
            .putInt(KEY_ORDERS_TODAY, prefs.getInt(KEY_ORDERS_TODAY, 0) + 1)
            .putLong(KEY_COOLDOWN_PREFIX + ticker, nowMs)
            .apply()
    }

    /** Adds an estimated realized P&L delta (sell proceeds − cost − fees). */
    fun noteRealized(deltaUsd: Double, nowMs: Long) = synchronized(lock) {
        rollDayLocked(nowMs)
        val cur = java.lang.Double.longBitsToDouble(prefs.getLong(KEY_REALIZED_TODAY, 0L))
        prefs.edit()
            .putLong(KEY_REALIZED_TODAY, java.lang.Double.doubleToRawLongBits(cur + deltaUsd))
            .apply()
    }

    /**
     * Per-strategy realized P&L from a SELL close (all-time since armed).
     * Counts one closed trade and books the delta to the total and the
     * scalp column — never to settlement.
     */
    fun noteStrategyRealized(strategy: String, deltaUsd: Double) = synchronized(lock) {
        addPnlLocked(KEY_PNL_PREFIX + strategy, deltaUsd)
        addPnlLocked(KEY_SCALP_PREFIX + strategy, deltaUsd)
        bumpTradeLocked(strategy)
    }

    fun strategyPnl(strategy: String): Double = synchronized(lock) {
        java.lang.Double.longBitsToDouble(prefs.getLong(KEY_PNL_PREFIX + strategy, 0L))
    }

    fun strategyScalpPnl(strategy: String): Double = synchronized(lock) {
        java.lang.Double.longBitsToDouble(prefs.getLong(KEY_SCALP_PREFIX + strategy, 0L))
    }

    fun strategySettlementPnl(strategy: String): Double = synchronized(lock) {
        java.lang.Double.longBitsToDouble(prefs.getLong(KEY_SETTLE_PREFIX + strategy, 0L))
    }

    fun strategyTrades(strategy: String): Int = synchronized(lock) {
        prefs.getInt(KEY_TRADES_PREFIX + strategy, 0)
    }

    /**
     * Settlement close for an open auto position: books the outcome to the
     * settlement bucket — never the scalp column — so expiry coin-flips
     * cannot inflate a scalper's rank (the kashi harness's leaderboard
     * illusion). Counts a closed trade and feeds the daily-loss limit.
     * Idempotent per ticker: the position is removed as it is booked, and
     * a ticker can hold at most one auto position.
     */
    fun noteSettlement(ticker: String, result: String, feeRate: Double, nowMs: Long) = synchronized(lock) {
        val outcome = result.lowercase().trim()
        if (outcome != "yes" && outcome != "no" && outcome != "void") return
        val all = openPositionsLocked()
        val hit = all.firstOrNull { it.ticker.equals(ticker, ignoreCase = true) } ?: return
        writeOpenLocked(all - hit)
        val won = outcome != "void" && hit.side.equals(outcome, ignoreCase = true)
        val payout = when {
            outcome == "void" -> hit.costUsd
            won -> hit.contracts * SignalConstants.CONTRACT_SETTLEMENT_USD
            else -> 0.0
        }
        val entryFee = AutoTradeEngine.feeEstimate(hit.contracts, hit.entryPrice, feeRate)
        val pnl = if (outcome == "void") 0.0 else payout - hit.costUsd - entryFee
        val strategy = hit.strategy.ifBlank { LEGACY }
        addPnlLocked(KEY_PNL_PREFIX + strategy, pnl)
        addPnlLocked(KEY_SETTLE_PREFIX + strategy, pnl)
        bumpTradeLocked(strategy)
        rollDayLocked(nowMs)
        val today = java.lang.Double.longBitsToDouble(prefs.getLong(KEY_REALIZED_TODAY, 0L))
        prefs.edit()
            .putLong(KEY_REALIZED_TODAY, java.lang.Double.doubleToRawLongBits(today + pnl))
            .apply()
    }

    private fun addPnlLocked(key: String, deltaUsd: Double) {
        val cur = java.lang.Double.longBitsToDouble(prefs.getLong(key, 0L))
        prefs.edit()
            .putLong(key, java.lang.Double.doubleToRawLongBits(cur + deltaUsd))
            .apply()
    }

    private fun bumpTradeLocked(strategy: String) {
        val key = KEY_TRADES_PREFIX + strategy
        prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply()
    }

    /**
     * Sweep settled outcomes from the prediction log — catches settlements
     * the app slept through. Idempotent: booked positions leave the open
     * set, so repeat sweeps are no-ops.
     */
    fun settleFromLog(
        entries: List<com.dirk.kalshiodds.prediction.PredictionLogEntry>,
        feeRate: Double,
        nowMs: Long
    ) {
        entries.forEach { e ->
            val o = e.outcome ?: return@forEach
            noteSettlement(e.ticker, o, feeRate, nowMs)
        }
    }

    fun cooldownRemainingMs(ticker: String, nowMs: Long): Long = synchronized(lock) {
        val last = prefs.getLong(KEY_COOLDOWN_PREFIX + ticker, 0L)
        (last + SignalConstants.AUTO_COOLDOWN_MS - nowMs).coerceAtLeast(0L)
    }

    fun openPositions(): List<OpenPosition> = synchronized(lock) { openPositionsLocked() }

    fun addOpenPosition(pos: OpenPosition) = synchronized(lock) {
        val kept = openPositionsLocked().filterNot {
            it.ticker == pos.ticker && it.side.equals(pos.side, true)
        }
        writeOpenLocked(kept + pos)
    }

    fun removeOpenPosition(ticker: String, side: String): OpenPosition? = synchronized(lock) {
        val all = openPositionsLocked()
        val hit = all.firstOrNull { it.ticker == ticker && it.side.equals(side, true) }
        if (hit != null) writeOpenLocked(all - hit)
        hit
    }

    /** One-line status for Settings: today's budget, estimated P&L, open slots. */
    fun statusLine(nowMs: Long): String {
        val base = String.format(
            Locale.US,
            "Today: %d/%d orders · est. P&L %s · %d/%d positions open",
            ordersToday(nowMs),
            SignalConstants.AUTO_MAX_DAILY_ORDERS,
            AutoTradeEngine.fmtSignedUsd(realizedToday(nowMs)),
            openPositions().size,
            SignalConstants.AUTO_MAX_OPEN_POSITIONS
        )
        val paused = pausedReason ?: return base
        return "PAUSED — $paused\n$base"
    }

    /**
     * Settings leaderboard. Ranked rows ([StrategyStatus.ranked] = enough
     * closed trades) sort by scalp P&L only — settlement lottery stays out
     * of the ranking. Unproven models follow, closest to ranking first.
     */
    fun boardEntries(): List<StrategyStatus> {
        val open = openPositions()
        val rows = AutoStrategy.entries.map { strategy ->
            StrategyStatus(
                strategy = strategy.name,
                label = strategy.label,
                blurb = strategy.blurb,
                pnlUsd = strategyPnl(strategy.name),
                scalpPnlUsd = strategyScalpPnl(strategy.name),
                settlementPnlUsd = strategySettlementPnl(strategy.name),
                closedTrades = strategyTrades(strategy.name),
                position = open.firstOrNull { it.strategy == strategy.name }
            )
        }
        val ranked = rows.filter { it.ranked }.sortedByDescending { it.scalpPnlUsd }
        val warming = rows.filterNot { it.ranked }.sortedWith(
            compareByDescending<StrategyStatus> { it.closedTrades }
                .thenByDescending { it.scalpPnlUsd }
        )
        val legacyPnl = strategyPnl(LEGACY)
        val legacyPos = open.firstOrNull { it.strategy.isBlank() }
        return if (legacyPnl != 0.0 || legacyPos != null) {
            ranked + warming + StrategyStatus(
                strategy = LEGACY,
                label = "Legacy",
                blurb = "Positions opened before the strategy tags existed.",
                pnlUsd = legacyPnl,
                scalpPnlUsd = strategyScalpPnl(LEGACY),
                settlementPnlUsd = strategySettlementPnl(LEGACY),
                closedTrades = strategyTrades(LEGACY),
                position = legacyPos
            )
        } else {
            ranked + warming
        }
    }

    private fun rollDayLocked(nowMs: Long) {
        val day = DAY_FMT.format(Date(nowMs))
        if (prefs.getString(KEY_DAY, null) != day) {
            prefs.edit()
                .putString(KEY_DAY, day)
                .putInt(KEY_ORDERS_TODAY, 0)
                .putLong(KEY_REALIZED_TODAY, 0L)
                .apply()
        }
    }

    private fun openPositionsLocked(): List<OpenPosition> =
        prefs.getStringSet(KEY_OPEN, emptySet()).orEmpty().mapNotNull { line ->
            val p = line.split("|")
            // v1 rows had no strategy tag (ticker|side|…); treat as untagged.
            if (p.size == 5) {
                val contracts = p[2].toIntOrNull() ?: return@mapNotNull null
                val entry = p[3].toDoubleOrNull() ?: return@mapNotNull null
                val cost = p[4].toDoubleOrNull() ?: return@mapNotNull null
                return@mapNotNull OpenPosition("", p[0], p[1], contracts, entry, cost)
            }
            if (p.size != 6) return@mapNotNull null
            val contracts = p[3].toIntOrNull() ?: return@mapNotNull null
            val entry = p[4].toDoubleOrNull() ?: return@mapNotNull null
            val cost = p[5].toDoubleOrNull() ?: return@mapNotNull null
            OpenPosition(p[0], p[1], p[2], contracts, entry, cost)
        }

    private fun writeOpenLocked(list: List<OpenPosition>) {
        val lines = list.map {
            "${it.strategy}|${it.ticker}|${it.side}|${it.contracts}|${it.entryPrice}|${it.costUsd}"
        }.toSet()
        prefs.edit().putStringSet(KEY_OPEN, lines).apply()
    }

    companion object {
        private val DAY_FMT = SimpleDateFormat("yyyyMMdd", Locale.US)
        private const val KEY_DAY = "day"
        private const val KEY_ORDERS_TODAY = "orders_today"
        private const val KEY_REALIZED_TODAY = "realized_today_bits"
        private const val KEY_ERRORS = "consec_errors"
        private const val KEY_PAUSED = "paused_reason"
        private const val KEY_OPEN = "open_positions"
        private const val KEY_COOLDOWN_PREFIX = "cooldown_"
        private const val KEY_PNL_PREFIX = "strategy_pnl_"
        private const val KEY_SCALP_PREFIX = "strategy_scalp_"
        private const val KEY_SETTLE_PREFIX = "strategy_settle_"
        private const val KEY_TRADES_PREFIX = "strategy_trades_"
        private const val LEGACY = "LEGACY"
    }
}

/** Pure auto-trade gates — every input passed in, so unit tests need no Android. */
object AutoTradeEngine {

    /** Kalshi taker fee estimate: feeRate × C × P × (1−P). */
    fun feeEstimate(contracts: Int, price: Double, feeRate: Double): Double =
        feeRate * contracts * price * (1.0 - price)

    fun fmtSignedUsd(v: Double): String {
        val sign = if (v >= 0) "+$" else "-$"
        return sign + String.format(Locale.US, "%.2f", kotlin.math.abs(v))
    }

    /**
     * Null = the auto buy may fire. Non-null = human-readable block reason.
     * Manual buy cards (user tapped Buy) always stay Approve-only.
     */
    fun entryBlockReason(
        armed: Boolean,
        credentialsConfigured: Boolean,
        pausedReason: String?,
        ticket: TradeTicket,
        settings: SignalSettings,
        ordersToday: Int,
        openPositions: Int,
        strategyBusy: Boolean,
        alreadyOpenOnTicker: Boolean,
        cooldownRemainingMs: Long,
        liveAsk: Double?,
        estimatedDailyPnlUsd: Double
    ): String? {
        if (!armed) return "auto-trade is off"
        if (pausedReason != null) return "paused: $pausedReason"
        if (!credentialsConfigured) return "no Kalshi API key saved"
        if (ticket.isSell || ticket.paperOnly || !ticket.canApprove) return "not an auto-buy ticket"
        if (ticket.kind == TicketKind.MANUAL) return "manual tickets stay Approve-only"
        if (ticket.stakeUsd > settings.autoMaxStakeUsd + 1e-9) {
            return String.format(
                Locale.US,
                "stake $%.2f over auto cap $%.2f",
                ticket.stakeUsd,
                settings.autoMaxStakeUsd
            )
        }
        if (ordersToday >= SignalConstants.AUTO_MAX_DAILY_ORDERS) return "daily order cap reached"
        if (openPositions >= SignalConstants.AUTO_MAX_OPEN_POSITIONS) return "max open auto positions"
        if (strategyBusy) return "this scalper is already riding a position"
        if (alreadyOpenOnTicker) return "already holding an auto position on ${ticket.ticker}"
        if (cooldownRemainingMs > 0L) return "cooldown ${cooldownRemainingMs / 1000}s"
        if (estimatedDailyPnlUsd <= -settings.autoDailyLossLimitUsd) return "daily loss limit hit"
        val ask = liveAsk
        if (ask != null && ask - ticket.limitPrice > SignalConstants.AUTO_MAX_SLIPPAGE + 1e-9) {
            return String.format(
                Locale.US,
                "price ran away (ask %.1f¢ vs signal %.1f¢)",
                ask * 100.0,
                ticket.limitPrice * 100.0
            )
        }
        return null
    }

    /**
     * Null = the auto sell may fire. Exits skip the stake / slippage gates —
     * cutting a losing position is never blocked by the pause state.
     */
    fun sellBlockReason(
        credentialsConfigured: Boolean,
        ticket: TradeTicket,
        ordersToday: Int,
        cooldownRemainingMs: Long
    ): String? {
        if (!credentialsConfigured) return "no Kalshi API key saved"
        if (!ticket.isSell || ticket.paperOnly || !ticket.canApprove) return "not a live sell ticket"
        if (ordersToday >= SignalConstants.AUTO_MAX_DAILY_ORDERS) return "daily order cap reached"
        if (cooldownRemainingMs > 0L) return "cooldown ${cooldownRemainingMs / 1000}s"
        return null
    }
}
