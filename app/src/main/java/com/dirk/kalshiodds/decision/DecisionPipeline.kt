package com.dirk.kalshiodds.decision

import com.dirk.kalshiodds.data.local.ledger.LedgerRow
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * One deterministic, versioned decision for one market at one moment:
 *
 *  raw model p → regime isotonic calibration (regime → coin → global)
 *  → market-as-prior residual (bounded correction) → side (favourite first)
 *  → fill model + expected net → NO BET gate → ledger row.
 *
 * Pure function of its inputs. No network, no clock, no LLM.
 */
object DecisionPipeline {
    const val VERSION = "kashi-decision-0.3.37"
    const val DEFAULT_NOTIONAL_USD = 10.0
    const val DEFAULT_SPREAD = 0.02
    const val SECONDS_PER_YEAR = 365.25 * 24.0 * 3600.0
    /** Fallback annualized vols when no realized vol is available (ETH/SOL before candles load). */
    val DEFAULT_ANNUAL_VOL = mapOf("BTC" to 0.50, "ETH" to 0.65, "SOL" to 0.85)

    data class CfSnapshot(
        val indexId: String,
        val value: Double,
        val avg60s: Double?,
        val finalMinuteAvg: Double?,
        val finalMinuteCount: Int?
    )

    data class Input(
        val ticker: String,
        val series: String,
        val nowMs: Long,
        val closeTimeMs: Long?,
        val rawModelYes: Double?,
        val marketYes: Double?,
        val yesAsk: Double?,
        val noAsk: Double?,
        val yesBid: Double?,
        val noBid: Double?,
        val yesDepth: Int?,
        val noDepth: Int?,
        val bookAgeMs: Long?,
        val spot: Double?,
        val strike: Double?,
        /** Per-second log-return vol. Null → [DEFAULT_ANNUAL_VOL]. */
        val volPerSec: Double?,
        val settlementSource: String,
        val settlementFresh: Boolean,
        val cf: CfSnapshot? = null,
        val ensembleSpread: Double? = null,
        val imbalance: Double? = null,
        val feeRate: Double = 0.07,
        val modelVersion: String = VERSION
    ) {
        val secondsRemaining: Double?
            get() = closeTimeMs?.let { (it - nowMs) / 1000.0 }
    }

    data class Context(
        val calibration: RegimeCalibration.Model = RegimeCalibration.Model(),
        val longshot: LongshotResidualModel = LongshotResidualModel.EMPTY
    )

    data class SideQuote(
        val side: String,
        val ask: Double,
        val pWin: Double,
        val contracts: Int,
        val depth: Int,
        val spread: Double,
        val feePerContract: Double,
        val adversePerContract: Double,
        val pFill: Double,
        val expectedNetPerContract: Double,
        val favourite: Boolean,
        val longshot: Boolean,
        val longshotValidated: Boolean
    )

    data class Assessment(
        val rawYes: Double?,
        val calibratedYes: Double?,
        val calibrationLevel: RegimeCalibration.Level?,
        val finalYes: Double?,
        val marketYes: Double?,
        val correction: Double,
        val key: RegimeCalibration.Key,
        val zDistance: Double?,
        val zSettle: Double?,
        val uncertaintyHalfWidth: Double,
        val regimeApproved: Boolean,
        val finalWindowReady: Boolean,
        val chosen: SideQuote?,
        val verdict: TradeEligibility.Verdict,
        val featuresHash: String
    ) {
        val allow: Boolean get() = verdict.allow
        val reason: String? get() = verdict.reason
    }

    /** Spec feature: ln(spot/target) / (realizedVol × √timeRemaining), units per second. */
    fun zDistance(spot: Double?, strike: Double?, volPerSec: Double?, secondsRemaining: Double?): Double? {
        if (spot == null || strike == null || volPerSec == null || secondsRemaining == null) return null
        return DecisionMath.zDistance(spot, strike, volPerSec, secondsRemaining)
    }

    /**
     * Settlement-aligned z. Kalshi settles on the 60-second CF average before
     * close. Before the final minute the average's std is σ√(T−40); inside it
     * the already-fixed part of the average comes from CF's running
     * final-minute average and only the remaining T seconds are uncertain
     * (std σ·√(T³/3)/60).
     */
    fun zSettle(
        spot: Double?,
        strike: Double?,
        volPerSec: Double?,
        secondsRemaining: Double?,
        runningFinalAvg: Double?
    ): Double? {
        if (spot == null || strike == null || volPerSec == null || secondsRemaining == null) return null
        if (spot <= 0.0 || strike <= 0.0 || volPerSec <= 0.0) return null
        val t = secondsRemaining
        if (t <= 0.0) {
            val settled = runningFinalAvg ?: spot
            return if (settled >= strike) 50.0 else -50.0
        }
        return if (t > 60.0) {
            val std = volPerSec * sqrt(t - 40.0)
            ln(spot / strike) / std
        } else {
            val fixedSec = 60.0 - t
            val avgSoFar = runningFinalAvg?.takeIf { it > 0.0 } ?: spot
            val center = (avgSoFar * fixedSec + spot * t) / 60.0
            val std = volPerSec * sqrt(t * t * t / 3.0) / 60.0
            if (std <= 0.0) null else ln(center / strike) / std
        }
    }

    fun coinOf(series: String): String = RegimeCalibration.keyOf(series, null, null).coin

    fun effectiveVol(series: String, volPerSec: Double?): Double? {
        volPerSec?.takeIf { it.isFinite() && it > 0.0 }?.let { return it }
        val annual = DEFAULT_ANNUAL_VOL[coinOf(series)] ?: return null
        return annual / sqrt(SECONDS_PER_YEAR)
    }

    fun assess(input: Input, ctx: Context = Context()): Assessment {
        val t = input.secondsRemaining
        val vol = effectiveVol(input.series, input.volPerSec)
        val settlePx = input.spot
        val z = zDistance(settlePx, input.strike, vol, t)
        val zs = zSettle(settlePx, input.strike, vol, t, input.cf?.finalMinuteAvg)
        val key = RegimeCalibration.keyOf(input.series, t, z)
        val raw = input.rawModelYes?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0)
        val applied = raw?.let { ctx.calibration.apply(it, key) }
        val calibrated = applied?.probability
        val approved = applied?.approved == true
        val market = input.marketYes?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0)
        val modelForPrior = calibrated ?: raw
        val cap = MarketPrior.capFor(approved)
        val final = market?.let { MarketPrior.finalProbability(it, modelForPrior, cap) }
        val corr = if (market != null && modelForPrior != null) MarketPrior.correction(market, modelForPrior, cap) else 0.0
        val localErr = applied?.let { a ->
            ctx.calibration.report(key.idAt(a.level))?.reliability
                ?.firstOrNull { b -> a.probability >= b.lo && a.probability <= b.hi }
                ?.let { b -> b.meanPredicted - b.observedRate }
        }
        val half = UncertaintyGate.halfWidth(calibrated ?: Double.NaN, applied?.n ?: 0, localErr, input.ensembleSpread)
        val finalReady = ctx.calibration.finalWindowReady(key)
        val hash = featuresHash(input, z, zs)

        val sides = if (final == null) emptyList() else listOfNotNull(
            sideQuote("YES", final, input.yesAsk, input.yesBid, input.yesDepth, input, ctx),
            sideQuote("NO", 1.0 - final, input.noAsk, input.noBid, input.noDepth, input, ctx)
        )
        val fav = FavouritePolicy.favouriteSide(input.yesAsk, input.noAsk)
        val favQuote = sides.firstOrNull { it.side == fav }
        val chosen = when {
            favQuote != null && favQuote.expectedNetPerContract > 0.0 -> favQuote
            else -> sides.maxByOrNull { it.expectedNetPerContract }
        }
        val verdict = when {
            raw == null -> TradeEligibility.Verdict(false, "NO BET — no model probability")
            market == null -> TradeEligibility.Verdict(false, "NO BET — no market probability")
            chosen == null -> TradeEligibility.Verdict(false, TradeEligibility.BOOK_REASON)
            else -> TradeEligibility.evaluate(
                TradeEligibility.Input(
                    calibratedProbability = calibrated,
                    calibrationLevel = applied?.level,
                    uncertaintyHalfWidth = half,
                    regimeApproved = approved,
                    finalWindow = key.finalWindow,
                    finalWindowReady = finalReady,
                    settlementSourceFresh = input.settlementFresh,
                    bookFresh = input.bookAgeMs != null && input.bookAgeMs in 0..FillModel.FRESH_BOOK_MS,
                    visibleDepth = chosen.depth.toDouble(),
                    orderSize = 1.0,
                    ask = chosen.ask,
                    longshotValidated = chosen.longshotValidated,
                    pFill = chosen.pFill,
                    expectedNetPerContract = chosen.expectedNetPerContract
                )
            )
        }
        return Assessment(
            rawYes = raw,
            calibratedYes = calibrated,
            calibrationLevel = applied?.level,
            finalYes = final,
            marketYes = market,
            correction = corr,
            key = key,
            zDistance = z,
            zSettle = zs,
            uncertaintyHalfWidth = half,
            regimeApproved = approved,
            finalWindowReady = finalReady,
            chosen = chosen,
            verdict = verdict,
            featuresHash = hash
        )
    }

    private fun sideQuote(
        side: String,
        pWin: Double,
        ask: Double?,
        bid: Double?,
        depth: Int?,
        input: Input,
        ctx: Context
    ): SideQuote? {
        val px = ask?.takeIf { it.isFinite() && it > 0.0 && it < 1.0 } ?: return null
        val contracts = kotlin.math.floor(DEFAULT_NOTIONAL_USD / px).toInt().coerceAtLeast(1)
        val spread = bid?.takeIf { it.isFinite() && it > 0.0 && it < px }?.let { px - it } ?: DEFAULT_SPREAD
        val fee = FillModel.feePerContract(px, contracts, input.feeRate)
        val adverse = FillModel.adversePerContract(marketable = true, spread = spread)
        val d = depth ?: 0
        val pFill = FillModel.pFill(
            FillModel.Quote(
                marketable = true,
                price = px,
                size = contracts.toDouble(),
                depth = d.toDouble(),
                spread = spread,
                imbalance = input.imbalance ?: 0.0,
                bookAgeMs = input.bookAgeMs ?: Long.MAX_VALUE,
                secondsRemaining = input.secondsRemaining ?: 0.0
            )
        )
        val net = ExpectedNet.perContract(pFill, pWin, px, fee, adverse)
        val fav = FavouritePolicy.favouriteSide(input.yesAsk, input.noAsk) == side
        return SideQuote(
            side = side,
            ask = px,
            pWin = pWin,
            contracts = contracts,
            depth = d,
            spread = spread,
            feePerContract = fee,
            adversePerContract = adverse,
            pFill = pFill,
            expectedNetPerContract = net,
            favourite = fav,
            longshot = FavouritePolicy.isLongshot(px),
            longshotValidated = ctx.longshot.validated(px)
        )
    }

    /** Stable SHA-256 (first 16 hex) of the canonical feature vector. */
    fun featuresHash(input: Input, z: Double?, zs: Double?): String {
        fun f(v: Double?): String = v?.takeIf { it.isFinite() }?.let { String.format(Locale.US, "%.6f", it) } ?: "-"
        val canonical = listOf(
            input.ticker.uppercase(), input.series.uppercase(),
            f(input.rawModelYes), f(input.marketYes), f(input.yesAsk), f(input.noAsk), f(input.yesBid), f(input.noBid),
            (input.yesDepth ?: -1).toString(), (input.noDepth ?: -1).toString(),
            f(input.spot), f(input.strike), f(input.volPerSec), f(input.secondsRemaining),
            f(z), f(zs), input.settlementSource, f(input.cf?.finalMinuteAvg), f(input.ensembleSpread), f(input.imbalance)
        ).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    fun ledgerRow(input: Input, a: Assessment, calibrationVersion: String): LedgerRow {
        val c = a.chosen
        val spread = c?.spread
        return LedgerRow(
            timestampMs = input.nowMs,
            modelVersion = "${input.modelVersion}+$calibrationVersion+${MarketPrior.VERSION}+${FillModel.VERSION}",
            ticker = input.ticker,
            series = input.series,
            settlementRule = "CF 60s average before close",
            settlementSource = input.settlementSource,
            secondsRemaining = input.secondsRemaining,
            spot = input.spot,
            targetStrike = input.strike,
            zDistance = a.zDistance,
            rawModelProb = a.rawYes,
            calibratedProb = a.calibratedYes,
            marketMid = a.marketYes,
            bid = if (c?.side == "NO") input.noBid else input.yesBid,
            ask = c?.ask ?: input.yesAsk,
            spread = spread,
            depthAtBest = c?.depth?.toDouble(),
            imbalance = input.imbalance,
            bookAgeMs = input.bookAgeMs,
            decision = a.verdict.decision,
            sizeContracts = c?.contracts?.toDouble(),
            reasonCodes = a.verdict.reason ?: "BET ${c?.side ?: ""}".trim(),
            passive = false,
            orderPrice = c?.ask,
            pFill = c?.pFill,
            expectedFilled = c?.let { it.pFill * minOf(it.contracts, it.depth) },
            expectedNet = c?.expectedNetPerContract,
            cfIndexId = input.cf?.indexId,
            cfValue = input.cf?.value,
            cfAvg60s = input.cf?.avg60s,
            cfFinalMinuteAvg = input.cf?.finalMinuteAvg,
            featuresHash = a.featuresHash,
            finalProb = a.finalYes,
            side = c?.side,
            regimeKey = a.key.regimeId,
            calibrationLevel = a.calibrationLevel?.name,
            zSettle = a.zSettle,
            closeTimeMs = input.closeTimeMs,
            yesAsk = input.yesAsk,
            noAsk = input.noAsk,
            yesDepth = input.yesDepth?.toDouble(),
            noDepth = input.noDepth?.toDouble()
        )
    }
}
