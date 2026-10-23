package com.dirk.kalshiodds

import android.os.Looper
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The real Application and MainActivity start, and the scalper takes trades with the fleet wired in. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AppStartSmokeTest {

    @Test
    fun appAndMainActivityStart() {
        val app = ApplicationProvider.getApplicationContext<KalshiOddsApp>()
        assertNotNull(app.container)
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            repeat(20) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(250)) }
            sc.onActivity { assertNotNull(it) }
        }
    }

    @Test
    fun scalperTakesTradesWithFleet() {
        val app = ApplicationProvider.getApplicationContext<KalshiOddsApp>()
        val now = System.currentTimeMillis()
        val close = (now / 900_000L + 1) * 900_000L
        val fmt = java.text.SimpleDateFormat("yyMMMddHHmm", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("America/New_York")
        }
        val ticker = "KXBTC15M-" + fmt.format(java.util.Date(close)).uppercase() + "-15"
        for (i in 0 until 200) {
            app.container.scalper.onTrade(ticker, if (i % 2 == 0) "yes" else "no", 0.40 + (i % 20) / 100.0, 10.0, now + i * 1000L)
        }
        assertNotNull(app.container.fleetHub)
    }
}
