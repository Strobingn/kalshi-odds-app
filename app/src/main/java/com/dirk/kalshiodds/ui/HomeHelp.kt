package com.dirk.kalshiodds.ui

/**
 * Long help copy that used to sit on the home screen. Settings and the
 * section (i) dialogs read the same strings so they cannot drift.
 */
object HomeHelp {
    const val TICKETS_TITLE = "Tickets"
    const val TICKETS_BODY =
        "Tickets wait for your Approve. Nothing is sent until you confirm."

    const val POSITIONS_TITLE = "Positions"
    const val POSITIONS_BODY =
        "Open Kalshi positions. Sell closes them at the current bid."

    const val PAPER_TITLE = "Paper book"
    const val PAPER_BODY =
        "Start / reset $100 · win-target sizing · never hits Kalshi. " +
            "Paper UP / Paper DOWN on the Bitcoin card log a $10 paper bet at the live ask. " +
            "The switch enables paper auto-log. Live Approve is the only path that can place a real V2 order."

    const val SIGNALS_TITLE = "Signals"
    const val SIGNALS_BODY =
        "Recent live calls for this session. UP / DOWN / NO BET only — the long diagnostic stays behind Details."

    const val HOME_TITLE = "Home screen"
    const val HOME_BODY =
        "This window is the BetCall for the current Bitcoin 15m window. " +
            "Home shows one Bitcoin card. " +
            "Home has no Signals list — Signal history is a text link. " +
            "A compact scorecard line (W-L, win rate, paper P&L) sits under This window and opens the full scorecard. " +
            "Tap a card for the full-screen chart. The primary button opens an approve-gated ticket — " +
            "nothing is sent until you tap Approve. NO BET cards keep Buy anyway for a manual ticket. " +
            "Paper UP and Paper DOWN are always on the card in LIVE \$ and paper mode — they never send a real order. " +
            "$10 min profit and $5 all-in are Settings; home only displays them."
}
