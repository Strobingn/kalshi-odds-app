package com.dirk.kalshiodds.domain

import com.dirk.kalshiodds.data.dto.MarketDto
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

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
    /** Implied YES probability 0–100 (mid of bid/ask when both present, else last). */
    val yesProbabilityPercent: Double?,
    /** Implied NO probability 0–100 (mid of NO bid/ask, else 100 − YES when YES known). */
    val noProbabilityPercent: Double?,
    val volume: Double?,
    val volume24h: Double?,
    val closeTimeLocal: String?,
    val status: String?,
    val seriesLabel: String
)

enum class SeriesKind(val ticker: String, val label: String) {
    BTC(com.dirk.kalshiodds.data.api.KalshiApi.SERIES_BTC, "Bitcoin"),
    WTI(com.dirk.kalshiodds.data.api.KalshiApi.SERIES_WTI, "WTI Crude")
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
        volume = volumeFp.toDoubleOrNullSafe(),
        volume24h = volume24hFp.toDoubleOrNullSafe(),
        closeTimeLocal = formatCloseTimeLocal(closeTime),
        status = status,
        seriesLabel = series.label
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
