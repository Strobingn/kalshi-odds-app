package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.domain.KalshiPrice
import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.domain.TimeLeft
import com.dirk.kalshiodds.signal.latefav.LateFavoriteTotals
import com.dirk.kalshiodds.signal.trade.BetCall
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What the big "AI SAYS UP / DOWN / WAIT" banner at the top of Home shows
 * for this window. The call is [BetCall]'s (the phone's rules, Kalshi data
 * only): late comeback first, then clear lead. Pure, unit tested.
 */
data class AiBanner(
    val headline: BetCall.Headline,
    /** "AI SAYS UP", "AI SAYS DOWN" or "AI SAYS WAIT". */
    val title: String,
    /** "BTC · 12:30 PM window". */
    val window: String,
    /** Which rule made the call ("Late comeback"), or "No rule is on yet". */
    val rule: String,
    /** Why, in plain words. */
    val reason: String,
    val figures: List<Figure>,
    /** One line under the figures: the rule's history and the Approve reminder. */
    val footer: String,
    /** The side to review a ticket on, when the call is a bet. */
    val side: String?
) {
    data class Figure(val label: String, val value: String)

    val isBet: Boolean get() = headline != BetCall.Headline.NO_BET

    companion object {
        const val UP = "AI SAYS UP"
        const val DOWN = "AI SAYS DOWN"
        const val WAIT = "AI SAYS WAIT"
        const val COMEBACK = "Late comeback"
        const val CLEAR_LEAD = "Clear lead"
        const val COMEBACK_HISTORY = "Tested on 12 recorded days: +71¢ a \$5 bet, 34% won."
        const val CLEAR_LEAD_HISTORY = "Tested on 12 recorded days: −12¢ a \$5 bet. Your rule, kept on."
        const val WATCHING =
            "Watching for a late comeback (minutes 10–13, an underdog at 20–40¢ up 4¢ in 30 s) " +
                "and a clear lead (80–90¢, minutes 3–10)."

        fun of(
            decision: BetCall.Decision?,
            market: MarketUiModel?,
            nowMs: Long,
            comebackRecord: LateFavoriteTotals? = null,
            clearLeadRecord: LateFavoriteTotals? = null
        ): AiBanner {
            val window = market?.let { WindowLabel.of(it.ticker, it.closeTimeEpochMs) } ?: "Bitcoin · 15m window"
            val closes = market?.let { TimeLeft.format(it.closeTimeEpochMs, nowMs) } ?: "—"
            val bitcoin = market?.let { bitcoinLine(it) }
            if (decision == null || market == null || !decision.isActionable) {
                val figures = buildList {
                    market?.let { m -> leanLine(m)?.let { add(Figure("Kalshi leans", it)) } }
                    add(Figure("Time left", closes))
                    bitcoin?.let { add(Figure("Bitcoin", it)) }
                    comebackRecord?.let { add(Figure("$COMEBACK record", record(it))) }
                }
                val why = decision?.noBetReason?.takeIf { it.isNotBlank() && it != BetCall.NO_RULE }
                return AiBanner(
                    headline = BetCall.Headline.NO_BET,
                    title = WAIT,
                    window = window,
                    rule = "No rule is on yet",
                    reason = why ?: WATCHING,
                    figures = figures,
                    footer = "The banner turns UP or DOWN the moment a rule fires. Live bets still need your Approve.",
                    side = null
                )
            }
            val ticket = decision.ticket
            val comeback = ticket?.comeback == true
            val rule = if (comeback) COMEBACK else CLEAR_LEAD
            // The ticket note reads "<rule> · <why> · $5 all-in cap … · Approve still required".
            val reason = ticket?.gateNote?.split(" · ")?.getOrNull(1)?.replaceFirstChar { it.uppercase(Locale.US) }
                ?: (if (comeback) "The late underdog is climbing" else "Kalshi prices this side as the clear favourite")
            val record = if (comeback) comebackRecord else clearLeadRecord
            val figures = buildList {
                decision.ask?.let { add(Figure("Buy at", cents(it))) }
                add(Figure("\$5 buys", "${decision.contracts} contracts"))
                decision.profitIfWinUsd?.let { add(Figure("If it wins", String.format(Locale.US, "+$%.2f", it))) }
                add(Figure("Time left", closes))
                bitcoin?.let { add(Figure("Bitcoin", it)) }
                record?.let { add(Figure("$rule record", record(it))) }
            }
            return AiBanner(
                headline = decision.headline,
                title = if (decision.headline == BetCall.Headline.BET_DOWN) DOWN else UP,
                window = window,
                rule = rule,
                reason = reason,
                figures = figures,
                footer = (if (comeback) COMEBACK_HISTORY else CLEAR_LEAD_HISTORY) + " Approve still required.",
                side = decision.side
            )
        }

        fun cents(price: Double): String = "${(price * 100).roundToInt()}¢"

        /** "UP 63%" from Kalshi's own YES mid, or null without a two-sided quote. */
        fun leanLine(m: MarketUiModel): String? {
            val bid = KalshiPrice.usable(m.yesBid)
            val ask = KalshiPrice.usable(m.yesAsk)
            val mid = when {
                bid != null && ask != null && bid <= ask -> (bid + ask) / 2.0
                else -> m.yesProbabilityPercent?.div(100.0)
            } ?: return null
            val pct = (mid * 100).roundToInt()
            return if (pct >= 50) "UP $pct%" else "DOWN ${100 - pct}%"
        }

        /** "$67,512 · $41 above" against the window's strike, when both are known. */
        fun bitcoinLine(m: MarketUiModel): String? {
            val spot = m.spotUsd?.takeIf { it.isFinite() && it > 0.0 } ?: return null
            val strike = m.floorStrike?.takeIf { it.isFinite() && it > 0.0 }
            val price = String.format(Locale.US, "$%,.0f", spot)
            if (strike == null) return price
            val d = spot - strike
            val side = if (d >= 0) "above" else "below"
            return String.format(Locale.US, "%s · $%,.0f %s", price, abs(d), side)
        }

        /** "12 won · 20 lost · +$8.40", or "no bets settled yet". */
        fun record(t: LateFavoriteTotals): String =
            if (t.settledBets == 0) {
                "no bets settled yet"
            } else {
                String.format(Locale.US, "%d won · %d lost · %s", t.wins, t.losses, money(t.pnlUsd))
            }

        private fun money(v: Double): String = (if (v < 0) "−" else "+") + String.format(Locale.US, "$%.2f", abs(v))
    }
}
