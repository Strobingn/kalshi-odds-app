package com.dirk.kalshiodds.decision

/**
 * Paper-only nudges of the kept winners (and the three ids that were still
 * printing after the 10-21 prune: sf43, g16, and the dip maker).
 * The parent scalpers are not modified. Each variant id ends in -v1, -v2, or -v3.
 * v1 and v2 loosen the trigger so it fires more often. v3 steps the other way.
 */
object ScalpVariants {
    enum class Kind { SIMPLE, SHOCK, FAV, FAIR, DIP, V2_FAIR, V2_DIP, FADE, DIP_MAKER }

    data class Spec(
        val id: String,
        val parentId: String,
        val label: String,
        val blurb: String,
        val kind: Kind,
        val shock: ShockFav.Params? = null,
        val fair: ScalpParams? = null,
        val fadeLo: Double = ForecastScalp.BAND_LO,
        val fadeHi: Double = ForecastScalp.FADE_BAND_HI,
        val fadeHorizonS: Int = KeptScalpers.FADE_HORIZON_S,
        val clip: Int = ShockFav.CLIP,
        val stopCents: Int? = null
    ) {
        fun differsFromParent(): Boolean = when (kind) {
            Kind.SIMPLE, Kind.SHOCK, Kind.FAV -> {
                val p = shock ?: return false
                val base = baseShock()
                p.dip != base.dip || p.tp != base.tp || p.lag != base.lag || p.xBp != base.xBp ||
                    p.tauLo != base.tauLo || p.tauHi != base.tauHi || p.fmin != base.fmin || p.gap != base.gap ||
                    p.favTmin != base.favTmin || p.favTmax != base.favTmax || p.maxHoldS != base.maxHoldS ||
                    p.conv != base.conv || clip != ShockFav.CLIP || stopCents != null
            }
            Kind.FAIR, Kind.DIP, Kind.V2_FAIR, Kind.V2_DIP, Kind.DIP_MAKER -> {
                val p = fair ?: return false
                val base = baseFair()
                p.minGap != base.minGap || p.target != base.target || p.stop != base.stop ||
                    p.turnDown != base.turnDown || p.tauMinS != base.tauMinS || p.tauMaxS != base.tauMaxS ||
                    clip != ShockFav.CLIP
            }
            Kind.FADE -> fadeLo != ForecastScalp.BAND_LO || fadeHi != ForecastScalp.FADE_BAND_HI ||
                fadeHorizonS != KeptScalpers.FADE_HORIZON_S || clip != ShockFav.CLIP
        }

        private fun baseShock(): ShockFav.Params = when (kind) {
            Kind.FAV -> ShockFav.v(parentId.removeSuffix("f").take(4))
            else -> ShockFav.v(parentId.take(4))
        }

        private fun baseFair(): ScalpParams = when (parentId) {
            KeptScalpers.FAIR_ID -> ScalpParams.byId(KeptScalpers.FAIR_ID)!!
            "g16-t14-s08-d15-w300" -> ScalpParams(minGap = 0.16, target = 0.14, stop = 0.08, turnDown = 0.15, tauMinS = 300.0)
            KeptScalpers.V2_FAIR_ID -> ScalpParams.byId(KeptScalpers.V2_FAIR_BASE)!!
            KeptScalpers.V2_DIP_ID -> ScalpParams.byId(KeptScalpers.V2_DIP_BASE)!!
            "dip-g05-t08-s06-d10-w180-mk" -> ScalpParams.byId(KeptScalpers.V2_DIP_BASE)!!.copy(maker = true)
            else -> ScalpParams.byId(parentId) ?: ScalpParams.seedFor("BTC")
        }
    }

    val ALL: List<Spec> = build()
    val IDS: Set<String> = ALL.map { it.id }.toSet()
    private val BY_ID: Map<String, Spec> = ALL.associateBy { it.id }

    fun isLive(id: String?): Boolean = id != null && id in IDS
    fun of(id: String?): Spec? = id?.let { BY_ID[it] }
    fun label(id: String): String = BY_ID[id]?.label ?: id
    fun blurb(id: String): String = BY_ID[id]?.blurb ?: "Paper variant"
    fun clipOf(id: String): Int? = BY_ID[id]?.clip
    fun stopCents(id: String): Int? = BY_ID[id]?.stopCents
    fun parentId(id: String): String? = BY_ID[id]?.parentId
    fun isFav(id: String): Boolean = BY_ID[id]?.kind == Kind.FAV
    fun fairParams(id: String): ScalpParams? = BY_ID[id]?.fair
    fun shockParams(id: String): ShockFav.Params? = BY_ID[id]?.shock

    private fun build(): List<Spec> {
        val out = ArrayList<Spec>()
        out += simple("sf41", 0.05, 0.08)
        out += simple("sf42", 0.05, 0.15)
        out += simple("sf43", 0.10, 0.08)
        out += shock("sf04", ShockFav.v("sf04"))
        out += shock("sf07", ShockFav.v("sf07"))
        out += shock("sf08", ShockFav.v("sf08"))
        out += shock("sf15", ShockFav.v("sf15"))
        out += fav("sf16f")
        out += fav("sf17f")
        out += fair(KeptScalpers.FAIR_ID, ScalpParams.byId(KeptScalpers.FAIR_ID)!!, Kind.FAIR)
        out += fair(
            "g16-t14-s08-d15-w300",
            ScalpParams(minGap = 0.16, target = 0.14, stop = 0.08, turnDown = 0.15, tauMinS = 300.0),
            Kind.FAIR
        )
        out += fair(KeptScalpers.V2_FAIR_ID, ScalpParams.byId(KeptScalpers.V2_FAIR_BASE)!!, Kind.V2_FAIR)
        out += fair(KeptScalpers.V2_DIP_ID, ScalpParams.byId(KeptScalpers.V2_DIP_BASE)!!, Kind.V2_DIP)
        out += fair("dip-g05-t08-s06-d10-w180-mk", ScalpParams.byId(KeptScalpers.V2_DIP_BASE)!!.copy(maker = true), Kind.DIP_MAKER)
        out += fade()
        require(out.size in 36..48) { "expected 36–48 variants, got ${out.size}" }
        require(out.map { it.id }.distinct().size == out.size)
        require(out.none { it.id in KeptScalpers.IDS })
        require(out.all { it.differsFromParent() })
        return out
    }

    private fun simple(parent: String, dip: Double, tp: Double): List<Spec> {
        val base = ShockFav.v(parent)
        val nudges = listOf(
            Triple(1, (dip - 0.02).coerceAtLeast(0.02) to (tp - 0.03).coerceAtLeast(0.04), 5 to 12),
            Triple(2, (dip - 0.01).coerceAtLeast(0.02) to tp, 10 to 15),
            Triple(3, (dip + 0.02) to (tp + 0.02), 20 to 18)
        )
        return nudges.map { (n, px, clipStop) ->
            val (d, t) = px
            val (clip, stop) = clipStop
            spec(
                parent, n, Kind.SIMPLE, clip, stop,
                "dip ${cents(d)} → +${cents(t)} · clip $clip",
                shock = base.copy(code = "$parent-v$n", dip = d, tp = t, shock = false)
            )
        }
    }

    private fun shock(parent: String, base: ShockFav.Params): List<Spec> {
        data class N(val n: Int, val lag: Double, val xBp: Double, val tauLo: Double, val tauHi: Double, val hold: Int, val conv: Double, val clip: Int, val stop: Int)
        val rows = listOf(
            N(1, (base.lag - 0.02).coerceAtLeast(0.02), (base.xBp - 2.0).coerceAtLeast(2.0), (base.tauLo - 60.0).coerceAtLeast(30.0), (base.tauHi + 30.0).coerceAtMost(890.0), (base.maxHoldS - 30).coerceAtLeast(30), base.conv, 5, 20),
            N(2, (base.lag - 0.01).coerceAtLeast(0.02), (base.xBp - 1.0).coerceAtLeast(2.0), (base.tauLo - 30.0).coerceAtLeast(30.0), base.tauHi, base.maxHoldS, base.conv, 10, 25),
            N(3, base.lag + 0.02, base.xBp + 2.0, (base.tauLo + 30.0).coerceAtMost(base.tauHi - 60.0), (base.tauHi - 60.0).coerceAtLeast(base.tauLo + 60.0), base.maxHoldS + 30, base.conv + 0.01, 20, 30)
        )
        return rows.map { r ->
            spec(
                parent, r.n, Kind.SHOCK, r.clip, r.stop,
                "lag ${cents(r.lag)} · ${r.xBp.toInt()}bp/${base.yS}s · τ ${r.tauLo.toInt()}–${r.tauHi.toInt()}s · clip ${r.clip}",
                shock = base.copy(
                    code = "$parent-v${r.n}", lag = r.lag, xBp = r.xBp, tauLo = r.tauLo, tauHi = r.tauHi,
                    maxHoldS = r.hold, conv = r.conv, shock = true, fav = false
                )
            )
        }
    }

    private fun fav(parent: String): List<Spec> {
        val base = ShockFav.v(parent.removeSuffix("f").take(4))
        data class N(val n: Int, val fmin: Double, val gap: Double, val tMin: Double, val tMax: Double, val clip: Int)
        val rows = listOf(
            N(1, (base.fmin - 0.05).coerceAtLeast(0.70), (base.gap - 0.02).coerceAtLeast(0.02), (base.favTmin - 10.0).coerceAtLeast(5.0), base.favTmax + 60.0, 5),
            N(2, (base.fmin - 0.02).coerceAtLeast(0.70), (base.gap - 0.01).coerceAtLeast(0.02), base.favTmin, base.favTmax + 30.0, 10),
            N(3, (base.fmin + 0.02).coerceAtMost(0.98), base.gap + 0.02, base.favTmin + 5.0, (base.favTmax - 15.0).coerceAtLeast(base.favTmin + 10.0), 20)
        )
        return rows.map { r ->
            spec(
                parent, r.n, Kind.FAV, r.clip, null,
                "fair ≥ ${pct(r.fmin)} · gap ${cents(r.gap)} · τ ${r.tMin.toInt()}–${r.tMax.toInt()}s · clip ${r.clip}",
                shock = base.copy(
                    code = parent.removeSuffix("f").take(4), shock = false, fav = true,
                    fmin = r.fmin, gap = r.gap, favTmin = r.tMin, favTmax = r.tMax
                )
            )
        }
    }

    private fun fair(parent: String, base: ScalpParams, kind: Kind): List<Spec> {
        data class N(val n: Int, val gap: Double, val target: Double, val stop: Double, val td: Double, val tau: Double, val clip: Int)
        val rows = listOf(
            N(1, (base.minGap - 0.04).coerceAtLeast(0.02), (base.target - 0.04).coerceAtLeast(0.04), (base.stop + 0.02).coerceAtMost(0.20), (base.turnDown - 0.03).coerceAtLeast(0.05), (base.tauMinS - 120.0).coerceAtLeast(60.0), 5),
            N(2, (base.minGap - 0.02).coerceAtLeast(0.02), (base.target - 0.02).coerceAtLeast(0.04), base.stop, base.turnDown, (base.tauMinS - 60.0).coerceAtLeast(60.0), 10),
            N(3, base.minGap + 0.02, base.target + 0.02, (base.stop - 0.02).coerceAtLeast(0.04), base.turnDown + 0.02, (base.tauMinS + 60.0).coerceAtMost(600.0), 20)
        )
        return rows.map { r ->
            spec(
                parent, r.n, kind, r.clip, null,
                "gap ${cents(r.gap)} · target +${cents(r.target)} · stop ${cents(r.stop)} · from ${r.tau.toInt()}s · clip ${r.clip}",
                fair = base.copy(minGap = r.gap, target = r.target, stop = r.stop, turnDown = r.td, tauMinS = r.tau)
            )
        }
    }

    private fun fade(): List<Spec> = listOf(
        Triple(1, 0.30 to 0.55, 300 to 5),
        Triple(2, 0.35 to 0.52, 450 to 10),
        Triple(3, 0.42 to 0.47, 900 to 20)
    ).map { (n, band, horizonClip) ->
        val (lo, hi) = band
        val (horizon, clip) = horizonClip
        spec(
            KeptScalpers.FADE_ID, n, Kind.FADE, clip, null,
            "band ${cents(lo)}–${cents(hi)} · horizon ${horizon}s · clip $clip",
            fadeLo = lo, fadeHi = hi, fadeHorizon = horizon
        )
    }

    private fun spec(
        parent: String,
        n: Int,
        kind: Kind,
        clip: Int,
        stop: Int?,
        detail: String,
        shock: ShockFav.Params? = null,
        fair: ScalpParams? = null,
        fadeLo: Double = ForecastScalp.BAND_LO,
        fadeHi: Double = ForecastScalp.FADE_BAND_HI,
        fadeHorizon: Int = KeptScalpers.FADE_HORIZON_S
    ): Spec {
        val id = "$parent-v$n"
        return Spec(
            id = id,
            parentId = parent,
            label = "$id · $detail",
            blurb = "Paper variant of $parent. $detail. Same fill and fee rules as the parent. Not auto-benched.",
            kind = kind,
            shock = shock,
            fair = fair,
            fadeLo = fadeLo,
            fadeHi = fadeHi,
            fadeHorizonS = fadeHorizon,
            clip = clip,
            stopCents = stop
        )
    }

    private fun cents(v: Double) = "${Math.round(v * 100)}"
    private fun pct(v: Double) = "${Math.round(v * 100)}%"
}
