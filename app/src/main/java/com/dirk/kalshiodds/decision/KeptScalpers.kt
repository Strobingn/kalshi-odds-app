package com.dirk.kalshiodds.decision

import java.time.Instant
import kotlin.math.exp

/**
 * Paper scalpers still on the board after the 10-21 ranking (3,798 closed round trips).
 * Twelve kept. The board holds at most [MAX_SLOTS]; the other slots stay empty.
 * Nothing here places a live order.
 */
/**
 * sf04, sf07, and sf08 often print the same contract. An all-scalpers total that adds
 * their stats counts that fill once per scalper. Per-scalper rows stay as booked.
 */
object ScalpPortfolio {
    val SHARED = setOf("sf04", "sf07", "sf08")
    const val NOTE = "sf04, sf07, and sf08 often buy the same contract. This total adds each scalper, so a shared fill is counted once for each of them."

    fun countsShared(ids: Collection<String>): Boolean = SHARED.count { it in ids } >= 2

    /** One copy of a shared shock fill (same ticker, side, cent, and 5s entry bucket). Other scalpers always count. */
    fun dedupedNet(trades: List<ScalpTrade>): Double {
        val seen = HashSet<String>()
        var net = 0.0
        for (t in trades) {
            if (t.state != ScalpState.CLOSED || t.netUsd == null || !t.netUsd.isFinite()) continue
            val id = t.variantId
            if (id in SHARED) {
                val cent = kotlin.math.round((t.entryPrice ?: t.signalAsk) * 100.0).toInt()
                val bucket = (t.entryAtMs ?: t.signalAtMs) / 5_000L
                val key = "${t.ticker.uppercase()}|${t.side.uppercase()}|$cent|$bucket"
                if (!seen.add(key)) continue
            }
            net += t.netUsd
        }
        return net
    }
}

object KeptScalpers {
    const val MAX_SLOTS = 80
    const val REMOVED_LABEL = "removed strategy"

    const val FAIR_ID = "g14-t12-s08-d15-w300"
    const val V2_FAIR_ID = "v2-g10-t12-s08-d15-w300-mk"
    const val V2_FAIR_BASE = "g10-t12-s08-d15-w300-mk"
    const val V2_DIP_ID = "v2-dip-g05-t08-s06-d10-w180"
    const val V2_DIP_BASE = "dip-g05-t08-s06-d10-w180"
    const val FADE_ID = "fcfade-h600"
    const val FADE_HORIZON_S = 600

    enum class Kind { SHOCK, FAV, SIMPLE, FAIR, V2_FAIR_MAKER, V2_DIP, FADE }

    data class Spec(val id: String, val label: String, val blurb: String, val kind: Kind, val family: String)

    val ALL: List<Spec> = listOf(
        Spec("sf42", "sf42 Simple dip 5¢ → +15¢", ShockFav.v("sf42").summary(), Kind.SIMPLE, "sf42"),
        Spec(V2_FAIR_ID, "v2 · Fair-gap", "v2 exits on maker fair-gap $V2_FAIR_BASE (gap 10¢, target +12¢, stop 8¢, turn-down 15¢, from 5 min). Paper only.", Kind.V2_FAIR_MAKER, "v2-fair"),
        Spec(FAIR_ID, "Fair-gap", "Fair-gap gap ≥ 14¢, target +12¢, stop 8¢, turn-down 15¢, from 5 min left. Paper only.", Kind.FAIR, "fair"),
        Spec("sf16f", "sf16 Late fav base", ShockFav.v("sf16").summary(), Kind.FAV, "sf16"),
        Spec("sf15", "sf15 Shock mid-window", ShockFav.v("sf15").summary(), Kind.SHOCK, "sf15"),
        Spec("sf17f", "sf17 Late fav 60s-avg fair", ShockFav.v("sf17").summary(), Kind.FAV, "sf17"),
        Spec(V2_DIP_ID, "v2 · Dip-hunter", "v2 exits on dip-hunter $V2_DIP_BASE (drop 5¢, target +8¢, stop 6¢, turn-down 10¢, from 3 min). Paper only.", Kind.V2_DIP, "v2-dip"),
        Spec("sf07", "sf07 Shock hold to close", ShockFav.v("sf07").summary(), Kind.SHOCK, "sf07"),
        Spec("sf04", "sf04 Shock wide lag", ShockFav.v("sf04").summary(), Kind.SHOCK, "sf04"),
        Spec("sf08", "sf08 Shock tight catch-up", ShockFav.v("sf08").summary(), Kind.SHOCK, "sf08"),
        Spec(FADE_ID, "Forecast fade", "Forecast fade, horizon ${FADE_HORIZON_S}s, entry band 40–49¢. Paper only.", Kind.FADE, "fcfade"),
        Spec("sf41", "sf41 Simple dip 5¢ → +8¢", ShockFav.v("sf41").summary(), Kind.SIMPLE, "sf41")
    )

    init {
        require(ALL.size <= MAX_SLOTS) { "scalper board is ${ALL.size}, max $MAX_SLOTS" }
        require(ALL.map { it.id }.distinct().size == ALL.size)
    }

    val IDS: Set<String> = ALL.map { it.id }.toSet()

    /** Paper HFT engines. Not part of the twelve kept winners. */
    val HFT: List<Spec> = listOf(
        Spec("dos-mm", "DOS-MM", "HJB bounded binary market maker. Paper only.", Kind.FAIR, "dos-mm"),
        Spec("ts-arb", "TS-Arb", "Settlement average and one-over-sqrt-three variance. Paper only.", Kind.FAIR, "ts-arb"),
        Spec("mrla-cs", "MRLA-CS", "Five-level OFI and transient impact. Paper only.", Kind.FAIR, "mrla-cs"),
        Spec("iv-smile", "IV smile", "Options smile versus the Kalshi book. Paper only.", Kind.FAIR, "iv-smile")
    )
    val HFT_IDS: Set<String> = HFT.map { it.id }.toSet()
    private val BY_ID: Map<String, Spec> = ALL.associateBy { it.id }

    /** ShockFav family codes allowed to open a new paper trade. */
    val SHOCK_FAMILIES: Set<String> = ALL.mapNotNull { s ->
        if (s.kind == Kind.SHOCK || s.kind == Kind.FAV || s.kind == Kind.SIMPLE) s.family else null
    }.toSet()

    /**
     * Old aggregate ids that are exactly one kept variant (sf16 only ever booked sf16f).
     * Read-time only. Stored rows are not rewritten or deleted.
     */
    val STAT_ALIASES: Map<String, String> = mapOf(
        "sf16" to "sf16f",
        "sf17" to "sf17f",
        // 1.0.22 renamed the maker fair-gap card. Old closed rows stay in the table; the card reads them.
        "g10-t12-s08-d15-w300-mk" to V2_FAIR_ID
    )

    fun isKept(id: String?): Boolean = id != null && id in IDS
    fun spec(id: String): Spec? = BY_ID[id]
    fun canonical(id: String): String = STAT_ALIASES[id] ?: id

    /** Ids to read from scalp_trades for a card. Includes the legacy alias. Does not delete the old id. */
    fun storedIds(code: String): List<String> {
        val extra = STAT_ALIASES.filterValues { it == code }.keys
        return (listOf(code) + extra).distinct()
    }

    /**
     * True when this kept scalper's own entry rule fires on a fixed fixture.
     * Paper signal only — no order.
     */
    fun entryFires(id: String): Boolean = when (spec(id)?.kind) {
        Kind.SIMPLE -> {
            val p = ShockFav.v(spec(id)!!.family)
            ShockFav.simpleDipSide(p, 0.40, 0.40 + p.dip + 0.02, null, 0.0) == "YES"
        }
        Kind.SHOCK -> shockFires(ShockFav.v(spec(id)!!.family))
        Kind.FAV -> {
            val p = ShockFav.v(spec(id)!!.family)
            val close = Instant.parse("2026-10-21T02:00:00Z").toEpochMilli() + 900_000L
            ShockFav.favEntryOk(p, 0.96, 0.90, 40.0, "BTC", close)
        }
        Kind.FAIR -> ScalpRule.entrySignal(fairQuote(), ScalpParams.byId(FAIR_ID)!!).first != null
        Kind.V2_FAIR_MAKER -> ScalpRule.entrySignal(fairQuote(), ScalpParams.byId(V2_FAIR_BASE)!!).first != null
        Kind.V2_DIP -> {
            val p = ScalpParams.byId(V2_DIP_BASE)!!
            val now = 1_700_000_000_000L
            val close = now + 400_000L
            val q = fairQuote().copy(nowMs = now, closeMs = close, bookAtMs = now, yesBid = 0.39, yesAsk = 0.40)
            val snaps = listOf(
                ScalpRule.Snap(now - 40_000L, 0.49, 0.50, 0.80),
                ScalpRule.Snap(now - 25_000L, 0.49, 0.50, 0.80)
            )
            ScalpRule.strategySignal(q, p, listOf("YES"), snaps).first != null
        }
        Kind.FADE -> {
            val band = ForecastScalp.entryBand(FADE_ID, null)
            ForecastScalp.variantId(false, FADE_HORIZON_S) == FADE_ID &&
                ForecastScalp.wantsEntry(0.45, 0.01, 0.45, 0.30, 300.0, bandLo = band.first, bandHi = band.second)
        }
        null -> false
    }

    private fun shockFires(p: ShockFav.Params): Boolean {
        val ring = ShockFav.SpotRing()
        val sec = 1_700_000_000L
        ring.put(sec - p.yS.toLong(), 100_000.0)
        ring.put(sec, 100_000.0 * exp(0.002))
        val side = ShockFav.shockSide(p, "BTC", ring, sec, 400.0, ShockFav.MEDIAN_SIGMA.getValue("BTC")) ?: return false
        val ask = if (p.lag >= 0.10) 0.40 else 0.45
        return side == "YES" && ShockFav.shockEntryOk(p, 0.70, ask)
    }

    private fun fairQuote(): ScalpRule.Quote {
        val now = 1_700_000_400_000L
        val close = now + 600_000L
        return ScalpRule.Quote(
            ticker = "KXBTC15M-26OCT181200-00", nowMs = now, closeMs = close, bookAtMs = now,
            yesBid = 0.34, yesBidSize = 50.0, yesAsk = 0.35, yesAskSize = 50.0,
            spot = 101_000.0, strike = 100_000.0, sigmaPerSec = 3e-5
        )
    }
}
