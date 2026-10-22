package com.dirk.kalshiodds.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.dirk.kalshiodds.signal.scalper.AiChat
import com.dirk.kalshiodds.signal.scalper.AiEvent
import com.dirk.kalshiodds.signal.scalper.ChatMsg
import com.dirk.kalshiodds.signal.scalper.PaperBet
import com.dirk.kalshiodds.ui.theme.DipTheme
import com.dirk.kalshiodds.ui.theme.KalshiOddsTheme
import org.junit.Rule
import org.junit.Test
import java.util.TimeZone

/** JVM screenshots of the AI tab with made-up data: dashboard charts, both chat threads, keys. */
class AiScreenScreenshotTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6.copy(
            softButtons = false, screenWidth = 411, screenHeight = 3900, xdpi = 160, ydpi = 160,
            density = com.android.resources.Density.MEDIUM
        ),
        theme = "android:Theme.Material3.DayNight.NoActionBar",
        maxPercentDifference = 1.0
    )

    @Test
    fun dashboard() {
        val s = AiScreenFixtures.dashboard()
        paparazzi.snapshot(name = "ai_dashboard") {
            KalshiOddsTheme(darkTheme = true) {
                Column(Modifier.background(DipTheme.colors.bg)) {
                    AiSectionTabs(0) {}
                    Column(Modifier.padding(16.dp)) { AiDashboardContent(s) }
                }
            }
        }
    }

    @Test
    fun dashboardWithNoKeys() {
        paparazzi.snapshot(name = "ai_dashboard_empty") {
            KalshiOddsTheme(darkTheme = true) {
                Column(Modifier.background(DipTheme.colors.bg).padding(16.dp)) {
                    AiDashboardContent(AiDashboard.build(AiDashboardState(version = "Claude Bitcoin v1.16.0 (69)"), AiScreenFixtures.NOW, emptyList(), emptyList(), emptyList()))
                }
            }
        }
    }

    @Test
    fun chat() {
        val msgs = listOf(
            ChatMsg(1, AiChat.SCALPING, "user", "Which scalpers are winning?", 0),
            ChatMsg(2, AiChat.SCALPING, "ai", "[groq openai/gpt-oss-120b] Late edge and Kashi fav base carry the arena: +\$212 and +\$148 net. Improve 1c is bleeding fees; I'd cut its size and widen its target.", 0),
            ChatMsg(3, AiChat.SCALPING, "proposal", "Change IMPROVE_1C targetCents to 6.0?", 0, "IMPROVE_1C", "targetCents", 6.0, "pending"),
            ChatMsg(4, AiChat.SCALPING, "proposal", "Change IMPROVE_1C contracts to 20.0?", 0, "IMPROVE_1C", "contracts", 20.0, "applied"),
            ChatMsg(5, AiChat.SCALPING, "change", "Applied: IMPROVE_1C contracts default → 20.0", 0)
        )
        paparazzi.snapshot(name = "ai_chat") {
            KalshiOddsTheme(darkTheme = true) {
                Box(Modifier.background(DipTheme.colors.bg).height(900.dp)) {
                    AiChatContent(AiChat.SCALPING, msgs, busy = true, onWhich = {}, onSend = {}, onApply = {}, onCancel = {}, onClear = {})
                }
            }
        }
    }

    @Test
    fun keys() {
        val k = AiKeysState(
            groqDraft = "", saved = listOf("Groq" to true, "Mistral" to false, "Gemini" to true),
            testLines = listOf(
                "groq: OK · openai/gpt-oss-120b · 1612 ms (tool call works)",
                "mistral: no key saved",
                "gemini: FAILED · gemini gemini-2.5-flash: HTTP 429 Resource has been exhausted"
            )
        )
        paparazzi.snapshot(name = "ai_keys") {
            KalshiOddsTheme(darkTheme = true) {
                Column(Modifier.background(DipTheme.colors.bg).fillMaxSize().padding(16.dp)) {
                    AiSectionTabs(2) {}
                    AiKeysContent(k, { _, _ -> }, {}, {}, {}, {}, {}, {})
                }
            }
        }
    }
}

object AiScreenFixtures {
    const val NOW = 1_792_000_000_000L
    private const val H = 3_600_000L

    fun dashboard(): AiDashboardState {
        val ev = ArrayList<AiEvent>()
        val rnd = java.util.Random(7)
        for (m in 0 until 24 * 60 step 3) {
            val t = NOW - 24 * H + m * 60_000L
            val fail = rnd.nextInt(14) == 0
            ev += AiEvent(t, AiEvent.SCALPING, AiEvent.CALL, if (fail) "groq" else "groq", !fail, 900L + rnd.nextInt(900), if (fail) "openai/gpt-oss-120b | HTTP 429 Rate limit reached" else "openai/gpt-oss-120b | ")
            if (m % 6 == 0) ev += AiEvent(t + 5_000, AiEvent.PAPER, AiEvent.CALL, "gemini", true, 1400L + rnd.nextInt(600), "gemini-2.5-flash | ")
            if (rnd.nextInt(3) == 0) ev += AiEvent(t + 9_000, AiEvent.SCALPING, AiEvent.APPLIED, detail = listOf("LATE_EDGE edgeCents 3 → 5", "IMPROVE_1C targetCents 2 → 6", "KASHI_FAV_BASE secondsLeftMax 240 → 300", "LATE_COMEBACK contracts 50 → 70")[rnd.nextInt(4)])
            if (rnd.nextInt(9) == 0) ev += AiEvent(t + 9_500, AiEvent.SCALPING, AiEvent.REJECTED, detail = "LONG_SHOT entryMinCents: below 10")
            if (rnd.nextInt(5) == 0) ev += AiEvent(t + 10_000, AiEvent.SCALPING, if (rnd.nextInt(3) == 0) AiEvent.SCORED_WORSE else AiEvent.SCORED_BETTER, detail = "LATE_EDGE edgeCents → 5")
        }
        for (i in 0 until 6) ev += AiEvent(NOW - 2 * H + i * 60_000L, AiEvent.CHAT, AiEvent.CALL, "mistral", i != 2, 2100, "mistral-large-latest | ${if (i == 2) "HTTP 401 Unauthorized" else ""}")
        val strategies = listOf(
            StrategyResult("LATE_EDGE", 140, 98, 212.4), StrategyResult("KASHI_FAV_BASE", 88, 79, 148.0),
            StrategyResult("LATE_COMEBACK", 61, 33, 96.2), StrategyResult("KASHI_FAV_97", 40, 38, 31.5),
            StrategyResult("KASHI_FAV_NIGHT", 22, 19, 12.1), StrategyResult("FLOW_FADE", 300, 160, 4.4),
            StrategyResult("IMPROVE_1C", 420, 190, -86.3), StrategyResult("OPENING_RIDE", 77, 31, -41.0),
            StrategyResult("RANGE_FLOOR", 150, 70, -22.8), StrategyResult("LONG_SHOT", 30, 4, -18.2),
            StrategyResult("QUIET_MAKER", 64, 30, -6.5)
        )
        var pnl = 0.0
        val bets = (0 until 40).map { i ->
            val won = rnd.nextInt(10) < 6
            val price = 0.40 + rnd.nextInt(30) / 100.0
            val c = 10 + rnd.nextInt(40)
            val p = if (won) c * (1 - price) - 0.5 else -c * price - 0.5
            pnl += p
            PaperBet(i.toLong(), "KXBTC15M-T$i", if (rnd.nextBoolean()) "YES" else "NO", price, c, 0.5, NOW - (40 - i) * 20 * 60_000L, 55 + rnd.nextInt(30), "edge", if (i < 38) "YES" else null, if (i < 38) p else null)
        }
        val base = AiDashboardState(
            version = "Claude Bitcoin v1.16.0 (69)",
            keys = listOf("Groq" to true, "Mistral" to true, "Gemini" to true),
            scalpingStatus = "Scalping AI · tick 412: 3 changes applied",
            paperStatus = "Paper AI · bet YES x30 on KXBTC15M-26OCT221245-45 at 54c (68%): spot pulling away above strike"
        )
        return AiDashboard.build(base, NOW, ev, strategies, bets, TimeZone.getTimeZone("America/New_York"))
    }
}
