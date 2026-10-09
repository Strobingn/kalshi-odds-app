package com.dirk.kalshiodds.signal.trade

import android.content.Context
import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.config.SignalSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The strategy scalpers that run in parallel when auto-trade is armed.
 * Each watches a different ticket family and holds at most one open
 * position, so up to [SignalConstants.AUTO_MAX_OPEN_POSITIONS] strategies
 * ride at once — the Settings board shows which one is actually winning.
 */
enum class AutoStrategy(val label: String, val kinds: Set<TicketKind>) {
    /** Bread-and-butter configured edge tickets. */
    MOMENTUM("Momentum", setOf(TicketKind.CONFIGURED)),
    /** Hunter prints ($1 can settle big). */
    HUNTER("Hunter", setOf(TicketKind.HUNTER)),
    /** Cheap long-shots where the AI beats implied after fees. */
    VALUE("Value", setOf(TicketKind.HUNTER_VALUE)),
    /** Whatever has the highest net EV right now, any family. */
    BEST_EV("Best-EV", setOf(TicketKind.CONFIGURED, TicketKind.HUNTER, TicketKind.HUNTER_VALUE))
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

    /** Per-strategy realized P&L (all-time since armed) for the leaderboard. */
    fun noteStrategyRealized(strategy: String, deltaUsd: Double) = synchronized(lock) {
        val key = KEY_PNL_PREFIX + strategy
        val cur = java.lang.Double.longBitsToDouble(prefs.getLong(key, 0L))
        prefs.edit()
            .putLong(key, java.lang.Double.doubleToRawLongBits(cur + deltaUsd))
            .apply()
    }

    fun strategyPnl(strategy: String): Double = synchronized(lock) {
        java.lang.Double.longBitsToDouble(prefs.getLong(KEY_PNL_PREFIX + strategy, 0L))
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
     * Multi-line Settings board: overall status plus one line per strategy
     * scalper — its all-time auto P&L and what it is riding right now.
     */
    fun strategyBoard(nowMs: Long): String {
        val open = openPositions()
        val lines = mutableListOf(statusLine(nowMs))
        for (strategy in AutoStrategy.entries) {
            val pos = open.firstOrNull { it.strategy == strategy.name }
            val riding = if (pos != null) {
                String.format(
                    Locale.US,
                    "riding %s %s ×%d @ %.0f¢",
                    pos.side,
                    pos.ticker,
                    pos.contracts,
                    pos.entryPrice * 100.0
                )
            } else {
                "flat"
            }
            lines.add("${strategy.label}: ${AutoTradeEngine.fmtSignedUsd(strategyPnl(strategy.name))} · $riding")
        }
        return lines.joinToString("\n")
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
