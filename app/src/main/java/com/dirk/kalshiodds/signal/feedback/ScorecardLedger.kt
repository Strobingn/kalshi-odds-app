package com.dirk.kalshiodds.signal.feedback

import com.dirk.kalshiodds.data.api.KalshiApi
import com.dirk.kalshiodds.data.local.archive.SettledWindowRow
import com.dirk.kalshiodds.domain.CryptoMarkets
import com.dirk.kalshiodds.domain.KalshiPrice
import com.dirk.kalshiodds.prediction.PredictionLogEntry
import com.dirk.kalshiodds.signal.model.ProbabilityClamp
import com.dirk.kalshiodds.signal.model.SignalStance
import com.dirk.kalshiodds.signal.paper.PaperFill
import com.dirk.kalshiodds.signal.paper.PaperPickSource
import com.dirk.kalshiodds.signal.paper.PaperTileBuy
import com.dirk.kalshiodds.signal.trade.KalshiFee
import com.dirk.kalshiodds.signal.trade.LiveOrderSizer
import com.dirk.kalshiodds.ui.WindowLabel
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs

/**
 * Single source of truth for the full Scorecard: settled prediction-log
 * rows plus settled paper fills. Never invents P&L, fees, or strikes.
 *
 * Combined W-L and P&L are the pick-row ledger. Every breakdown (side,
 * price, time, confidence) is a partition of that same list, so W / L /
 * P&L always sum to Combined. Combined P&L is **not** a separate sum of
 * raw [PaperFill.pnlUsd] — that was the $0.71 side-vs-combined gap
 * (fill bag vs row.pnlUsd, plus dropped unknown-price rows).
 *
 * Bitcoin-only: [isScorecardTicker] / [CryptoMarkets.isLiveTicker]
 * (KXBTC15M). Stored ETH/SOL rows are ignored.
 *
 * Paper P&L, Won, Lost, avg win/loss, and biggest win/loss come only
 * from real settled fills in the current (post-reset) book. A
 * prediction-log row with no fill still counts in W-L and win rate,
 * at $0. Asks outside 2–98¢ ([ProbabilityClamp.LO]/[ProbabilityClamp.HI])
 * are an unknown price, not a $10 clip — a 0.1¢ ask used to size
 * thousands of contracts and invent a four-figure "win".
 *
 * 0.3.29 does not wipe the device. The inflated number was a display
 * mix of the pre-reset archive and those hypothetical clips. After
 * update the cards recompute from the live book (including fills that
 * rolled into the sync tail / retained history). Archived fills stay
 * on their own card.
 *
 * Entry ask / contracts / stake / fee on a row:
 *  1. Prefer a matched paper fill (its stored pnlUsd).
 *  2. Else the row counts in W-L at $0. A separate hypothetical line
 *     may show per-contract `(won ? 1−ask : −ask)` for asks inside
 *     2–98¢. That line is not Paper P&L.
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
        val pnlUsd: Double,
        val note: String? = null
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
        val pickSource: String? = null,
        val aiConfidence: Double? = null,
        val confidenceLegacy: Boolean = false,
        val kellyF: Double? = null,
        val kellyFraction: Double? = null,
        val bankrollAfterUsd: Double? = null,
        val evUsd: Double? = null
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
        val bySource: List<Bucket> = emptyList(),
        val picks: List<PickRow>,
        val cumulativePnl: List<Pair<Long, Double>>,
        val openCount: Int,
        val voidCount: Int,
        /** Sum of per-contract hypotheticals for log rows with no fill. Not Paper P&L. */
        val hypotheticalPerContractUsd: Double = 0.0,
        val hypotheticalPicks: Int = 0
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
    const val UNKNOWN_CONF_NOTE = "logged before the app saved confidence"
    const val UNKNOWN_SIDE_LABEL = "Unknown side"
    const val SOURCE_TITLE = "By source"
    const val ENTRY_NOT_RECORDED = "entry not recorded"

    /**
     * Same $10 all-in clip as [com.dirk.kalshiodds.ui.HomeCopy.TILE_STAKE_USD]
     * / `tenDollarWins`. Live Approve stays the $5 cap.
     */
    const val PAPER_STAKE_USD = 10.0

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
        val uniqueFills = fills
            .groupBy { it.id }
            .map { (_, rows) -> rows.maxBy { it.syncAtMs() } }
        val btcEntries = entries.filter { isScorecardTicker(it.ticker) }
        val scored = btcEntries.filter {
            (it.outcome.equals("yes", true) || it.outcome.equals("no", true)) &&
                ForecastUnits.isScoredPick(it)
        }
        val noBet = btcEntries.filter {
            (it.outcome.equals("yes", true) || it.outcome.equals("no", true)) &&
                SignalStance.isNoBetSide(it.predictedSide)
        }
        val settledFills = uniqueFills.filter {
            isScorecardTicker(it.ticker) && it.settled && it.won != null
        }
        val (hypUsd, hypN) = hypotheticalPerContract(scored, settledFills.filter { !isLastMinuteSource(it) })
        val scoredFills = settledFills.filter { !isLastMinuteSource(it) }
        val aiFills = scoredFills.filter { isAiSource(it.source) }
        val manualFills = scoredFills.filter { !isAiSource(it.source) }
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

        val ai = record(aiPicks).copy(money = moneyOf(aiFills))
        val manual = record(manualRows).copy(money = moneyOf(manualFills))
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
            noBetWouldHave = record(noBetRows),
            bySide = sideBuckets(allMoneyRows),
            byPrice = priceBuckets(allMoneyRows),
            byTime = timeBuckets(allMoneyRows, zoneId),
            byConfidence = confidenceBuckets(allMoneyRows),
            bySource = sourceBuckets(allMoneyRows),
            picks = displayPicks,
            cumulativePnl = cumulative(allMoneyRows),
            openCount = btcEntries.count { it.outcome == null },
            voidCount = btcEntries.count { it.outcome.equals("void", true) },
            hypotheticalPerContractUsd = hypUsd,
            hypotheticalPicks = hypN
        )
    }

    fun isAiSource(source: String?): Boolean {
        val s = source?.trim()?.lowercase(Locale.US) ?: return false
        if (s == PaperTileBuy.SOURCE) return false
        if (s.startsWith("tile")) return false
        if (s.contains("manual")) return false
        if (PaperPickSource.parse(s) == PaperPickSource.LAST_MINUTE) return false
        if (PaperPickSource.parse(s) == PaperPickSource.MANUAL) return false
        return s.startsWith("ai") || s.contains("ai hunter") || s.contains("ai ticket") ||
            s.contains("ai signal") || s.contains("autopilot") ||
            s.contains("hunter") && !s.contains("paper buy") ||
            PaperPickSource.parse(s) == PaperPickSource.TICKET ||
            PaperPickSource.parse(s) == PaperPickSource.LONG_SHOT ||
            PaperPickSource.parse(s) == PaperPickSource.AI_ALERT ||
            PaperPickSource.parse(s) == PaperPickSource.AUTOPILOT
    }

    fun isLastMinuteSource(fill: PaperFill): Boolean =
        PaperPickSource.of(fill) == PaperPickSource.LAST_MINUTE

    fun isLastMinuteSource(source: String?, pickSource: String? = null): Boolean =
        PaperPickSource.of(source, pickSource) == PaperPickSource.LAST_MINUTE

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
    /**
     * Ask usable for a paper clip or a per-contract hypothetical.
     * Below [ProbabilityClamp.LO] (2¢) or above [ProbabilityClamp.HI]
     * (98¢) is an unknown price — including Kalshi's 0.1¢ tick, which
     * used to size thousands of contracts under a $10 clip.
     */
    fun scoreAsk(ask: Double?): Double? {
        val px = KalshiPrice.usable(ask) ?: return null
        if (px + 1e-12 < ProbabilityClamp.LO || px - 1e-12 > ProbabilityClamp.HI) return null
        return px
    }

    fun paperClipFromAsk(ask: Double?): LiveOrderSizer.Clip? {
        val px = scoreAsk(ask) ?: return null
        val clip = LiveOrderSizer.size(px, PAPER_STAKE_USD)
        return clip.takeIf { it.ok }
    }

    /** Per-contract hypothetical: won ? (1 − ask) : −ask. Not a paper fill. */
    fun hypotheticalPerContract(won: Boolean, ask: Double): Double =
        if (won) 1.0 - ask else -ask

    fun captureEntryFromBook(
        sideYes: Boolean,
        yesAsk: Double?,
        noAsk: Double?,
        yesBid: Double? = null
    ): StoredEntry {
        val ask = if (sideYes) {
            scoreAsk(yesAsk)
        } else {
            scoreAsk(noAsk) ?: yesBid?.let { scoreAsk(1.0 - it) }
        }
        val clip = paperClipFromAsk(ask)
        if (clip == null) return StoredEntry(null, null, null, null)
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
        moneyFromPnls(rows.map { it.pnlUsd ?: 0.0 })

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
        val sized = sizePick(entry, fill, won)
        val entryAi = ForecastUnits.sideProbability01(entry) * 100.0
        val entryMkt = ForecastUnits.probability01(entry.marketMid) * 100.0
        val aiPct = fill?.aiPct ?: entryAi
        return PickRow(
            kind = kind,
            ticker = entry.ticker,
            side = side,
            displaySide = displaySide(side),
            windowEt = WindowLabel.of(entry.ticker, entry.closeTimeMs ?: at),
            settledAtMs = at,
            entryAsk = sized.entryAsk,
            aiPct = aiPct,
            marketPct = fill?.marketPct ?: entryMkt,
            stakeUsd = sized.stakeUsd,
            contracts = sized.contracts,
            feeUsd = sized.feeUsd,
            won = won,
            pnlUsd = sized.pnlUsd,
            strikeUsd = window?.strikeUsd,
            finalUsd = null,
            source = fill?.pickSource ?: fill?.source ?: if (noBetWouldHave) "NO BET would-have" else "AI pick",
            noBetWouldHave = noBetWouldHave,
            entryNotRecorded = sized.entryNotRecorded,
            pickSource = fill?.pickSource ?: fill?.let { PaperPickSource.of(it)?.label }
                ?: if (noBetWouldHave) null else PaperPickSource.TICKET.label,
            aiConfidence = fill?.aiConfidence ?: entry.confidence,
            confidenceLegacy = false,
            kellyF = fill?.kellyF,
            kellyFraction = fill?.kellyFraction,
            bankrollAfterUsd = fill?.bankrollAfterUsd,
            evUsd = fill?.evUsd
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
        val storedAi = fill.aiPct
        val lookupAi = entry?.let { ForecastUnits.sideProbability01(it) * 100.0 }
        val aiPct = storedAi ?: lookupAi
        val storedMkt = fill.marketPct
        val lookupMkt = entry?.let { ForecastUnits.probability01(it.marketMid) * 100.0 }
        val sourceKind = PaperPickSource.of(fill)
        return PickRow(
            kind = kind,
            ticker = fill.ticker,
            side = fill.side,
            displaySide = displaySide(fill.side),
            windowEt = WindowLabel.of(fill.ticker, fill.createdAtMs),
            settledAtMs = fill.createdAtMs,
            entryAsk = ask,
            aiPct = aiPct,
            marketPct = storedMkt ?: lookupMkt,
            stakeUsd = fill.stakeUsd,
            contracts = fill.contracts.takeIf { it > 0 },
            feeUsd = feeUsd(fill),
            won = won,
            pnlUsd = fill.pnlUsd ?: 0.0,
            strikeUsd = window?.strikeUsd,
            finalUsd = null,
            source = fill.pickSource ?: fill.source,
            entryNotRecorded = ask == null,
            pickSource = fill.pickSource ?: sourceKind?.label,
            aiConfidence = fill.aiConfidence ?: entry?.confidence,
            confidenceLegacy = aiPct == null,
            kellyF = fill.kellyF,
            kellyFraction = fill.kellyFraction,
            bankrollAfterUsd = fill.bankrollAfterUsd,
            evUsd = fill.evUsd
        )
    }

    private data class SizedPick(
        val entryAsk: Double?,
        val contracts: Int?,
        val stakeUsd: Double?,
        val feeUsd: Double?,
        val pnlUsd: Double,
        val entryNotRecorded: Boolean
    )

    /**
     * Dollars come from a matched paper fill only. A log row with no
     * fill counts in W-L at $0 — never a hypothetical $10 clip.
     * [won] is unused for dollars; the hypothetical line uses it separately.
     */
    private fun sizePick(
        entry: PredictionLogEntry,
        fill: PaperFill?,
        @Suppress("UNUSED_PARAMETER") won: Boolean
    ): SizedPick {
        if (fill == null) {
            val ask = scoreAsk(entry.entryAsk)
            return SizedPick(
                entryAsk = ask,
                contracts = null,
                stakeUsd = null,
                feeUsd = null,
                pnlUsd = 0.0,
                entryNotRecorded = ask == null
            )
        }
        val ask = fill.limitPrice.takeIf { it > 0.0 }
        return SizedPick(
            entryAsk = ask,
            contracts = fill.contracts.takeIf { it > 0 },
            stakeUsd = fill.stakeUsd,
            feeUsd = feeUsd(fill),
            pnlUsd = fill.pnlUsd ?: 0.0,
            entryNotRecorded = ask == null
        )
    }

    private fun hypotheticalPerContract(
        entries: List<PredictionLogEntry>,
        fills: List<PaperFill>
    ): Pair<Double, Int> {
        val pool = fills.toMutableList()
        var sum = 0.0
        var n = 0
        for (entry in entries) {
            if (takeMatchingFill(pool, entry) != null) continue
            val ask = scoreAsk(entry.entryAsk) ?: continue
            sum += hypotheticalPerContract(ForecastUnits.hit(entry), ask)
            n += 1
        }
        return sum to n
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
            bucket(
                UNKNOWN_KEY,
                UNKNOWN_CONF_LABEL,
                groups[null].orEmpty(),
                note = UNKNOWN_CONF_NOTE.takeIf { groups[null].orEmpty().isNotEmpty() }
            )
    }

    private fun sourceBuckets(rows: List<PickRow>): List<Bucket> {
        val groups = rows.groupBy { PaperPickSource.of(it.source, it.pickSource) }
        val known = PaperPickSource.SCORECARD_ORDER.map { src ->
            bucket(src.name.lowercase(Locale.US), src.label, groups[src].orEmpty())
        }
        val unknown = rows.filter { PaperPickSource.of(it.source, it.pickSource) == null }
        return known + bucket(UNKNOWN_KEY, "Unknown source", unknown)
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

    private fun bucket(
        key: String,
        label: String,
        rows: List<PickRow>,
        note: String? = null
    ): Bucket {
        val wins = rows.count { it.won }
        val n = rows.size
        return Bucket(
            key = key,
            label = label,
            wins = wins,
            losses = (n - wins).coerceAtLeast(0),
            settledCount = n,
            hitRate = rate(wins, n),
            pnlUsd = rows.sumOf { it.pnlUsd ?: 0.0 },
            note = note
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
