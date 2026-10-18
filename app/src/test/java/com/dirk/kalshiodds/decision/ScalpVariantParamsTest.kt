package com.dirk.kalshiodds.decision

import org.junit.Assert.*
import org.junit.Test

/**
 * 0.3.89: v2 copies must each run their own settings. Before this, v2-sf51…v2-sf60 had no Spec and resolved to the
 * same default (55¢ ceiling, global 3¢ trail, no take-profit), so every group of five produced identical trades.
 */
class ScalpVariantParamsTest {
    private val defaults = ScalpControls()

    private fun resolved(id: String): Pair<ScalpV2.Spec, Double> {
        val sp = ScalpV2.resolvedSpec(ScalpV2.spec(id), null, defaults.peakTrailEnabled, defaults.peakTrailCents, defaults.manual)
        return sp to ScalpV2.resolvedTakeProfit(sp, null, defaults.manual, defaults.takeProfitCents)
    }

    /** Deterministic v2 paper decision on one fixed ask/bid path: (entry tick, exit tick, exit reason). */
    private fun decide(id: String, path: List<Pair<Double, Double>>): Triple<Int?, Int?, String?> {
        val (sp, tp) = resolved(id)
        var entry: Double? = null; var at: Int? = null; var peak = 0.0
        for ((i, pt) in path.withIndex()) {
            val (ask, bid) = pt
            if (entry == null) {
                if (ScalpV2.entryOk(ask, sp.maxAsk)) { entry = ask; at = i; peak = bid }
                continue
            }
            peak = maxOf(peak, bid)
            val net = (bid - entry) - (ScalpRule.orderFee(sp.clip, bid) + ScalpRule.orderFee(sp.clip, entry)) / sp.clip
            val r = ScalpV2.peakExit(entry, ScalpRisk.DEFAULT_PERCENT.toDouble(), peak, bid, sp.trail)
                ?: if (tp > 0 && net >= tp - 1e-9) "tp" else null
            if (r != null) return Triple(at, i, r)
        }
        return Triple(at, null, null)
    }

    // Opens at 50¢ (49¢ ask one tick later for maker ceilings), climbs with a 2¢ then 3¢ pullback, then past every TP.
    private val path: List<Pair<Double, Double>> = listOf(.50 to .49, .49 to .485, .50 to .49, .52 to .51, .54 to .53,
        .56 to .55, .58 to .57, .56 to .55, .55 to .54, .58 to .57, .60 to .59, .62 to .61, .64 to .63, .66 to .65,
        .68 to .67, .65 to .64, .62 to .61, .58 to .57)

    private val o50 = (51..60).map { "v2-sf$it" }

    @Test fun resolvedParamsMatchDefinitions() {
        for (id in o50) {
            val p = ShockFav.v(id.removePrefix("v2-"))
            val (sp, tp) = resolved(id)
            assertEquals(id, p.trail ?: ScalpV2.NO_TRAIL, sp.trail, 1e-9)
            assertEquals(id, p.tpNetO50 ?: 0.0, tp, 1e-9)
            assertEquals(id, if (p.o50 == ShockFav.O50.MAKER) ShockFav.O50_MAKER_PX else ScalpV2.MAX_ASK, sp.maxAsk, 1e-9)
        }
        for (i in 1..20) {
            val id = String.format(java.util.Locale.US, "v2-sf%02d", i)
            assertEquals(id, ScalpV2.spec(id).trail, resolved(id).first.trail, 1e-9)
        }
        for (b in listOf("sf41", "sf42", "sf43", "sf44")) for (t in listOf("t1", "t2")) {
            val id = "v2-$b$t"; assertEquals(id, ScalpV2.spec(id).trail, resolved(id).first.trail, 1e-9)
        }
    }

    @Test fun distinctConfigsGiveDistinctDecisions() {
        val ids = o50 + (1..20).map { String.format(java.util.Locale.US, "v2-sf%02d", it) } +
            ScalpV2.EXTRA_SPECS.filter { !it.walk }.map { it.id }
        val byBase = ids.groupBy { ScalpV2.baseOf(it) }
        // Within one shared original signal, any two copies with different configs must decide differently.
        val sameSignalGroups = listOf(o50.take(5), o50.drop(5)) + byBase.values.filter { it.size > 1 }
        for (g in sameSignalGroups) for (i in g.indices) for (j in i + 1 until g.size) {
            val a = resolved(g[i]); val b = resolved(g[j])
            val cfgA = Triple(a.first.maxAsk, a.first.trail, a.second); val cfgB = Triple(b.first.maxAsk, b.first.trail, b.second)
            if (cfgA == cfgB) continue
            assertNotEquals("${g[i]} vs ${g[j]} decided identically", decide(g[i], path), decide(g[j], path))
        }
    }

    @Test fun userKnobsStillWinAndManualStillGlobal() {
        val sp = ScalpV2.spec("v2-sf53")
        assertEquals(.05, ScalpV2.resolvedSpec(sp, 5, true, 3, false).trail, 1e-9)
        assertEquals(.02, ScalpV2.resolvedSpec(sp, null, true, 2, true).trail, 1e-9)
        assertEquals(.07, ScalpV2.resolvedTakeProfit(sp, 7, false, 0), 1e-9)
        assertEquals(.0, ScalpV2.resolvedTakeProfit(sp, null, true, 0), 1e-9)
        // Plain copies without their own trail keep the global 3¢ peak trail.
        assertEquals(.03, ScalpV2.resolvedSpec(ScalpV2.spec("v2-sf30"), null, true, 3, false).trail, 1e-9)
    }
}
