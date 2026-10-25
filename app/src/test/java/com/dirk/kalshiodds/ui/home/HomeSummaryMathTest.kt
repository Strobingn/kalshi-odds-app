package com.dirk.kalshiodds.ui.home

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dirk.kalshiodds.data.local.history.SettingsChange
import com.dirk.kalshiodds.decision.InMemoryScalpPersistence
import com.dirk.kalshiodds.decision.ScalpDaily
import com.dirk.kalshiodds.decision.ScalpPersistence
import com.dirk.kalshiodds.decision.ScalpStat
import com.dirk.kalshiodds.decision.ScalpState
import com.dirk.kalshiodds.decision.ScalpTrade
import com.dirk.kalshiodds.ui.ScalpTabCopy
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

class HomeSummaryMathTest {
    private val et: ZoneId = HomeSummarySchedule.ET

    @Test fun last24hRanksByNetAfterFeesAndDropsOlderCloses() {
        val now = 5_000_000_000L
        val closes = listOf(
            HomeScalpClose("highFee", now - 3_600_000L, netUsd = 1.0, feesUsd = 10.0),
            HomeScalpClose("lowFee", now - 3_600_000L, netUsd = 2.0, feesUsd = 0.01),
            HomeScalpClose("lowFee", now - 1_000L, netUsd = -0.25, feesUsd = 0.20),
            HomeScalpClose("stale", now - HomeScalpRank.DAY_MS - 1L, netUsd = 100.0, feesUsd = 0.0),
            HomeScalpClose("future", now + 1L, netUsd = 80.0)
        )
        val best = HomeScalpRank.best(closes, now, HomeScalpRank.DAY_MS)!!
        assertEquals("lowFee", best.scalperId)
        assertEquals(1.75, best.netUsd, 1e-9)
        assertEquals(1, best.wins)
        assertEquals(2, best.trades)
        assertEquals(0.5, best.winRate, 1e-9)
        val week = HomeScalpRank.best(
            closes + HomeScalpClose("stale", now - 2 * HomeScalpRank.DAY_MS, netUsd = 4.0),
            now,
            HomeScalpRank.WEEK_MS
        )!!
        assertEquals("stale", week.scalperId)
        assertEquals(104.0, week.netUsd, 1e-9)
    }

    @Test fun sevenDayWindowIsInclusiveAtTheBoundaryAndSeparateFrom24h() {
        val now = 10 * HomeScalpRank.WEEK_MS
        val closes = listOf(
            HomeScalpClose("edge", now - HomeScalpRank.WEEK_MS, netUsd = 3.0),
            HomeScalpClose("out", now - HomeScalpRank.WEEK_MS - 1L, netUsd = 50.0),
            HomeScalpClose("recent", now - HomeScalpRank.DAY_MS, netUsd = 1.5),
            HomeScalpClose("recent", now - 1_000L, netUsd = -0.5)
        )
        val day = HomeScalpRank.best(closes, now, HomeScalpRank.DAY_MS)!!
        assertEquals("recent", day.scalperId)
        assertEquals(1.0, day.netUsd, 1e-9)
        assertEquals(1, day.wins)
        assertEquals(2, day.trades)
        val week = HomeScalpRank.best(closes, now, HomeScalpRank.WEEK_MS)!!
        assertEquals("edge", week.scalperId)
        assertEquals(3.0, week.netUsd, 1e-9)
        assertEquals(1, week.trades)
        assertNull(HomeScalpRank.best(emptyList(), now, HomeScalpRank.DAY_MS))
        assertNull(HomeScalpRank.best(listOf(HomeScalpClose("nan", now - 1, Double.NaN)), now, HomeScalpRank.DAY_MS))
    }

    @Test fun tiesBreakByWinRateThenTradeCountThenId() {
        val now = 9_000_000L
        val closes = listOf(
            HomeScalpClose("m2", now - 1_000, netUsd = 2.0),
            HomeScalpClose("m2", now - 2_000, netUsd = -2.0),
            HomeScalpClose("m1", now - 1_000, netUsd = 0.0),
            HomeScalpClose("m3", now - 1_000, netUsd = 1.0),
            HomeScalpClose("m3", now - 2_000, netUsd = -1.0),
            HomeScalpClose("m3", now - 3_000, netUsd = 0.0),
            HomeScalpClose("aa", now - 1_000, netUsd = 5.0),
            HomeScalpClose("bb", now - 1_000, netUsd = 5.0)
        )
        assertEquals("m2", HomeScalpRank.best(closes.filter { it.scalperId.startsWith("m") }, now, HomeScalpRank.DAY_MS)!!.scalperId)
        assertEquals("bb", HomeScalpRank.best(closes.filter { it.scalperId == "aa" || it.scalperId == "bb" }, now, HomeScalpRank.DAY_MS)!!.scalperId)
        val worst = HomeScalpRank.pickWorst(HomeScalpRank.ranks(closes.filter { it.scalperId == "aa" || it.scalperId == "bb" }))
        assertEquals("aa", worst!!.scalperId)
    }

    @Test fun leastNegativeNetStillWinsTheWindow() {
        val now = 4_000_000L
        val closes = listOf(
            HomeScalpClose("a", now - 10, netUsd = -1.0),
            HomeScalpClose("b", now - 10, netUsd = -5.0)
        )
        assertEquals("a", HomeScalpRank.best(closes, now, HomeScalpRank.DAY_MS)!!.scalperId)
    }

    @Test fun zeroNetIsNotAWinAndCopyUsesThatRate() {
        val flat = HomeScalpRank.ranks(listOf(HomeScalpClose("flat", 1L, 0.0), HomeScalpClose("flat", 2L, 1.0)))
        assertEquals(1, flat.single().wins)
        assertEquals(2, flat.single().trades)
        val rank = RankedScalper("sf41", "sf41", netUsd = 1.0, wins = 2, trades = 3)
        assertEquals("67%", ScalpTabCopy.pct(rank.winRate))
        val line = HomeSummaryCopy.windowLine(rank)
        assertTrue(line.contains("sf41"))
        assertTrue(line.contains("67%"))
        assertTrue(line.contains("3 trades"))
        assertEquals("1 trade", HomeSummaryCopy.tradeCount(1))
        assertEquals(HomeSummaryCopy.EMPTY_WINDOW, HomeSummaryCopy.windowLine(null))
        assertEquals("sf41", HomeSummaryCopy.displayName("sf41"))
        assertEquals("sf16f", HomeSummaryCopy.displayName("sf16"))
        assertEquals("s05 Student-t tail", HomeSummaryCopy.displayName("s05-student-tail"))
        val archived = HomeSummaryCopy.displayName("sf01")
        assertTrue(archived.contains("sf01"))
        assertFalse(archived.contains("removed"))
        assertEquals("not-a-model", HomeSummaryCopy.displayName("not-a-model"))
        assertEquals("unknown strategy", HomeSummaryCopy.displayName("  "))
        assertFalse(HomeSummaryCopy.displayName("s05-student-tail").contains("removed"))
    }

    @Test fun todayEtSumsNetBestWorstAndSettingsOnThatDateOnly() {
        val morning = etTime(2026, 10, 18, 9, 0)
        val noon = etTime(2026, 10, 18, 12, 0)
        val lateUtcThatIsStillToday = ZonedDateTime.of(2026, 10, 19, 0, 30, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        val nextEtDay = etTime(2026, 10, 19, 0, 30)
        val yesterday = etTime(2026, 10, 17, 22, 0)
        val closes = listOf(
            HomeScalpClose("best", morning, netUsd = 5.0, feesUsd = 0.4),
            HomeScalpClose("best", noon, netUsd = -1.0, feesUsd = 0.1),
            HomeScalpClose("worst", noon, netUsd = -3.0, feesUsd = 0.2),
            HomeScalpClose("best", lateUtcThatIsStillToday, netUsd = 0.5),
            HomeScalpClose("best", yesterday, netUsd = 100.0),
            HomeScalpClose("best", nextEtDay, netUsd = 80.0)
        )
        val settings = listOf(
            SettingsChange(createdAtMs = noon, key = "greenContracts", oldValue = "10", newValue = "20"),
            SettingsChange(createdAtMs = yesterday, key = "ignored", oldValue = "a", newValue = "b"),
            SettingsChange(createdAtMs = noon, key = "note", oldValue = "x".repeat(60), newValue = "y")
        )
        val day = HomeDailyAggregate.fromCloses(closes, "2026-10-18", settings, { com.dirk.kalshiodds.decision.ScalpModels.dayEt(it) })
        assertEquals(1.5, day.netUsd, 1e-9)
        assertEquals(4, day.trades)
        assertEquals(2, day.wins)
        assertEquals("best", day.best!!.scalperId)
        assertEquals(4.5, day.best!!.netUsd, 1e-9)
        assertEquals(2, day.best!!.wins)
        assertEquals(3, day.best!!.trades)
        assertEquals("worst", day.worst!!.scalperId)
        assertEquals(2, day.settings.size)
        assertEquals("2 settings changes", HomeSummaryCopy.settingsHeadline(day))
        assertTrue(HomeSummaryCopy.settingsLine(settings[2]).contains("…"))
        assertFalse(HomeSummaryCopy.settingsLine(settings[2]).contains("x".repeat(60)))
        assertTrue(HomeSummaryCopy.notificationBody(day).contains("Today ET paper"))
        assertTrue(HomeSummaryCopy.notificationBody(day).contains("Best best"))
        assertTrue(HomeSummaryCopy.notificationBody(day).contains("Worst worst"))

        val only = HomeDailyAggregate.fromCloses(
            listOf(HomeScalpClose("solo", noon, netUsd = -2.0)),
            "2026-10-18",
            emptyList(),
            { com.dirk.kalshiodds.decision.ScalpModels.dayEt(it) }
        )
        assertEquals("solo", only.best!!.scalperId)
        assertEquals("solo", only.worst!!.scalperId)
        assertEquals("No settings changes", HomeSummaryCopy.settingsHeadline(only))

        val untracked = HomeDailyAggregate.fromCloses(closes, "2026-10-18", settings, { "2026-10-18" }, settingsTracked = false)
        assertFalse(untracked.settingsTracked)
        assertTrue(untracked.settings.isEmpty())
        assertEquals("Settings changes not tracked", HomeSummaryCopy.settingsHeadline(untracked))
    }

    @Test fun dailyLedgerRowsSumTheRequestedEtDay() {
        val rows = listOf(
            ScalpDaily("sf41", "2026-10-18", n = 4, wins = 3, netUsd = 2.5, feesUsd = 0.4),
            ScalpDaily("sf42", "2026-10-18", n = 2, wins = 0, netUsd = -1.0, feesUsd = 0.2),
            ScalpDaily("sf41", "2026-10-17", n = 9, wins = 9, netUsd = 50.0, feesUsd = 1.0),
            ScalpDaily("sf43", "2026-10-18", n = 0, wins = 0, netUsd = 0.0, feesUsd = 0.0)
        )
        val day = HomeDailyAggregate.fromDaily(rows, "2026-10-18", emptyList(), { "2026-10-18" })
        assertEquals(1.5, day.netUsd, 1e-9)
        assertEquals(6, day.trades)
        assertEquals(3, day.wins)
        assertEquals("sf41", day.best!!.scalperId)
        assertEquals("sf42", day.worst!!.scalperId)
        assertEquals(0.5, day.winRate!!, 1e-9)
    }

    @Test fun loaderRanksFromClosesAndPrefersTheDailyLedger() {
        val now = etTime(2026, 10, 18, 15, 0)
        val backing = InMemoryScalpPersistence()
        backing.upsertScalpAndStats(closed("t-recent", "sf42", now - 60_000L, net = 9.0), null)
        backing.upsertScalpAndStats(closed("t-old", "sf41", now - 3 * HomeScalpRank.DAY_MS, net = 4.0), null)
        backing.upsertScalpAndStats(closed("t-shadow", "sf42", now - 60_000L, net = 1_000.0, shadow = true), null)
        backing.upsertScalpAndStats(closed("t-open", "sf42", now - 60_000L, net = 500.0, state = ScalpState.OPEN), null)
        val store = object : ScalpPersistence by backing {
            override fun loadDaily(dayEt: String): List<ScalpDaily> =
                if (dayEt == "2026-10-18") listOf(
                    ScalpDaily("sf41", dayEt, n = 4, wins = 3, netUsd = 2.5, feesUsd = 0.4),
                    ScalpDaily("sf42", dayEt, n = 1, wins = 0, netUsd = -0.5, feesUsd = 0.1)
                ) else emptyList()
        }
        val snap = HomeSummaryLoader.load(
            store,
            listOf(SettingsChange(createdAtMs = now - 500, key = "clip", oldValue = "1", newValue = "2")),
            now
        )
        assertEquals("sf42", snap.best24!!.scalperId)
        assertEquals(9.0, snap.best24!!.netUsd, 1e-9)
        assertEquals(1, snap.best24!!.trades)
        assertEquals(1, snap.best24!!.wins)
        assertEquals("sf42", snap.best7!!.scalperId)
        assertEquals(2.0, snap.daily.netUsd, 1e-9)
        assertEquals(5, snap.daily.trades)
        assertEquals(3, snap.daily.wins)
        assertEquals("sf41", snap.daily.best!!.scalperId)
        assertEquals("sf42", snap.daily.worst!!.scalperId)
        assertEquals(listOf("clip"), snap.daily.settings.map { it.key })
    }

    @Test fun queryPagesEveryCloseAndIgnoresAStuckCursor() {
        val now = etTime(2026, 10, 18, 16, 0)
        val store = InMemoryScalpPersistence()
        listOf("c", "b", "a").forEach { id ->
            store.upsertScalpAndStats(closed(id, "sf41", now - 5_000L, net = 1.0, fees = 0.3), null)
        }
        store.upsertScalpAndStats(closed("old", "sf41", now - HomeScalpRank.DAY_MS - 5_000L, net = 20.0), null)
        val closes = HomeSummaryQuery.closesSince(store, now - HomeScalpRank.DAY_MS, now, pageSize = 1)
        assertEquals(listOf(1.0, 1.0, 1.0), closes.map { it.netUsd })
        assertTrue(closes.all { it.feesUsd == 0.3 })
        assertEquals(3.0, closes.sumOf { it.netUsd }, 1e-9)

        val stuckTrade = closed("same", "sf41", now - 1_000L, net = 1.0)
        val stuck = object : ScalpPersistence by InMemoryScalpPersistence() {
            override fun loadStats() = listOf(ScalpStat(scalperId = "sf41", n = 1, lastClosedAtMs = now - 1_000L))
            override fun pageTrades(scalperId: String, afterClosedAtMs: Long?, afterId: String?, limit: Int) = listOf(stuckTrade)
        }
        assertEquals(1, HomeSummaryQuery.closesSince(stuck, now - HomeScalpRank.DAY_MS, now, pageSize = 1).size)
    }

    @Test fun emptyStatsFallBackToStoredClosesAndLoadDayUsesThem() {
        val noon = etTime(2026, 10, 18, 12, 0)
        val store = InMemoryScalpPersistence()
        store.upsertScalp(closed("plain", "sf43", noon, net = 1.25, fees = 0.4))
        store.upsertScalp(closed("shadow", "sf43", noon, net = 9.0, shadow = true))
        val fromFallback = HomeSummaryQuery.closesSince(store, noon - HomeScalpRank.DAY_MS, noon + 1)
        assertEquals(listOf("sf43"), fromFallback.map { it.scalperId })
        assertEquals(1.25, fromFallback.single().netUsd, 1e-9)
        val day = HomeSummaryLoader.loadDay(store, emptyList(), "2026-10-18")
        assertEquals(1.25, day.netUsd, 1e-9)
        assertEquals(1, day.trades)
        assertEquals("sf43", day.best!!.scalperId)
    }

    @Test fun elevenFiftyNineEtScheduleSkipsTheMinuteItJustFired() {
        val zone = HomeSummarySchedule.ET
        val afternoon = ZonedDateTime.of(2026, 10, 18, 16, 51, 0, 0, zone)
        val fire = HomeSummarySchedule.nextFire(afternoon.toInstant().toEpochMilli(), zone)
        assertEquals(LocalDate.of(2026, 10, 18), fire.toLocalDate())
        assertEquals(LocalTime.of(23, 59), fire.toLocalTime())
        assertTrue(HomeSummarySchedule.delayMs(afternoon.toInstant().toEpochMilli(), zone) > 0L)

        val exact = ZonedDateTime.of(2026, 10, 18, 23, 59, 0, 0, zone)
        assertEquals(exact.toInstant(), HomeSummarySchedule.nextFire(exact.toInstant().toEpochMilli(), zone).toInstant())
        val follow = HomeSummarySchedule.followingFire(exact.toInstant().toEpochMilli(), zone)
        assertEquals(LocalDate.of(2026, 10, 19), follow.toLocalDate())
        assertEquals(LocalTime.of(23, 59), follow.toLocalTime())

        val justAfter = ZonedDateTime.of(2026, 10, 18, 23, 59, 1, 0, zone)
        val next = HomeSummarySchedule.nextFire(justAfter.toInstant().toEpochMilli(), zone)
        assertEquals(LocalDate.of(2026, 10, 19), next.toLocalDate())

        val fallBackNoon = ZonedDateTime.of(2026, 11, 1, 12, 0, 0, 0, zone)
        val fallFire = HomeSummarySchedule.nextFire(fallBackNoon.toInstant().toEpochMilli(), zone)
        assertEquals(LocalDate.of(2026, 11, 1), fallFire.toLocalDate())
        assertEquals(23, fallFire.hour)
        assertEquals(59, fallFire.minute)
    }

    @Test fun homeSlotStaysAboveExistingContentAndTheWorkerStaysPaper() {
        val home = source("ui/HomeScreen.kt")
        val slot = home.indexOf("if (showHomeSummary)")
        val feed = home.indexOf("key = \"feed-status\"")
        assertTrue(slot in 1 until feed)
        assertTrue(source("ui/OddsScreen.kt").contains("HomeSummarySection()"))
        assertTrue(source("KalshiOddsApp.kt").contains("HomeSummaryScheduler.sync"))
        val worker = source("worker/HomeSummaryWorker.kt")
        assertFalse(worker.contains("KalshiTradeClient"))
        assertFalse(worker.contains("createOrder"))
        assertTrue(worker.contains("Dispatchers.IO"))
        val cards = source("ui/home/HomeSummaryCards.kt")
        assertTrue(cards.contains("withContext(Dispatchers.IO)"))
        assertTrue(cards.indexOf("HomeBestScalperCard(") < cards.indexOf("HomeDailySummaryCard("))
    }

    private fun etTime(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, et).toInstant().toEpochMilli()

    private fun source(rel: String): String {
        val root = listOf(File("app/src/main/java/com/dirk/kalshiodds"), File("src/main/java/com/dirk/kalshiodds")).first { it.isDirectory }
        return File(root, rel).readText()
    }

    private fun closed(
        id: String,
        scalper: String,
        closedAtMs: Long,
        net: Double,
        fees: Double = 0.2,
        state: ScalpState = ScalpState.CLOSED,
        shadow: Boolean = false
    ) = ScalpTrade(
        id = id,
        ticker = "KXBTC15M-TEST",
        side = "YES",
        state = state,
        signalAtMs = closedAtMs - 1_000L,
        signalAsk = 0.40,
        fairAtSignal = 0.55,
        contracts = 2,
        entryPrice = 0.40,
        entryFeeUsd = fees / 2.0,
        exitFeeUsd = fees / 2.0,
        closedAtMs = if (state == ScalpState.CLOSED) closedAtMs else null,
        netUsd = if (state == ScalpState.CLOSED) net else null,
        ruleVersion = "1|$scalper|${if (shadow) "S" else "P"}"
    )
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class HomeSummaryPrefsTest {
    @Test fun notificationDefaultsOn() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        assertTrue(HomeSummaryPrefs.isEnabled(ctx))
        HomeSummaryPrefs.write(ctx, false)
        assertFalse(HomeSummaryPrefs.isEnabled(ctx))
        HomeSummaryPrefs.write(ctx, true)
        assertTrue(HomeSummaryPrefs.isEnabled(ctx))
    }
}
