package com.dirk.kalshiodds.signal.feedback

import com.dirk.kalshiodds.data.api.KalshiApi
import com.dirk.kalshiodds.data.local.archive.SettledWindowRow
import com.dirk.kalshiodds.domain.CryptoMarkets
import com.dirk.kalshiodds.domain.KalshiPrice
import com.dirk.kalshiodds.prediction.PredictionLogEntry
import com.dirk.kalshiodds.signal.model.SignalStance
import com.dirk.kalshiodds.signal.paper.PaperFill
import com.dirk.kalshiodds.signal.paper.PaperTileBuy
import com.dirk.kalshiodds.signal.trade.KalshiFee
import com.dirk.kalshiodds.signal.trade.LiveOrderSizer
import com.dirk.kalshiodds.ui.WindowLabel
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs

/**
 * Single source of truth for the full Scorecard: settled prediction-log
 * rows plus settled paper fills since the last bankroll reset.
 *
 * Dollar P&L comes only from real paper fills ([PickRow.countsMoney]).
 * A prediction-log row with no fill counts as a win or a loss and
 * contributes $0. Pre-reset fills are not passed in here; the scorecard
 * shows them on a separate archive card.
 *
 * Combined W-L is the pick-row ledger (log picks + fills). Combined
 * dollars are the fill rows only, so they match the paper bankroll
 * change since reset. Every breakdown is a partition of that same list.
 *
 * Bitcoin-only: [isScorecardTicker] / [CryptoMarkets.isLiveTicker]
 * (KXBTC15M). Stored ETH/SOL rows are ignored.
 *
 * An entry price under [MIN_SCORE_PRICE] or over [MAX_SCORE_PRICE] is
 * not scored when it comes from the log (no W-L, no dollars). A real
 * fill still contributes its stored P&L at any price, because that
 * cash already moved the bankroll.
 *
 * Missing entry ask: count the log row in W-L, dollars stay $0, and
 * the row shows "entry not recorded".
 */
object ScorecardLedger {

    val ET_ZONE: ZoneId = ZoneId.of("America/New_York")

    enum class Kind { AI, MANUAL }

    data class Money(
        val wonUsd: Double = 0.0,
        val lostUsd: Double = 0.0,
        val pnlUsd: Double = 0.0,
        val biggestWinUsd: Double? = null,
        val biggestLossUsd: Double? = null,
        val avgWinUsd: Double? = null,
        val avgLossUsd: Double? = null,
        val winCount: Int = 0,
        val lossCount: Int = 0
    )

    data class Record(
        val wins: Int = 0,
        val losses: Int = 0,
        val settledCount: Int = 0,
        val hitRate: Double? = null,
        val streak: String = EM_DASH,
        val money: Money = Money()
    )

    data class Bucket(
        val key: String,
        val label: String,
        val wins: Int,
        val losses: Int,
        val settledCount: Int,
        val hitRate: Double?,
        val pnlUsd: Double
    )

    data class PickRow(
        val kind: Kind,
        val ticker: String,
        val side: String,
        val displaySide: String,
        val windowEt: String,
        val settledAtMs: Long,
        val entryAsk: Double?,
        val aiPct: Double?,
        val marketPct: Double?,
        val stakeUsd: Double?,
        val contracts: Int?,
        val feeUsd: Double?,
        val won: Boolean,
        val pnlUsd: Double?,
        val strikeUsd: Double?,
        val finalUsd: Double?,
        val source: String,
        val noBetWouldHave: Boolean = false,
        val entryNotRecorded: Boolean = false,
        /** True only when [pnlUsd] is a real paper fill, not a log pick. */
        val countsMoney: Boolean = false
    )

    data class Snapshot(
        val ai: Record,
        val manual: Record,
        val combined: Record,
        val noBetWouldHave: Record,
        val bySide: List<Bucket>,
        val byPrice: List<Bucket>,
        val byTime: List<Bucket>,
        val byConfidence: List<Bucket>,
        val picks: List<PickRow>,
        val cumulativePnl: List<Pair<Long, Double>>,
        val openCount: Int,
        val voidCount: Int
    )

    data class StoredEntry(
        val entryAsk: Double?,
        val contracts: Int?,
        val stakeUsd: Double?,
        val feeUsd: Double?
    )

    const val EM_DASH = "—"
    const val UNKNOWN_KEY = "unknown"
    const val UNKNOWN_PRICE_LABEL = "Unknown price"
    const val UNKNOWN_TIME_LABEL = "Unknown time"
    const val UNKNOWN_CONF_LABEL = "Unknown confidence"
    const val UNKNOWN_SIDE_LABEL = "Unknown side"
    const val ENTRY_NOT_RECORDED = "entry not recorded"

    /**
     * Same $10 all-in clip as [com.dirk.kalshiodds.ui.HomeCopy.TILE_STAKE_USD]
     * / `tenDollarWins`. Live Approve stays the $5 cap. The scorecard does
     * not turn this clip into dollar P&L.
     */
    const val PAPER_STAKE_USD = 10.0

    /** Log picks outside this band are not scored. 2¢ and 98¢ are included. */
    const val MIN_SCORE_PRICE = 0.02
    const val MAX_SCORE_PRICE = 0.98

    val PRICE_BANDS: List<Pair<String, String>> = listOf(
        "le31" to "≤31¢",
        "32-50" to "32–50¢",
        "51-70" to "51–70¢",
        "gt70" to ">70¢"
    )

    val CONFIDENCE_BANDS: List<Pair<String, String>> = listOf(
        "le55" to "AI ≤55%",
        "56-70" to "AI 56–70%",
        "71-85" to "AI 71–85%",
        "gt85" to "AI >85%"
    )

    /** KXBTC15M only — same live-universe gate the rest of the app uses. */
    fun isScorecardTicker(ticker: String): Boolean = CryptoMarkets.isLiveTicker(ticker)

    fun isScorecardSeries(series: String): Boolean =
        series.trim().equals(KalshiApi.SERIES_BTC, ignoreCase = true)

    fun of(
        entries: List<PredictionLogEntry>,
        fills: List<PaperFill> = emptyList(),
        windows: List<SettledWindowRow> = emptyList(),
        zoneId: ZoneId = ET_ZONE
    ): Snapshot {
        val btcEntries = entries.filter { isScorecardTicker(it.ticker) }
        val scored = btcEntries.filter {
            (it.outcome.equals("yes", true) || it.outcome.equals("no", true)) &&
                ForecastUnits.isScoredPick(it) &&
                scoreableLogPrice(it.entryAsk)
        }
        val noBet = btcEntries.filter {
            (it.outcome.equals("yes", true) || it.outcome.equals("no", true)) &&
                SignalStance.isNoBetSide(it.predictedSide) &&
                scoreableLogPrice(it.entryAsk)
        }
        val settledFills = fills.filter {
            isScorecardTicker(it.ticker) && it.settled && it.won != null
        }
        val aiFills = settledFills.filter { isAiSource(it.source) }
        val manualFills = settledFills.filter { !isAiSource(it.source) }
        val windowByTicker = windows.associateBy { it.ticker.uppercase(Locale.US) }

        val unusedAiFills = aiFills.toMutableList()
        val aiRows = scored.map { entry ->
            val match = takeMatchingFill(unusedAiFills, entry)
            pickFromEntry(entry, match, windowByTicker[entry.ticker.uppercase(Locale.US)], zoneId, Kind.AI)
        }
        val leftoverAiRows = unusedAiFills.map { fill ->
            pickFromFill(fill, scored, windowByTicker[fill.ticker.uppercase(Locale.US)], zoneId, Kind.AI)
        }
        val scoredTickers = scored.map { it.ticker.uppercase(Locale.US) }.toSet()
        val manualRows = manualFills
            .filter { it.ticker.uppercase(Locale.US) !in scoredTickers }
            .map { fill ->
                pickFromFill(fill, scored + noBet, windowByTicker[fill.ticker.uppercase(Locale.US)], zoneId, Kind.MANUAL)
            }
        val noBetRows = noBet.map { entry ->
            pickFromEntry(
                entry,
                fill = null,
                window = windowByTicker[entry.ticker.uppercase(Locale.US)],
                zoneId = zoneId,
                kind = Kind.AI,
                noBetWouldHave = true
            )
        }

        val aiPicks = (aiRows + leftoverAiRows).sortedByDescending { it.settledAtMs }
        val allMoneyRows = aiPicks + manualRows
        val displayPicks = (allMoneyRows + noBetRows).sortedByDescending { it.settledAtMs }

        val ai = record(aiPicks)
        val manual = record(manualRows)
        val combined = Record(
            wins = ai.wins + manual.wins,
            losses = ai.losses + manual.losses,
            settledCount = ai.settledCount + manual.settledCount,
            hitRate = rate(ai.wins + manual.wins, ai.settledCount + manual.settledCount),
            streak = streakOf(allMoneyRows.sortedByDescending { it.settledAtMs }),
            money = moneyOfRows(allMoneyRows)
        )
        return Snapshot(
            ai = ai,
            manual = manual,
            combined = combined,
            noBetWouldHave = record(noBetRows),
            bySide = sideBuckets(allMoneyRows),
            byPrice = priceBuckets(allMoneyRows),
            byTime = timeBuckets(allMoneyRows, zoneId),
            byConfidence = confidenceBuckets(allMoneyRows),
            picks = displayPicks,
            cumulativePnl = cumulative(allMoneyRows),
            openCount = btcEntries.count { it.outcome == null },
            voidCount = btcEntries.count { it.outcome.equals("void", true) }
        )
    }

    fun isAiSource(source: String?): Boolean {
        val s = source?.trim()?.lowercase(Locale.US) ?: return false
        if (s == PaperTileBuy.SOURCE) return false
        if (s.startsWith("tile")) return false
        if (s.contains("manual")) return false
        return s.startsWith("ai") || s.contains("ai hunter") || s.contains("ai ticket") ||
            s.contains("ai signal") || s.contains("hunter") && !s.contains("paper buy")
    }

    /**
     * Log / imaginary picks at a price under 2¢ or over 98¢ are not scored.
     * A missing price still counts as W-L with no dollars.
     */
    fun scoreableLogPrice(ask: Double?): Boolean {
        val px = ask ?: return true
        if (!px.isFinite() || px <= 0.0) return false
        return px + 1e-12 >= MIN_SCORE_PRICE && px - 1e-12 <= MAX_SCORE_PRICE
    }

    fun priceBandKey(ask01: Double?): String? {
        val cents = ask01?.takeIf { it.isFinite() && it > 0.0 }?.times(100.0) ?: return null
        return when {
            cents <= 31.0 + 1e-9 -> "le31"
            cents < 51.0 - 1e-9 -> "32-50"
            cents <= 70.0 + 1e-9 -> "51-70"
            else -> "gt70"
        }
    }

    fun confidenceBandKey(aiPct: Double?): String? {
        val pct = aiPct?.takeIf { it.isFinite() } ?: return null
        return when {
            pct <= 55.0 + 1e-9 -> "le55"
            pct <= 70.0 + 1e-9 -> "56-70"
            pct <= 85.0 + 1e-9 -> "71-85"
            else -> "gt85"
        }
    }

    fun timeBucketKey(settledAtMs: Long, zoneId: ZoneId = ET_ZONE): String? {
        if (settledAtMs <= 0L) return null
        return ScorecardMetrics.etBucketKey(settledAtMs, zoneId)
    }

    fun feeUsd(fill: PaperFill): Double? {
        val fromNote = Regex("""fee\s+\$([0-9]+(?:\.[0-9]+)?)""", RegexOption.IGNORE_CASE)
            .find(fill.note)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        if (fromNote != null) return fromNote
        if (fill.contracts <= 0 || fill.limitPrice <= 0.0) return null
        return KalshiFee.total(fill.contracts, fill.limitPrice)
    }

    /**
     * $10 paper-stake clip used to score an AI pick when contracts / stake /
     * fee were not stored but the entry ask was. Same rule as
     * [com.dirk.kalshiodds.ui.HomeCopy.tenDollarWins]:
     * `LiveOrderSizer.size(ask, PAPER_STAKE_USD)`.
     */
    fun paperClipFromAsk(ask: Double?): LiveOrderSizer.Clip? {
        val px = KalshiPrice.usable(ask) ?: return null
        if (!scoreableLogPrice(px)) return null
        val clip = LiveOrderSizer.size(px, PAPER_STAKE_USD)
        return clip.takeIf { it.ok }
    }

    fun captureEntryFromBook(
        sideYes: Boolean,
        yesAsk: Double?,
        noAsk: Double?,
        yesBid: Double? = null
    ): StoredEntry {
        val ask = if (sideYes) {
            KalshiPrice.usable(yesAsk)
        } else {
            KalshiPrice.usable(noAsk) ?: yesBid?.let { KalshiPrice.usable(1.0 - it) }
        }
        val clip = paperClipFromAsk(ask)
        if (clip == null) return StoredEntry(ask, null, null, null)
        return StoredEntry(
            entryAsk = clip.price,
            contracts = clip.count,
            stakeUsd = clip.allInUsd,
            feeUsd = clip.feeUsd
        )
    }

    fun moneyOf(fills: List<PaperFill>): Money {
        val pnls = fills.mapNotNull { it.pnlUsd }
        return moneyFromPnls(pnls)
    }

    fun moneyOfRows(rows: List<PickRow>): Money =
        moneyFromPnls(rows.filter { it.countsMoney }.map { it.pnlUsd ?: 0.0 })

    fun streakOf(newestFirst: List<PickRow>): String {
        val scored = newestFirst.filter { !it.noBetWouldHave }
        if (scored.isEmpty()) return EM_DASH
        val won = scored.first().won
        var n = 0
        for (row in scored) {
            if (row.won != won) break
            n += 1
        }
        return if (won) "${n}W" else "${n}L"
    }

    fun displaySide(side: String): String =
        if (side.equals("NO", true) || side.equals("DOWN", true)) "DOWN" else "UP"

    fun moneyLine(money: Money): String {
        return String.format(
            Locale.US,
            "P&L %s  won $%.2f  lost $%.2f",
            signedUsd(money.pnlUsd),
            money.wonUsd,
            money.lostUsd
        )
    }

    fun signedUsd(usd: Double?): String {
        if (usd == null) return EM_DASH
        val sign = when {
            usd > 1e-9 -> "+"
            usd < -1e-9 -> "−"
            else -> ""
        }
        return String.format(Locale.US, "%s$%.2f", sign, abs(usd))
    }

    private fun moneyFromPnls(pnls: List<Double>): Money {
        val wins = pnls.filter { it > 1e-9 }
        val losses = pnls.filter { it < -1e-9 }
        return Money(
            wonUsd = wins.sum(),
            lostUsd = abs(losses.sum()),
            pnlUsd = pnls.sum(),
            biggestWinUsd = wins.maxOrNull(),
            biggestLossUsd = losses.minOrNull(),
            avgWinUsd = wins.takeIf { it.isNotEmpty() }?.average(),
            avgLossUsd = losses.takeIf { it.isNotEmpty() }?.average(),
            winCount = wins.size,
            lossCount = losses.size
        )
    }

    private fun record(rows: List<PickRow>): Record {
        val wins = rows.count { it.won }
        val losses = rows.count { !it.won }
        val n = rows.size
        return Record(
            wins = wins,
            losses = losses,
            settledCount = n,
            hitRate = rate(wins, n),
            streak = streakOf(rows.sortedByDescending { it.settledAtMs }),
            money = moneyOfRows(rows)
        )
    }

    private fun rate(wins: Int, total: Int): Double? =
        if (total <= 0) null else wins.toDouble() / total

    private fun takeMatchingFill(
        fills: MutableList<PaperFill>,
        entry: PredictionLogEntry
    ): PaperFill? {
        val idx = fills.indexOfFirst { fill ->
            fill.ticker.equals(entry.ticker, true) &&
                fill.side.equals(if (ForecastUnits.pickedSideIsYes(entry)) "YES" else "NO", true)
        }
        if (idx < 0) return null
        return fills.removeAt(idx)
    }

    private fun pickFromEntry(
        entry: PredictionLogEntry,
        fill: PaperFill?,
        window: SettledWindowRow?,
        zoneId: ZoneId,
        kind: Kind,
        noBetWouldHave: Boolean = false
    ): PickRow {
        val won = if (noBetWouldHave) {
            ForecastUnits.predictedYesSide(entry) == ForecastUnits.outcomeYes(entry.outcome)
        } else {
            ForecastUnits.hit(entry)
        }
        val side = when {
            noBetWouldHave -> if (ForecastUnits.predictedYesSide(entry)) "YES" else "NO"
            ForecastUnits.pickedSideIsYes(entry) -> "YES"
            else -> "NO"
        }
        val at = entry.settledAtMs ?: entry.closeTimeMs ?: entry.timestampMs
        val sized = sizePick(entry, fill)
        return PickRow(
            kind = kind,
            ticker = entry.ticker,
            side = side,
            displaySide = displaySide(side),
            windowEt = WindowLabel.of(entry.ticker, entry.closeTimeMs ?: at),
            settledAtMs = at,
            entryAsk = sized.entryAsk,
            aiPct = ForecastUnits.sideProbability01(entry) * 100.0,
            marketPct = ForecastUnits.probability01(entry.marketMid) * 100.0,
            stakeUsd = sized.stakeUsd,
            contracts = sized.contracts,
            feeUsd = sized.feeUsd,
            won = won,
            pnlUsd = sized.pnlUsd,
            strikeUsd = window?.strikeUsd,
            finalUsd = null,
            source = fill?.source ?: if (noBetWouldHave) "NO BET would-have" else "AI pick",
            noBetWouldHave = noBetWouldHave,
            entryNotRecorded = sized.entryNotRecorded,
            countsMoney = sized.countsMoney
        )
    }

    private fun pickFromFill(
        fill: PaperFill,
        entries: List<PredictionLogEntry>,
        window: SettledWindowRow?,
        @Suppress("UNUSED_PARAMETER") zoneId: ZoneId,
        kind: Kind
    ): PickRow {
        val entry = entries.firstOrNull { it.ticker.equals(fill.ticker, true) }
        val won = fill.won == true
        val ask = fill.limitPrice.takeIf { it > 0.0 }
        return PickRow(
            kind = kind,
            ticker = fill.ticker,
            side = fill.side,
            displaySide = displaySide(fill.side),
            windowEt = WindowLabel.of(fill.ticker, fill.createdAtMs),
            settledAtMs = fill.createdAtMs,
            entryAsk = ask,
            aiPct = entry?.let { ForecastUnits.sideProbability01(it) * 100.0 },
            marketPct = entry?.let { ForecastUnits.probability01(it.marketMid) * 100.0 },
            stakeUsd = fill.stakeUsd,
            contracts = fill.contracts.takeIf { it > 0 },
            feeUsd = feeUsd(fill),
            won = won,
            pnlUsd = fill.pnlUsd ?: 0.0,
            strikeUsd = window?.strikeUsd,
            finalUsd = null,
            source = fill.source,
            entryNotRecorded = ask == null,
            countsMoney = true
        )
    }

    private data class SizedPick(
        val entryAsk: Double?,
        val contracts: Int?,
        val stakeUsd: Double?,
        val feeUsd: Double?,
        val pnlUsd: Double,
        val entryNotRecorded: Boolean,
        val countsMoney: Boolean
    )

    /**
     * A matched paper fill is the only dollar outcome. A log row with no
     * fill keeps its ask for the price bands and counts W-L only.
     */
    private fun sizePick(entry: PredictionLogEntry, fill: PaperFill?): SizedPick {
        if (fill != null) {
            val ask = fill.limitPrice.takeIf { it > 0.0 }
            return SizedPick(
                entryAsk = ask ?: KalshiPrice.usable(entry.entryAsk),
                contracts = fill.contracts.takeIf { it > 0 },
                stakeUsd = fill.stakeUsd,
                feeUsd = feeUsd(fill),
                pnlUsd = fill.pnlUsd ?: 0.0,
                entryNotRecorded = ask == null && entry.entryAsk == null,
                countsMoney = true
            )
        }
        val ask = KalshiPrice.usable(entry.entryAsk)
        return SizedPick(
            entryAsk = ask,
            contracts = null,
            stakeUsd = null,
            feeUsd = null,
            pnlUsd = 0.0,
            entryNotRecorded = ask == null,
            countsMoney = false
        )
    }

    private fun sideBuckets(rows: List<PickRow>): List<Bucket> {
        val known = listOf("UP" to "UP picks", "DOWN" to "DOWN picks").map { (key, label) ->
            bucket(key, label, rows.filter { it.displaySide == key })
        }
        val unknown = rows.filter { it.displaySide != "UP" && it.displaySide != "DOWN" }
        return known + bucket(UNKNOWN_KEY, UNKNOWN_SIDE_LABEL, unknown)
    }

    private fun priceBuckets(rows: List<PickRow>): List<Bucket> {
        val groups = rows.groupBy { priceBandKey(it.entryAsk) }
        return PRICE_BANDS.map { (key, label) -> bucket(key, label, groups[key].orEmpty()) } +
            bucket(UNKNOWN_KEY, UNKNOWN_PRICE_LABEL, groups[null].orEmpty())
    }

    private fun confidenceBuckets(rows: List<PickRow>): List<Bucket> {
        val groups = rows.groupBy { confidenceBandKey(it.aiPct) }
        return CONFIDENCE_BANDS.map { (key, label) -> bucket(key, label, groups[key].orEmpty()) } +
            bucket(UNKNOWN_KEY, UNKNOWN_CONF_LABEL, groups[null].orEmpty())
    }

    private fun timeBuckets(rows: List<PickRow>, zoneId: ZoneId): List<Bucket> {
        val defs = (0 until 6).map { i ->
            val start = i * 4
            val key = "%02d-%02d".format(start, start + 4)
            val label = "%d–%d ET".format(start, start + 4)
            key to label
        }
        val groups = rows.groupBy { timeBucketKey(it.settledAtMs, zoneId) }
        return defs.map { (key, label) -> bucket(key, label, groups[key].orEmpty()) } +
            bucket(UNKNOWN_KEY, UNKNOWN_TIME_LABEL, groups[null].orEmpty())
    }

    private fun bucket(key: String, label: String, rows: List<PickRow>): Bucket {
        val wins = rows.count { it.won }
        val n = rows.size
        return Bucket(
            key = key,
            label = label,
            wins = wins,
            losses = (n - wins).coerceAtLeast(0),
            settledCount = n,
            hitRate = rate(wins, n),
            pnlUsd = rows.filter { it.countsMoney }.sumOf { it.pnlUsd ?: 0.0 }
        )
    }

    private fun cumulative(rows: List<PickRow>): List<Pair<Long, Double>> {
        var run = 0.0
        return rows.filter { it.countsMoney }.sortedBy { it.settledAtMs }.map { row ->
            run += row.pnlUsd ?: 0.0
            row.settledAtMs to run
        }
    }
}
