package com.dirk.kalshiodds.signal.ml

import com.dirk.kalshiodds.signal.config.SignalSettings
import com.dirk.kalshiodds.signal.engine.TteRegime

/**
 * On-device 0.3.0 stack. Phone-fast Kotlin inference; optional student
 * weights from assets. Disable in Settings to fall back to the 0.2.x MLP blend.
 */
class HeavyMlRuntime(
    cnn: TemporalCnn = TemporalCnn.defaults(),
    lstm: TinyLstm = TinyLstm.defaults(),
    backbone: SharedBackbone = SharedBackbone.defaults(),
    encoder: MicrostructureEncoder = MicrostructureEncoder.defaults(),
    heads: MultiTaskHeads = MultiTaskHeads.defaults(),
    gbm: GbmModel = GbmBooster.defaultModel(),
    val sequences: SequenceBuffer = SequenceBuffer()
) {
    private val cnnModel = cnn
    private val lstmModel = lstm
    private val backboneModel = backbone
    private val ae = encoder
    val heads = heads
    private var gbmModel = gbm

    fun installGbm(model: GbmModel) {
        gbmModel = model
    }

    @Volatile
    var stack: EnsembleStack.Weights = EnsembleStack.identity()

    @Volatile
    var regimeCal: RegimeCalibrator.State = RegimeCalibrator.identity()

    @Volatile
    var lastSettledAtMs: Long = 0L

    val replay = ReplayBuffer()

    data class Input(
        val ticker: String,
        val series: String,
        val mid: Double,
        val volume: Double,
        val openInterest: Double,
        val tteFrac: Double,
        val tte: TteRegime,
        val spread: Double?,
        val imbalance: Double?,
        val aggressor: Double,
        val depthQuality: Double?,
        val leadLag: Double?,
        val spot: Double?,
        val mlpYes: Double?,
        val volatility: Double,
        val momentum: Double,
        val bookSnap: FloatArray?,
        val velocityPerSec: Double?,
        val mids: List<Double>,
        val suggestedContracts: Double? = null,
        val nowMs: Long
    )

    data class Output(
        val ensembleYes: Double,
        val mlpYes: Double?,
        val cnnYes: Double?,
        val lstmYes: Double?,
        val gbmYes: Double?,
        val uncertainty: Double,
        val uncertaintyPassed: Boolean,
        val uncertaintyMethod: String,
        val timeToMoveSec: Double?,
        val midVol: Double?,
        val pFill: Double?,
        val usedHeavy: Boolean,
        val note: String,
        val backbone: FloatArray? = null,
        val tabular: FloatArray? = null,
        val calibrated: Boolean = false
    )

    fun pushFrame(
        ticker: String,
        mid: Double,
        size: Double,
        imbalance: Double?,
        aggressor: Double,
        spot: Double?,
        nowMs: Long
    ) {
        sequences.push(
            ticker,
            SequenceFrame(
                mid = mid.toFloat().coerceIn(0f, 1f),
                size = SequenceFeatures.normalizeSize(size),
                imbalance = (imbalance ?: 0.0).toFloat().coerceIn(-1f, 1f),
                aggressor = aggressor.toFloat().coerceIn(-1f, 1f),
                spot = (spot ?: 0.0).toFloat().coerceIn(-1f, 1f),
                tMs = nowMs
            )
        )
    }

    fun lightOutput(mlp: Double?, note: String = "0.2.x blend (fail-soft)"): Output {
        val p = mlp?.let { MlMath.clip01(it) } ?: 0.5
        return Output(
            ensembleYes = p,
            mlpYes = mlp,
            cnnYes = null,
            lstmYes = null,
            gbmYes = null,
            uncertainty = 0.06,
            uncertaintyPassed = true,
            uncertaintyMethod = "fail-soft",
            timeToMoveSec = null,
            midVol = null,
            pFill = null,
            usedHeavy = false,
            note = note
        )
    }

    /**
     * Synchronized: REST annotate and WS ticks share one runtime. Concurrent
     * mutation of [heads]/[stack]/[lastActivation] was a CME / native-adjacent
     * crash path after 0.3.0.
     */
    @Synchronized
    fun infer(
        input: Input,
        settings: SignalSettings,
        stackOverride: EnsembleStack.Weights? = null
    ): Output {
        return try {
            inferUnchecked(input, settings, stackOverride)
        } catch (t: Throwable) {
            HeavyMlGuard.noteFailure(t, "heavy.infer")
            lightOutput(input.mlpYes, "0.2.x blend (heavy ML failed)")
        }
    }

    private fun inferUnchecked(
        input: Input,
        settings: SignalSettings,
        stackOverride: EnsembleStack.Weights?
    ): Output {
        val mlp = input.mlpYes?.let { MlMath.clip01(it) }
        if (!settings.heavyMlEnabled || HeavyMlGuard.sessionDisabled) {
            return fallback(mlp, input, settings, used = false, note = "0.2.x blend (heavy ML off)")
        }

        val seqReady = settings.sequenceModelEnabled && sequences.ready(input.ticker, input.nowMs)
        val allowExtras = seqReady || stack.ready
        var cnnYes: Double? = null
        var lstmYes: Double? = null
        var backbone: FloatArray? = null
        var headsOut: MultiTaskOutput? = null
        val mc = mutableListOf<Double>()

        if (seqReady) {
            val frames = sequences.window(input.ticker, input.nowMs)
            val cnnPool = cnnModel.encode(frames)
            val lstmH = lstmModel.encode(frames)
            backbone = backboneModel.encode(cnnPool, lstmH, input.series)
            headsOut = this.heads.infer(backbone)
            cnnYes = headsOut.pYes
            lstmYes = lstmHeadYes(lstmH, input.series)
            // Skip 3× MC-dropout CNN re-encodes. Ensemble variance is enough
            // and the extra allocations were a mid-session OOM source.
        }

        val snap = input.bookSnap
        val microZ = if (snap != null) ae.surprise(snap) else 0.0
        val microLatent = snap?.let { ae.encode(it) }
        val microFeat = microLatent?.average()?.toDouble() ?: 0.0

        val cnnForGbm = cnnYes ?: mlp ?: 0.5
        val tabular = GbmBooster.tabular(
            mid = input.mid,
            volume = input.volume,
            tteFrac = input.tteFrac,
            volatility = input.volatility,
            momentum = input.momentum,
            seriesId = SequenceFeatures.seriesIndex(input.series).toDouble() / 2.0,
            openInterest = input.openInterest,
            imbalance = input.imbalance ?: 0.0,
            aggressor = input.aggressor,
            depthQuality = input.depthQuality ?: 0.0,
            leadLag = input.leadLag ?: 0.0,
            spot = input.spot ?: 0.0,
            cnnP = cnnForGbm,
            microZ = microZ + microFeat,
            spread = input.spread ?: 0.0
        )
        val gbmYes = if (settings.gbmEnabled && allowExtras) {
            GbmBooster.predictYes(tabular, gbmModel)
        } else {
            null
        }

        val members = listOf(
            EnsembleStack.Member("mlp", mlp ?: 0.5, enabled = mlp != null),
            EnsembleStack.Member("cnn", cnnYes ?: 0.5, enabled = cnnYes != null),
            EnsembleStack.Member("lstm", lstmYes ?: 0.5, enabled = lstmYes != null),
            EnsembleStack.Member("gbm", gbmYes ?: 0.5, enabled = gbmYes != null)
        )
        val stacked = EnsembleStack.blend(members, stackOverride ?: stack)
        val rawYes = if (stacked.used.isEmpty()) mlp ?: 0.5 else stacked.pYes
        val calYes = RegimeCalibrator.apply(rawYes, input.series, input.tte.name, regimeCal)
        val gate = UncertaintyGate.evaluate(
            members = stacked.members.map { it.pYes },
            threshold = settings.maxUncertainty,
            enabled = settings.uncertaintyGateEnabled,
            mcSamples = mc
        )

        val ttm = headsOut?.timeToMoveSec
            ?: MultiTaskHeuristics.timeToMoveSec(input.velocityPerSec)
        val vol = headsOut?.midVol
            ?: MultiTaskHeuristics.midVol(input.mids)
        val fill = headsOut?.pFill
            ?: MultiTaskHeuristics.pFill(input.depthQuality, input.suggestedContracts, input.spread)

        val used = stacked.used.any { it != "mlp" }
        val note = buildString {
            append(stacked.used.joinToString("+").ifBlank { "mlp" })
            append(" · unc ").append(String.format(java.util.Locale.US, "%.2f", gate.uncertainty))
            append(" (").append(gate.method).append(")")
            if (regimeCal.bucket(input.series, input.tte.name).ready) append(" · regime-cal")
        }

        return Output(
            ensembleYes = calYes,
            mlpYes = mlp,
            cnnYes = cnnYes,
            lstmYes = lstmYes,
            gbmYes = gbmYes,
            uncertainty = gate.uncertainty,
            uncertaintyPassed = gate.passed,
            uncertaintyMethod = gate.method,
            timeToMoveSec = ttm,
            midVol = vol,
            pFill = fill,
            usedHeavy = used,
            note = note,
            backbone = null,
            tabular = null,
            calibrated = regimeCal.bucket(input.series, input.tte.name).ready
        )
    }

    @Synchronized
    fun rememberInference(
        ticker: String,
        series: String,
        tte: String,
        out: Output,
        mid: Double,
        edgePp: Double,
        nowMs: Long
    ) {
        // Open predictions are persisted by SignalHub; this only keeps the
        // latest activation so a later settlement can attach it.
        lastActivation[ticker] = Pending(
            series = series,
            tte = tte,
            out = out.copy(backbone = null, tabular = null),
            mid = mid,
            edgePp = edgePp,
            atMs = nowMs
        )
        while (lastActivation.size > MAX_PENDING) {
            val first = lastActivation.keys.firstOrNull() ?: break
            lastActivation.remove(first)
        }
    }

    @Synchronized
    fun attachSettlement(ticker: String, outcomeYes: Boolean, settledAtMs: Long): ReplaySample? {
        val pending = lastActivation[ticker] ?: return null
        val o = pending.out
        val sample = ReplaySample(
            ticker = ticker,
            series = pending.series,
            tte = pending.tte,
            pYes = o.ensembleYes,
            outcomeYes = outcomeYes,
            settledAtMs = settledAtMs,
            backbone = o.backbone?.toList().orEmpty(),
            tabular = o.tabular?.toList().orEmpty(),
            mlpYes = o.mlpYes,
            cnnYes = o.cnnYes,
            lstmYes = o.lstmYes,
            gbmYes = o.gbmYes,
            mid = pending.mid,
            edgePp = pending.edgePp
        )
        replay.add(sample)
        return sample
    }

    @Synchronized
    fun applySettlements(
        samples: List<ReplaySample>,
        calSamples: List<RegimeCalibrator.Sample>,
        enabled: Boolean
    ) {
        if (!enabled) return
        val fresh = samples.filter { it.settledAtMs > lastSettledAtMs }.sortedBy { it.settledAtMs }
        if (fresh.isNotEmpty()) {
            fresh.forEach { replay.add(it) }
            val tuned = ContinualFineTune.update(heads.weights, stack, fresh)
            heads.weights = tuned.heads
            stack = tuned.stack
            lastSettledAtMs = fresh.maxOf { it.settledAtMs }
        }
        if (calSamples.isNotEmpty()) {
            regimeCal = RegimeCalibrator.fit(calSamples)
        }
    }

    private val lastActivation = linkedMapOf<String, Pending>()

    companion object {
        const val MAX_PENDING = 24
    }

    private data class Pending(
        val series: String,
        val tte: String,
        val out: Output,
        val mid: Double,
        val edgePp: Double,
        val atMs: Long
    )

    private fun lstmHeadYes(h: FloatArray, series: String): Double {
        val emb = backboneModel.series.get(series)
        var z = 0.0
        for (i in h.indices) z += h[i] * if (i == 0 || i == 2) 0.35 else 0.08
        for (i in emb.indices) z += emb[i] * 0.05
        return MlMath.clip01(MlMath.sigmoid(z))
    }

    private fun fallback(
        mlp: Double?,
        input: Input,
        settings: SignalSettings,
        used: Boolean,
        note: String
    ): Output {
        val p = mlp ?: 0.5
        val gate = UncertaintyGate.evaluate(
            members = listOfNotNull(mlp),
            threshold = settings.maxUncertainty,
            enabled = settings.uncertaintyGateEnabled && used
        )
        return Output(
            ensembleYes = p,
            mlpYes = mlp,
            cnnYes = null,
            lstmYes = null,
            gbmYes = null,
            uncertainty = gate.uncertainty,
            uncertaintyPassed = true,
            uncertaintyMethod = gate.method,
            timeToMoveSec = MultiTaskHeuristics.timeToMoveSec(input.velocityPerSec),
            midVol = MultiTaskHeuristics.midVol(input.mids),
            pFill = MultiTaskHeuristics.pFill(input.depthQuality, input.suggestedContracts, input.spread),
            usedHeavy = used,
            note = note
        )
    }
}
