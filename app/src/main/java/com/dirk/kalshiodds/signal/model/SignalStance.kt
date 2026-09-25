package com.dirk.kalshiodds.signal.model

/**
 * One definition for the signal side and the home-card model-vs-market
 * line. A side is UP/DOWN only when that side's model probability is
 * strictly above the market's (positive edge). Component disagreement
 * (AI / imported model vs blended fair, or vs the stored pick) is
 * [NO_BET] — never a green/red call.
 *
 * Probability source matches the home BET/NO BET path:
 * `importedModelPp ?: aiYesPercent` vs market mid.
 */
object SignalStance {
    const val YES = "YES"
    const val NO = "NO"
    const val NO_BET = "NO_BET"
    const val CALL_UP = "UP"
    const val CALL_DOWN = "DOWN"
    const val CALL_NO_BET = "NO BET"
    const val DISAGREE_NOTE = "Model signals disagreed on direction"

    data class Resolved(
        val storedSide: String,
        val call: String,
        val modelYes: Double?,
        val marketYes: Double?,
        val lineSide: String,
        val edgePts: Double?,
        val disagreed: Boolean
    ) {
        val isActionable: Boolean get() = call == CALL_UP || call == CALL_DOWN
        val detailsExtra: String? get() = if (disagreed) DISAGREE_NOTE else null
    }

    fun homeModelYes(importedModelPp: Double?, aiYesPercent: Double?): Double? =
        importedModelPp?.takeIf { it.isFinite() } ?: aiYesPercent?.takeIf { it.isFinite() }

    fun normalizeSide(side: String?): String? = when (side?.trim()?.uppercase()) {
        YES, CALL_UP -> YES
        NO, CALL_DOWN -> NO
        NO_BET, "NONE", "FLAT", "NO BET" -> NO_BET
        else -> null
    }

    fun isNoBetSide(side: String?): Boolean {
        val n = normalizeSide(side) ?: return false
        return n == NO_BET
    }

    fun signedSide(modelYes: Double?, marketYes: Double?): String? {
        val model = modelYes?.takeIf { it.isFinite() } ?: return null
        val market = marketYes?.takeIf { it.isFinite() } ?: return null
        val edge = model - market
        return when {
            edge > EPS -> YES
            edge < -EPS -> NO
            else -> null
        }
    }

    fun edgeFor(modelYes: Double?, marketYes: Double?, side: String?): Double? {
        val model = sidePercent(modelYes, side) ?: return null
        val market = sidePercent(marketYes, side) ?: return null
        return model - market
    }

    fun sidePercent(yesPercent: Double?, side: String?): Double? {
        val p = yesPercent?.takeIf { it.isFinite() } ?: return null
        return if (lineIsUp(side)) p else 100.0 - p
    }

    fun lineIsUp(side: String?): Boolean = when (normalizeSide(side)) {
        NO -> false
        else -> true
    }

    fun leanLineSide(modelYes: Double?): String =
        if ((modelYes ?: 50.0) >= 50.0) CALL_UP else CALL_DOWN

    fun resolve(
        storedSide: String?,
        modelYes: Double?,
        marketYes: Double?,
        fairYes: Double? = null
    ): Resolved {
        val homeSide = signedSide(modelYes, marketYes)
        val fairSide = signedSide(fairYes, marketYes)
        val stored = when (val n = normalizeSide(storedSide)) {
            YES, NO -> n
            else -> null
        }
        val disagreed = sidesDisagree(homeSide, fairSide, stored)
        val candidate = when {
            disagreed -> null
            homeSide != null -> homeSide
            else -> null
        }
        val edge = candidate?.let { edgeFor(modelYes, marketYes, it) }
        val picked = if (candidate != null && edge != null && edge > EPS) candidate else null
        val call = when (picked) {
            YES -> CALL_UP
            NO -> CALL_DOWN
            else -> CALL_NO_BET
        }
        val lineSide = when (picked) {
            YES -> CALL_UP
            NO -> CALL_DOWN
            else -> leanLineSide(modelYes)
        }
        return Resolved(
            storedSide = picked ?: NO_BET,
            call = call,
            modelYes = modelYes,
            marketYes = marketYes,
            lineSide = lineSide,
            edgePts = edgeFor(modelYes, marketYes, lineSide),
            disagreed = disagreed
        )
    }

    fun fromAlert(
        predictedSide: String?,
        modelYes: Double?,
        marketYes: Double?,
        fairYes: Double?
    ): Resolved = resolve(
        storedSide = predictedSide,
        modelYes = modelYes,
        marketYes = marketYes,
        fairYes = fairYes
    )

    fun shouldNotify(resolved: Resolved): Boolean = resolved.isActionable

    private fun sidesDisagree(homeSide: String?, fairSide: String?, stored: String?): Boolean {
        val votes = listOfNotNull(homeSide, fairSide, stored)
        return votes.toSet().size > 1
    }

    private const val EPS = 1e-9
}
