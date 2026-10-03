package com.dirk.kalshiodds.signal.config

import com.dirk.kalshiodds.data.api.KalshiApi
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchBtcForcedTest {
    @Test
    fun storedWatchBtcFalseCannotTurnBitcoinOff() {
        assertTrue(SignalPreferences.withBitcoinForcedOn(false))
        assertTrue(SignalPreferences.withBitcoinForcedOn(true))
        val settings = SignalSettings(watchBtc = false)
        assertTrue(KalshiApi.SERIES_BTC in settings.watchedSeries)
    }
}
