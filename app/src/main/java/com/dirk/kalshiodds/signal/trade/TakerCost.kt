package com.dirk.kalshiodds.signal.trade

import com.dirk.kalshiodds.signal.config.SignalConstants
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/**
 * What a buy at the ask really costs, shown on every live ticket.
 *
 * Two parts:
 *  - exact arithmetic for this ticket: the win rate it needs to break even
 *    (all-in cost per contract) against the win rate the price implies, and
 *    the fee as a share of the bet;
 *  - what buyers at this price and at this point in the window got in the
 *    trade-tape study (docs/tape-study-2026-10-04.md): 952 settled KXBTC15M
 *    windows, 33.3M public trades, held to settlement, exact taker fee.
 *
 * The study numbers are averages over [STUDY], not a forecast. Single price
 * bands are noisy (one outcome per window); the totals are not: takers lost
 * 1.01¢ per contract, 95% CI [−1.14, −0.87].
 *
 * Display only. Never sizes, gates or places an order.
 */
object TakerCost {

    const val STUDY = "952 settled windows, Sep 24 – Oct 4 2026"
    const val FOOTNOTE = "History from $STUDY. Averages, not a forecast."

    /** 15-minute window length in seconds. */
    const val WINDOW_SECONDS = 900L

    /** Below this |ROI| the copy says "about broke even". */
    const val FLAT_ROI_PCT = 0.25

    /** At or below this ROI the line is shown as a warning. */
    const val WARN_ROI_PCT = -5.0

    /** Price paid in cents, [loCents, hiCents), and the buyers' return on stake. */
    data class PriceBand(val loCents: Int, val hiCents: Int, val roiPct: Double) {
        val label: String get() = "$loCents–$hiCents¢"
    }

    /** Seconds left, [loSec, hiSec), and the buyers' return on stake. */
    data class TimeBand(val loSec: Long, val hiSec: Long, val label: String, val roiPct: Double)

    /** `tools/research/tape_study.py report`, "By price the taker paid". */
    val PRICE_BANDS: List<PriceBand> = listOf(
        PriceBand(0, 5, -25.21),
        PriceBand(5, 10, -11.33),
        PriceBand(10, 20, -8.81),
        PriceBand(20, 30, -9.97),
        PriceBand(30, 40, -7.80),
        PriceBand(40, 50, -7.91),
        PriceBand(50, 60, -1.04),
        PriceBand(60, 70, -1.18),
        PriceBand(70, 80, -1.13),
        PriceBand(80, 90, -0.73),
        PriceBand(90, 95, -0.48),
        PriceBand(95, 100, 0.04)
    )

    /** `tools/research/tape_study.py report`, "By time left". */
    val TIME_BANDS: List<TimeBand> = listOf(
        TimeBand(0L, 60L, "under 1 min", -0.77),
        TimeBand(60L, 180L, "1–3 min", -0.22),
        TimeBand(180L, 360L, "3–6 min", -1.25),
        TimeBand(360L, 600L, "6–10 min", -2.65),
        TimeBand(600L, WINDOW_SECONDS + 1L, "10–15 min", -5.22)
    )

    data class Summary(
        /** Win rate (0–100) at which this ticket breaks even after the fee. */
        val breakEvenPct: Double,
        /** Win rate (0–100) the price paid implies. */
        val impliedPct: Double,
        val feeUsd: Double,
        /** Fee as a percent of the all-in cost. */
        val feePctOfCost: Double,
        val priceBand: PriceBand?,
        val timeBand: TimeBand?
    ) {
        val breakEvenLine: String
            get() = String.format(
                Locale.US,
                "Break-even: must win %.1f%% of the time. The price says %.1f%%.",
                breakEvenPct,
                impliedPct
            )

        val feeLine: String
            get() = String.format(Locale.US, "Fee $%.2f is %.1f%% of this bet.", feeUsd, feePctOfCost)

        val priceLine: String?
            get() = priceBand?.let { "Buyers at ${it.label} ${outcome(it.roiPct)}." }

        val timeLine: String?
            get() = timeBand?.let { "With ${it.label} left, buyers ${outcome(it.roiPct)}." }

        /** True when either history line is at or below [WARN_ROI_PCT]. */
        val warn: Boolean
            get() = (priceBand?.roiPct ?: 0.0) <= WARN_ROI_PCT || (timeBand?.roiPct ?: 0.0) <= WARN_ROI_PCT

        /** Arithmetic first, then history; the footnote only when history is shown. */
        val lines: List<String>
            get() {
                val history = listOfNotNull(priceLine, timeLine)
                return listOf(breakEvenLine, feeLine) + history +
                    (if (history.isEmpty()) emptyList() else listOf(FOOTNOTE))
            }
    }

    /** `lost 10.0% of their stake on average` / `about broke even`. */
    fun outcome(roiPct: Double): String = when {
        kotlin.math.abs(roiPct) < FLAT_ROI_PCT -> "about broke even"
        roiPct < 0.0 -> String.format(Locale.US, "lost %.1f%% of their stake on average", -roiPct)
        else -> String.format(Locale.US, "made %.1f%% on their stake on average", roiPct)
    }

    fun priceBand(price: Double?): PriceBand? {
        val p = price?.takeIf { it.isFinite() && it > 0.0 && it < 1.0 } ?: return null
        val cents = p * 100.0
        return PRICE_BANDS.firstOrNull { cents >= it.loCents && cents < it.hiCents }
    }

    fun timeBand(tteSeconds: Long?): TimeBand? {
        val t = tteSeconds?.takeIf { it in 0L..WINDOW_SECONDS } ?: return null
        return TIME_BANDS.firstOrNull { t >= it.loSec && t < it.hiSec }
    }

    private val ET: ZoneId = ZoneId.of("America/New_York")
    private val MONTHS = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")
    private val WINDOW_KEY = Regex("""^(\d{2})([A-Za-z]{3})(\d{2})(\d{2})(\d{2})$""")

    /**
     * Close time from a 15m ticker: `KXBTC15M-26SEP251345-45` closes at
     * 13:45 America/New_York on 25 Sep 2026. Null when the ticker does not
     * carry a `YYMMMDDHHMM` window key.
     */
    fun closeEpochMs(ticker: String): Long? {
        val key = ticker.split("-").getOrNull(1) ?: return null
        val m = WINDOW_KEY.matchEntire(key) ?: return null
        val year = 2000 + m.groupValues[1].toInt()
        val month = MONTHS.indexOf(m.groupValues[2].uppercase(Locale.US)) + 1
        val day = m.groupValues[3].toInt()
        val hour = m.groupValues[4].toInt()
        val minute = m.groupValues[5].toInt()
        if (month !in 1..12 || day !in 1..31 || hour !in 0..23 || minute !in 0..59) return null
        return runCatching {
            ZonedDateTime.of(year, month, day, hour, minute, 0, 0, ET).toInstant().toEpochMilli()
        }.getOrNull()
    }

    /** Seconds until [ticker] closes, or null when unknown / not inside its window. */
    fun tteSeconds(ticker: String, nowMs: Long): Long? {
        val close = closeEpochMs(ticker) ?: return null
        val tte = (close - nowMs) / 1000L
        return tte.takeIf { it in 0L..WINDOW_SECONDS }
    }

    /**
     * Cost summary for buying [contracts] at [price]. [allInUsd] / [feeUsd]
     * are the ticket's own figures when it has them; otherwise the exact
     * order fee from [KalshiFee]. Null when there is nothing to price.
     */
    fun of(
        price: Double?,
        contracts: Int,
        allInUsd: Double? = null,
        feeUsd: Double? = null,
        feeRate: Double = SignalConstants.DEFAULT_FEE_RATE,
        tteSeconds: Long? = null
    ): Summary? {
        val p = price?.takeIf { it.isFinite() && it > 0.0 && it < 1.0 } ?: return null
        if (contracts <= 0) return null
        val fee = feeUsd?.takeIf { it.isFinite() && it >= 0.0 } ?: KalshiFee.total(contracts, p, feeRate)
        val cost = allInUsd?.takeIf { it.isFinite() && it > 0.0 } ?: (contracts * p + fee)
        if (cost <= 0.0) return null
        return Summary(
            breakEvenPct = (cost / contracts * 100.0).coerceIn(0.0, 100.0),
            impliedPct = p * 100.0,
            feeUsd = fee,
            feePctOfCost = fee / cost * 100.0,
            priceBand = priceBand(p),
            timeBand = timeBand(tteSeconds)
        )
    }

    /** Summary for a buy [ticket] at [nowMs]; null for sells and blocked tickets. */
    fun of(ticket: TradeTicket, nowMs: Long, feeRate: Double = SignalConstants.DEFAULT_FEE_RATE): Summary? {
        if (ticket.isSell || ticket.blockedReason != null) return null
        return of(
            price = ticket.limitPrice,
            contracts = ticket.contracts,
            allInUsd = ticket.allInUsd,
            feeUsd = ticket.feeUsd,
            feeRate = feeRate,
            tteSeconds = tteSeconds(ticket.ticker, nowMs)
        )
    }
}
