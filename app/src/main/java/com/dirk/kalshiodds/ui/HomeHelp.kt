package com.dirk.kalshiodds.ui

/**
 * Long help copy that used to sit on the home screen. Settings and the
 * section (i) dialogs read the same strings so they cannot drift.
 */
object HomeHelp {
    const val TICKETS_TITLE = "Tickets"
    const val TICKETS_BODY =
        "LIVE $ = real V2 GTC ($5 all-in including fees). PAPER = simulated $100 book. " +
            "Paper fills never block Live. Paper trading ON does not swallow a keyed Live Approve. " +
            "Approve opens the REAL MONEY confirm. Cancel leaves no live order."

    const val POSITIONS_TITLE = "Positions"
    const val POSITIONS_BODY =
        "Live Kalshi holdings (GET /portfolio/positions). Sell opens an approve-gated V2 reduce-only limit."

    const val PAPER_TITLE = "Paper book"
    const val PAPER_BODY =
        "Start / reset $100 · win-target sizing · never hits Kalshi. " +
            "The switch enables paper auto-log. Live Approve is the only path that can place a real V2 order."

    const val SIGNALS_TITLE = "Signals"
    const val SIGNALS_BODY =
        "Recent live calls for this session. UP / DOWN / NO BET only — the long diagnostic stays behind Details."

    const val HOME_TITLE = "Home screen"
    const val HOME_BODY =
        "This window is the single best BetCall across the current BTC / ETH / SOL 15m windows. " +
            "Each card is that coin's current window, actionable first. Tap a card for the full-screen chart. " +
            "The primary button opens an approve-gated ticket — nothing is sent until you tap Approve. " +
            "NO BET cards keep Buy anyway for a manual ticket. The scorecard line opens hit rate. " +
            "$10 min profit and $5 all-in are Settings; home only displays them."
}
