package com.dirk.kalshiodds.ui

import com.dirk.kalshiodds.domain.KalshiPrice
import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.domain.TimeLeft
import com.dirk.kalshiodds.signal.model.SignalStance
import com.dirk.kalshiodds.signal.trade.BetCall
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Restored home-card AI / model copy. Every line is formatted from
 * [MarketUiModel] / [BetCall] — never a placeholder string.
 *
 * First-seen versions (git tags v0.3.0-debug … v0.3.14-debug) are in
 * [RESTORED_FIELDS]. 0.3.12 moved most of this behind a collapsed
 * Details toggle; this object puts the dropped fields back.
 */
object HomeCardDetails {
    const val SECTION = "BITCOIN CLAUDE AI"
    const val EDGE_TITLE = "Mis Bitcoin edge"
    const val MARKET_REF = "Kalshi market (reference)"
    const val LIVE_BOOK = "LIVE BOOK · UP / DOWN"
    const val DETAILS = "Details"
    const val HIDE_DETAILS = "Hide details"
    const val FAIR_NOTE =
        "Fair − market (pp). Net EV subtracts Kalshi-style fee + half-spread. Analysis stays advisory; tickets need a separate Approve."
    const val UP_YES = "UP  YES"
    const val DOWN_NO = "DOWN  NO"

    data class RestoredField(
        val name: String,
        val firstSeen: String,
        val key: String
    )

    /**
     * Every AI / model / market field the BTC card has shown across
     * v0.3.0-debug … v0.3.14-debug. [key] matches [Snapshot.lines].
     */
    val RESTORED_FIELDS: List<RestoredField> = listOf(
        RestoredField("DIP HUNTER AI header", "v0.3.0-debug", "section"),
        RestoredField("AI YES / FV YES percent", "v0.3.0-debug", "aiYes"),
        RestoredField("AI NO / FV NO percent", "v0.3.0-debug", "aiNo"),
        RestoredField("Model reasons (aiNote)", "v0.3.0-debug", "reasons"),
        RestoredField("Confidence", "v0.3.0-debug", "confidence"),
        RestoredField("Signal strength (from confidence)", "v0.3.0-debug", "signalStrength"),
        RestoredField("Dip Hunter edge (pp)", "v0.3.0-debug", "edge"),
        RestoredField("Net EV / expected value", "v0.3.0-debug", "netEv"),
        RestoredField("Fee per contract", "v0.3.0-debug", "netEv"),
        RestoredField("Contracts max + sizing note", "v0.3.0-debug", "sizing"),
        RestoredField("Time-to-move / mid vol / P(fill) / uncertainty", "v0.3.0-debug", "microstructure"),
        RestoredField("Ensemble / extended / RL notes", "v0.3.0-debug", "notes"),
        RestoredField("Stance", "v0.3.0-debug", "stance"),
        RestoredField("Fair − market explanation", "v0.3.0-debug", "fairNote"),
        RestoredField("Kalshi market YES / NO percents", "v0.3.0-debug", "mktYes"),
        RestoredField("Spread", "v0.3.0-debug", "spread"),
        RestoredField("Volume", "v0.3.0-debug", "volume"),
        RestoredField("Open interest", "v0.3.0-debug", "openInterest"),
        RestoredField("24h volume", "v0.3.0-debug", "volume24h"),
        RestoredField("Liquidity", "v0.3.0-debug", "liquidity"),
        RestoredField("Time left", "v0.3.4-debug", "timeLeft"),
        RestoredField("Model lean (tape)", "v0.3.4-debug", "modelLean"),
        RestoredField("Fair value · model ¢", "v0.3.7-debug", "fairValue"),
        RestoredField("LIVE BOOK · UP / DOWN", "v0.3.7-debug", "liveBook"),
        RestoredField("Strike / target vs spot", "v0.3.7-debug", "targetVsSpot"),
        RestoredField("Spot move vs target", "v0.3.12-debug", "spotMove"),
        RestoredField("Model vs market % · edge pts", "v0.3.12-debug", "modelVsMarket"),
        RestoredField("Payout multiples", "v0.3.12-debug", "payout"),
        RestoredField("Feature drivers (blend deviations)", "v0.3.0-debug", "drivers"),
        RestoredField("Value vs likely side", "v0.3.15", "value"),
        RestoredField("Likely-side conflict", "v0.3.15", "conflict")
    )

    data class Snapshot(
        val section: String = SECTION,
        val liveBook: String = LIVE_BOOK,
        val fairNote: String = FAIR_NOTE,
        val aiYesLabel: String,
        val aiNoLabel: String,
        val aiYes: String,
        val aiNo: String,
        val mktYesLabel: String = "Mkt YES",
        val mktNoLabel: String = "Mkt NO",
        val mktYes: String,
        val mktNo: String,
        val reasons: String?,
        val confidence: String?,
        val signalStrength: String?,
        val drivers: String?,
        val modelVsMarket: String,
        val edgeTitle: String = EDGE_TITLE,
        val edge: String?,
        val netEv: String?,
        val sizing: String?,
        val microstructure: String?,
        val notes: String?,
        val stance: String?,
        val fairValue: String?,
        val payout: String?,
        val targetVsSpot: String?,
        val spotMove: String?,
        val timeLeft: String,
        val spread: String,
        val volume: String,
        val openInterest: String,
        val volume24h: String,
        val liquidity: String,
        val modelLean: String?,
        val value: String?,
        val conflict: String?
    ) {
        fun lines(): Map<String, String?> = mapOf(
            "section" to section,
            "liveBook" to liveBook,
            "fairNote" to fairNote,
            "aiYes" to "$aiYesLabel $aiYes",
            "aiNo" to "$aiNoLabel $aiNo",
            "mktYes" to "$mktYesLabel $mktYes",
            "mktNo" to "$mktNoLabel $mktNo",
            "reasons" to reasons,
            "confidence" to confidence,
            "signalStrength" to signalStrength,
            "drivers" to drivers,
            "modelVsMarket" to modelVsMarket,
            "edge" to edge,
            "netEv" to netEv,
            "sizing" to sizing,
            "microstructure" to microstructure,
            "notes" to notes,
            "stance" to stance,
            "fairValue" to fairValue,
            "payout" to payout,
            "targetVsSpot" to targetVsSpot,
            "spotMove" to spotMove,
            "timeLeft" to timeLeft,
            "spread" to spread,
            "volume" to volume,
            "openInterest" to openInterest,
            "volume24h" to volume24h,
            "liquidity" to liquidity,
            "modelLean" to modelLean,
            "value" to value,
            "conflict" to conflict
        )
    }

    fun of(
        market: MarketUiModel,
        decision: BetCall.Decision,
        nowMs: Long
    ): Snapshot = Snapshot(
        aiYesLabel = aiYesLabel(market),
        aiNoLabel = aiNoLabel(market),
        aiYes = percentLabel(market.aiYesPercent),
        aiNo = percentLabel(market.aiNoPercent),
        mktYes = percentLabel(market.yesProbabilityPercent),
        mktNo = percentLabel(market.noProbabilityPercent),
        reasons = reasonsLine(market),
        confidence = confidenceLine(market),
        signalStrength = signalStrengthLine(market),
        drivers = featureDriversLine(market),
        modelVsMarket = HomeCopy.modelVsMarket(market, decision),
        edge = edgePpLine(market),
        netEv = netEvLine(market),
        sizing = sizingLine(market),
        microstructure = microstructureLine(market),
        notes = notesLine(market),
        stance = stanceLine(market),
        fairValue = fairValueLine(market),
        payout = payoutLine(market),
        targetVsSpot = targetVsSpotLine(market),
        spotMove = HomeCopy.spotDeltaText(market),
        timeLeft = TimeLeft.format(market.closeTimeEpochMs, nowMs),
        spread = spreadLine(market),
        volume = formatCompact(market.volume),
        openInterest = formatCompact(market.openInterest),
        volume24h = formatCompact(market.volume24h),
        liquidity = market.liquidityDollars?.let { formatCompact(it) } ?: "—",
        modelLean = modelLeanLine(market),
        value = valueLine(market),
        conflict = conflictLine(market)
    )

    fun aiYesLabel(market: MarketUiModel): String =
        if (market.calibrated) "FV YES" else "AI YES"

    fun aiNoLabel(market: MarketUiModel): String =
        if (market.calibrated) "FV NO" else "AI NO"

    fun percentLabel(pp: Double?): String =
        pp?.takeIf { it.isFinite() }?.let { String.format(Locale.US, "%.1f%%", it) } ?: "—"

    fun reasonsLine(market: MarketUiModel): String? =
        sanitizePickLanguage(market.aiNote, market) ?: market.aiNote?.trim()?.takeIf { it.isNotEmpty() }

    fun confidenceLine(market: MarketUiModel): String? =
        market.aiConfidence?.takeIf { it.isFinite() }?.let {
            String.format(Locale.US, "Confidence %.0f%%", it * 100.0)
        }

    /**
     * Same [MarketUiModel.aiConfidence] the 0.3.0 card appended as
     * `· conf N%`. Band is derived from that number, not a stub.
     */
    fun signalStrengthLine(market: MarketUiModel): String? {
        val c = market.aiConfidence?.takeIf { it.isFinite() } ?: return null
        val pct = (c * 100.0).roundToInt().coerceIn(0, 100)
        val band = when {
            c >= 0.70 -> "strong"
            c >= 0.50 -> "medium"
            else -> "weak"
        }
        return "Signal strength $pct% ($band)"
    }

    fun modelYesPercent(market: MarketUiModel): Double? =
        SignalStance.homeModelYes(market.importedModelPp, market.aiYesPercent)

    /** Side with more than 50% model probability. 50% is not a pick. */
    fun likelySide(market: MarketUiModel): String? =
        DisagreementLabel.modelFavoredSide(modelYesPercent(market))

    fun likelyPercent(market: MarketUiModel): Int? {
        val yes = modelYesPercent(market) ?: return null
        val side = likelySide(market) ?: return null
        return if (side == "UP") yes.roundToInt() else (100.0 - yes).roundToInt()
    }

    fun likelySideLabel(market: MarketUiModel): String? {
        val side = likelySide(market) ?: return null
        val pct = likelyPercent(market) ?: return side
        return "$side $pct%"
    }

    /**
     * Underpriced side: model probability vs that side's live ask.
     * Separate from [likelySide] — a 39% UP print can still be cheap at 34¢.
     */
    fun valueSide(market: MarketUiModel): String? {
        val modelYes = modelYesPercent(market) ?: return null
        val yesAskCents = KalshiPrice.usable(market.yesAsk)?.times(100.0)
        val noAskCents = KalshiPrice.usable(market.noAsk)?.times(100.0)
        val upGap = yesAskCents?.let { modelYes - it }
        val downGap = noAskCents?.let { (100.0 - modelYes) - it }
        return when {
            upGap != null && downGap != null -> when {
                upGap > downGap && upGap > 1e-9 -> "UP"
                downGap > upGap && downGap > 1e-9 -> "DOWN"
                else -> null
            }
            upGap != null && upGap > 1e-9 -> "UP"
            downGap != null && downGap > 1e-9 -> "DOWN"
            else -> null
        }
    }

    fun valueAskCents(market: MarketUiModel): Double? = when (valueSide(market)) {
        "UP" -> KalshiPrice.usable(market.yesAsk)?.times(100.0)
        "DOWN" -> KalshiPrice.usable(market.noAsk)?.times(100.0)
        else -> null
    }

    fun valueModelPercent(market: MarketUiModel): Int? {
        val yes = modelYesPercent(market) ?: return null
        return when (valueSide(market)) {
            "UP" -> yes.roundToInt()
            "DOWN" -> (100.0 - yes).roundToInt()
            else -> null
        }
    }

    /**
     * Explicit value-vs-likely sentence. Example: phone case
     * `Value: UP is underpriced (model 39% vs ask 34¢) · More likely: DOWN 61%`.
     */
    fun valueLine(market: MarketUiModel): String? {
        val value = valueSide(market) ?: return null
        val modelPct = valueModelPercent(market) ?: return null
        val ask = valueAskCents(market) ?: return null
        val likely = likelySideLabel(market) ?: return null
        return String.format(
            Locale.US,
            "Value: %s is underpriced (model %d%% vs ask %.0f¢) · More likely: %s",
            value,
            modelPct,
            ask,
            likely
        )
    }

    fun likelyLine(market: MarketUiModel): String? =
        likelySideLabel(market)?.let { "Likely side: $it" }

    fun valueSideLine(market: MarketUiModel): String? {
        val value = valueSide(market) ?: return null
        return "Value side: $value underpriced"
    }

    /** Never "Slight UP / YES" as an AI pick — those words are value vs likely. */
    fun stanceLine(market: MarketUiModel): String? {
        val value = valueSide(market)
        val likely = likelySideLabel(market)
        if (value == null && likely == null) return null
        val valuePart = value?.let { "Value side: $it" }
        val likelyPart = likely?.let { "Likely side: $it" }
        return listOfNotNull(valuePart, likelyPart).joinToString(" · ")
    }

    /**
     * Yellow-box copy only when the model's *likely* side disagrees with
     * market + spot. Stored [MarketUiModel.tapeConflictNote] is ignored
     * when it names the value side as what the AI "says".
     */
    fun conflictLine(market: MarketUiModel): String? {
        val copy = DisagreementLabel.of(market) ?: return null
        return "${copy.title}. ${copy.detail}"
    }

    fun edgePpLine(market: MarketUiModel): String? {
        val edge = market.edgePp?.takeIf { it.isFinite() } ?: return null
        val value = valueSide(market)
        return if (value != null) {
            String.format(Locale.US, "Value edge %+.1f pp %s", edge, value)
        } else {
            String.format(Locale.US, "Value edge %+.1f pp", edge)
        }
    }

    fun netEvLine(market: MarketUiModel): String? {
        val net = market.netEdgePp?.takeIf { it.isFinite() } ?: return null
        return String.format(
            Locale.US,
            "Net EV %+.1f pp  ·  %+.3f $/ct  ·  fee %.1f¢",
            net,
            market.netEvDollars ?: 0.0,
            (market.feePerContract ?: 0.0) * 100.0
        )
    }

    fun sizingLine(market: MarketUiModel): String? {
        val n = market.suggestedContracts ?: return market.sizingNote?.trim()?.takeIf { it.isNotEmpty() }
        val note = market.sizingNote?.trim()?.takeIf { it.isNotEmpty() }
        return if (note != null) "$n contracts max · $note" else "$n contracts max"
    }

    fun microstructureLine(market: MarketUiModel): String? {
        val parts = listOfNotNull(
            market.timeToMoveSec?.takeIf { it.isFinite() }?.let {
                String.format(Locale.US, "TTM %.0fs", it)
            },
            market.midVolPp?.takeIf { it.isFinite() }?.let {
                String.format(Locale.US, "vol %.1fpp", it)
            },
            market.pFill?.takeIf { it.isFinite() }?.let {
                String.format(Locale.US, "P(fill) %.0f%%", it * 100.0)
            },
            market.uncertainty?.takeIf { it.isFinite() }?.let {
                String.format(Locale.US, "unc %.2f", it)
            }
        )
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    fun notesLine(market: MarketUiModel): String? {
        val parts = listOfNotNull(
            sanitizePickLanguage(market.ensembleNote, market),
            sanitizePickLanguage(market.extendedNote, market),
            sanitizePickLanguage(market.rlNote, market)
        )
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    fun fairValueLine(market: MarketUiModel): String? {
        val fv = market.digitalFairPp?.takeIf { it.isFinite() } ?: return null
        val model = market.importedModelPp?.takeIf { it.isFinite() }?.let {
            String.format(Locale.US, "%.0f¢", it)
        } ?: "baseline"
        return String.format(Locale.US, "Fair value %.0f¢  ·  model %s", fv, model)
    }

    fun payoutLine(market: MarketUiModel): String {
        val quotes = com.dirk.kalshiodds.domain.MarketQuoteView.of(market)
        return "Payout  UP ${quotes.upMultipleLabel}  ·  DOWN ${quotes.downMultipleLabel}"
    }

    fun targetVsSpotLine(market: MarketUiModel): String? {
        val target = HomeCopy.targetText(market)
        val spot = market.spotUsd?.takeIf { it.isFinite() && it > 0.0 }?.let {
            String.format(Locale.US, "spot $%,.0f", it)
        }
        val delta = HomeCopy.spotDeltaText(market)
        val parts = listOfNotNull(target, spot, delta)
        return parts.takeIf { it.isNotEmpty() }?.joinToString("  ·  ")
    }

    fun spreadLine(market: MarketUiModel): String =
        market.spreadDollars?.takeIf { it.isFinite() }?.let {
            String.format(Locale.US, "%.1f¢", it * 100.0)
        } ?: "—"

    fun modelLeanLine(market: MarketUiModel): String? {
        if (conflictLine(market) == null) return null
        val likely = likelySideLabel(market) ?: return null
        return "Likely side: $likely · primary follows live tape"
    }

    /**
     * Rewrite stored stance / tape strings that treat the value side as
     * what the AI says, leans, or picks.
     */
    fun sanitizePickLanguage(raw: String?, market: MarketUiModel): String? {
        val t = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (!claimsAiPick(t)) return t
        return stanceLine(market)
    }

    fun claimsAiPick(text: String): Boolean {
        val u = text.lowercase(Locale.US)
        if (u.startsWith("value:") || u.startsWith("value side") || u.startsWith("likely side") ||
            u.startsWith("value edge") || u.contains("underpriced")
        ) {
            return false
        }
        return u.contains("ai says") ||
            u.contains("model lean") ||
            u.contains("leans ") ||
            u.contains("lean up") ||
            u.contains("lean down") ||
            u.contains("slight up") ||
            u.contains("slight down") ||
            u.contains("picks up") ||
            u.contains("picks down")
    }

    /**
     * Blend-channel deviations (featureFair − mid) from the scorer, plus
     * any on-model notes. Empty when the engine has no drivers yet.
     */
    fun featureDriversLine(market: MarketUiModel): String? {
        val named = listOfNotNull(
            market.flowNote?.trim()?.takeIf { it.isNotEmpty() }?.let { "flow $it" },
            market.spotLabel?.trim()?.takeIf { it.isNotEmpty() },
            market.tapeTrend?.trim()?.takeIf { it.isNotEmpty() }?.let { "tape $it" },
            market.mlpPp?.takeIf { it.isFinite() }?.let { String.format(Locale.US, "MLP %.0f¢", it) },
            market.cnnPp?.takeIf { it.isFinite() }?.let { String.format(Locale.US, "CNN %.0f¢", it) },
            market.gbmPp?.takeIf { it.isFinite() }?.let { String.format(Locale.US, "GBM %.0f¢", it) },
            market.mmShadowPp?.takeIf { it.isFinite() }?.let {
                String.format(Locale.US, "MM shadow %.0f¢", it)
            },
            market.newsLabel?.trim()?.takeIf { it.isNotEmpty() }
        )
        val devs = market.featureDevs
            .filter { it.value.isFinite() && abs(it.value) >= 0.05 }
            .entries
            .sortedByDescending { abs(it.value) }
            .take(6)
            .map { String.format(Locale.US, "%s %+.1fpp", it.key, it.value) }
        val parts = named + devs
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    fun detailsToggleLabel(open: Boolean): String = if (open) HIDE_DETAILS else DETAILS

    fun formatCompact(value: Double?): String {
        if (value == null || !value.isFinite()) return "—"
        return when {
            value >= 1_000_000 -> String.format(Locale.US, "%.2fM", value / 1_000_000)
            value >= 1_000 -> String.format(Locale.US, "%.1fK", value / 1_000)
            else -> String.format(Locale.US, "%.0f", value)
        }
    }
}
