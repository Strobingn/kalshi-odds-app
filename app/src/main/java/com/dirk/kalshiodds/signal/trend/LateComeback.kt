package com.dirk.kalshiodds.signal.trend

import android.content.Context
import com.dirk.kalshiodds.domain.CryptoMarkets
import com.dirk.kalshiodds.signal.flowfade.FlowFadeSummary
import com.dirk.kalshiodds.signal.latefav.LateFavoriteLedger
import com.dirk.kalshiodds.signal.latefav.LateFavoriteRule
import com.dirk.kalshiodds.signal.latefav.LateFavoriteState
import kotlinx.serialization.json.Json

/**
 * "Late comeback": in minutes 10–13, the underdog Kalshi prices at 20–40¢
 * whose mid rose 4¢ or more over the last 30 seconds. Buy it at the ask,
 * $5 all-in, hold to the settlement, once a window. **Kalshi data only**:
 * the prices and the move are Kalshi's own.
 *
 * The phone's copy of the arena strategy of the same name, which made
 * money on the 12 recorded days (2026-09-28 → 10-16, ArenaReplayTest).
 * This one reads the order book's mid instead of the trade prints and is
 * sized at $5 all-in; ArenaReplayTest.sweepPhoneRules scores it on the
 * recorded book. Pure math. Never places an order.
 */
object LateComebackRule {

    /** When, at what price and on how big a rise the rule buys. */
    data class Band(
        val minTte: Long = 120L,
        val maxTte: Long = 300L,
        val minAsk: Double = 0.20,
        val maxAsk: Double = 0.40,
        val riseDollars: Double = 0.04,
        val lookbackSeconds: Int = 30
    )

    val DEFAULT_BAND = Band()

    private const val EPS = 1e-9

    private fun inBand(ask: Double?, band: Band): Boolean =
        ask != null && ask.isFinite() && ask >= band.minAsk - EPS && ask <= band.maxAsk + EPS

    /**
     * The side to buy now, or null. [yesMidNow] / [yesMidThen] are the YES
     * mid now and [Band.lookbackSeconds] ago; NO's mid moves the other way.
     */
    fun signal(
        tteSeconds: Long?,
        yesAsk: Double?,
        noAsk: Double?,
        yesMidNow: Double?,
        yesMidThen: Double?,
        band: Band = DEFAULT_BAND
    ): ClearLeadRule.Signal? {
        val tte = tteSeconds?.takeIf { it in band.minTte..band.maxTte } ?: return null
        val now = yesMidNow?.takeIf { it.isFinite() } ?: return null
        val then = yesMidThen?.takeIf { it.isFinite() } ?: return null
        val rise = now - then
        return when {
            inBand(yesAsk, band) && rise >= band.riseDollars - EPS -> ClearLeadRule.Signal("YES", yesAsk!!, tte)
            inBand(noAsk, band) && -rise >= band.riseDollars - EPS -> ClearLeadRule.Signal("NO", noAsk!!, tte)
            else -> null
        }
    }

    /** The $5 paper buy for [signal], or null without a usable ask or on a market the app does not trade. */
    fun decide(ticker: String, nowMs: Long, signal: ClearLeadRule.Signal, yesAsk: Double?, noAsk: Double?): LateFavoriteRule.Decision? {
        if (!CryptoMarkets.isLiveTicker(ticker)) return null
        return ClearLeadRule.decide(ticker, nowMs, signal, yesAsk, noAsk)
    }
}

/**
 * Each market's YES mid, one sample a second for the last two minutes, so
 * [LateComebackRule] can read the move over its lookback. Thread-safe.
 */
class MidTape(private val keepSeconds: Int = 120) {
    private val lock = Any()
    private val byTicker = HashMap<String, ArrayDeque<Pair<Long, Double>>>()

    fun note(ticker: String, nowMs: Long, yesMid: Double?) {
        val mid = yesMid?.takeIf { it.isFinite() && it > 0.0 && it < 1.0 } ?: return
        val sec = nowMs / 1000L
        synchronized(lock) {
            val q = byTicker.getOrPut(ticker) { ArrayDeque() }
            if (q.isNotEmpty() && q.last().first == sec) q.removeLast()
            q.addLast(sec to mid)
            while (q.isNotEmpty() && q.first().first < sec - keepSeconds) q.removeFirst()
            if (byTicker.size > MAX_TICKERS) byTicker.keys.firstOrNull { it != ticker }?.let { byTicker.remove(it) }
        }
    }

    /** The mid at the last sample at or before [seconds] ago, if one is within [toleranceSeconds] of it. */
    fun midAgo(ticker: String, nowMs: Long, seconds: Int, toleranceSeconds: Int = 5): Double? {
        val target = nowMs / 1000L - seconds
        synchronized(lock) {
            val q = byTicker[ticker] ?: return null
            val hit = q.lastOrNull { it.first <= target } ?: return null
            return if (target - hit.first <= toleranceSeconds) hit.second else null
        }
    }

    fun forget(ticker: String) = synchronized(lock) { byTicker.remove(ticker) }

    private companion object {
        const val MAX_TICKERS = 64
    }
}

/** On-device paper ledger for the late-comeback rule, same entry / settle logic as the other trackers. */
class LateComebackStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Every bet also goes to the permanent archive ([com.dirk.kalshiodds.signal.archive.BetArchiveStore]). */
    private val archive = com.dirk.kalshiodds.signal.archive.BetArchiveStore.of(context)

    val ledger: LateFavoriteLedger = LateFavoriteLedger(
        initial = load(),
        persist = {
            save(it)
            archive.tracker(com.dirk.kalshiodds.signal.archive.BetRows.LATE_COMEBACK, it)
        }
    ).also { archive.tracker(com.dirk.kalshiodds.signal.archive.BetRows.LATE_COMEBACK, it.snapshot()) }

    private fun load(): LateFavoriteState {
        val raw = prefs.getString(KEY, null) ?: return LateFavoriteState()
        return runCatching { json.decodeFromString(LateFavoriteState.serializer(), raw) }
            .getOrElse { LateFavoriteState() }
    }

    private fun save(state: LateFavoriteState) {
        runCatching {
            prefs.edit().putString(KEY, json.encodeToString(LateFavoriteState.serializer(), state)).apply()
        }
    }

    private companion object {
        const val PREFS = "bitcoin_claude_late_comeback"
        const val KEY = "state_json"
    }
}

/** Home-card copy. Same lines as the other tracker cards, its own title and note. */
object LateComebackSummary {
    const val TITLE = "Late comeback (phone rule) · PAPER RECORD"
    const val NOTE =
        "Kalshi data only: in minutes 10–13, buys the underdog Kalshi prices at 20–40¢ once its mid has risen 4¢ " +
            "or more in 30 seconds, $5 at the ask, held to the close, once a window. On the 12 recorded days " +
            "(2026-09-28 → 10-16, the recorded order book): 405 bets, +$286.22 after fees (+71¢ a bet), up in both " +
            "parts of the days; most bets lose (34% won), the winners pay. The paper record here is the real test."

    fun of(state: LateFavoriteState): FlowFadeSummary {
        val base = FlowFadeSummary.of(state)
        return base.copy(
            title = TITLE,
            recordLine = base.recordLine.replace("¢ bid", "¢ ask"),
            worseLine = base.worseLine.replace("With a maker fee", "Worse fill (+1¢)"),
            note = NOTE
        )
    }
}
