package com.dirk.kalshiodds.ui

/**
 * Copy and visibility for the Settings API-key section and the home
 * no-key banner. One place so the banner and the status line cannot drift.
 */
object ApiKeyUi {
    const val HEADER = "Kalshi API key"
    const val READY = "Live trading ready"
    const val SAVED = "Key saved - tap Test connection"
    const val NONE = "No key - live orders disabled"
    const val BANNER = "Add your Kalshi API key to place real bets"

    /**
     * [hasKey] is Key ID + PEM stored ([com.dirk.kalshiodds.signal.config.SignalSettings.tradingCredentialsConfigured]).
     * [connectionOk] is true only after a successful Settings Test connection
     * (`GET /portfolio/balance`).
     */
    fun statusLine(hasKey: Boolean, connectionOk: Boolean): String = when {
        hasKey && connectionOk -> READY
        hasKey -> SAVED
        else -> NONE
    }

    /** Home banner: only when no tradable key is stored. */
    fun showNoKeyBanner(hasKey: Boolean): Boolean = !hasKey
}
