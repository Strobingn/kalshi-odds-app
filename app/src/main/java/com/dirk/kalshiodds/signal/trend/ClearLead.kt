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
 *
 * Dirk's rule: watch the market; if Bitcoin is going up, go UP, and the same
 * for DOWN. Tested on every settled KXBTC15M window from 2026-08-24 to
 * 2026-10-08 (4,264 windows, Kalshi 1-minute quotes, Coinbase 1-minute
 * closes): betting the side Bitcoin is on wins often but the ask already
 * charges for it (minutes 3–12 pooled: 75.7% wins at 75.2¢, −0.6¢ per
 * contract after the fee). One slice of it came out ahead in both halves of
 * the data:
 *
 *  - Bitcoin is between [MIN_BP] and [MAX_BP] basis points (0.10–0.20%) from
 *    the window's start price,
 *  - between minute 3 and minute 10 of the window ([MAX_TTE_SECONDS] to
 *    [MIN_TTE_SECONDS] left),
 *  - buy the side Bitcoin is on at the ask, once per window (first time).
 *
 * First 23 days: 82.9% wins at 79.9¢, +2.0¢ per contract, 95% interval
 * [+0.0, +3.9]. Last 23 days: 84.1% at 82.2¢, +0.9¢, [−1.3, +2.8].
 * **A lead, not proof**: the slice was found after looking at the data and
 * the second half's interval includes zero. The paper ledger below is the
 * real test; [TARGET_SETTLED] settled windows are needed before it means
 * anything.
 *
 * Pure math. Never places an order: the live ticket still needs Approve and
 * is capped at $5 all-in like every other live buy.
 */
object ClearLeadRule {

    /** Minute 10 of the window: do not enter with less than this left. */
    const val MIN_TTE_SECONDS = 300L

    /** Minute 3 of the window: do not enter with more than this left. */
    const val MAX_TTE_SECONDS = 720L

    /** Distance of spot from the start price, in basis points: [MIN_BP, MAX_BP). */
    const val MIN_BP = 10.0
    const val MAX_BP = 20.0

    const val TARGET_SETTLED = 1000

    private const val EPS = 1e-9

    data class Signal(
        /** "YES" (UP) or "NO" (DOWN): the side Bitcoin is on. */
        val side: String,
        /** Signed distance from the start price in basis points (+ above, − below). */
        val distanceBp: Double,
        val tteSeconds: Long
    ) {
        /** `Bitcoin is 0.13% above the start price with 7:10 left` */
        val reason: String
            get() = String.format(
                Locale.US,
                "Bitcoin is %.2f%% %s the start price with %d:%02d left",
                abs(distanceBp) / 100.0,
                if (distanceBp >= 0.0) "above" else "below",
                tteSeconds / 60L,
                tteSeconds % 60L
            )
    }

    /** Signed distance of [spotUsd] from [strikeUsd] in basis points, or null on bad input. */
    fun distanceBp(spotUsd: Double?, strikeUsd: Double?): Double? {
        val s = spotUsd?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val k = strikeUsd?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        return (s / k - 1.0) * 10_000.0
    }

    fun inTimeBand(tteSeconds: Long?): Boolean =
        tteSeconds != null && tteSeconds in MIN_TTE_SECONDS..MAX_TTE_SECONDS

    /** The rule's call right now, or null when Bitcoin is not clearly ahead inside the time band. */
    fun signal(tteSeconds: Long?, spotUsd: Double?, strikeUsd: Double?): Signal? {
        val tte = tteSeconds?.takeIf { inTimeBand(it) } ?: return null
        val bp = distanceBp(spotUsd, strikeUsd) ?: return null
        val gap = abs(bp)
        if (gap + EPS < MIN_BP || gap >= MAX_BP - EPS) return null
        return Signal(side = if (bp > 0.0) "YES" else "NO", distanceBp = bp, tteSeconds = tte)
    }

    data class Inputs(
        val ticker: String,
        val nowMs: Long,
        val tteSeconds: Long?,
        val spotUsd: Double?,
        val strikeUsd: Double?,
        val yesAsk: Double?,
        val noAsk: Double?
    )

    /**
     * The paper bet the rule takes now ($5 all-in at the ask, exact taker
     * fee), or null. [alreadyEntered] enforces one entry per window.
     * `z` in the returned decision is the signed distance in basis points.
     */
    fun evaluate(inputs: Inputs, alreadyEntered: Boolean): LateFavoriteRule.Decision? {
        if (alreadyEntered) return null
        if (!CryptoMarkets.isLiveTicker(inputs.ticker)) return null
        val sig = signal(inputs.tteSeconds, inputs.spotUsd, inputs.strikeUsd) ?: return null
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
            z = signal.distanceBp,
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
        "Bets the side Bitcoin is on when it is 0.10–0.20% from the start price, between minute 3 and minute 10, " +
            "once per window, $5 at the ask. History (46 days): about +11¢ per $5 bet in the first half and +6¢ in " +
            "the second; the second half is not clear of zero. This card is the real test: every window is logged " +
            "here whether or not you approve the live ticket."

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
