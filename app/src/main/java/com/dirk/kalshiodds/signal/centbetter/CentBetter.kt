package com.dirk.kalshiodds.signal.centbetter

import android.content.Context
import com.dirk.kalshiodds.domain.CryptoMarkets
import com.dirk.kalshiodds.domain.KalshiPrice
import com.dirk.kalshiodds.signal.flowfade.FlowFadeRule
import com.dirk.kalshiodds.signal.flowfade.FlowFadeSummary
import com.dirk.kalshiodds.signal.latefav.LateFavoriteLedger
import com.dirk.kalshiodds.signal.latefav.LateFavoriteState
import kotlinx.serialization.json.Json

/**
 * "One cent better" resting bid. **PAPER ONLY.** Nothing here places an order.
 *
 * The `improve` rule from tools/research/maker_sim.py, the only maker
 * config still positive on the cloud recordings (2026-10-05: 17 fills,
 * +$41.73 over ~2.5 days; far from proven). Same numbers here so the phone
 * and the cloud simulation can be compared:
 * - time left between [MIN_TTE_SECONDS] and [MAX_TTE_SECONDS] (2:00–12:00 elapsed)
 * - fair = the digital option fair for YES (Φ(d2) from spot vs strike)
 * - on each side, price = best bid + 1¢, only if still below that side's ask
 * - pick the side with the larger fair − price; post only if it exceeds [MARGIN]
 * - nobody is ahead of us at the new price (queue 0): any later print at our
 *   price, or through it, fills us; cancel after [CANCEL_MS]
 * - one fill per market, $5 all-in at no maker fee (stress line at 1.75%),
 *   held to settlement
 */
object CentBetterRule {
    const val MIN_TTE_SECONDS = 180L
    const val MAX_TTE_SECONDS = 780L
    const val CANCEL_MS = 30_000L
    const val MARGIN = 0.02
    const val TICK = 0.01
    const val MIN_PRICE = 0.05
    const val MAX_PRICE = 0.95
    private const val EPS = 1e-9

    data class Inputs(
        val ticker: String,
        val nowMs: Long,
        val tteSeconds: Long?,
        /** Digital-option probability that YES wins, 0..1. */
        val fairYes: Double?,
        val yesBid: Double?,
        val yesAsk: Double?,
        val noBid: Double?,
        val noAsk: Double?
    )

    /** The resting paper bid to post now, or null. [FlowFadeRule.Order.imbalance] carries the edge. */
    fun order(inputs: Inputs, alreadyEntered: Boolean): FlowFadeRule.Order? {
        if (alreadyEntered) return null
        if (!CryptoMarkets.isLiveTicker(inputs.ticker)) return null
        val tte = inputs.tteSeconds ?: return null
        if (tte < MIN_TTE_SECONDS || tte > MAX_TTE_SECONDS) return null
        val fair = inputs.fairYes?.takeIf { it.isFinite() && it in 0.0..1.0 } ?: return null
        val yesAsk = KalshiPrice.usable(inputs.yesAsk) ?: KalshiPrice.usable(inputs.noBid)?.let { 1.0 - it }
        val noAsk = KalshiPrice.usable(inputs.noAsk) ?: KalshiPrice.usable(inputs.yesBid)?.let { 1.0 - it }
        val best = listOfNotNull(
            quote("YES", inputs.yesBid, yesAsk, fair),
            quote("NO", inputs.noBid, noAsk, 1.0 - fair)
        ).maxByOrNull { it.second } ?: return null
        val (side, edge, price) = best
        if (edge <= MARGIN + EPS) return null
        if (FlowFadeRule.sizeResting(price) == null) return null
        return FlowFadeRule.Order(
            ticker = inputs.ticker,
            side = side,
            price = price,
            queueAhead = 0.0,
            postedAtMs = inputs.nowMs,
            tteSeconds = tte,
            imbalance = edge
        )
    }

    /** (side, edge, price) for a bid 1¢ above [bid] on one side, or null. */
    private fun quote(side: String, bid: Double?, ask: Double?, fairSide: Double): Triple<String, Double, Double>? {
        val b = KalshiPrice.usable(bid) ?: return null
        val a = ask ?: return null
        val p = Math.round((b + TICK) * 100.0) / 100.0
        if (p >= a - EPS || p < MIN_PRICE - EPS || p > MAX_PRICE + EPS) return null
        return Triple(side, fairSide - p, p)
    }
}

/** On-device ledger for the cent-better tracker, same entry / settle logic as the others. */
class CentBetterStore(context: Context) {
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
        const val PREFS = "bitcoin_claude_cent_better"
        const val KEY = "state_json"
    }
}

/** Home-card copy. Same lines as the flow-fade card, its own title, target and note. */
object CentBetterSummary {
    const val TITLE = "1¢ better bid · PAPER"
    const val TARGET_SETTLED = 300
    const val NOTE =
        "Paper only. Rests a no-fee bid 1¢ above the best bid on the side the spot model favors by more than 2¢, " +
            "between 12:00 and 3:00 left, and counts a fill when a later trade prints at that price. " +
            "Cloud recordings so far: 17 fills, about +$2 per fill, not proven. Needs ~300 fills and Live signals on."

    fun of(state: LateFavoriteState): FlowFadeSummary {
        val base = FlowFadeSummary.of(state)
        return base.copy(
            title = TITLE,
            recordLine = base.recordLine.replace(
                "/ ${FlowFadeSummary.TARGET_SETTLED} settled",
                "/ $TARGET_SETTLED settled"
            ),
            note = NOTE
        )
    }
}
