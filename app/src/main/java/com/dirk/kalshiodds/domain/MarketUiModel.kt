package com.dirk.kalshiodds.domain

import com.dirk.kalshiodds.data.dto.MarketDto
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/** Absolute edge (AI YES% − Market YES%) threshold for alerts, in percentage points. */
const val EDGE_ALERT_THRESHOLD_PP = 5.0

data class MarketUiModel(
    val ticker: String,
    val title: String,
    val subtitle: String?,
    val floorStrike: Double?,
    val yesBid: Double?,
    val yesAsk: Double?,
    val noBid: Double?,
    val noAsk: Double?,
    /** Contracts at the YES ask (`yes_ask_size_fp`). */
    val yesAskSize: Double? = null,
    val lastPrice: Double?,
    /** Raw Kalshi YES mid 0–100 (secondary display). */
    val yesProbabilityPercent: Double?,
    /** Raw Kalshi NO mid 0–100 (secondary display). */
    val noProbabilityPercent: Double?,
    /** Dip Hunter on-device predicted YES 0–100 (primary). */
    val aiYesPercent: Double? = null,
    /** Dip Hunter on-device predicted NO 0–100 (primary). */
    val aiNoPercent: Double? = null,
    val aiConfidence: Double? = null,
    val aiNote: String? = null,
    /** AI YES% − Market YES% in percentage points. */
    val edgePp: Double? = null,
    /** Suggested stance text only — never an order. */
    val stance: String? = null,
    val edgeAlert: Boolean = false,
    /** yes_ask − yes_bid in dollars (0–1). */
    val spreadDollars: Double? = null,
    val volume: Double?,
    val volume24h: Double?,
    val openInterest: Double? = null,
    val liquidityDollars: Double? = null,
    val closeTimeLocal: String?,
    val closeTimeEpochMs: Long? = null,
    val status: String?,
    val seriesLabel: String,
    val regimeTag: String? = null,
    val tteRegimeLabel: String? = null,
    val passedFilter: Boolean = true,
    val skipReason: String? = null,
    /**
     * [com.dirk.kalshiodds.signal.engine.EntryFilter] block from the last
     * score (too early in the window / spot on the strike). Null = allowed.
     */
    val entryBlockReason: String? = null,
    val calibrated: Boolean = false,
    val predictedSide: String? = null,
    val netEvDollars: Double? = null,
    val netEdgePp: Double? = null,
    val suggestedContracts: Int? = null,
    val sizingNote: String? = null,
    val muted: Boolean = false,
    val muteReason: String? = null,
    val feePerContract: Double? = null,
    val halfSpread: Double? = null,
    val spotLabel: String? = null,
    val adapterReady: Boolean = false,
    val uncertainty: Double? = null,
    val uncertaintyPassed: Boolean = true,
    val timeToMoveSec: Double? = null,
    val midVolPp: Double? = null,
    val pFill: Double? = null,
    val heavyMl: Boolean = false,
    val ensembleNote: String? = null,
    val mlpPp: Double? = null,
    val cnnPp: Double? = null,
    val gbmPp: Double? = null,
    val sessionTag: String? = null,
    val newsShock: Boolean = false,
    val anomalyNote: String? = null,
    val survivalYesPp: Double? = null,
    val rlStakeUsd: Double? = null,
    val rlNote: String? = null,
    val newsLabel: String? = null,
    val flowNote: String? = null,
    val mmShadowPp: Double? = null,
    val conformalSet: String? = null,
    val conformalAmbiguous: Boolean = false,
    val metaTake: Boolean? = null,
    val metaNote: String? = null,
    val pathSurvive: Double? = null,
    val extendedNote: String? = null,
    /** YES mid history in percent, oldest → newest. Copied off live structures. */
    val oddsHistory: List<Float> = emptyList(),
    val tapeTrend: String? = null,
    val tapeConflict: Boolean = false,
    val tapeConflictNote: String? = null,
    val primaryHeroSide: String? = null,
    val modelLeanSide: String? = null,
    val bidHistory: List<com.dirk.kalshiodds.chart.BidPoint> = emptyList(),
    val digitalFairPp: Double? = null,
    val importedModelPp: Double? = null,
    val modelEdgeQualified: Boolean = true,
    val spotUsd: Double? = null,
    val spotVsTargetUsd: Double? = null,
    val pastSettlements: List<Boolean> = emptyList(),
    /** Blend-channel deviations (featureFair − mid) in pp from [ScoringEngine.Score]. */
    val featureDevs: Map<String, Double> = emptyMap(),
    /** Kalshi `open_time`. When null, 15m windows infer close − [MarketLifecycle.WINDOW_MS]. */
    val openTimeEpochMs: Long? = null
)

enum class SeriesKind(val ticker: String, val label: String) {
    BTC(com.dirk.kalshiodds.data.api.KalshiApi.SERIES_BTC, "Bitcoin"),
    ETH(com.dirk.kalshiodds.data.api.KalshiApi.SERIES_ETH, "Ethereum"),
    SOL(com.dirk.kalshiodds.data.api.KalshiApi.SERIES_SOL, "Solana"),
    CRYPTO("", "Crypto")
}

/**
 * Overlay a live ticker / REST tick onto the snapshot quote so the hero,
 * buttons, and [MarketQuoteView] multiple use the same ask the ticket path
 * already reads from [com.dirk.kalshiodds.signal.engine.TickBook.lastTick].
 * Order-book ticks (YES ask = 1 − best NO bid) update lastTick; trades
 * and last-as-book prints do not replace a real spread.
 */
fun MarketUiModel.withLiveQuote(tick: com.dirk.kalshiodds.signal.model.MarketTick?): MarketUiModel {
    val sameTicker = tick != null && tick.ticker.equals(ticker, ignoreCase = true)
    val quote = ConsistentQuote.overlay(this, if (sameTicker) tick else null)
    val yb = quote.yesBid
    val ya = quote.yesAsk
    val nb = quote.noBid
    val na = quote.noAsk
    val last = if (sameTicker) KalshiPrice.usable(tick?.lastPrice) ?: lastPrice else lastPrice
    if (yb == yesBid && ya == yesAsk && nb == noBid && na == noAsk && last == lastPrice) return this
    val yesImplied = when {
        yb != null && ya != null -> (yb + ya) / 2.0
        last != null -> last
        else -> yesProbabilityPercent?.div(100.0)
    }
    val noImplied = when {
        nb != null && na != null -> (nb + na) / 2.0
        yesImplied != null -> 1.0 - yesImplied
        else -> noProbabilityPercent?.div(100.0)
    }
    val spread = if (yb != null && ya != null) (ya - yb).coerceAtLeast(0.0) else spreadDollars
    return copy(
        yesBid = yb,
        yesAsk = ya,
        noBid = nb,
        noAsk = na,
        lastPrice = last,
        yesProbabilityPercent = yesImplied?.times(100.0),
        noProbabilityPercent = noImplied?.times(100.0),
        spreadDollars = spread
    )
}

fun MarketDto.toUiModel(series: SeriesKind): MarketUiModel {
    val yesBid = KalshiPrice.parseDollars(yesBidDollars)
    val yesAsk = KalshiPrice.parseDollars(yesAskDollars)
    val noBid = KalshiPrice.parseDollars(noBidDollars)
    val noAsk = KalshiPrice.parseDollars(noAskDollars)
    val last = KalshiPrice.parseDollars(lastPriceDollars)
    val yesImplied = when {
        yesBid != null && yesAsk != null -> (yesBid + yesAsk) / 2.0
        last != null -> last
        else -> null
    }
    val noImplied = when {
        noBid != null && noAsk != null -> (noBid + noAsk) / 2.0
        yesImplied != null -> 1.0 - yesImplied
        else -> null
    }
    val spread = if (yesBid != null && yesAsk != null) (yesAsk - yesBid).coerceAtLeast(0.0) else null
    return MarketUiModel(
        ticker = ticker,
        title = title.orEmpty().ifBlank { ticker },
        subtitle = yesSubTitle,
        floorStrike = floorStrike,
        yesBid = yesBid,
        yesAsk = yesAsk,
        noBid = noBid,
        noAsk = noAsk,
        yesAskSize = KalshiPrice.parseCount(yesAskSizeFp),
        lastPrice = last,
        yesProbabilityPercent = yesImplied?.times(100.0),
        noProbabilityPercent = noImplied?.times(100.0),
        spreadDollars = spread,
        volume = volumeFp.toDoubleOrNullSafe(),
        volume24h = volume24hFp.toDoubleOrNullSafe(),
        openInterest = openInterestFp.toDoubleOrNullSafe(),
        liquidityDollars = liquidityDollars.toDoubleOrNullSafe(),
        closeTimeLocal = formatCloseTimeLocal(closeTime ?: expirationTime ?: expectedExpirationTime),
        closeTimeEpochMs = parseCloseEpochMs(closeTime)
            ?: parseCloseEpochMs(expirationTime)
            ?: parseCloseEpochMs(expectedExpirationTime),
        status = status,
        seriesLabel = series.label
    )
}

/** Attach edge / stance / alert after AI annotate. */
fun MarketUiModel.withEdgeMetrics(thresholdPp: Double = EDGE_ALERT_THRESHOLD_PP): MarketUiModel {
    val ai = aiYesPercent ?: return this
    val mkt = yesProbabilityPercent ?: return this
    val edge = ai - mkt
    val alert = abs(edge) >= thresholdPp
    val stance = stanceFor(side = if (edge >= 0) "YES" else "NO", edgePp = edge, thresholdPp = thresholdPp)
    return copy(edgePp = edge, stance = stance, edgeAlert = alert)
}

fun MarketUiModel.withSignalScore(
    score: com.dirk.kalshiodds.signal.engine.ScoringEngine.Score,
    thresholdPp: Double = EDGE_ALERT_THRESHOLD_PP
): MarketUiModel {
    val alert = score.passedFilter && abs(score.deltaPp) >= thresholdPp
    val stance = when {
        !score.passedFilter -> "Filtered — ${score.skipReason ?: "weak"}"
        score.directionalLock -> if (score.predictedSide.equals("NO", true)) "Lean DOWN / NO" else "Lean UP / YES"
        else -> stanceFor(score.predictedSide, score.deltaPp, thresholdPp)
    }
    return copy(
        aiYesPercent = score.fairValuePp,
        aiNoPercent = (100.0 - score.fairValuePp).coerceIn(2.0, 98.0),
        aiConfidence = score.confidence,
        aiNote = score.reason,
        edgePp = score.deltaPp,
        stance = stance,
        edgeAlert = alert,
        regimeTag = score.regime.label,
        tteRegimeLabel = score.tteRegime.label,
        passedFilter = score.passedFilter,
        skipReason = score.skipReason,
        entryBlockReason = score.entryBlockReason,
        calibrated = score.calibrated,
        predictedSide = score.predictedSide,
        netEvDollars = score.netEvDollars,
        netEdgePp = score.netEdgePp,
        suggestedContracts = score.suggestedContracts,
        sizingNote = score.sizingNote,
        muted = score.muted,
        muteReason = score.muteReason,
        feePerContract = score.feePerContract,
        halfSpread = score.halfSpread,
        spotLabel = score.spotLabel,
        adapterReady = score.adapterReady,
        uncertainty = score.uncertainty,
        uncertaintyPassed = score.uncertaintyPassed,
        timeToMoveSec = score.timeToMoveSec,
        midVolPp = score.midVolPp,
        pFill = score.pFill,
        heavyMl = score.heavyMl,
        ensembleNote = score.ensembleNote,
        mlpPp = score.mlpPp,
        cnnPp = score.cnnPp,
        gbmPp = score.gbmPp,
        sessionTag = score.sessionTag,
        newsShock = score.newsShock,
        anomalyNote = score.anomalyNote,
        survivalYesPp = score.survivalYesPp,
        rlStakeUsd = score.rlStakeUsd,
        rlNote = score.rlNote,
        newsLabel = score.newsLabel,
        flowNote = score.flowNote,
        mmShadowPp = score.mmShadowPp,
        conformalSet = score.conformalSet,
        conformalAmbiguous = score.conformalAmbiguous,
        metaTake = score.metaTake,
        metaNote = score.metaNote,
        pathSurvive = score.pathSurvive,
        extendedNote = score.extendedNote,
        tapeTrend = score.tapeTrend,
        tapeConflict = score.tapeConflict,
        tapeConflictNote = score.tapeConflictNote,
        primaryHeroSide = score.primaryHeroSide,
        modelLeanSide = score.modelLeanSide,
        digitalFairPp = score.digitalFairPp,
        importedModelPp = score.importedModelPp,
        modelEdgeQualified = score.modelEdgeQualified,
        spotUsd = score.spotUsd,
        spotVsTargetUsd = score.spotVsTargetUsd,
        featureDevs = score.featureDevs
    )
}

internal fun stanceFor(side: String, edgePp: Double, thresholdPp: Double): String {
    val up = !side.equals("NO", ignoreCase = true)
    return when {
        abs(edgePp) >= thresholdPp -> if (up) "Lean UP / YES" else "Lean DOWN / NO"
        abs(edgePp) >= 2.0 -> if (up) "Slight UP / YES" else "Slight DOWN / NO"
        else -> "No edge"
    }
}

private fun String?.toDoubleOrNullSafe(): Double? =
    this?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()

private val localTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, h:mm a z", Locale.US)

fun formatCloseTimeLocal(iso: String?): String? {
    if (iso.isNullOrBlank()) return null
    return try {
        val instant = Instant.parse(iso)
        localTimeFormatter.format(instant.atZone(ZoneId.systemDefault()))
    } catch (_: Exception) {
        iso
    }
}

fun parseCloseEpochMs(iso: String?): Long? {
    if (iso.isNullOrBlank()) return null
    return try {
        Instant.parse(iso).toEpochMilli()
    } catch (_: Exception) {
        null
    }
}
