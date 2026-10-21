package com.dirk.kalshiodds.ui.home

import com.dirk.kalshiodds.data.local.history.SettingsChange
import com.dirk.kalshiodds.decision.ScalpDaily
import com.dirk.kalshiodds.decision.ScalpModels
import com.dirk.kalshiodds.ui.ScalpTabCopy
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * One closed paper scalp. [netUsd] is already after entry and exit fees.
 * [feesUsd] is recorded so callers cannot accidentally rank on gross.
 */
data class HomeScalpClose(
    val scalperId: String,
    val closedAtMs: Long,
    val netUsd: Double,
    val feesUsd: Double = 0.0
)

data class RankedScalper(
    val scalperId: String,
    val label: String,
    val netUsd: Double,
    val wins: Int,
    val trades: Int
) {
    val winRate: Double get() = if (trades == 0) 0.0 else wins.toDouble() / trades
}

data class DailySummary(
    val dayEt: String,
    val netUsd: Double,
    val trades: Int,
    val wins: Int,
    val best: RankedScalper?,
    val worst: RankedScalper?,
    val settings: List<SettingsChange>,
    val settingsTracked: Boolean
) {
    val winRate: Double? get() = if (trades == 0) null else wins.toDouble() / trades
}

data class HomeSummarySnapshot(
    val best24: RankedScalper?,
    val best7: RankedScalper?,
    val daily: DailySummary
)

/** Rolling 24h / 7d paper ranking. A win is net after fees > 0. */
object HomeScalpRank {
    const val DAY_MS: Long = 24L * 60L * 60L * 1000L
    const val WEEK_MS: Long = 7L * DAY_MS

    /** Higher net, then win rate, then trade count, then id. maxWith is the best scalper. */
    private val bestFirst: Comparator<RankedScalper> =
        compareBy<RankedScalper> { it.netUsd }
            .thenBy { it.winRate }
            .thenBy { it.trades }
            .thenBy { it.scalperId }

    fun windowed(closes: List<HomeScalpClose>, nowMs: Long, windowMs: Long): List<HomeScalpClose> {
        val since = nowMs - windowMs
        return closes.filter { row ->
            row.scalperId.isNotBlank() && row.netUsd.isFinite() && row.closedAtMs in since..nowMs
        }
    }

    fun ranks(closes: List<HomeScalpClose>, labelOf: (String) -> String = { it }): List<RankedScalper> =
        closes.groupBy { it.scalperId }.map { (id, rows) ->
            RankedScalper(
                scalperId = id,
                label = labelOf(id),
                netUsd = rows.sumOf { it.netUsd },
                wins = rows.count { it.netUsd > 0.0 },
                trades = rows.size
            )
        }

    fun best(
        closes: List<HomeScalpClose>,
        nowMs: Long,
        windowMs: Long,
        labelOf: (String) -> String = { it }
    ): RankedScalper? = ranks(windowed(closes, nowMs, windowMs), labelOf).maxWithOrNull(bestFirst)

    fun pickBest(ranks: List<RankedScalper>): RankedScalper? = ranks.maxWithOrNull(bestFirst)

    fun pickWorst(ranks: List<RankedScalper>): RankedScalper? = ranks.minWithOrNull(bestFirst)
}

/** Today's America/New_York paper book: P&L, best/worst scalper, settings changes. */
object HomeDailyAggregate {
    fun fromCloses(
        closes: List<HomeScalpClose>,
        dayEt: String,
        settings: List<SettingsChange>,
        dayOf: (Long) -> String,
        settingsTracked: Boolean = true,
        labelOf: (String) -> String = { it }
    ): DailySummary {
        val todays = closes.filter { it.scalperId.isNotBlank() && it.netUsd.isFinite() && dayOf(it.closedAtMs) == dayEt }
        val ranked = HomeScalpRank.ranks(todays, labelOf)
        return summary(dayEt, todays.sumOf { it.netUsd }, todays.size, todays.count { it.netUsd > 0.0 }, ranked, settings, dayOf, settingsTracked)
    }

    fun fromDaily(
        rows: List<ScalpDaily>,
        dayEt: String,
        settings: List<SettingsChange>,
        dayOf: (Long) -> String,
        settingsTracked: Boolean = true,
        labelOf: (String) -> String = { it }
    ): DailySummary {
        val active = rows.filter { it.dayEt == dayEt && it.n > 0 && it.scalperId.isNotBlank() && it.netUsd.isFinite() }
        val ranked = active.map { RankedScalper(it.scalperId, labelOf(it.scalperId), it.netUsd, it.wins, it.n) }
        return summary(dayEt, active.sumOf { it.netUsd }, active.sumOf { it.n }, active.sumOf { it.wins }, ranked, settings, dayOf, settingsTracked)
    }

    private fun summary(
        dayEt: String,
        netUsd: Double,
        trades: Int,
        wins: Int,
        ranked: List<RankedScalper>,
        settings: List<SettingsChange>,
        dayOf: (Long) -> String,
        settingsTracked: Boolean
    ) = DailySummary(
        dayEt = dayEt,
        netUsd = netUsd,
        trades = trades,
        wins = wins,
        best = HomeScalpRank.pickBest(ranked),
        worst = HomeScalpRank.pickWorst(ranked),
        settings = if (settingsTracked) settings.filter { dayOf(it.createdAtMs) == dayEt } else emptyList(),
        settingsTracked = settingsTracked
    )
}

/** Next 11:59 PM America/New_York, and the run after the one that just fired. */
object HomeSummarySchedule {
    val ET: ZoneId = ZoneId.of("America/New_York")

    fun nextFire(nowMs: Long, zone: ZoneId = ET): ZonedDateTime {
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        val today = now.toLocalDate().atTime(23, 59).atZone(zone)
        return if (!now.isAfter(today)) today else today.plusDays(1)
    }

    /** The fire after [nowMs]. A run that lands on 11:59 PM schedules the next night, not itself. */
    fun followingFire(nowMs: Long, zone: ZoneId = ET): ZonedDateTime {
        val upcoming = nextFire(nowMs, zone)
        val wait = Duration.between(Instant.ofEpochMilli(nowMs), upcoming.toInstant()).toMillis()
        return if (wait <= 1_000L) upcoming.plusDays(1) else upcoming
    }

    fun delayMs(nowMs: Long, zone: ZoneId = ET): Long =
        Duration.between(Instant.ofEpochMilli(nowMs), nextFire(nowMs, zone).toInstant()).toMillis().coerceAtLeast(0)
}

object HomeSummaryCopy {
    const val BEST_TITLE = "Best scalper"
    const val DAILY_TITLE = "Today"
    const val PAPER = "PAPER · net after fees"
    const val EMPTY_WINDOW = "No closed paper trades"
    const val LOADING = "Reading paper results…"
    const val FAILED = "Couldn't read paper results"
    const val NOTIFY_LABEL = "11:59 PM ET summary"
    const val NOTIFY_HINT = "Posts today's paper P&L. No orders."
    const val NOTIFY_TITLE = "Paper summary · today ET"
    const val SETTINGS_SHOWN = 4

    fun displayName(scalperId: String): String =
        ScalpModels.modelByCode(com.dirk.kalshiodds.decision.KeptScalpers.canonical(scalperId))?.code
            ?: com.dirk.kalshiodds.decision.KeptScalpers.REMOVED_LABEL

    fun tradeCount(n: Int): String = if (n == 1) "1 trade" else "$n trades"

    fun windowLine(rank: RankedScalper?): String = if (rank == null) {
        EMPTY_WINDOW
    } else {
        "${rank.label} · ${ScalpTabCopy.money(rank.netUsd)} net · ${ScalpTabCopy.pct(rank.winRate)} win · ${tradeCount(rank.trades)}"
    }

    fun settingsHeadline(summary: DailySummary): String = when {
        !summary.settingsTracked -> "Settings changes not tracked"
        summary.settings.isEmpty() -> "No settings changes"
        summary.settings.size == 1 -> "1 settings change"
        else -> "${summary.settings.size} settings changes"
    }

    fun settingsLine(change: SettingsChange): String {
        fun clip(s: String) = if (s.length <= 48) s else s.take(45) + "…"
        return "${change.key}: ${clip(change.oldValue)} → ${clip(change.newValue)}"
    }

    fun notificationBody(summary: DailySummary): String {
        val best = summary.best?.let { "${it.label} ${ScalpTabCopy.money(it.netUsd)}" } ?: "—"
        val worst = summary.worst?.let { "${it.label} ${ScalpTabCopy.money(it.netUsd)}" } ?: "—"
        val wr = ScalpTabCopy.pct(summary.winRate)
        return "Today ET paper ${ScalpTabCopy.money(summary.netUsd)} · ${tradeCount(summary.trades)} · $wr win. " +
            "Best $best. Worst $worst. ${settingsHeadline(summary)}."
    }
}
