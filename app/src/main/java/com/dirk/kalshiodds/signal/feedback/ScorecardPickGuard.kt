package com.dirk.kalshiodds.signal.feedback

import com.dirk.kalshiodds.prediction.OpenPredictionMerge
import com.dirk.kalshiodds.prediction.PredictionLogEntry
import com.dirk.kalshiodds.signal.paper.PaperFill

/**
 * Settled scorecard rows that were never a real pick.
 *
 * A row is fake when it has no usable entry (ask outside 2–98¢), when a
 * win was stored with no stake, or when the market is the settlement
 * print (0% or 100%). Those rows stay out of W-L, win rate, and dollars.
 * Remote sync is not deleted; local writers refuse to create or re-import them.
 */
object ScorecardPickGuard {
    const val NO_DATA_YET = "no data yet"

    fun isSettlementMarketUnit(raw: Double?): Boolean {
        if (raw == null || !raw.isFinite()) return false
        val unit = if (raw > 1.0 + 1e-6) raw / 100.0 else raw
        return unit <= 1e-9 || unit >= 1.0 - 1e-9
    }

    /** Scorecard market percent is 0–100. 1.0 means 1%, not a settled book. */
    fun isSettlementMarketPct(pct: Double?): Boolean {
        if (pct == null || !pct.isFinite()) return false
        return pct <= 1e-6 || pct >= 100.0 - 1e-6
    }

    fun isFakeSettled(
        entryAsk: Double?,
        stakeUsd: Double?,
        won: Boolean,
        marketUnit: Double?,
        marketPct: Double?
    ): Boolean {
        if (ScorecardLedger.scoreAsk(entryAsk) == null) return true
        if (won && (stakeUsd == null || !stakeUsd.isFinite() || stakeUsd <= 1e-9)) return true
        if (isSettlementMarketUnit(marketUnit)) return true
        if (isSettlementMarketPct(marketPct)) return true
        return false
    }

    fun hideLog(entry: PredictionLogEntry): Boolean {
        if (entry.outcome == null) {
            if (isSettlementMarketUnit(entry.marketMid)) return true
            val noEntry = ScorecardLedger.scoreAsk(entry.entryAsk) == null
            return noEntry && OpenPredictionMerge.isAtOrAfterClose(entry.timestampMs, entry.closeTimeMs)
        }
        if (entry.outcome.equals("void", ignoreCase = true)) return false
        if (!entry.outcome.equals("yes", true) && !entry.outcome.equals("no", true)) return false
        return isFakeSettled(
            entryAsk = entry.entryAsk,
            stakeUsd = entry.stakeUsd,
            won = ForecastUnits.hit(entry),
            marketUnit = entry.marketMid,
            marketPct = null
        )
    }

    fun hideFill(fill: PaperFill): Boolean {
        if (fill.outcome.equals("void", ignoreCase = true)) return false
        val ask = fill.limitPrice.takeIf { it > 0.0 }
        if (!fill.settled || fill.won == null) {
            if (ScorecardLedger.scoreAsk(ask) == null) return true
            return isSettlementMarketPct(fill.marketPct)
        }
        return isFakeSettled(
            entryAsk = ask,
            stakeUsd = fill.stakeUsd,
            won = fill.won == true,
            marketUnit = null,
            marketPct = fill.marketPct
        )
    }

    fun shouldDropOnSettlement(entry: PredictionLogEntry, result: String): Boolean {
        if (result != "yes" && result != "no") return false
        val won = ForecastUnits.pickedSideIsYes(entry) == result.equals("yes", ignoreCase = true)
        return isFakeSettled(entry.entryAsk, entry.stakeUsd, won, entry.marketMid, null)
    }
}
