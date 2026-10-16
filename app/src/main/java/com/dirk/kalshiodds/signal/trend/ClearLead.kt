package com.dirk.kalshiodds.signal.trend

import android.content.Context
import com.dirk.kalshiodds.domain.CryptoMarkets
import com.dirk.kalshiodds.domain.KalshiPrice
import com.dirk.kalshiodds.signal.flowfade.FlowFadeSummary
import com.dirk.kalshiodds.signal.latefav.LateFavoriteLedger
import com.dirk.kalshiodds.signal.latefav.LateFavoriteRule
import com.dirk.kalshiodds.signal.latefav.LateFavoriteState
import kotlinx.serialization.json.Json
import java.util.Locale
import kotlin.math.abs

/**
 * "Clear lead": bet the way Bitcoin is going, once it is clearly ahead.
 * **Kalshi data only** (since 1.9.6): the lead is read from Kalshi's own
 * prices, not from an outside Bitcoin feed. A side Kalshi prices at 80–90¢
 * between minute 3 and minute 10 is the side Bitcoin is clearly ahead on.
 *
 *  - between minute 3 and minute 10 ([MAX_TTE_SECONDS] to [MIN_TTE_SECONDS] left),
 *  - the favourite's ask is in [[MIN_ASK], [MAX_ASK]),
 *  - buy that side at the ask, once per window (first time).
 *
 * History, Kalshi 1-minute prices only, 4,264 windows 2026-08-24 → 10-08
 * (`tools/research/clear_lead_kalshi.py`): first 23 days 84.9% wins at
 * 83.3¢, +0.66¢ per contract [−1.31, +2.51]; last 23 days 84.9% at 83.4¢,
 * +0.48¢ [−1.36, +2.27]. Same size as the old Bitcoin-feed version, and like
 * it **not clear of zero**. The paper record is the real test.
 *
 * Pure math. Never places an order: the live ticket still needs Approve and
 * is capped at $5 all-in like every other live buy.
 */
object ClearLeadRule {

    /** Minute 10 of the window: do not enter with less than this left. */
    const val MIN_TTE_SECONDS = 300L

    /** Minute 3 of the window: do not enter with more than this left. */
    const val MAX_TTE_SECONDS = 720L

    /** The favourite's ask must be in [MIN_ASK, MAX_ASK). */
    const val MIN_ASK = 0.80
    const val MAX_ASK = 0.90

    const val TARGET_SETTLED = 1000

    private const val EPS = 1e-9

    data class Signal(
        /** "YES" (UP) or "NO" (DOWN): the side Kalshi prices as the clear favourite. */
        val side: String,
        /** That side's ask, dollars. */
        val ask: Double,
        val tteSeconds: Long
    ) {
        /** `Kalshi prices UP at 84¢ with 7:10 left` */
        val reason: String
            get() = String.format(
                Locale.US,
                "Kalshi prices %s at %.0f¢ with %d:%02d left",
                if (side == "NO") "DOWN" else "UP",
                ask * 100.0,
                tteSeconds / 60L,
                tteSeconds % 60L
            )
    }

    fun inTimeBand(tteSeconds: Long?): Boolean =
        tteSeconds != null && tteSeconds in MIN_TTE_SECONDS..MAX_TTE_SECONDS

    fun inBand(ask: Double?): Boolean =
        ask != null && ask.isFinite() && ask >= MIN_ASK - EPS && ask < MAX_ASK - EPS

    /** The rule's call right now from Kalshi's asks, or null. */
    fun signal(tteSeconds: Long?, yesAsk: Double?, noAsk: Double?): Signal? {
        val tte = tteSeconds?.takeIf { inTimeBand(it) } ?: return null
        return when {
            inBand(yesAsk) -> Signal("YES", yesAsk!!, tte)
            inBand(noAsk) -> Signal("NO", noAsk!!, tte)
            else -> null
        }
    }

    data class Inputs(
        val ticker: String,
        val nowMs: Long,
        val tteSeconds: Long?,
        val yesAsk: Double?,
        val noAsk: Double?
    )

    /**
     * The paper bet the rule takes now ($5 all-in at the ask, exact taker
     * fee), or null. [alreadyEntered] enforces one entry per window.
     * `z` in the returned decision is the ask in cents.
     */
    fun evaluate(inputs: Inputs, alreadyEntered: Boolean): LateFavoriteRule.Decision? {
        if (alreadyEntered) return null
        if (!CryptoMarkets.isLiveTicker(inputs.ticker)) return null
        val sig = signal(inputs.tteSeconds, inputs.yesAsk, inputs.noAsk) ?: return null
        return decide(inputs.ticker, inputs.nowMs, sig, inputs.yesAsk, inputs.noAsk)
    }

    /** The $5 paper buy for a [signal] the engine already produced, or null without a usable ask. */
    fun decide(
        ticker: String,
        nowMs: Long,
        signal: Signal,
        yesAsk: Double?,
        noAsk: Double?
    ): LateFavoriteRule.Decision? {
        val ask = KalshiPrice.usable(if (signal.side == "YES") yesAsk else noAsk) ?: return null
        val sized = LateFavoriteRule.sizeAllIn(ask) ?: return null
        val worseAsk = ask + LateFavoriteRule.WORSE_FILL_SLIPPAGE
        return LateFavoriteRule.Decision(
            ticker = ticker,
            nowMs = nowMs,
            tteSeconds = signal.tteSeconds,
            z = signal.ask * 100.0,
            side = signal.side,
            ask = ask,
            sized = sized,
            worseAsk = worseAsk,
            worse = LateFavoriteRule.sizeAllIn(worseAsk)
        )
    }
}

/** On-device paper ledger for the clear-lead rule. Same entry / settle logic as the other trackers. */
class ClearLeadStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val ledger: LateFavoriteLedger = LateFavoriteLedger(
        initial = load(),
        persist = { save(it) }
    )

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
        const val PREFS = "bitcoin_claude_clear_lead"
        const val KEY = "state_json"
    }
}

/** Home-card copy. Same lines as the other tracker cards, its own title, target and note. */
object ClearLeadSummary {
    const val TITLE = "Clear lead (your rule) · PAPER RECORD"
    const val NOTE =
        "Kalshi data only: bets the side Kalshi prices at 80–90¢ between minute 3 and minute 10, once per " +
            "window, $5 at the ask. History (46 days of Kalshi prices): about +4¢ per $5 bet in the first half and " +
            "+3¢ in the second; neither is clear of zero. This card is the real test: every window is logged here " +
            "whether or not you approve the live ticket."

    fun of(state: LateFavoriteState): FlowFadeSummary {
        val base = FlowFadeSummary.of(state)
        return base.copy(
            title = TITLE,
            recordLine = base.recordLine
                .replace("/ ${FlowFadeSummary.TARGET_SETTLED} settled", "/ ${ClearLeadRule.TARGET_SETTLED} settled")
                .replace("¢ bid", "¢ ask"),
            worseLine = base.worseLine.replace("With a maker fee", "Worse fill (+1¢)"),
            note = NOTE
        )
    }
}
