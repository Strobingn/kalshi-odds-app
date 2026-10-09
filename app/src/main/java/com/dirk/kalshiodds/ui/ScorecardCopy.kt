package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.data.local.archive.SettledWindowRow
import com.dirk.kalshiodds.prediction.PredictionLogEntry
import com.dirk.kalshiodds.signal.feedback.ForecastUnits
import com.dirk.kalshiodds.signal.feedback.ScorecardLedger
import com.dirk.kalshiodds.signal.feedback.ScorecardMetrics
import com.dirk.kalshiodds.signal.model.SignalStance
import com.dirk.kalshiodds.signal.paper.PaperBookState
import com.dirk.kalshiodds.signal.paper.PaperFill
import com.dirk.kalshiodds.signal.paper.PaperPickSource
import java.time.ZoneId
import java.util.Locale

/**
 * Full-scorecard presentation. Counts, empty state, and every section
 * line come from [ScorecardLedger] (settled prediction-log rows +
 * settled paper fills). Empty-state copy is shown only when that
 * combined settled list is empty.
 */
object ScorecardCopy {
    const val TITLE = "Scorecard"
    const val SUBTITLE = "Post-settlement paper record from stored settled KXBTC15M rows. Voids are excluded from W-L. Paper dollars are settled fills in the current book only. A log row with no fill counts in W-L at $0."
    const val ARCHIVE_TITLE = "Pre-reset archive (before 0.3.28 reset)"
    const val RECONCILE_WARN = "Paper P&L does not match the bankroll (off by more than 1¢)."
    const val HYPOTHETICAL_LABEL = "Hypothetical (per 1 contract)"
    const val NO_SETTLED = HomeScorecardSummary.NO_SETTLED
    const val TIME_TITLE = "By time of day"
    const val SIDE_TITLE = "By side"
    const val PRICE_TITLE = "By entry price"
    const val CONF_TITLE = "By AI confidence"
    const val SOURCE_TITLE = ScorecardLedger.SOURCE_TITLE
    const val UNKNOWN_CONF_NOTE = ScorecardLedger.UNKNOWN_CONF_NOTE
    const val AI_TITLE = "AI picks"
    const val MANUAL_TITLE = "Manual Paper UP/DOWN"
    const val AUTOPILOT_TITLE = "AI paper autopilot"
    const val AUTOPILOT_SUBTITLE =
        "Paper bankroll and settled P&L are the score. Hit rate is secondary. " +
            "Autopilot edge fills are separate from prediction-log favorites. " +
            "Regime slices use tags stored on each fill. No fitted holdout is stored."
    const val LAST_MINUTE_TITLE = "Last-minute strategy (retired — history only)"
    const val LAST_MINUTE_SUBTITLE =
        com.dirk.kalshiodds.signal.lastminute.LastMinuteCopy.UNPROVEN_SUBTITLE
    const val D3_TITLE = "D3 daily favourite"
    const val D3_SUBTITLE = com.dirk.kalshiodds.signal.d3.D3Copy.EVIDENCE
    const val COMBINED_TITLE = "Combined"
    const val NO_BET_TITLE = "NO BET would-have-been"
    const val PICKS_TITLE = "Every settled pick"
    const val PNL_TITLE = "Running P&L"
    const val WON = "WIN"
    const val LOST = "LOSS"
    const val EM_DASH = "—"
    const val PICKED_SIDE_DIRECTION_NOTE =
        "Direction only, not money: favorites cost 70-90¢, so a high hit rate can still lose"
    val ET_ZONE: ZoneId = ScorecardLedger.ET_ZONE

    fun hypotheticalPolicyTitle(stakeUsd: Double): String =
        String.format(
            Locale.US,
            "Hypothetical: every alert @ $%.0f at mid price, NO fees, not real money",
            stakeUsd
        )

    fun windowRealPnlLine(pnlUsd: Double?): String? =
        pnlUsd?.let { "Real paper P&L ${ScorecardLedger.signedUsd(it)}" }

    /**
     * Break-even hit rate given average win and average loss.
     * Uses magnitudes so [ScorecardLedger.Money.avgLossUsd] (signed
     * negative) and positive loss sizes both work:
     * `|avgLoss| / (|avgWin| + |avgLoss|)`.
     */
    fun breakEvenWinRate(avgWinUsd: Double?, avgLossUsd: Double?): Double? {
        val win = avgWinUsd?.let { kotlin.math.abs(it) } ?: return null
        val loss = avgLossUsd?.let { kotlin.math.abs(it) } ?: return null
        val den = win + loss
        if (den <= 1e-12) return null
        return loss / den
    }

    fun breakEvenWinRateLine(avgWinUsd: Double?, avgLossUsd: Double?): String? {
        val rate = breakEvenWinRate(avgWinUsd, avgLossUsd) ?: return null
        return String.format(Locale.US, "Break-even win rate at your avg win/loss: %.0f%%", rate * 100.0)
    }

    fun breakEvenWinRateLine(money: ScorecardLedger.Money): String? =
        breakEvenWinRateLine(money.avgWinUsd, money.avgLossUsd)

    data class Bucket(
        val key: String,
        val label: String,
        val wins: Int,
        val losses: Int,
        val settledCount: Int,
        val hitRate: Double?,
        val pnlUsd: Double = 0.0,
        val line: String,
        val note: String? = null
    )

    data class AutopilotBet(
        val ticker: String,
        val side: String,
        val displaySide: String,
        val createdAtMs: Long,
        val entryAsk: Double?,
        val aiPct: Double?,
        val evUsd: Double?,
        val stakeUsd: Double,
        val feeUsd: Double?,
        val result: String,
        val line: String
    )

    data class AutopilotSection(
        val bets: List<AutopilotBet> = emptyList(),
        val openCount: Int = 0,
        val settledCount: Int = 0,
        val wins: Int = 0,
        val losses: Int = 0,
        val pnlUsd: Double = 0.0,
        val record: String = EM_DASH,
        val bankrollLabel: String = EM_DASH,
        val pnlLabel: String = EM_DASH,
        val credibility: String = "",
        val favoriteLogLine: String = "",
        val edgeFillLine: String = "",
        val laterSliceLine: String = "",
        val regimeLines: List<String> = emptyList(),
        val shadowLine: String = ""
    )

    data class LastMinuteSection(
        val wins: Int = 0,
        val losses: Int = 0,
        val winRate: Double? = null,
        val wonUsd: Double = 0.0,
        val lostUsd: Double = 0.0,
        val pnlUsd: Double = 0.0,
        val settledCount: Int = 0,
        val picks: List<com.dirk.kalshiodds.signal.lastminute.LastMinutePick> = emptyList(),
        val record: String = EM_DASH
    )

    data class RecentPick(
        val ticker: String,
        val coin: String,
        val side: String,
        val won: Boolean,
        val settledAtMs: Long,
        val line: String,
        val row: ScorecardLedger.PickRow? = null
    )

    data class View(
        val summary: HomeScorecardSummary,
        val timeOfDay: List<Bucket>,
        val recent: List<RecentPick>,
        val ledger: ScorecardLedger.Snapshot,
        val bySide: List<Bucket>,
        val byPrice: List<Bucket>,
        val byConfidence: List<Bucket>,
        val bySource: List<Bucket> = emptyList(),
        val lastMinute: LastMinuteSection = LastMinuteSection(),
        val d3: D3Section = D3Section(),
        val autopilot: AutopilotSection = AutopilotSection(),
        val paperBankrollUsd: Double? = null,
        val reconcileWarning: String? = null,
        val hypotheticalLine: String? = null,
        val archive: ArchiveSection = ArchiveSection()
    ) {
        val settledCount: Int get() = ledger.combined.settledCount
        val showsEmptyState: Boolean get() = showsEmptyState(settledCount)
        fun emptyState(): String? = emptyState(settledCount)

        fun allLines(recentExpanded: Boolean = true): List<String> {
            val lines = mutableListOf(SUBTITLE)
            emptyState()?.let { lines += it }
            lines += LAST_MINUTE_TITLE
            lines += LAST_MINUTE_SUBTITLE
            lines += lastMinute.record
            if (lastMinute.settledCount > 0) {
                lines += com.dirk.kalshiodds.signal.lastminute.LastMinuteCopy.wonUsdLine(lastMinute.wonUsd)
                lines += com.dirk.kalshiodds.signal.lastminute.LastMinuteCopy.lostUsdLine(lastMinute.lostUsd)
                lines += com.dirk.kalshiodds.signal.lastminute.LastMinuteCopy.netPnlLine(lastMinute.pnlUsd)
            }
            if (lastMinute.picks.isNotEmpty()) {
                lines += lastMinute.picks.map { com.dirk.kalshiodds.signal.lastminute.LastMinuteCopy.pickLine(it) }
            }
            lines += D3_TITLE
            lines += D3_SUBTITLE
            lines += d3.record
            if (d3.picks.isNotEmpty()) {
                lines += com.dirk.kalshiodds.signal.d3.D3Copy.wonUsdLine(d3.wonUsd)
                lines += com.dirk.kalshiodds.signal.d3.D3Copy.lostUsdLine(d3.lostUsd)
                lines += com.dirk.kalshiodds.signal.d3.D3Copy.netPnlLine(d3.pnlUsd)
                lines += com.dirk.kalshiodds.signal.d3.D3Copy.fillRateLine(d3.fillRate)
                lines += d3.picks.map { com.dirk.kalshiodds.signal.d3.D3Copy.pickLine(it) }
            }
            lines += AUTOPILOT_TITLE
            lines += AUTOPILOT_SUBTITLE
            lines += autopilot.bankrollLabel
            lines += autopilot.pnlLabel
            lines += autopilot.record
            if (autopilot.credibility.isNotBlank()) lines += autopilot.credibility
            if (autopilot.favoriteLogLine.isNotBlank()) lines += autopilot.favoriteLogLine
            if (autopilot.edgeFillLine.isNotBlank()) lines += autopilot.edgeFillLine
            if (autopilot.laterSliceLine.isNotBlank()) lines += autopilot.laterSliceLine
            if (autopilot.regimeLines.isNotEmpty()) lines += autopilot.regimeLines
            if (autopilot.shadowLine.isNotBlank()) lines += autopilot.shadowLine
            if (autopilot.bets.isNotEmpty()) {
                lines += autopilot.bets.map { it.line }
            }
            if (archive.fillCount > 0) {
                lines += ARCHIVE_TITLE
                lines += archive.line
            }
            if (!showsEmptyState) {
                lines += recordLine(summary)
                lines += winRateLine(summary)
                lines += paperPnlLine(summary)
                paperBankrollLine(paperBankrollUsd)?.let { lines += it }
                reconcileWarning?.let { lines += it }
                hypotheticalLine?.let { lines += it }
                lines += settledCountLine(summary)
                lines += COMBINED_TITLE
                lines += recordLine(ledger.combined)
                lines += AI_TITLE
                lines += recordLine(ledger.ai)
                breakEvenWinRateLine(ledger.ai.money)?.let { lines += it }
                lines += MANUAL_TITLE
                lines += recordLine(ledger.manual)
            }
            lines += SIDE_TITLE
            lines += bySide.map { it.line }
            lines += PRICE_TITLE
            lines += byPrice.map { it.line }
            lines += TIME_TITLE
            lines += timeOfDay.map { it.line }
            lines += CONF_TITLE
            lines += byConfidence.map { it.line }
            byConfidence.filter { it.note != null }.forEach { lines += it.note!! }
            lines += SOURCE_TITLE
            lines += bySource.map { it.line }
            if (recent.isNotEmpty()) {
                lines += PICKS_TITLE
                if (recentExpanded) lines += recent.map { it.line }
            }
            return lines
        }
    }

    val EMPTY: View = of(emptyList(), 0.0)

    fun settledPicks(entries: List<PredictionLogEntry>): List<PredictionLogEntry> =
        ScorecardMetrics.settledScoredPicks(entries)
            .filter { ScorecardLedger.isScorecardTicker(it.ticker) }

    fun showsEmptyState(settledCount: Int): Boolean = settledCount <= 0

    fun emptyState(settledCount: Int): String? =
        if (showsEmptyState(settledCount)) NO_SETTLED else null

    fun of(
        entries: List<PredictionLogEntry>,
        paperPnlUsd: Double,
        zoneId: ZoneId = ET_ZONE
    ): View = of(
        entries = entries,
        fills = emptyList(),
        paperPnlUsd = paperPnlUsd,
        windows = emptyList(),
        zoneId = zoneId
    )

    fun of(
        entries: List<PredictionLogEntry>,
        paper: PaperBookState,
        windows: List<SettledWindowRow> = emptyList(),
        zoneId: ZoneId = ET_ZONE,
        lastMinutePicks: List<com.dirk.kalshiodds.signal.lastminute.LastMinutePick> = emptyList(),
        d3Picks: List<com.dirk.kalshiodds.signal.d3.D3Pick> = emptyList(),
        shadow: com.dirk.kalshiodds.signal.paper.ShadowBookState? = null
    ): View = of(
        entries = entries,
        fills = paper.scorecardFills(),
        paperPnlUsd = paper.realizedPnlUsd,
        windows = windows,
        zoneId = zoneId,
        lastMinutePicks = lastMinutePicks,
        d3Picks = d3Picks,
        paperBankrollUsd = paper.paperBankrollUsd,
        startingUsd = paper.startingUsd,
        archivedFills = paper.archivedFills(),
        shadow = shadow
    )

    fun of(
        entries: List<PredictionLogEntry>,
        fills: List<PaperFill>,
        paperPnlUsd: Double,
        windows: List<SettledWindowRow> = emptyList(),
        zoneId: ZoneId = ET_ZONE,
        lastMinutePicks: List<com.dirk.kalshiodds.signal.lastminute.LastMinutePick> = emptyList(),
        d3Picks: List<com.dirk.kalshiodds.signal.d3.D3Pick> = emptyList(),
        paperBankrollUsd: Double? = null,
        startingUsd: Double? = null,
        archivedFills: List<PaperFill> = emptyList(),
        shadow: com.dirk.kalshiodds.signal.paper.ShadowBookState? = null
    ): View {
        val ledger = ScorecardLedger.of(entries, fills, windows, zoneId)
        val lastMinute = lastMinuteSection(lastMinutePicks)
        val d3 = d3Section(d3Picks)
        val summary = HomeScorecardSummary(
            wins = ledger.combined.wins,
            losses = ledger.combined.losses,
            hitRate = ledger.combined.hitRate,
            paperPnlUsd = if (fills.isEmpty()) paperPnlUsd else ledger.combined.money.pnlUsd,
            settledCount = ledger.combined.settledCount
        )
        return View(
            summary = summary,
            timeOfDay = ledger.byTime.map { toBucket(it) },
            recent = ledger.picks.filter { !it.noBetWouldHave }.map { recentPick(it) },
            ledger = ledger,
            bySide = ledger.bySide.map { toBucket(it) },
            byPrice = ledger.byPrice.map { toBucket(it) },
            byConfidence = ledger.byConfidence.map { toBucket(it) },
            bySource = mergeSourceBuckets(ledger.bySource.map { toBucket(it) }, lastMinute),
            lastMinute = lastMinute,
            d3 = d3,
            autopilot = autopilotSection(fills, entries, paperBankrollUsd, shadow),
            paperBankrollUsd = paperBankrollUsd,
            reconcileWarning = reconcileWarning(ledger.combined.money.pnlUsd, paperBankrollUsd, startingUsd),
            hypotheticalLine = hypotheticalLine(ledger.hypotheticalPerContractUsd, ledger.hypotheticalPicks),
            archive = archiveSection(archivedFills)
        )
    }

    data class ArchiveSection(
        val wins: Int = 0,
        val losses: Int = 0,
        val pnlUsd: Double = 0.0,
        val settledCount: Int = 0,
        val fillCount: Int = 0,
        val line: String = EM_DASH
    )

    fun reconcileWarning(combinedPnlUsd: Double, bankrollUsd: Double?, startingUsd: Double?): String? {
        if (bankrollUsd == null || startingUsd == null) return null
        if (!bankrollUsd.isFinite() || !startingUsd.isFinite()) return null
        val delta = bankrollUsd - startingUsd
        return if (kotlin.math.abs(combinedPnlUsd - delta) > 0.01) RECONCILE_WARN else null
    }

    fun hypotheticalLine(usd: Double, picks: Int): String? {
        if (picks <= 0) return null
        return "$HYPOTHETICAL_LABEL ${ScorecardLedger.signedUsd(usd)} · $picks picks, not in Paper P&L"
    }

    fun logCountLine(open: Int, voidCount: Int, settled: Int): String =
        "Log: open $open · void $voidCount · settled $settled"

    fun archiveSection(fills: List<PaperFill>): ArchiveSection {
        if (fills.isEmpty()) return ArchiveSection()
        val ledger = ScorecardLedger.of(emptyList(), fills)
        val record = ledger.combined
        val pnl = record.money.pnlUsd
        val line = "${record.wins}-${record.losses} · ${ScorecardLedger.signedUsd(pnl)} · ${fills.size} fills"
        return ArchiveSection(
            wins = record.wins,
            losses = record.losses,
            pnlUsd = pnl,
            settledCount = record.settledCount,
            fillCount = fills.size,
            line = line
        )
    }

    data class D3Section(
        val wins: Int = 0,
        val losses: Int = 0,
        val wonUsd: Double = 0.0,
        val lostUsd: Double = 0.0,
        val pnlUsd: Double = 0.0,
        val fillRate: Double? = null,
        val settledCount: Int = 0,
        val picks: List<com.dirk.kalshiodds.signal.d3.D3Pick> = emptyList(),
        val record: String = EM_DASH
    )

    fun d3Section(picks: List<com.dirk.kalshiodds.signal.d3.D3Pick>): D3Section {
        val attempted = picks
        val filled = picks.filter { it.filled }
        val settled = filled.filter { it.settled && it.won != null }
        val wins = settled.count { it.won == true }
        val losses = settled.count { it.won == false }
        val wonUsd = settled.filter { (it.pnlUsd ?: 0.0) > 0.0 }.sumOf { it.pnlUsd ?: 0.0 }
        val lostUsd = settled.filter { (it.pnlUsd ?: 0.0) <= 0.0 }.sumOf { -(it.pnlUsd ?: 0.0) }
        val pnl = settled.sumOf { it.pnlUsd ?: 0.0 }
        val fillRate = if (attempted.isEmpty()) null else filled.size.toDouble() / attempted.size
        return D3Section(
            wins = wins,
            losses = losses,
            wonUsd = wonUsd,
            lostUsd = lostUsd,
            pnlUsd = pnl,
            fillRate = fillRate,
            settledCount = settled.size,
            picks = picks,
            record = if (attempted.isEmpty()) {
                EM_DASH
            } else {
                com.dirk.kalshiodds.signal.d3.D3Copy.recordLine(
                    wins, losses, wonUsd, lostUsd, pnl, fillRate, attempted.size
                )
            }
        )
    }

    fun autopilotSection(
        fills: List<PaperFill>,
        entries: List<PredictionLogEntry> = emptyList(),
        bankrollUsd: Double? = null,
        shadow: com.dirk.kalshiodds.signal.paper.ShadowBookState? = null
    ): AutopilotSection {
        val report = com.dirk.kalshiodds.signal.paper.AutopilotScorecard.of(fills, entries, bankrollUsd)
        val bets = fills
            .filter { com.dirk.kalshiodds.domain.CryptoMarkets.isAutopilotTicker(it.ticker) }
            .filter { com.dirk.kalshiodds.signal.paper.AutopilotScorecard.isAutopilotEdge(it) }
            .sortedByDescending { it.createdAtMs }
            .map { autopilotBet(it) }
        val settled = fills.filter {
            com.dirk.kalshiodds.domain.CryptoMarkets.isAutopilotTicker(it.ticker) &&
                it.settled &&
                it.won != null &&
                com.dirk.kalshiodds.signal.paper.AutopilotScorecard.isAutopilotEdge(it)
        }
        val wins = settled.count { it.won == true }
        val losses = settled.count { it.won == false }
        val pnl = settled.sumOf { it.pnlUsd ?: 0.0 }
        val rate = if (settled.isEmpty()) null else wins.toDouble() / settled.size
        val shadowLine = shadow?.let { book ->
            val booked = book.tickets.count { it.booked }
            val unfilled = book.tickets.count { !it.booked }
            String.format(
                Locale.US,
                "Shadow bankroll $%.2f · shadow P&L %s · %d would-fill · %d unfilled · SHADOW — not submitted",
                book.bankrollUsd,
                ScorecardLedger.signedUsd(book.lifetimeRealizedPnlUsd),
                booked,
                unfilled
            )
        }.orEmpty()
        return AutopilotSection(
            bets = bets,
            openCount = bets.count { it.result == "OPEN" },
            settledCount = settled.size,
            wins = wins,
            losses = losses,
            pnlUsd = pnl,
            record = if (settled.isEmpty()) {
                EM_DASH
            } else {
                "${report.pnlLabel} · ${wins}-${losses} · ${percentOrDash(rate)} · ${settled.size} settled"
            },
            bankrollLabel = report.bankrollLabel,
            pnlLabel = report.pnlLabel,
            credibility = report.credibility,
            favoriteLogLine = report.favoriteLogLine,
            edgeFillLine = report.edgeFillLine,
            laterSliceLine = report.laterSliceLine,
            regimeLines = report.regimeLines,
            shadowLine = shadowLine
        )
    }

    fun autopilotBet(fill: PaperFill): AutopilotBet {
        val result = when {
            !fill.settled -> "OPEN"
            fill.outcome == "void" -> "void"
            fill.won == true -> WON
            else -> LOST
        }
        val side = if (fill.side.equals("NO", true)) "DOWN" else "UP"
        val fee = ScorecardLedger.feeUsd(fill)
        val time = WindowLabel.of(fill.ticker, fill.createdAtMs)
        val ask = fill.limitPrice.takeIf { it > 0.0 }?.let {
            String.format(Locale.US, "%.0f¢", it * 100.0)
        } ?: EM_DASH
        val ai = fill.aiPct?.let { String.format(Locale.US, "AI %.0f%%", it) } ?: "AI $EM_DASH"
        val ev = fill.evUsd?.let { String.format(Locale.US, "EV $%.2f", it) } ?: "EV $EM_DASH"
        val stake = String.format(Locale.US, "stake $%.2f", fill.stakeUsd)
        val feeLine = fee?.let { String.format(Locale.US, "fee $%.2f", it) } ?: "fee $EM_DASH"
        val pnl = fill.pnlUsd?.let { ScorecardLedger.signedUsd(it) } ?: EM_DASH
        val regime = listOfNotNull(fill.regimePath, fill.regimeRole, fill.regimeVol, fill.regimeSession, fill.regimeStrike)
            .joinToString(" · ")
        val regimeBit = if (regime.isBlank()) "" else "  $regime"
        return AutopilotBet(
            ticker = fill.ticker,
            side = fill.side,
            displaySide = side,
            createdAtMs = fill.createdAtMs,
            entryAsk = fill.limitPrice.takeIf { it > 0.0 },
            aiPct = fill.aiPct,
            evUsd = fill.evUsd,
            stakeUsd = fill.stakeUsd,
            feeUsd = fee,
            result = result,
            line = "$time  $side  $ask  $ai  $ev  $stake  $feeLine  $result  $pnl  ${fill.ticker}$regimeBit"
        )
    }

    fun lastMinuteSection(
        picks: List<com.dirk.kalshiodds.signal.lastminute.LastMinutePick>
    ): LastMinuteSection {
        val settled = picks.filter { it.settled && it.won != null }
        val wins = settled.count { it.won == true }
        val losses = settled.count { it.won == false }
        val wonUsd = settled.filter { (it.pnlUsd ?: 0.0) > 0.0 }.sumOf { it.pnlUsd ?: 0.0 }
        val lostUsd = settled.filter { (it.pnlUsd ?: 0.0) <= 0.0 }.sumOf { -(it.pnlUsd ?: 0.0) }
        val pnl = settled.sumOf { it.pnlUsd ?: 0.0 }
        val rate = if (settled.isEmpty()) null else wins.toDouble() / settled.size
        return LastMinuteSection(
            wins = wins,
            losses = losses,
            winRate = rate,
            wonUsd = wonUsd,
            lostUsd = lostUsd,
            pnlUsd = pnl,
            settledCount = settled.size,
            picks = picks,
            record = if (settled.isEmpty()) EM_DASH else com.dirk.kalshiodds.signal.lastminute.LastMinuteCopy.recordLine(
                wins, losses, rate, wonUsd, lostUsd, pnl
            )
        )
    }

    fun timeBuckets(
        settled: List<PredictionLogEntry>,
        zoneId: ZoneId = ET_ZONE
    ): List<Bucket> {
        val defs = (0 until 6).map { i ->
            val start = i * 4
            val key = "%02d-%02d".format(start, start + 4)
            val label = "%d–%d ET".format(start, start + 4)
            key to label
        }
        val groups = settled.groupBy { ScorecardMetrics.etBucketKey(settledAt(it), zoneId) }
        return defs.map { (key, label) -> bucket(key, label, groups[key].orEmpty()) }
    }

    fun bucket(key: String, label: String, rows: List<PredictionLogEntry>): Bucket {
        val stats = ScorecardMetrics.window(rows)
        val losses = (stats.total - stats.hits).coerceAtLeast(0)
        return Bucket(
            key = key,
            label = label,
            wins = stats.hits,
            losses = losses,
            settledCount = stats.total,
            hitRate = stats.hitRate,
            line = bucketLine(label, stats.hits, losses, stats.total, stats.hitRate)
        )
    }

    fun bucketLine(
        label: String,
        wins: Int,
        losses: Int,
        settledCount: Int,
        hitRate: Double?,
        pnlUsd: Double? = null
    ): String {
        if (settledCount <= 0) return "$label  $EM_DASH"
        val money = pnlUsd?.let { " · ${ScorecardLedger.signedUsd(it)}" }.orEmpty()
        return "$label  $wins-$losses · ${percentOrDash(hitRate)} · $settledCount settled$money"
    }

    fun mergeSourceBuckets(
        ledger: List<Bucket>,
        lastMinute: LastMinuteSection
    ): List<Bucket> {
        if (lastMinute.settledCount <= 0) return ledger
        val key = PaperPickSource.LAST_MINUTE.name.lowercase(Locale.US)
        val existing = ledger.firstOrNull { it.key == key }
        if (existing != null && existing.settledCount > 0) return ledger
        val lm = Bucket(
            key = key,
            label = PaperPickSource.LAST_MINUTE.label,
            wins = lastMinute.wins,
            losses = lastMinute.losses,
            settledCount = lastMinute.settledCount,
            hitRate = lastMinute.winRate,
            pnlUsd = lastMinute.pnlUsd,
            line = bucketLine(
                PaperPickSource.LAST_MINUTE.label,
                lastMinute.wins,
                lastMinute.losses,
                lastMinute.settledCount,
                lastMinute.winRate,
                lastMinute.pnlUsd
            )
        )
        return ledger.map { if (it.key == key) lm else it }
    }

    fun recentPicks(settled: List<PredictionLogEntry>): List<RecentPick> =
        settled
            .sortedByDescending { settledAt(it) }
            .map { recentPick(it) }

    fun recentPick(entry: PredictionLogEntry): RecentPick {
        val coin = ScorecardMetrics.coinOf(entry.series.ifBlank { entry.ticker })
        val side = SignalCopy.callLabel(entry.predictedSide)
        val won = ForecastUnits.hit(entry)
        return RecentPick(
            ticker = entry.ticker,
            coin = coin,
            side = side,
            won = won,
            settledAtMs = settledAt(entry),
            line = recentPickLine(coin, side, won, entry.ticker)
        )
    }

    fun recentPick(row: ScorecardLedger.PickRow): RecentPick {
        val coin = ScorecardMetrics.coinOf(row.ticker)
        return RecentPick(
            ticker = row.ticker,
            coin = coin,
            side = row.displaySide,
            won = row.won,
            settledAtMs = row.settledAtMs,
            line = pickLine(row),
            row = row
        )
    }

    fun recentPickLine(coin: String, side: String, won: Boolean, ticker: String): String =
        "$coin  $side  ${if (won) WON else LOST}  $ticker"

    fun pickLine(row: ScorecardLedger.PickRow): String {
        val result = if (row.won) WON else LOST
        val ai = row.aiPct?.let { String.format(Locale.US, "AI %.0f%%", it) } ?: "AI $EM_DASH"
        val mkt = row.marketPct?.let { String.format(Locale.US, "mkt %.0f%%", it) } ?: "mkt $EM_DASH"
        val pnl = ScorecardLedger.signedUsd(row.pnlUsd)
        val strike = row.strikeUsd?.let { String.format(Locale.US, "strike $%,.0f", it) } ?: "strike $EM_DASH"
        val fin = row.finalUsd?.let { String.format(Locale.US, "final $%,.0f", it) } ?: "final $EM_DASH"
        val src = row.pickSource ?: row.source
        if (row.entryNotRecorded) {
            return "${row.windowEt}  ${row.displaySide}  ${ScorecardLedger.ENTRY_NOT_RECORDED}  $ai  $mkt  $result  $pnl  $strike  $fin  $src  ${row.ticker}"
        }
        val ask = row.entryAsk?.let { String.format(Locale.US, "%.0f¢", it * 100.0) } ?: EM_DASH
        val stake = row.stakeUsd?.let { String.format(Locale.US, "stake $%.2f", it) } ?: "stake $EM_DASH"
        val ct = row.contracts?.let { "$it ct" } ?: "$EM_DASH ct"
        val fee = row.feeUsd?.let { String.format(Locale.US, "fee $%.2f", it) } ?: "fee $EM_DASH"
        val kelly = row.kellyF?.let { String.format(Locale.US, "Kelly f=%.3f", it) }
            ?: row.kellyFraction?.let { String.format(Locale.US, "Kelly ×%.2f", it) }
            ?: "Kelly $EM_DASH"
        val ev = row.evUsd?.let { String.format(Locale.US, "EV $%.2f", it) } ?: "EV $EM_DASH"
        val bank = row.bankrollAfterUsd?.let { String.format(Locale.US, "bankroll $%.2f", it) }
            ?: "bankroll $EM_DASH"
        return "${row.windowEt}  ${row.displaySide}  $ask  $ai  $mkt  $ct  $stake  $fee  $ev  $kelly  $bank  $result  $pnl  $strike  $fin  $src  ${row.ticker}"
    }

    fun recordLine(summary: HomeScorecardSummary): String =
        if (summary.settledCount <= 0) EM_DASH else "${summary.wins}-${summary.losses}"

    fun recordLine(record: ScorecardLedger.Record): String =
        if (record.settledCount <= 0) EM_DASH else "${record.wins}-${record.losses} · ${percentOrDash(record.hitRate)} · streak ${record.streak}"

    fun winRateLine(summary: HomeScorecardSummary): String = percentOrDash(summary.hitRate)

    fun paperPnlLine(summary: HomeScorecardSummary): String =
        if (summary.settledCount <= 0) EM_DASH else HomeScorecardSummary.paperPnlPart(summary.paperPnlUsd)

    fun paperBankrollLine(bankrollUsd: Double?): String? =
        bankrollUsd?.takeIf { it.isFinite() }?.let {
            String.format(Locale.US, "Paper bankroll $%.2f", it)
        }

    fun settledCountLine(summary: HomeScorecardSummary): String =
        if (summary.settledCount <= 0) EM_DASH else "${summary.settledCount} settled"

    fun summaryLine(summary: HomeScorecardSummary): String = HomeCopy.scorecardSummaryLine(summary)

    fun percentOrDash(rate: Double?): String =
        rate?.let { String.format(Locale.US, "%.0f%%", it * 100.0) } ?: EM_DASH

    fun sideHeadline(side: String): com.dirk.kalshiodds.signal.trade.BetCall.Headline =
        when (side) {
            SignalStance.CALL_DOWN, "DOWN", "NO" -> com.dirk.kalshiodds.signal.trade.BetCall.Headline.BET_DOWN
            SignalStance.CALL_UP, "UP", "YES" -> com.dirk.kalshiodds.signal.trade.BetCall.Headline.BET_UP
            else -> com.dirk.kalshiodds.signal.trade.BetCall.Headline.NO_BET
        }

    fun resultColorIsWin(won: Boolean): Boolean = won

    private fun toBucket(b: ScorecardLedger.Bucket): Bucket = Bucket(
        key = b.key,
        label = b.label,
        wins = b.wins,
        losses = b.losses,
        settledCount = b.settledCount,
        hitRate = b.hitRate,
        pnlUsd = b.pnlUsd,
        line = bucketLine(b.label, b.wins, b.losses, b.settledCount, b.hitRate, b.pnlUsd),
        note = b.note
    )

    private fun settledAt(entry: PredictionLogEntry): Long = entry.settledAtMs ?: entry.timestampMs
}
