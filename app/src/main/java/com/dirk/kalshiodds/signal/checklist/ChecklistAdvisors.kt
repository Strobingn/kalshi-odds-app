package com.dirk.kalshiodds.signal.checklist

import com.dirk.kalshiodds.domain.MarketUiModel
import com.dirk.kalshiodds.signal.config.SignalConstants
import com.dirk.kalshiodds.signal.engine.RegimeTag
import com.dirk.kalshiodds.signal.ml.ConformalSets
import com.dirk.kalshiodds.signal.ml.PathSimulator
import com.dirk.kalshiodds.signal.ml.RlSizer
import com.dirk.kalshiodds.signal.ml.SurvivalModel
import com.dirk.kalshiodds.signal.ml.UncertaintyGate
import java.util.Locale
import kotlin.math.abs

/**
 * Live numbers for the five checklist rows that used to render "—".
 *
 * Heavy ML and extended AI default off (they OOM'd phones). Their master
 * switches still gate trade blocks. These rows are display-only and always
 * compute from the quote in hand. Conformal and the RL sizer say
 * "warming up (n/N)" until stored settlements fit them.
 *
 * Nothing here places an order.
 */
object ChecklistAdvisors {

    data class Live(
        val uncertainty: Double,
        val uncertaintyMethod: String,
        val survivalYesPp: Double,
        val conformalSet: String,
        val conformalAmbiguous: Boolean,
        val pathSurvive: Double,
        val rlNote: String,
        val rlStakeUsd: Double
    )

    fun fromLive(
        uncertainty: Double,
        uncertaintyMethod: String,
        mid: Double,
        fairYes: Double,
        tteFrac: Double,
        tteSeconds: Long?,
        momentum: Double,
        imbalance: Double?,
        velocityPerSec: Double?,
        spot: Double?,
        vol: Double,
        regime: RegimeTag,
        edgePp: Double,
        confidence: Double,
        conformal: ConformalSets.State,
        rl: RlSizer,
        stakeUsd: Double
    ): Live {
        val unc = uncertainty.takeIf { it.isFinite() } ?: UncertaintyGate.evaluate(emptyList(), 0.12, false).uncertainty
        val method = uncertaintyMethod.ifBlank { "cold" }
        val fair = fairYes.takeIf { it.isFinite() }?.coerceIn(0.02, 0.98) ?: mid.coerceIn(0.02, 0.98)
        val m = mid.coerceIn(0.02, 0.98)
        val surv = SurvivalModel.predict(
            mid = m,
            tteFrac = tteFrac,
            momentum = momentum,
            imbalance = imbalance,
            velocityPerSec = velocityPerSec,
            spot = spot
        )
        val conf = ConformalSets.predict(fair, conformal)
        val path = PathSimulator.simulate(
            mid = m,
            fairYes = fair,
            tteSeconds = tteSeconds,
            vol = vol,
            velocityPerSec = velocityPerSec,
            regime = regime
        )
        val advice = rl.suggest(
            edgePp = edgePp,
            confidence = confidence.coerceIn(0.0, 1.0),
            uncertainty = unc,
            tteFrac = tteFrac.coerceIn(0.0, 1.0),
            configuredStake = stakeUsd
        )
        return Live(
            uncertainty = unc,
            uncertaintyMethod = method,
            survivalYesPp = surv.pYes * 100.0,
            conformalSet = conformalLabel(conf, conformal.scores.size),
            conformalAmbiguous = conf.ambiguous,
            pathSurvive = path.pSurvive,
            rlNote = advice.note,
            rlStakeUsd = advice.stakeUsd
        )
    }

    fun conformalLabel(result: ConformalSets.Result, samples: Int): String {
        val set = result.set.toSortedSet().joinToString(",", "{", "}")
        return if (result.ready) {
            if (result.ambiguous) "$set ambiguous" else set
        } else {
            "$set warming up ($samples/${ConformalSets.MIN_SAMPLES})"
        }
    }

    fun uncertaintyText(value: Double?, passed: Boolean, method: String?): String {
        val u = value?.takeIf { it.isFinite() } ?: return coldUncertaintyText()
        val num = String.format(Locale.US, "%.2f", u)
        val gate = if (passed) "" else " GATE"
        val tag = when (method) {
            "cold", "fail-soft" -> " warming up"
            "single-proxy" -> " proxy"
            else -> ""
        }
        return num + gate + tag
    }

    fun survivalText(storedPp: Double?, market: MarketUiModel): String {
        storedPp?.takeIf { it.isFinite() }?.let { return String.format(Locale.US, "%.0f%%", it) }
        val mid = mid01(market) ?: return "warming up"
        val pred = SurvivalModel.predict(
            mid = mid,
            tteFrac = tteFrac(market),
            momentum = 0.0,
            imbalance = null,
            velocityPerSec = null,
            spot = null
        )
        return String.format(Locale.US, "%.0f%%", pred.pYes * 100.0)
    }

    fun conformalText(market: MarketUiModel): String {
        val stored = market.conformalSet?.trim().orEmpty()
        if (stored.isNotEmpty()) {
            return if (market.conformalAmbiguous && !stored.contains("ambiguous")) "$stored ambiguous" else stored
        }
        val p = fair01(market) ?: mid01(market) ?: return "warming up (0/${ConformalSets.MIN_SAMPLES})"
        return conformalLabel(ConformalSets.predict(p, ConformalSets.State()), 0)
    }

    fun pathText(stored: Double?, market: MarketUiModel): String {
        stored?.takeIf { it.isFinite() }?.let { return String.format(Locale.US, "%.0f%%", it * 100.0) }
        val mid = mid01(market) ?: return "warming up"
        val fair = fair01(market) ?: mid
        val pred = PathSimulator.simulate(
            mid = mid,
            fairYes = fair,
            tteSeconds = tteSeconds(market),
            vol = 0.05,
            velocityPerSec = null,
            regime = regimeOf(market.regimeTag)
        )
        return String.format(Locale.US, "%.0f%%", pred.pSurvive * 100.0)
    }

    fun rlText(stored: String?): String {
        val note = stored?.trim().orEmpty()
        if (note.isNotEmpty()) return note
        return RlSizer().suggest(0.0, 0.5, 0.06, 0.5, SignalConstants.DEFAULT_TICKET_STAKE_USD).note
    }

    fun tradedSide(predictedSide: String?): String? = when (predictedSide?.trim()?.uppercase(Locale.US)) {
        "YES", "UP" -> "UP"
        "NO", "DOWN" -> "DOWN"
        else -> null
    }

    /** Official taker fee: ceil to the cent of 0.07 × C × P × (1 − P). */
    fun kalshiTakerFee(contracts: Int, price: Double, feeRate: Double = SignalConstants.DEFAULT_FEE_RATE): Double {
        if (contracts <= 0 || !price.isFinite() || price <= 0.0 || price >= 1.0) return 0.0
        val model = feeRate * contracts * price * (1.0 - price)
        if (!model.isFinite() || model <= 0.0) return 0.0
        val cents = kotlin.math.ceil(model * 100.0 - 1e-9)
        return cents / 100.0
    }

    private fun coldUncertaintyText(): String {
        val cold = UncertaintyGate.evaluate(emptyList(), SignalConstants.DEFAULT_MAX_UNCERTAINTY, enabled = false)
        return uncertaintyText(cold.uncertainty, passed = true, method = cold.method)
    }

    private fun mid01(market: MarketUiModel): Double? {
        market.yesProbabilityPercent?.takeIf { it.isFinite() }?.let { return (it / 100.0).coerceIn(0.02, 0.98) }
        val bid = market.yesBid?.takeIf { it.isFinite() }
        val ask = market.yesAsk?.takeIf { it.isFinite() }
        if (bid != null && ask != null) return ((bid + ask) / 2.0).coerceIn(0.02, 0.98)
        return market.lastPrice?.takeIf { it.isFinite() }?.coerceIn(0.02, 0.98)
    }

    private fun fair01(market: MarketUiModel): Double? {
        val pp = market.importedModelPp?.takeIf { it.isFinite() }
            ?: market.aiYesPercent?.takeIf { it.isFinite() }
            ?: return null
        return (pp / 100.0).coerceIn(0.02, 0.98)
    }

    private fun tteFrac(market: MarketUiModel): Double {
        val sec = tteSeconds(market)
        return if (sec != null) (sec / 900.0).coerceIn(0.0, 1.0) else when {
            market.tteRegimeLabel?.contains("last", true) == true -> 0.15
            market.tteRegimeLabel?.contains("late", true) == true -> 0.15
            market.tteRegimeLabel?.contains("early", true) == true -> 0.70
            else -> 0.50
        }
    }

    private fun tteSeconds(market: MarketUiModel): Long? {
        val close = market.closeTimeEpochMs ?: return null
        val left = (close - System.currentTimeMillis()) / 1000
        return left.coerceIn(5L, 900L)
    }

    private fun regimeOf(label: String?): RegimeTag {
        val u = label?.uppercase(Locale.US).orEmpty()
        return when {
            u.contains("VOL") || u.contains("SPIKE") -> RegimeTag.VOL_SPIKE
            u.contains("CHOP") -> RegimeTag.CHOP
            u.contains("TREND") -> RegimeTag.TREND
            else -> RegimeTag.QUIET
        }
    }

    fun finiteGap(modelPct: Double, askCents: Double): Boolean = abs(modelPct - askCents) > 1e-9
}
