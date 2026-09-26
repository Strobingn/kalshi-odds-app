package com.dirk.kalshiodds.signal.feedback

import com.dirk.kalshiodds.data.local.archive.SettledWindowRow
import com.dirk.kalshiodds.prediction.PredictionLogEntry
import com.dirk.kalshiodds.signal.model.SignalStance
import com.dirk.kalshiodds.signal.paper.PaperFill
import com.dirk.kalshiodds.signal.paper.PaperTileBuy
import com.dirk.kalshiodds.signal.trade.KalshiFee
import com.dirk.kalshiodds.ui.WindowLabel
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs

/**
 * Single source of truth for the full Scorecard: settled prediction-log
 * rows plus settled paper fills. Never invents P&L, fees, or strikes.
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
        val noBetWouldHave: Boolean = false
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

    const val EM_DASH = "—"

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

    fun of(
        entries: List<PredictionLogEntry>,
        fills: List<PaperFill> = emptyList(),
        windows: List<SettledWindowRow> = emptyList(),
        zoneId: ZoneId = ET_ZONE
    ): Snapshot {
        val scored = entries.filter {
            (it.outcome.equals("yes", true) || it.outcome.equals("no", true)) &&
                ForecastUnits.isScoredPick(it)
        }
        val noBet = entries.filter {
            (it.outcome.equals("yes", true) || it.outcome.equals("no", true)) &&
                SignalStance.isNoBetSide(it.predictedSide)
        }
        val settledFills = fills.filter { it.settled && it.won != null }
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
        val manualRows = manualFills.map { fill ->
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

        val ai = record(aiPicks, moneyOf(aiFills))
        val manual = record(manualRows, moneyOf(manualFills))
        val combined = Record(
            wins = ai.wins + manual.wins,
            losses = ai.losses + manual.losses,
            settledCount = ai.settledCount + manual.settledCount,
            hitRate = rate(ai.wins + manual.wins, ai.settledCount + manual.settledCount),
            streak = streakOf(allMoneyRows.sortedByDescending { it.settledAtMs }),
            money = moneyOf(settledFills)
        )
        return Snapshot(
            ai = ai,
            manual = manual,
            combined = combined,
            noBetWouldHave = record(noBetRows, Money()),
            bySide = sideBuckets(allMoneyRows),
            byPrice = priceBuckets(allMoneyRows),
            byTime = timeBuckets(allMoneyRows, zoneId),
            byConfidence = confidenceBuckets(allMoneyRows),
            picks = displayPicks,
            cumulativePnl = cumulative(allMoneyRows),
            openCount = entries.count { it.outcome == null },
            voidCount = entries.count { it.outcome.equals("void", true) }
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

    fun feeUsd(fill: PaperFill): Double? {
        val fromNote = Regex("""fee\s+\$([0-9]+(?:\.[0-9]+)?)""", RegexOption.IGNORE_CASE)
            .find(fill.note)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        if (fromNote != null) return fromNote
        if (fill.contracts <= 0 || fill.limitPrice <= 0.0) return null
        return KalshiFee.total(fill.contracts, fill.limitPrice)
    }

    fun moneyOf(fills: List<PaperFill>): Money {
        val pnls = fills.mapNotNull { it.pnlUsd }
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

    private fun record(rows: List<PickRow>, money: Money): Record {
        val wins = rows.count { it.won }
        val losses = rows.count { !it.won }
        val n = rows.size
        return Record(
            wins = wins,
            losses = losses,
            settledCount = n,
            hitRate = rate(wins, n),
            streak = streakOf(rows.sortedByDescending { it.settledAtMs }),
            money = money
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
        return PickRow(
            kind = kind,
            ticker = entry.ticker,
            side = side,
            displaySide = displaySide(side),
            windowEt = WindowLabel.of(entry.ticker, entry.closeTimeMs ?: at),
            settledAtMs = at,
            entryAsk = fill?.limitPrice,
            aiPct = ForecastUnits.sideProbability01(entry) * 100.0,
            marketPct = ForecastUnits.probability01(entry.marketMid) * 100.0,
            stakeUsd = fill?.stakeUsd,
            contracts = fill?.contracts?.takeIf { it > 0 },
            feeUsd = fill?.let { feeUsd(it) },
            won = won,
            pnlUsd = fill?.pnlUsd,
            strikeUsd = window?.strikeUsd,
            finalUsd = null,
            source = fill?.source ?: if (noBetWouldHave) "NO BET would-have" else "AI pick",
            noBetWouldHave = noBetWouldHave
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
        return PickRow(
            kind = kind,
            ticker = fill.ticker,
            side = fill.side,
            displaySide = displaySide(fill.side),
            windowEt = WindowLabel.of(fill.ticker, fill.createdAtMs),
            settledAtMs = fill.createdAtMs,
            entryAsk = fill.limitPrice.takeIf { it > 0.0 },
            aiPct = entry?.let { ForecastUnits.sideProbability01(it) * 100.0 },
            marketPct = entry?.let { ForecastUnits.probability01(it.marketMid) * 100.0 },
            stakeUsd = fill.stakeUsd,
            contracts = fill.contracts.takeIf { it > 0 },
            feeUsd = feeUsd(fill),
            won = won,
            pnlUsd = fill.pnlUsd,
            strikeUsd = window?.strikeUsd,
            finalUsd = null,
            source = fill.source
        )
    }

    private fun sideBuckets(rows: List<PickRow>): List<Bucket> {
        return listOf("UP" to "UP picks", "DOWN" to "DOWN picks").map { (key, label) ->
            bucket(key, label, rows.filter { it.displaySide == key })
        }
    }

    private fun priceBuckets(rows: List<PickRow>): List<Bucket> {
        val groups = rows.groupBy { priceBandKey(it.entryAsk) }
        return PRICE_BANDS.map { (key, label) -> bucket(key, label, groups[key].orEmpty()) }
    }

    private fun confidenceBuckets(rows: List<PickRow>): List<Bucket> {
        val groups = rows.groupBy { confidenceBandKey(it.aiPct) }
        return CONFIDENCE_BANDS.map { (key, label) -> bucket(key, label, groups[key].orEmpty()) }
    }

    private fun timeBuckets(rows: List<PickRow>, zoneId: ZoneId): List<Bucket> {
        val defs = (0 until 6).map { i ->
            val start = i * 4
            val key = "%02d-%02d".format(start, start + 4)
            val label = "%d–%d ET".format(start, start + 4)
            key to label
        }
        val groups = rows.groupBy { ScorecardMetrics.etBucketKey(it.settledAtMs, zoneId) }
        return defs.map { (key, label) -> bucket(key, label, groups[key].orEmpty()) }
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
            pnlUsd = rows.mapNotNull { it.pnlUsd }.sum()
        )
    }

    private fun cumulative(rows: List<PickRow>): List<Pair<Long, Double>> {
        var run = 0.0
        return rows.sortedBy { it.settledAtMs }.map { row ->
            run += row.pnlUsd ?: 0.0
            row.settledAtMs to run
        }
    }
}
