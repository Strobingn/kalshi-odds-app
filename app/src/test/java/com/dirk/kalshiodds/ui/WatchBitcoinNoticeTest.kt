package com.dirk.kalshiodds.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchBitcoinNoticeTest {
    @Test
    fun offSwitchWarnsAndTheEmptyBoardCanBeTurnedBackOn() {
        assertEquals(
            "Bitcoin is the only live market. With this off, the board will be empty.",
            WatchBitcoinNotice.SETTINGS_WARNING
        )
        assertTrue(WatchBitcoinNotice.HOME_EMPTY.contains("board is empty"))
        assertTrue(WatchBitcoinNotice.TURN_ON.contains("Watch Bitcoin"))
        assertTrue(WatchBitcoinNotice.boardHidden(watchBtc = false, visibleMarkets = 0))
        assertFalse(WatchBitcoinNotice.boardHidden(watchBtc = true, visibleMarkets = 0))
        assertTrue(WatchBitcoinNotice.boardHidden(watchBtc = false, visibleMarkets = 1))
    }
}
