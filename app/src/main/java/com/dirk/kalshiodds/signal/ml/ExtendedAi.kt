package com.dirk.kalshiodds.signal.ml

import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.engine.RegimeTag
import com.dirk.kalshiodds.signal.engine.TteRegime
import kotlin.math.abs

/**
 * Orchestrates capabilities 10–19. Phone-fast; each piece can be
 * disabled in Settings. Never places an order.
 */
class ExtendedAiRuntime(
    val rl: RlSizer = RlSizer(),
    val meta: MetaLabeler = MetaLabeler(),
    val flow: RivalFlowCluster = RivalFlowCluster(),
    val mm: BayesianMmShadow = BayesianMmShadow()
) {
    @Volatile
    var conformal: ConformalSets.State = ConformalSets.State()

    @Volatile
    var news: NewsPulseSnapshot = NewsPulseSnapshot()

    @Volatile
    var lastSettledAtMs: Long = 0L

    private val flicker = linkedMapOf<String, Flicker>()

    data class Input(
        val ticker: String,
        val series: String,
        val mid: Double,
        val fairYes: Double,
        val edgePp: Double,
        val confidence: Double,
        val uncertainty: Double,
        val tteFrac: Double,
        val tte: TteRegime,
        val tteSeconds: Long?,
        val vol: Double,
        val momentum: Double,
        val velocityPerSec: Double?,
        val imbalance: Double?,
        val aggressor: Double,
        val sizeNorm: Double,
        val midFollow: Double,
        val spread: Double?,
        val depthNear: Double?,
        val depthFar: Double?,
        val depthQuality: Double?,
        val cancelSpike: Double?,
        val quotePull: Double?,
        val bestBid: Double?,
        val bestAsk: Double?,
        val spot: Double?,
        val micro: RegimeTag,
        val nowMs: Long,
        val nTicks: Int,
        val configuredStake: Double
    )

    data class Output(
        val regime: ExtendedRegime,
        val stack: EnsembleStack.Weights? = null,
        val anomaly: AnomalyDetector.Result,
        val survival: SurvivalModel.Result?,
        val rl: RlSizer.Advice?,
        val newsTilt: Double,
        val newsLabel: String?,
        val flow: FlowClusterResult?,
        val mm: BayesianMmShadow.Result?,
        val conformal: ConformalSets.Result?,
        val meta: MetaLabeler.Result?,
        val path: PathSimulator.Result?,
        val fairBlendYes: Double?,
        val blockReason: String?,
        val note: String
    )

    fun noteFlicker(ticker: String, bid: Double?, ask: Double?, nowMs: Long): Double {
        val prev = flicker[ticker]
        val flipped = prev != null && (
            (bid != null && prev.bid != null && abs(bid - prev.bid) >= 0.005) ||
                (ask != null && prev.ask != null && abs(ask - prev.ask) >= 0.005)
            )
        val window = if (prev == null || nowMs - prev.windowStartMs > 1_000L) {
            Flicker(bid, ask, nowMs, if (flipped) 1 else 0, nowMs)
        } else {
            prev.copy(
                bid = bid,
                ask = ask,
                tMs = nowMs,
                flips = prev.flips + if (flipped) 1 else 0
            )
        }
        flicker[ticker] = window
        if (flicker.size > 64) flicker.remove(flicker.keys.first())
        val dt = ((nowMs - window.windowStartMs).coerceAtLeast(1L)) / 1000.0
        return window.flips / dt
    }

    fun evaluate(input: Input, settings: SignalSettings, stack: EnsembleStack.Weights): Output {
        return try {
            evaluateUnchecked(input, settings, stack)
        } catch (t: Throwable) {
            HeavyMlGuard.noteFailure(t, "extended")
            idle("extended AI failed — 0.2.x blend")
        }
    }

    private fun evaluateUnchecked(input: Input, settings: SignalSettings, stack: EnsembleStack.Weights): Output {
        if (!settings.extendedAiEnabled || HeavyMlGuard.sessionDisabled) {
            return idle("extended AI off")
        }
        val regime = if (settings.regimeClassifierEnabled) {
            RegimeClassifier.classify(
                micro = input.micro,
                vol = input.vol * 100.0,
                velocityPerSec = input.velocityPerSec,
                netPp = input.momentum * 100.0,
                spotAbs = input.spot?.let { abs(it) },
                nowMs = input.nowMs
            )
        } else {
            ExtendedRegime(input.micro, RegimeClassifier.sessionOf(input.nowMs), false, emptyMap(), input.micro.shortLabel)
        }
        val scaled = if (settings.regimeClassifierEnabled) {
            RegimeClassifier.scaleStack(stack, regime)
        } else {
            null
        }
        val flick = noteFlicker(input.ticker, input.bestBid, input.bestAsk, input.nowMs)
        val anomaly = AnomalyDetector.evaluate(
            cancelSpike = input.cancelSpike,
            quotePull = input.quotePull,
            depthNear = input.depthNear,
            depthFar = input.depthFar,
            spread = input.spread,
            flickerPerSec = flick,
            enabled = settings.anomalyGateEnabled
        )
        val voteReady = input.nTicks >= 8
        val survival = if (settings.survivalModelEnabled) {
            SurvivalModel.predict(
                mid = input.mid,
                tteFrac = input.tteFrac,
                momentum = input.momentum,
                imbalance = input.imbalance,
                velocityPerSec = input.velocityPerSec,
                spot = input.spot
            )
        } else null
        val rlAdvice = if (settings.rlSizerEnabled) {
            rl.suggest(input.edgePp, input.confidence, input.uncertainty, input.tteFrac, input.configuredStake)
        } else null
        val newsTilt = if (settings.newsPulseEnabled) news.forSeries(input.series) else 0.0
        val newsLabel = if (settings.newsPulseEnabled) news.label(input.series) else null
        val flowR = if (settings.rivalFlowEnabled) {
            flow.observe(input.aggressor, input.midFollow, input.sizeNorm)
        } else null
        val mmR = if (settings.bayesianMmEnabled) {
            mm.update(input.ticker, input.mid, input.imbalance, input.cancelSpike, input.depthQuality)
        } else null
        val conf = if (settings.conformalEnabled) {
            ConformalSets.predict(input.fairYes, conformal)
        } else null
        val metaR = if (settings.metaLabelEnabled) {
            meta.predict(abs(input.edgePp), input.confidence, input.uncertainty, input.spread, anomaly.score)
        } else null
        val path = if (settings.pathSimEnabled) {
            PathSimulator.simulate(
                mid = input.mid,
                fairYes = input.fairYes,
                tteSeconds = input.tteSeconds,
                vol = input.vol,
                velocityPerSec = input.velocityPerSec,
                regime = input.micro
            )
        } else null

        val voters = mutableListOf<Pair<Double, Double>>()
        if (voteReady) {
            if (survival != null) voters += survival.pYes to 0.22
            if (mmR != null) voters += mmR.shadowYes to 0.18
            if (settings.newsPulseEnabled && abs(newsTilt) >= 0.04) {
                voters += MlMath.clip01(input.mid + 0.06 * newsTilt) to 0.08
            }
        }
        val fairBlend = if (voters.isEmpty()) {
            null
        } else {
            val w = 0.52 + (flowR?.boost ?: 0.0)
            val num = w * input.fairYes + voters.sumOf { it.first * it.second }
            val den = w + voters.sumOf { it.second }
            MlMath.clip01(num / den)
        }

        val block = when {
            settings.anomalyGateEnabled && anomaly.block -> "anomaly ${anomaly.note}"
            settings.conformalEnabled && conf?.ready == true && conf.ambiguous -> conf.note
            settings.metaLabelEnabled && metaR?.ready == true && metaR.take.not() -> "meta-skip ${metaR.note}"
            settings.pathSimEnabled && path != null && path.pSurvive < 0.35 && voteReady ->
                String.format(java.util.Locale.US, "path P(edge) %.0f%%", path.pSurvive * 100.0)
            else -> null
        }

        val note = listOfNotNull(
            regime.label,
            anomaly.takeIf { it.anomalous }?.note,
            survival?.note,
            rlAdvice?.takeIf { it.ready }?.note,
            newsLabel,
            flowR?.note,
            mmR?.note,
            conf?.note,
            metaR?.note,
            path?.note
        ).joinToString(" · ")

        return Output(
            regime = regime,
            stack = scaled,
            anomaly = anomaly,
            survival = survival,
            rl = rlAdvice,
            newsTilt = newsTilt,
            newsLabel = newsLabel,
            flow = flowR,
            mm = mmR,
            conformal = conf,
            meta = metaR,
            path = path,
            fairBlendYes = fairBlend,
            blockReason = block,
            note = note
        )
    }

    fun learnFromSettlements(
        samples: List<ReplaySample>,
        enabled: Boolean
    ) {
        if (!enabled) return
        val fresh = samples.filter { it.settledAtMs > lastSettledAtMs }.sortedBy { it.settledAtMs }
        val calPairs = samples.map { it.pYes to it.outcomeYes }
        if (calPairs.size >= ConformalSets.MIN_SAMPLES) {
            conformal = ConformalSets.fit(calPairs)
        }
        for (s in fresh) {
            val edge = s.edgePp ?: 0.0
            val hit = s.outcomeYes == (s.pYes >= 0.5)
            rl.update(
                edgePp = edge,
                confidence = 0.55,
                uncertainty = 0.08,
                tteFrac = if (s.tte.equals("LATE", true)) 0.15 else 0.7,
                actionIndex = rl.policyIndex(edge, 0.55, 0.08, 0.5),
                reward = RlSizer.rewardFromSettlement(hit, edge)
            )
            meta.update(
                absEdgePp = abs(edge),
                confidence = 0.55,
                uncertainty = 0.08,
                spread = null,
                anomaly = 0.0,
                primaryHit = hit
            )
        }
        if (fresh.isNotEmpty()) lastSettledAtMs = fresh.maxOf { it.settledAtMs }
    }

    private fun idle(note: String) = Output(
        regime = ExtendedRegime(RegimeTag.QUIET, SessionTag.US, false, emptyMap(), "off"),
        anomaly = AnomalyDetector.Result(0.0, emptyList(), false, "off"),
        survival = null,
        rl = null,
        newsTilt = 0.0,
        newsLabel = null,
        flow = null,
        mm = null,
        conformal = null,
        meta = null,
        path = null,
        fairBlendYes = null,
        blockReason = null,
        note = note
    )

    private data class Flicker(
        val bid: Double?,
        val ask: Double?,
        val tMs: Long,
        val flips: Int,
        val windowStartMs: Long
    )
}
