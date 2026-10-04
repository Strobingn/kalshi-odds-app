package com.dirk.kalshiodds.signal.trade

import android.content.Context
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Daily cap on **live buys**. Every measured way of buying this market has
 * a negative expectation (docs/tape-study-2026-10-04.md), so the only sure
 * lever is how much goes in per day.
 *
 * - Counts the all-in cost of each live buy the moment Kalshi accepts it,
 *   filled or resting. The part of a resting order that Kalshi confirms
 *   as cancelled is given back ([release]).
 * - The day is the phone's local calendar day; the count resets at midnight.
 * - Sells are never blocked or counted (they reduce risk). Paper is never
 *   counted.
 * - [State.capUsd] = 0 turns the cap off.
 *
 * Pure logic in this object; [LiveDailyCapStore] persists it.
 */
object LiveDailyCap {

    const val DEFAULT_CAP_USD = 50.0
    const val MAX_CAP_USD = 500.0
    const val SETTINGS_HINT = "Change it in Settings → Live Approve tickets."

    private const val EPS = 1e-9

    @Serializable
    data class State(
        val capUsd: Double = DEFAULT_CAP_USD,
        /** Local day `yyyy-MM-dd` that [spentUsd] / [orders] belong to. */
        val day: String = "",
        val spentUsd: Double = 0.0,
        val orders: Int = 0
    ) {
        val enabled: Boolean get() = capUsd > 0.0
    }

    /** Local calendar day for [nowMs]. */
    fun dayKey(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().toString()

    /** [state] as of [today]: a new day starts the count at zero and keeps the cap. */
    fun rolled(state: State, today: String): State =
        if (state.day == today) state else state.copy(day = today, spentUsd = 0.0, orders = 0)

    fun clampCap(capUsd: Double): Double =
        if (!capUsd.isFinite()) DEFAULT_CAP_USD else capUsd.coerceIn(0.0, MAX_CAP_USD)

    /** Dollars left today, or null when the cap is off. */
    fun remainingUsd(state: State, today: String): Double? {
        val s = rolled(state, today)
        if (!s.enabled) return null
        return (s.capUsd - s.spentUsd).coerceAtLeast(0.0)
    }

    /**
     * Why a live buy costing [orderCostUsd] cannot go out now, or null when
     * it fits. Never null-safe-skips: an unknown / non-positive cost is
     * treated as the $5 live cap so a bad ticket cannot slip past.
     */
    fun blockReason(state: State, today: String, orderCostUsd: Double?): String? {
        val s = rolled(state, today)
        if (!s.enabled) return null
        val cost = orderCostUsd?.takeIf { it.isFinite() && it > 0.0 } ?: LiveOrderGates.LIVE_ALL_IN
        if (s.spentUsd + cost <= s.capUsd + EPS) return null
        return String.format(
            Locale.US,
            "Daily live cap: $%.2f of $%.0f already sent today, so this $%.2f order would pass it. Nothing was sent. %s",
            s.spentUsd,
            s.capUsd,
            cost,
            SETTINGS_HINT
        )
    }

    /** Count an accepted live buy. */
    fun record(state: State, today: String, orderCostUsd: Double): State {
        val s = rolled(state, today)
        val cost = orderCostUsd.takeIf { it.isFinite() && it > 0.0 } ?: return s
        return s.copy(spentUsd = s.spentUsd + cost, orders = s.orders + 1)
    }

    /** Give back [usd] of today's count (a resting order cancelled unfilled). */
    fun release(state: State, today: String, usd: Double): State {
        val s = rolled(state, today)
        val back = usd.takeIf { it.isFinite() && it > 0.0 } ?: return s
        return s.copy(spentUsd = (s.spentUsd - back).coerceAtLeast(0.0))
    }

    /** All-in dollars a live buy [ticket] commits. */
    fun costOf(ticket: TradeTicket): Double =
        listOfNotNull(ticket.allInUsd, ticket.estimatedFillUsd, ticket.stakeUsd)
            .firstOrNull { it.isFinite() && it > 0.0 }
            ?: LiveOrderGates.LIVE_ALL_IN

    private val REDUCED_BY = Regex("""\(−\s*([0-9]+(?:\.[0-9]+)?)\)""")

    /**
     * Dollars to give back after a cancel. Uses the contract count Kalshi
     * reported as cancelled (`reduced_by`, carried on [PlacedOrder.error] as
     * `cancelled (−N)`); with no count, nothing is released, so a cancel can
     * never free more of the cap than was really pulled.
     */
    fun cancelledCostOf(order: PlacedOrder): Double {
        val total = order.ticket.contracts
        if (total <= 0) return 0.0
        val reduced = REDUCED_BY.find(order.error.orEmpty())?.groupValues?.get(1)?.toDoubleOrNull() ?: return 0.0
        val share = (reduced / total.toDouble()).coerceIn(0.0, 1.0)
        return costOf(order.ticket) * share
    }

    /** `Daily live cap  $50  ·  $15.00 sent today (3 orders)` / `Daily live cap  off`. */
    fun settingsLabel(state: State, today: String): String {
        val s = rolled(state, today)
        if (!s.enabled) return "Daily live cap  off"
        val orders = if (s.orders == 1) "1 order" else "${s.orders} orders"
        return String.format(Locale.US, "Daily live cap  $%.0f  ·  $%.2f sent today (%s)", s.capUsd, s.spentUsd, orders)
    }
}

/**
 * On-device store for [LiveDailyCap] (SharedPreferences JSON, same pattern
 * as the paper book). One instance per process so the Settings slider and
 * the Approve path see the same count.
 */
class LiveDailyCapStore internal constructor(
    private val load: () -> String?,
    private val save: (String) -> Unit,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val zone: () -> ZoneId = { ZoneId.systemDefault() }
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val lock = Any()
    private val _state = MutableStateFlow(initial())
    val state: StateFlow<LiveDailyCap.State> = _state.asStateFlow()

    private fun initial(): LiveDailyCap.State {
        val stored = runCatching { load() }.getOrNull()
            ?.let { raw -> runCatching { json.decodeFromString(LiveDailyCap.State.serializer(), raw) }.getOrNull() }
            ?: LiveDailyCap.State()
        return LiveDailyCap.rolled(stored.copy(capUsd = LiveDailyCap.clampCap(stored.capUsd)), today())
    }

    fun today(): String = LiveDailyCap.dayKey(nowMs(), zone())

    /** Current state rolled to today. */
    fun snapshot(): LiveDailyCap.State = synchronized(lock) {
        val cur = _state.value
        val rolled = LiveDailyCap.rolled(cur, today())
        if (rolled != cur) publish(rolled)
        rolled
    }

    fun setCapUsd(capUsd: Double) = synchronized(lock) {
        publish(LiveDailyCap.rolled(_state.value, today()).copy(capUsd = LiveDailyCap.clampCap(capUsd)))
    }

    fun blockReason(orderCostUsd: Double?): String? =
        LiveDailyCap.blockReason(snapshot(), today(), orderCostUsd)

    fun record(orderCostUsd: Double) = synchronized(lock) {
        publish(LiveDailyCap.record(_state.value, today(), orderCostUsd))
    }

    fun release(usd: Double) = synchronized(lock) {
        publish(LiveDailyCap.release(_state.value, today(), usd))
    }

    private fun publish(next: LiveDailyCap.State) {
        _state.value = next
        runCatching { save(json.encodeToString(LiveDailyCap.State.serializer(), next)) }
    }

    companion object {
        private const val PREFS = "bitcoin_claude_live_daily_cap"
        private const val KEY = "state_json"

        private var instance: LiveDailyCapStore? = null
        private var owner: java.lang.ref.WeakReference<Context>? = null

        /**
         * One store per application context. A new Application (process
         * restart, or the next Robolectric test) gets a fresh store.
         */
        fun get(context: Context): LiveDailyCapStore = synchronized(this) {
            val app = context.applicationContext ?: context
            val cur = instance
            if (cur != null && owner?.get() === app) return@synchronized cur
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            LiveDailyCapStore(
                load = { prefs.getString(KEY, null) },
                save = { prefs.edit().putString(KEY, it).apply() }
            ).also {
                instance = it
                owner = java.lang.ref.WeakReference(app)
            }
        }
    }
}
