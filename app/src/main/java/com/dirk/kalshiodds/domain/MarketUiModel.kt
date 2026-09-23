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
    val extendedNote: String? = null
)

enum class SeriesKind(val ticker: String, val label: String) {
    BTC(com.dirk.kalshiodds.data.api.KalshiApi.SERIES_BTC, "Bitcoin"),
    ETH(com.dirk.kalshiodds.data.api.KalshiApi.SERIES_ETH, "Ethereum"),
    SOL(com.dirk.kalshiodds.data.api.KalshiApi.SERIES_SOL, "Solana"),
    CRYPTO("", "Crypto")
}

fun MarketDto.toUiModel(series: SeriesKind): MarketUiModel {
    val yesBid = yesBidDollars.toDoubleOrNullSafe()
    val yesAsk = yesAskDollars.toDoubleOrNullSafe()
    val noBid = noBidDollars.toDoubleOrNullSafe()
    val noAsk = noAskDollars.toDoubleOrNullSafe()
    val last = lastPriceDollars.toDoubleOrNullSafe()
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
        lastPrice = last,
        yesProbabilityPercent = yesImplied?.times(100.0),
        noProbabilityPercent = noImplied?.times(100.0),
        spreadDollars = spread,
        volume = volumeFp.toDoubleOrNullSafe(),
        volume24h = volume24hFp.toDoubleOrNullSafe(),
        openInterest = openInterestFp.toDoubleOrNullSafe(),
        liquidityDollars = liquidityDollars.toDoubleOrNullSafe(),
        closeTimeLocal = formatCloseTimeLocal(closeTime),
        closeTimeEpochMs = parseCloseEpochMs(closeTime),
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
    val stance = when {
        edge >= thresholdPp -> "Lean YES vs market"
        edge <= -thresholdPp -> "Lean NO vs market"
        abs(edge) >= 2.0 -> if (edge > 0) "Slight YES lean" else "Slight NO lean"
        else -> "No edge"
    }
    return copy(edgePp = edge, stance = stance, edgeAlert = alert)
}

fun MarketUiModel.withSignalScore(
    score: com.dirk.kalshiodds.signal.engine.ScoringEngine.Score,
    thresholdPp: Double = EDGE_ALERT_THRESHOLD_PP
): MarketUiModel {
    val alert = score.passedFilter && abs(score.deltaPp) >= thresholdPp
    val stance = when {
        !score.passedFilter -> "Filtered — ${score.skipReason ?: "weak"}"
        score.deltaPp >= thresholdPp -> "Lean YES vs market"
        score.deltaPp <= -thresholdPp -> "Lean NO vs market"
        abs(score.deltaPp) >= 2.0 -> if (score.deltaPp > 0) "Slight YES lean" else "Slight NO lean"
        else -> "No edge"
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
        extendedNote = score.extendedNote
    )
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
