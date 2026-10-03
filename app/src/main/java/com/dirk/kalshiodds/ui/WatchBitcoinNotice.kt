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

    fun boardHidden(watchBtc: Boolean, visibleMarkets: Int): Boolean =
        !watchBtc && visibleMarkets <= 0
}
