package com.dirk.kalshiodds.ui

/**
 * Bitcoin is the only live market. The Settings switch stays so it can be
 * turned back on, and the home board explains an empty list instead of
 * looking blank.
 */
object WatchBitcoinNotice {
    const val SETTINGS_WARNING =
        "Bitcoin is the only live market. With this off, the board will be empty."

    const val HOME_EMPTY =
        "Bitcoin is the only live market. With this off, the board is empty."

    const val TURN_ON = "Turn Watch Bitcoin on"

    /** The switch hides the board even when a cached snapshot still has markets. */
    @Suppress("UNUSED_PARAMETER")
    fun boardHidden(watchBtc: Boolean, visibleMarkets: Int = 0): Boolean = !watchBtc
}
