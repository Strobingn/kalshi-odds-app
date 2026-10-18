package com.dirk.kalshiodds.ui.home

import com.dirk.kalshiodds.data.local.history.SettingsChange
import com.dirk.kalshiodds.decision.ScalpModels
import com.dirk.kalshiodds.decision.ScalpPersistence
import com.dirk.kalshiodds.decision.ScalpState
import com.dirk.kalshiodds.decision.ScalpTrade
import java.time.LocalDate
import java.time.ZoneId

/**
 * Reads closed paper scalps from the results store ([ScalpPersistence]).
 * Callers must invoke this off the main thread; every method is blocking SQLite or in-memory work.
 */
object HomeSummaryQuery {
    const val PAGE = 250
    const val MAX_PAGES = 40

    fun fromTrade(trade: ScalpTrade): HomeScalpClose? {
        if (trade.state != ScalpState.CLOSED) return null
        val id = ScalpModels.scalperIdOf(trade) ?: return null
        val net = trade.netUsd?.takeIf { it.isFinite() } ?: return null
        val closed = trade.closedAtMs ?: return null
        return HomeScalpClose(id, closed, net, trade.entryFeeUsd + trade.exitFeeUsd)
    }

    fun closesSince(
        store: ScalpPersistence,
        sinceMs: Long,
        nowMs: Long,
        pageSize: Int = PAGE
    ): List<HomeScalpClose> {
        val stats = store.loadStats()
        if (stats.none { it.n > 0 }) {
            return store.loadScalps().mapNotNull { fromTrade(it) }
                .filter { it.closedAtMs in sinceMs..nowMs }
        }
        val out = ArrayList<HomeScalpClose>()
        val limit = pageSize.coerceIn(1, 500)
        for (st in stats) {
            if (st.scalperId.isBlank() || st.n <= 0) continue
            val last = st.lastClosedAtMs
            if (last != null && last < sinceMs) continue
            var afterMs: Long? = null
            var afterId: String? = null
            var pages = 0
            while (pages < MAX_PAGES) {
                val page = store.pageTrades(st.scalperId, afterMs, afterId, limit)
                if (page.isEmpty()) break
                val tail = page.last()
                if (tail.closedAtMs == afterMs && tail.id == afterId) break
                pages++
                var stop = false
                for (trade in page) {
                    val closed = trade.closedAtMs
                    if (closed == null || closed > nowMs) continue
                    if (closed < sinceMs) {
                        stop = true
                        break
                    }
                    fromTrade(trade)?.let { out += it }
                }
                afterMs = tail.closedAtMs
                afterId = tail.id
                if (stop || page.size < limit) break
            }
        }
        return out
    }
}

object HomeSummaryLoader {
    const val SETTINGS_LIMIT = 400

    fun load(
        store: ScalpPersistence,
        settings: List<SettingsChange>,
        nowMs: Long,
        settingsTracked: Boolean = true
    ): HomeSummarySnapshot {
        val closes = HomeSummaryQuery.closesSince(store, nowMs - HomeScalpRank.WEEK_MS, nowMs)
        val day = ScalpModels.dayEt(nowMs)
        val rows = store.loadDaily(day)
        val daily = if (rows.any { it.n > 0 }) {
            HomeDailyAggregate.fromDaily(rows, day, settings, ScalpModels::dayEt, settingsTracked, HomeSummaryCopy::displayName)
        } else {
            HomeDailyAggregate.fromCloses(closes, day, settings, ScalpModels::dayEt, settingsTracked, HomeSummaryCopy::displayName)
        }
        return HomeSummarySnapshot(
            best24 = HomeScalpRank.best(closes, nowMs, HomeScalpRank.DAY_MS, HomeSummaryCopy::displayName),
            best7 = HomeScalpRank.best(closes, nowMs, HomeScalpRank.WEEK_MS, HomeSummaryCopy::displayName),
            daily = daily
        )
    }

    /** Ledger row for [dayEt] when the incremental daily table has it; otherwise the closes on that ET date. */
    fun loadDay(
        store: ScalpPersistence,
        settings: List<SettingsChange>,
        dayEt: String,
        settingsTracked: Boolean = true
    ): DailySummary {
        val rows = store.loadDaily(dayEt)
        if (rows.any { it.n > 0 }) {
            return HomeDailyAggregate.fromDaily(rows, dayEt, settings, ScalpModels::dayEt, settingsTracked, HomeSummaryCopy::displayName)
        }
        val zone = ZoneId.of("America/New_York")
        val start = LocalDate.parse(dayEt).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = LocalDate.parse(dayEt).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1L
        val closes = HomeSummaryQuery.closesSince(store, start, end)
        return HomeDailyAggregate.fromCloses(closes, dayEt, settings, ScalpModels::dayEt, settingsTracked, HomeSummaryCopy::displayName)
    }

    fun empty(nowMs: Long, settingsTracked: Boolean): HomeSummarySnapshot {
        val day = ScalpModels.dayEt(nowMs)
        return HomeSummarySnapshot(
            best24 = null,
            best7 = null,
            daily = DailySummary(day, 0.0, 0, 0, null, null, emptyList(), settingsTracked)
        )
    }
}
