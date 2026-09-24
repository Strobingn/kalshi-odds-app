package com.dirk.kalshiodds.signal.engine

import com.dirk.kalshiodds.domain.CryptoMarkets
import com.dirk.kalshiodds.prediction.DipHunterModel
import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.external.ExternalSnapshot
import com.dirk.kalshiodds.signal.external.SpotFeatureMath
import com.dirk.kalshiodds.signal.feedback.Allowlist
import com.dirk.kalshiodds.signal.feedback.Calibrator
import com.dirk.kalshiodds.signal.feedback.Guardrails
import com.dirk.kalshiodds.signal.feedback.OnlineAdapter
import com.dirk.kalshiodds.signal.ml.ExtendedAiRuntime
import com.dirk.kalshiodds.signal.ml.HeavyMlRuntime
import com.dirk.kalshiodds.signal.ml.MicrostructureEncoder
import com.dirk.kalshiodds.signal.ml.RegimeClassifier
import com.dirk.kalshiodds.signal.ml.SequenceFeatures
import com.dirk.kalshiodds.signal.sizing.NetExpectedValue
import com.dirk.kalshiodds.signal.sizing.PositionSizer
import com.dirk.kalshiodds.signal.model.MarketTick
import com.dirk.kalshiodds.signal.model.SignalAlert
import com.dirk.kalshiodds.signal.model.TickSource
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.tanh

/**
 * Fair-value vs market-mid scoring for **crypto** contracts only.
 * Analysis only — never places orders.
 *
 * ## Blend weights (documented; missing features drop out and the rest
 * renormalize. Late window = last ~3 min before settlement.)
 *
 * | Feature                         | Early | Late | Notes |
 * |---------------------------------|------:|-----:|-------|
 * | AI (TFLite / fallback MLP)      |  0.30 | 0.20 | Then temperature + reliability-bin calibration |
 * | Volume-flow + aggressor         |  0.12 | 0.14 | Taker side when the trade feed provides it |
 * | Related crypto mid              |  0.08 | 0.04 | BTC ↔ ETH ↔ SOL last mid |
 * | Tick velocity `Δmid/Δt` + accel |  0.10 | 0.14 | Last N ticker/trade/REST ticks |
 * | Order-book imbalance            |  0.10 | 0.14 | Near-mid bid vs ask |
 * | Cross-asset lead–lag            |  0.10 | 0.06 | BTC leads ETH/SOL (~3s); reverse when useful |
 * | Depth near mid / decay          |  0.10 | 0.14 | Size in 3¢ band vs 15¢ band |
 * | Quote pull / cancel spike       |  0.10 | 0.14 | Sudden best-quote moves, large cancels |
 * | External spot / fund / rvol     |  0.08 | 0.06 | Public Binance/Coinbase; drops out if stale |
 *
 * When spot is present the row is included and the rest **renormalize**.
 * [OnlineAdapter] then reweights channels from the user's settlements.
 *
 * Regime nudges (before renormalize): TREND ↑vel/lead-lag; CHOP ↓vel ↑AI;
 * VOL_SPIKE ↑micro ↓AI; QUIET ↑related ↓flow.
 *
 * `delta = calibratedFair − marketMid` (percentage points).
 * Alerts fire only when |delta| ≥ threshold **and** the skip filter passes
 * (confidence, liquidity, spread).
 */
class ScoringEngine(
    private val model: DipHunterModel = DipHunterModel(context = null),
    val book: TickBook = TickBook(),
    val heavy: HeavyMlRuntime = HeavyMlRuntime(),
    val extended: ExtendedAiRuntime = ExtendedAiRuntime(),
    private val idFactory: () -> String = { UUID.randomUUID().toString() }
) {
    data class Score(
        val fairValuePp: Double,
        val marketMidPp: Double,
        val deltaPp: Double,
        val reason: String,
        val aiPp: Double?,
        val flowPp: Double?,
        val relatedPp: Double?,
        val velocityPp: Double? = null,
        val imbalancePp: Double? = null,
        val velocityPerSec: Double? = null,
        val accelerationPerSec: Double? = null,
        val imbalance: Double? = null,
        val rawFairValuePp: Double = fairValuePp,
        val leadLagPp: Double? = null,
        val depthPp: Double? = null,
        val cancelPp: Double? = null,
        val leadLag: Double? = null,
        val depthNearMid: Double? = null,
        val depthDecay: Double? = null,
        val confidence: Double = 0.5,
        val regime: RegimeTag = RegimeTag.QUIET,
        val tteRegime: TteRegime = TteRegime.EARLY,
        val tteSeconds: Long? = null,
        val passedFilter: Boolean = true,
        val skipReason: String? = null,
        val predictedSide: String = "YES",
        val calibrated: Boolean = false,
        val spreadDollars: Double? = null,
        val netEvDollars: Double? = null,
        val netEdgePp: Double? = null,
        val suggestedContracts: Int? = null,
        val sizingNote: String? = null,
        val muted: Boolean = false,
        val muteReason: String? = null,
        val feePerContract: Double? = null,
        val halfSpread: Double? = null,
        val spotLabel: String? = null,
        val spotPp: Double? = null,
        val adapterReady: Boolean = false,
        val featureDevs: Map<String, Double> = emptyMap(),
        val mlpPp: Double? = null,
        val cnnPp: Double? = null,
        val lstmPp: Double? = null,
        val gbmPp: Double? = null,
        val uncertainty: Double? = null,
        val uncertaintyPassed: Boolean = true,
        val timeToMoveSec: Double? = null,
        val midVolPp: Double? = null,
        val pFill: Double? = null,
        val heavyMl: Boolean = false,
        val ensembleNote: String? = null,
        val sessionTag: String? = null,
        val newsShock: Boolean = false,
        val anomalyScore: Double? = null,
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
        val directionalLock: Boolean = false,
        val spotVsTargetUsd: Double? = null
    )

    data class BlendWeights(
        val ai: Double,
        val flow: Double,
        val related: Double,
        val velocity: Double,
        val imbalance: Double,
        val leadLag: Double,
        val depth: Double,
        val cancel: Double,
        val spot: Double = 0.0
    )

    @Volatile
    var calibration: Calibrator.State = Calibrator.State()

    @Volatile
    var adapter: OnlineAdapter.State = OnlineAdapter.identity()

    @Volatile
    var allowlist: Allowlist.State = Allowlist.State()

    @Volatile
    var guardrails: Guardrails.State = Guardrails.identity()

    @Volatile
    var external: ExternalSnapshot = ExternalSnapshot()

    private val lastAlertMs = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val lastBookScoreMs = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun rememberMeta(
        ticker: String,
        closeTimeEpochMs: Long?,
        volume: Double?,
        openInterest: Double?,
        floorStrike: Double? = null
    ) {
        if (!CryptoMarkets.isCryptoTicker(ticker)) return
        book.rememberStrike(ticker, floorStrike)
        val last = book.last(ticker)
        val mid = last?.mid01 ?: return
        book.push(
            MarketTick(
                ticker = ticker,
                series = CryptoMarkets.inferSeries(ticker),
                yesBid = mid,
                yesAsk = mid,
                lastPrice = mid,
                volume = volume,
                openInterest = openInterest,
                closeTimeEpochMs = closeTimeEpochMs,
                source = TickSource.REST,
                receiveElapsedNanos = 0L
            )
        )
    }

    fun applySnapshot(
        ticker: String,
        yesLevels: List<Pair<Double, Double>>,
        noLevels: List<Pair<Double, Double>>,
        seq: Int? = null
    ): LocalOrderBook? = book.applySnapshot(ticker, yesLevels, noLevels, seq)

    fun applyDelta(
        ticker: String,
        price: Double,
        delta: Double,
        side: String,
        seq: Int? = null
    ): LocalOrderBook? = book.applyDelta(ticker, price, delta, side, seq)

    fun score(tick: MarketTick, settings: SignalSettings, nowMs: Long = System.currentTimeMillis()): Score? {
        return try {
            scoreUnchecked(tick, settings, nowMs)
        } catch (t: Throwable) {
            com.dirk.kalshiodds.data.local.results.CrashBreadcrumb.record("score ${tick.ticker}", t)
            com.dirk.kalshiodds.signal.ml.HeavyMlGuard.noteFailure(t, "score")
            try {
                scoreUnchecked(tick, settings.copy(heavyMlEnabled = false, extendedAiEnabled = false), nowMs)
            } catch (t2: Throwable) {
                com.dirk.kalshiodds.data.local.results.CrashBreadcrumb.record("score-light ${tick.ticker}", t2)
                null
            }
        }
    }

    private fun scoreUnchecked(tick: MarketTick, rawSettings: SignalSettings, nowMs: Long): Score? {
        if (!CryptoMarkets.isCryptoTicker(tick.ticker)) return null
        if (!rawSettings.isWatchedTicker(tick.ticker)) return null
        var settings = com.dirk.kalshiodds.signal.ml.HeavyMlGuard.apply(rawSettings)
        if (settings.heavyMlEnabled || settings.extendedAiEnabled) {
            when {
                com.dirk.kalshiodds.signal.ml.HeapGuard.isCritical() -> {
                    com.dirk.kalshiodds.signal.ml.HeavyMlGuard.noteHeapPressure()
                    settings = settings.copy(heavyMlEnabled = false, extendedAiEnabled = false)
                }
                com.dirk.kalshiodds.signal.ml.HeapGuard.isTight() -> {
                    settings = settings.copy(heavyMlEnabled = false, extendedAiEnabled = false)
                }
            }
        }
        book.push(tick, nowMs)
        if (tick.floorStrike != null) book.rememberStrike(tick.ticker, tick.floorStrike)
        val view = book.bookView(tick.ticker)
        val mid01 = tick.mid01 ?: return null
        val midPp = mid01 * 100.0
        val close = tick.closeTimeEpochMs ?: book.closeTime(tick.ticker)
        val volume = tick.volume ?: book.volume(tick.ticker)
        val oi = tick.openInterest ?: book.openInterest(tick.ticker)
        val spread = if (tick.yesBid != null && tick.yesAsk != null) {
            (tick.yesAsk - tick.yesBid).coerceAtLeast(0.0)
        } else {
            null
        }

        val tte = MarketRegime.tteRegime(close, nowMs)
        val tteSec = MarketRegime.tteSeconds(close, nowMs)
        val pts = book.series(tick.ticker)
        val vel = book.velocityPerSec(tick.ticker)
        val acc = book.accelerationPerSec(tick.ticker)
        val regime = MarketRegime.classify(pts.map { it.mid01 }, pts.map { it.nowMs }, vel)

        val ai = runCatching {
            model.predict(
                ticker = tick.ticker,
                marketMid = mid01,
                volume = volume ?: 0.0,
                closeEpochMs = close,
                nowMs = nowMs,
                openInterest = oi ?: 0.0
            )
        }.getOrNull()
        val mlpPp = ai?.yes?.times(100.0)
        val tteFrac = ((tteSec ?: 900L).toDouble() / 900.0).coerceIn(0.0, 1.0)
        val spotFeat = external.forSeries(tick.series)
        val spotRet = spotFeat?.let { it.spotReturn1m ?: it.spotReturn5m }
        heavy.pushFrame(
            ticker = tick.ticker,
            mid = mid01,
            size = tick.tradeSize ?: 0.0,
            imbalance = view.imbalance,
            aggressor = book.aggressorScore(tick.ticker),
            spot = spotRet,
            nowMs = nowMs
        )
        val midHist = pts.map { it.mid01 }
        val volForMl = if (midHist.size >= 3) {
            val mean = midHist.average()
            kotlin.math.sqrt(midHist.map { (it - mean) * (it - mean) }.average())
        } else {
            0.05
        }
        val momForMl = if (midHist.size >= 2) midHist.last() - midHist.first() else 0.0
        val pulseEarly = view.pulse
        val bookSnap = MicrostructureEncoder.snapshot(
            bestBid = tick.yesBid,
            bestAsk = tick.yesAsk,
            spread = spread,
            imbalance = view.imbalance,
            depthNear = view.depthNear,
            depthFar = view.depthFar,
            cancelSpike = pulseEarly?.cancelSpike,
            quotePull = pulseEarly?.quotePull
        )
        val extRegimePre = if (settings.extendedAiEnabled && settings.regimeClassifierEnabled) {
            RegimeClassifier.classify(
                micro = regime,
                vol = volForMl * 100.0,
                velocityPerSec = vel,
                netPp = momForMl * 100.0,
                spotAbs = spotRet?.let { abs(it) },
                nowMs = nowMs
            )
        } else {
            null
        }
        val stackOverride = extRegimePre?.let { RegimeClassifier.scaleStack(heavy.stack, it) }
        val heavyOut = try {
            heavy.infer(
            HeavyMlRuntime.Input(
                ticker = tick.ticker,
                series = tick.series,
                mid = mid01,
                volume = volume ?: 0.0,
                openInterest = oi ?: 0.0,
                tteFrac = tteFrac,
                tte = tte,
                spread = spread,
                imbalance = view.imbalance,
                aggressor = book.aggressorScore(tick.ticker),
                depthQuality = view.depthDecay,
                leadLag = book.leadLagScore(tick.series, nowMs),
                spot = spotRet,
                mlpYes = ai?.yes,
                volatility = volForMl,
                momentum = momForMl,
                bookSnap = bookSnap,
                velocityPerSec = vel,
                mids = midHist,
                nowMs = nowMs
            ),
            settings,
            stackOverride = stackOverride
            )
        } catch (t: Throwable) {
            com.dirk.kalshiodds.signal.ml.HeavyMlGuard.noteFailure(t, "heavy.infer")
            heavy.lightOutput(ai?.yes, "0.2.x blend (heavy ML failed)")
        }
        val aiPp = if (settings.heavyMlEnabled && heavyOut.usedHeavy) {
            heavyOut.ensembleYes * 100.0
        } else {
            mlpPp
        }

        val aggressor = book.aggressorScore(tick.ticker)
        val rawFlow = book.flowScore(tick.ticker)
        val flow = (0.65 * rawFlow + 0.35 * aggressor).coerceIn(-1.0, 1.0)
        val momentumPp = book.momentumPp(tick.ticker)
        val flowAdjPp = (midPp + 8.0 * tanh(flow) + 0.35 * momentumPp).coerceIn(2.0, 98.0)
        val related01 = book.relatedCryptoMid(tick.series, settings.watchedSeries)
        val relatedPp = related01?.times(100.0)

        val velAdjPp = vel?.let {
            val velPpPerSec = it * 100.0
            val accPpPerSec = (acc ?: 0.0) * 100.0
            (midPp + 6.0 * tanh(velPpPerSec / 2.0) + 2.0 * tanh(accPpPerSec / 2.0)).coerceIn(2.0, 98.0)
        }
        val imb = view.imbalance
        val imbAdjPp = imb?.let { (midPp + 8.0 * it).coerceIn(2.0, 98.0) }

        val leadLag = book.leadLagScore(tick.series, nowMs)
        val leadLagAdjPp = leadLag?.let { (midPp + 8.0 * it).coerceIn(2.0, 98.0) }

        val depthNear = view.depthNear
        val decay = view.depthDecay
        val depthQuality = when {
            decay != null && depthNear != null ->
                (0.6 * decay + 0.4 * tanh(ln(1.0 + depthNear) / 4.0)).coerceIn(0.0, 1.0)
            decay != null -> decay
            depthNear != null -> tanh(ln(1.0 + depthNear) / 4.0)
            else -> null
        }
        val depthAdjPp = if (imb != null && depthQuality != null) {
            (midPp + 6.0 * imb * depthQuality).coerceIn(2.0, 98.0)
        } else {
            null
        }

        val pulse = view.pulse
        val cancelFeat = pulse?.let { (it.cancelSpike + it.quotePull) / 2.0 }
        val cancelAdjPp = cancelFeat?.let { (midPp + 7.0 * it).coerceIn(2.0, 98.0) }

        val spotAdjPp = SpotFeatureMath.adjustPp(midPp, spotFeat)
        val spotLabel = SpotFeatureMath.label(spotFeat)

        val adapterState = adapter
        val baseW = blendWeights(
            tte = tte,
            regime = regime,
            hasAi = aiPp != null,
            hasRelated = relatedPp != null,
            hasVel = velAdjPp != null,
            hasImb = imbAdjPp != null,
            hasLeadLag = leadLagAdjPp != null,
            hasDepth = depthAdjPp != null,
            hasCancel = cancelAdjPp != null,
            hasSpot = spotAdjPp != null
        ) ?: return null
        val w = OnlineAdapter.scaleBlend(baseW, adapterState)

        val rawFair = (
            (aiPp ?: 0.0) * w.ai +
                flowAdjPp * w.flow +
                (relatedPp ?: 0.0) * w.related +
                (velAdjPp ?: 0.0) * w.velocity +
                (imbAdjPp ?: 0.0) * w.imbalance +
                (leadLagAdjPp ?: 0.0) * w.leadLag +
                (depthAdjPp ?: 0.0) * w.depth +
                (cancelAdjPp ?: 0.0) * w.cancel +
                (spotAdjPp ?: 0.0) * w.spot
            ).coerceIn(2.0, 98.0)

        val calState = calibration
        val afterTemp = Calibrator.applyPp(rawFair, calState)
        var fair = OnlineAdapter.applyPp(afterTemp, adapterState).coerceIn(2.0, 98.0)
        var delta = fair - midPp
        var predictedSide = if (delta >= 0) "YES" else "NO"

        var ev = NetExpectedValue.compute(
            fairYes = fair / 100.0,
            mid = mid01,
            spreadDollars = spread,
            feeRate = settings.feeRate,
            preferSide = predictedSide
        )
        val liquidityObs = listOfNotNull(volume, oi, depthNear).maxOrNull()
        var size = PositionSizer.suggest(
            fairSide = if (predictedSide == "YES") fair / 100.0 else 1.0 - fair / 100.0,
            contractPrice = ev.contractPrice,
            bankrollUsd = settings.bankrollUsd,
            mode = PositionSizer.modeOf(settings.useKelly),
            kellyFraction = settings.kellyFraction,
            fixedFraction = settings.fixedFraction,
            maxFraction = settings.maxBankrollFraction,
            liquidity = liquidityObs,
            depthNearMid = depthNear,
            spreadDollars = spread,
            maxSpreadCents = settings.maxSpreadCents,
            netEvPositive = ev.netEv > 0.0
        )

        val muteReason = if (settings.autoMute) {
            allowlist.muteReason(tick.series, regime.name, tte.name)
        } else {
            null
        }
        val muted = muteReason != null

        val featureDevs = OnlineAdapter.FeatureDevs(
            ai = aiPp?.minus(midPp),
            flow = flowAdjPp - midPp,
            related = relatedPp?.minus(midPp),
            velocity = velAdjPp?.minus(midPp),
            imbalance = imbAdjPp?.minus(midPp),
            leadLag = leadLagAdjPp?.minus(midPp),
            depth = depthAdjPp?.minus(midPp),
            cancel = cancelAdjPp?.minus(midPp),
            spot = spotAdjPp?.minus(midPp)
        ).asMap()

        val confidence = confidence(
            aiConfidence = ai?.confidence,
            spreadDollars = spread,
            depthNear = depthNear,
            regime = regime,
            tte = tte,
            nTicks = pts.size
        ).let { c ->
            if (settings.heavyMlEnabled && heavyOut.usedHeavy) {
                (c * (1.0 - 0.45 * (heavyOut.uncertainty / 0.25).coerceIn(0.0, 1.0))).coerceIn(0.10, 0.95)
            } else {
                c
            }
        }
        val midFollow = if (midHist.size >= 2) {
            val d = midHist.last() - midHist[midHist.lastIndex - 1]
            val signed = if (aggressor == 0.0) 0.0 else kotlin.math.sign(d) * kotlin.math.sign(aggressor)
            (signed * abs(d) * 8.0).coerceIn(-1.0, 1.0)
        } else {
            0.0
        }
        val sizeNorm = SequenceFeatures.normalizeSize(tick.tradeSize ?: depthNear ?: 0.0).toDouble()
        val extOut = if (settings.extendedAiEnabled) {
            com.dirk.kalshiodds.signal.ml.SafeMl.run("extended", fallback = { null }) {
                extended.evaluate(
                    ExtendedAiRuntime.Input(
                        ticker = tick.ticker,
                        series = tick.series,
                        mid = mid01,
                        fairYes = fair / 100.0,
                        edgePp = delta,
                        confidence = confidence,
                        uncertainty = heavyOut.uncertainty,
                        tteFrac = tteFrac,
                        tte = tte,
                        tteSeconds = tteSec,
                        vol = volForMl,
                        momentum = momForMl,
                        velocityPerSec = vel,
                        imbalance = imb,
                        aggressor = aggressor,
                        sizeNorm = sizeNorm,
                        midFollow = midFollow,
                        spread = spread,
                        depthNear = depthNear,
                        depthFar = view.depthFar,
                        depthQuality = decay,
                        cancelSpike = pulse?.cancelSpike,
                        quotePull = pulse?.quotePull,
                        bestBid = tick.yesBid,
                        bestAsk = tick.yesAsk,
                        spot = spotRet,
                        micro = regime,
                        nowMs = nowMs,
                        nTicks = pts.size,
                        configuredStake = settings.ticketStakeUsd
                    ),
                    settings,
                    heavy.stack
                )
            }
        } else {
            null
        }
        if (extOut?.fairBlendYes != null) {
            fair = (extOut.fairBlendYes!! * 100.0).coerceIn(2.0, 98.0)
            delta = fair - midPp
            predictedSide = if (delta >= 0) "YES" else "NO"
        }
        val strikeUsd = tick.floorStrike ?: book.strike(tick.ticker)
            ?: DirectionSanity.parseStrike(tick.ticker)
        val dir = DirectionSanity.apply(
            spotUsd = spotFeat?.lastPrice,
            strikeUsd = strikeUsd,
            spotReturn = spotRet,
            fairPp = fair,
            predictedSide = predictedSide
        )
        if (dir.applied) {
            fair = dir.fairPp
            predictedSide = dir.side
            delta = fair - midPp
        }
        if (extOut?.fairBlendYes != null || dir.applied) {
            ev = NetExpectedValue.compute(
                fairYes = fair / 100.0,
                mid = mid01,
                spreadDollars = spread,
                feeRate = settings.feeRate,
                preferSide = predictedSide
            )
            size = PositionSizer.suggest(
                fairSide = if (predictedSide == "YES") fair / 100.0 else 1.0 - fair / 100.0,
                contractPrice = ev.contractPrice,
                bankrollUsd = settings.bankrollUsd,
                mode = PositionSizer.modeOf(settings.useKelly),
                kellyFraction = settings.kellyFraction,
                fixedFraction = settings.fixedFraction,
                maxFraction = settings.maxBankrollFraction,
                liquidity = liquidityObs,
                depthNearMid = depthNear,
                spreadDollars = spread,
                maxSpreadCents = settings.maxSpreadCents,
                netEvPositive = ev.netEv > 0.0
            )
        }
        val combinedSpotLabel = listOfNotNull(spotLabel, dir.note).joinToString(" · ").ifBlank { null }
        val filter = SkipFilter.evaluate(
            confidence = confidence,
            spreadDollars = spread,
            volume = volume,
            openInterest = oi,
            depthNearMid = depthNear,
            settings = settings
        )
        val uncBlocked = settings.uncertaintyGateEnabled &&
            settings.heavyMlEnabled &&
            heavyOut.usedHeavy &&
            !heavyOut.uncertaintyPassed
        val extBlocked = extOut?.blockReason
        val passed = filter.passed && !muted && !uncBlocked && extBlocked == null
        val skipReason = when {
            muted -> muteReason
            uncBlocked -> String.format(
                java.util.Locale.US,
                "uncertainty %.2f > %.2f",
                heavyOut.uncertainty,
                settings.maxUncertainty
            )
            extBlocked != null -> extBlocked
            else -> filter.reason
        }
        val reason = buildReason(
            aiPp = aiPp,
            flow = flow,
            momentumPp = momentumPp,
            relatedPp = relatedPp,
            midPp = midPp,
            fairPp = fair,
            deltaPp = delta,
            velocityPerSec = vel,
            imbalance = imb,
            leadLag = leadLag,
            regime = regime,
            tte = tte,
            calibrated = calState.ready,
            passedFilter = passed,
            netEdgePp = ev.netEdgePp,
            muted = muted,
            adapterReady = adapterState.ready,
            spotLabel = combinedSpotLabel,
            heavyNote = if (settings.heavyMlEnabled) heavyOut.note else null,
            uncertaintyBlocked = uncBlocked,
            extendedNote = extOut?.note
        )
        heavy.rememberInference(
            ticker = tick.ticker,
            series = tick.series,
            tte = tte.name,
            out = heavyOut,
            mid = mid01,
            edgePp = delta,
            nowMs = nowMs
        )
        return Score(
            fairValuePp = fair,
            marketMidPp = midPp,
            deltaPp = delta,
            reason = reason,
            aiPp = aiPp,
            flowPp = flowAdjPp,
            relatedPp = relatedPp,
            velocityPp = velAdjPp,
            imbalancePp = imbAdjPp,
            velocityPerSec = vel,
            accelerationPerSec = acc,
            imbalance = imb,
            rawFairValuePp = rawFair,
            leadLagPp = leadLagAdjPp,
            depthPp = depthAdjPp,
            cancelPp = cancelAdjPp,
            leadLag = leadLag,
            depthNearMid = depthNear,
            depthDecay = decay,
            confidence = confidence,
            regime = regime,
            tteRegime = tte,
            tteSeconds = tteSec,
            passedFilter = passed,
            skipReason = skipReason,
            predictedSide = predictedSide,
            calibrated = calState.ready || adapterState.ready,
            spreadDollars = spread,
            netEvDollars = ev.netEv,
            netEdgePp = ev.netEdgePp,
            suggestedContracts = size.contracts,
            sizingNote = size.reason,
            muted = muted,
            muteReason = muteReason,
            feePerContract = ev.feePerContract,
            halfSpread = ev.halfSpread,
            spotLabel = combinedSpotLabel,
            spotPp = spotAdjPp,
            adapterReady = adapterState.ready,
            featureDevs = featureDevs,
            mlpPp = mlpPp,
            cnnPp = heavyOut.cnnYes?.times(100.0),
            lstmPp = heavyOut.lstmYes?.times(100.0),
            gbmPp = heavyOut.gbmYes?.times(100.0),
            uncertainty = if (settings.heavyMlEnabled) heavyOut.uncertainty else null,
            uncertaintyPassed = !uncBlocked,
            timeToMoveSec = heavyOut.timeToMoveSec,
            midVolPp = heavyOut.midVol?.times(100.0),
            pFill = heavyOut.pFill,
            heavyMl = heavyOut.usedHeavy,
            ensembleNote = if (settings.heavyMlEnabled) heavyOut.note else null,
            sessionTag = extOut?.regime?.session?.name,
            newsShock = extOut?.regime?.newsShock == true,
            anomalyScore = extOut?.anomaly?.score,
            anomalyNote = extOut?.anomaly?.takeIf { it.anomalous }?.note,
            survivalYesPp = extOut?.survival?.pYes?.times(100.0),
            rlStakeUsd = extOut?.rl?.stakeUsd,
            rlNote = extOut?.rl?.note,
            newsLabel = extOut?.newsLabel,
            flowNote = extOut?.flow?.note,
            mmShadowPp = extOut?.mm?.shadowYes?.times(100.0),
            conformalSet = extOut?.conformal?.set?.sorted()?.joinToString(",", "{", "}"),
            conformalAmbiguous = extOut?.conformal?.ambiguous == true,
            metaTake = extOut?.meta?.take,
            metaNote = extOut?.meta?.note,
            pathSurvive = extOut?.path?.pSurvive,
            extendedNote = extOut?.note,
            directionalLock = dir.applied,
            spotVsTargetUsd = dir.spotVsTargetUsd
        )
    }

    fun maybeAlert(
        tick: MarketTick,
        settings: SignalSettings,
        nowMs: Long = System.currentTimeMillis(),
        precomputed: Score? = null
    ): SignalAlert? {
        val scored = precomputed ?: score(tick, settings, nowMs) ?: return null
        if (!scored.passedFilter) return null
        if (scored.muted) return null
        if (guardrails.paused) return null
        val edgeForAlert = if (settings.rankByNetEv) {
            scored.netEdgePp ?: scored.deltaPp
        } else {
            scored.deltaPp
        }
        if (abs(edgeForAlert) < settings.edgeThresholdPp) return null
        val last = lastAlertMs[tick.ticker] ?: 0L
        if (nowMs - last < settings.debounceMs) return null
        lastAlertMs[tick.ticker] = nowMs
        return SignalAlert(
            id = idFactory(),
            ticker = tick.ticker,
            series = tick.series,
            deltaPp = scored.deltaPp,
            fairValuePp = scored.fairValuePp,
            marketMidPp = scored.marketMidPp,
            reason = scored.reason,
            createdAtMs = nowMs,
            receiveElapsedNanos = tick.receiveElapsedNanos,
            regime = scored.regime.shortLabel,
            tteRegime = scored.tteRegime.shortLabel,
            confidence = scored.confidence,
            passedFilter = true,
            predictedSide = scored.predictedSide
        )
    }

    fun maybeAlertFromBook(
        ticker: String,
        settings: SignalSettings,
        receiveElapsedNanos: Long,
        nowMs: Long = System.currentTimeMillis()
    ): SignalAlert? {
        val last = lastBookScoreMs[ticker] ?: 0L
        if (nowMs - last < BOOK_SCORE_MIN_INTERVAL_MS) return null
        val tick = book.tickFromBook(ticker, receiveElapsedNanos, nowMs) ?: return null
        lastBookScoreMs[ticker] = nowMs
        return maybeAlert(tick, settings, nowMs)
    }

    fun blendWeights(
        tte: TteRegime,
        regime: RegimeTag,
        hasAi: Boolean,
        hasRelated: Boolean,
        hasVel: Boolean,
        hasImb: Boolean,
        hasLeadLag: Boolean,
        hasDepth: Boolean,
        hasCancel: Boolean,
        hasSpot: Boolean = false
    ): BlendWeights? {
        val late = tte == TteRegime.LATE
        var wAi = if (hasAi) if (late) W_AI_LATE else W_AI else 0.0
        var wFlow = if (late) W_FLOW_LATE else W_FLOW
        var wRel = if (hasRelated) if (late) W_RELATED_LATE else W_RELATED else 0.0
        var wVel = if (hasVel) if (late) W_VELOCITY_LATE else W_VELOCITY else 0.0
        var wImb = if (hasImb) if (late) W_IMBALANCE_LATE else W_IMBALANCE else 0.0
        var wLl = if (hasLeadLag) if (late) W_LEADLAG_LATE else W_LEADLAG else 0.0
        var wDep = if (hasDepth) if (late) W_DEPTH_LATE else W_DEPTH else 0.0
        var wCan = if (hasCancel) if (late) W_CANCEL_LATE else W_CANCEL else 0.0
        var wSpot = if (hasSpot) if (late) W_SPOT_LATE else W_SPOT else 0.0

        when (regime) {
            RegimeTag.TREND -> {
                wVel *= 1.25
                wLl *= 1.20
            }
            RegimeTag.CHOP -> {
                wVel *= 0.55
                wAi *= 1.15
            }
            RegimeTag.VOL_SPIKE -> {
                wCan *= 1.25
                wDep *= 1.20
                wAi *= 0.85
            }
            RegimeTag.QUIET -> {
                wFlow *= 0.85
                wRel *= 1.10
            }
        }

        val sum = wAi + wFlow + wRel + wVel + wImb + wLl + wDep + wCan + wSpot
        if (sum < 1e-9) return null
        return BlendWeights(
            ai = wAi / sum,
            flow = wFlow / sum,
            related = wRel / sum,
            velocity = wVel / sum,
            imbalance = wImb / sum,
            leadLag = wLl / sum,
            depth = wDep / sum,
            cancel = wCan / sum,
            spot = wSpot / sum
        )
    }

    private fun confidence(
        aiConfidence: Double?,
        spreadDollars: Double?,
        depthNear: Double?,
        regime: RegimeTag,
        tte: TteRegime,
        nTicks: Int
    ): Double {
        var c = aiConfidence ?: 0.48
        c *= (0.75 + 0.25 * minOf(nTicks / 12.0, 1.0))
        spreadDollars?.let { c *= (1.0 - (it / 0.20).coerceIn(0.0, 0.40)) }
        depthNear?.let { c *= (0.72 + 0.28 * tanh(ln(1.0 + it) / 4.0)) }
        c *= when (regime) {
            RegimeTag.VOL_SPIKE -> 0.75
            RegimeTag.CHOP -> 0.85
            RegimeTag.QUIET -> 0.95
            RegimeTag.TREND -> 1.05
        }
        if (tte == TteRegime.LATE) c *= 0.90
        return c.coerceIn(0.10, 0.95)
    }

    private fun buildReason(
        aiPp: Double?,
        flow: Double,
        momentumPp: Double,
        relatedPp: Double?,
        midPp: Double,
        fairPp: Double,
        deltaPp: Double,
        velocityPerSec: Double?,
        imbalance: Double?,
        leadLag: Double?,
        regime: RegimeTag,
        tte: TteRegime,
        calibrated: Boolean,
        passedFilter: Boolean,
        netEdgePp: Double? = null,
        muted: Boolean = false,
        adapterReady: Boolean = false,
        spotLabel: String? = null,
        heavyNote: String? = null,
        uncertaintyBlocked: Boolean = false,
        extendedNote: String? = null
    ): String {
        val parts = mutableListOf<String>()
        parts += "${regime.shortLabel}/${tte.shortLabel}"
        if (calibrated) parts += "cal"
        if (adapterReady) parts += "adapt"
        if (aiPp != null) {
            parts += String.format(java.util.Locale.US, "AI %.0f%% vs mkt %.0f%%", aiPp, midPp)
        } else {
            parts += String.format(java.util.Locale.US, "mkt %.0f%%", midPp)
        }
        parts += when {
            flow > 0.25 -> "flow YES"
            flow < -0.25 -> "flow NO"
            abs(momentumPp) >= 2.0 -> String.format(java.util.Locale.US, "mom %+.1fpp", momentumPp)
            else -> "flow flat"
        }
        val velPp = velocityPerSec?.times(100.0)
        if (velPp != null && abs(velPp) >= 0.4) {
            parts += String.format(java.util.Locale.US, "vel %+.1fpp/s", velPp)
        }
        if (imbalance != null && abs(imbalance) >= 0.12) {
            val label = if (imbalance >= 0) "book bid" else "book ask"
            parts += String.format(java.util.Locale.US, "%s %+.0f%%", label, imbalance * 100.0)
        }
        if (leadLag != null && abs(leadLag) >= 0.12) {
            parts += String.format(java.util.Locale.US, "lead %+.0f", leadLag * 100.0)
        }
        if (relatedPp != null) {
            val label = if (relatedPp >= midPp) "related crypto higher" else "related crypto lower"
            parts += String.format(java.util.Locale.US, "%s (%.0f%%)", label, relatedPp)
        }
        parts += String.format(java.util.Locale.US, "Δ %+.1fpp (fv %.0f%%)", deltaPp, fairPp)
        if (netEdgePp != null) {
            parts += String.format(java.util.Locale.US, "net %+.1fpp", netEdgePp)
        }
        if (spotLabel != null) parts += spotLabel
        if (heavyNote != null) parts += heavyNote
        if (extendedNote != null) parts += extendedNote
        if (muted) parts += "muted"
        if (uncertaintyBlocked) parts += "unc-gated"
        if (!passedFilter) parts += "filtered"
        return parts.joinToString(" · ")
    }

    companion object {
        const val W_AI = 0.30
        const val W_FLOW = 0.12
        const val W_RELATED = 0.08
        const val W_VELOCITY = 0.10
        const val W_IMBALANCE = 0.10
        const val W_LEADLAG = 0.10
        const val W_DEPTH = 0.10
        const val W_CANCEL = 0.10

        const val W_AI_LATE = 0.20
        const val W_FLOW_LATE = 0.14
        const val W_RELATED_LATE = 0.04
        const val W_VELOCITY_LATE = 0.14
        const val W_IMBALANCE_LATE = 0.14
        const val W_LEADLAG_LATE = 0.06
        const val W_DEPTH_LATE = 0.14
        const val W_CANCEL_LATE = 0.14
        const val W_SPOT = 0.08
        const val W_SPOT_LATE = 0.06

        /** Book deltas update depth immediately; re-score at most this often. */
        const val BOOK_SCORE_MIN_INTERVAL_MS = 250L
    }
}
